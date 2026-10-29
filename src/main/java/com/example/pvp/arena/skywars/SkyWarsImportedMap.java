package com.example.pvp.arena.skywars;

import com.example.pvp.arena.ArenaWorld;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 外部空岛地图只导入静态地形及经过配置的出生点、物资箱。
 * 原地图的实体、箱子物资和可执行方块不能沿用，否则会绕开本服开局与补货规则。
 */
public final class SkyWarsImportedMap {
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_BLOCKS = 600_000;
    private static final int MAX_PALETTE = 4096;
    private static final Set<Block> EXCLUDED_BLOCKS = Set.of(
            Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
            Blocks.STRUCTURE_BLOCK, Blocks.STRUCTURE_VOID, Blocks.JIGSAW, Blocks.BARRIER,
            Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.LIGHT,
            Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.NETHER_PORTAL);

    public record Chest(BlockPos pos, SkyWarsLootPolicy.Tier tier, int spawnIndex) { }

    private final String id;
    private final String name;
    private final String author;
    private final String source;
    private final String license;
    private final int maxRadius;
    private final int finalRadius;
    private final List<BlockPos> spawns;
    private final List<Chest> chests;
    private final Map<BlockPos, BlockState> blocks;
    private final List<Map.Entry<BlockPos, BlockState>> orderedBlocks;

    private SkyWarsImportedMap(String id, String name, String author, String source, String license,
                               int maxRadius, int finalRadius, List<BlockPos> spawns,
                               List<Chest> chests, Map<BlockPos, BlockState> blocks) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.source = source;
        this.license = license;
        this.maxRadius = maxRadius;
        this.finalRadius = finalRadius;
        this.spawns = List.copyOf(spawns);
        this.chests = List.copyOf(chests);
        this.blocks = Map.copyOf(blocks);
        // 先落地基再放附着方块，沿用 ArenaWorld 的分帧暂存通道。
        this.orderedBlocks = this.blocks.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<BlockPos, BlockState> entry) -> entry.getKey().getY())
                        .thenComparingInt(entry -> entry.getKey().getX())
                        .thenComparingInt(entry -> entry.getKey().getZ()))
                .toList();
    }

    public String id() { return this.id; }
    public String name() { return this.name; }
    public String author() { return this.author; }
    public String source() { return this.source; }
    public String license() { return this.license; }
    public int maxRadius() { return this.maxRadius; }
    public int finalRadius() { return this.finalRadius; }
    public List<BlockPos> spawns() { return this.spawns; }
    public List<Chest> chests() { return this.chests; }
    public Map<BlockPos, BlockState> blocks() { return this.blocks; }

    /** 资源坐标直接相对本局区域中心，保留原图高度和水平间距。 */
    public static SkyWarsImportedMap load(String id) throws IOException {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IOException("Invalid imported SkyWars map id: " + id);
        }
        String directory = "/maps/skywars/" + id + "/";
        try (InputStream metadataInput = resource(directory + "metadata.json");
             InputStreamReader reader = new InputStreamReader(metadataInput, StandardCharsets.UTF_8)) {
            JsonObject metadata = JsonParser.parseReader(reader).getAsJsonObject();
            if (!id.equals(text(metadata, "id"))) throw new IOException("SkyWars map id does not match resource: " + id);
            String name = text(metadata, "name");
            String author = text(metadata, "author");
            String source = text(metadata, "source");
            String license = text(metadata, "license");
            int maxRadius = metadata.get("maxRadius").getAsInt();
            int finalRadius = metadata.get("finalRadius").getAsInt();
            if (maxRadius < 8 || maxRadius >= 176 || finalRadius < 3 || finalRadius >= maxRadius) {
                throw new IOException("Imported SkyWars map has invalid arena radius: " + id);
            }
            Map<BlockPos, BlockState> blocks;
            try (InputStream terrain = resource(directory + "terrain.nbt")) {
                blocks = readTerrain(NbtIo.readCompressed(terrain, NbtSizeTracker.of(MAX_NBT_BYTES)), maxRadius);
            }
            List<BlockPos> spawns = new ArrayList<>();
            for (JsonElement element : metadata.getAsJsonArray("spawns")) {
                spawns.add(position(element.getAsJsonObject()));
            }
            List<Chest> chests = new ArrayList<>();
            for (JsonElement element : metadata.getAsJsonArray("chests")) {
                JsonObject encoded = element.getAsJsonObject();
                SkyWarsLootPolicy.Tier tier = SkyWarsLootPolicy.Tier.valueOf(text(encoded, "tier").toUpperCase(Locale.ROOT));
                int spawnIndex = encoded.has("spawnIndex") ? encoded.get("spawnIndex").getAsInt() : -1;
                chests.add(new Chest(position(encoded), tier, spawnIndex));
            }
            validate(blocks, spawns, chests, maxRadius);
            return new SkyWarsImportedMap(id, name, author, source, license, maxRadius,
                    finalRadius, spawns, chests, blocks);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid imported SkyWars map: " + id, exception);
        }
    }

    private static InputStream resource(String path) throws IOException {
        InputStream input = SkyWarsImportedMap.class.getResourceAsStream(path);
        if (input == null) throw new IOException("Missing imported SkyWars map resource: " + path);
        return input;
    }

    private static String text(JsonObject object, String key) throws IOException {
        String value = object.get(key).getAsString();
        if (value.isBlank() || value.length() > 2048) throw new IOException("Invalid imported map field: " + key);
        return value;
    }

    private static BlockPos position(JsonObject encoded) {
        return new BlockPos(encoded.get("x").getAsInt(), encoded.get("y").getAsInt(), encoded.get("z").getAsInt());
    }

    private static Map<BlockPos, BlockState> readTerrain(NbtCompound root, int maxRadius) throws IOException {
        NbtList encodedPalette = root.getList("palette", NbtElement.COMPOUND_TYPE);
        if (encodedPalette.isEmpty() || encodedPalette.size() > MAX_PALETTE) {
            throw new IOException("Invalid imported SkyWars block palette");
        }
        List<BlockState> palette = new ArrayList<>(encodedPalette.size());
        for (int i = 0; i < encodedPalette.size(); i++) palette.add(blockState(encodedPalette.getCompound(i)));
        NbtList cells = root.getList("blocks", NbtElement.COMPOUND_TYPE);
        if (cells.isEmpty() || cells.size() > MAX_BLOCKS) throw new IOException("Invalid imported SkyWars block count");
        Map<BlockPos, BlockState> result = new HashMap<>();
        Set<BlockPos> seen = new HashSet<>();
        for (int i = 0; i < cells.size(); i++) {
            NbtCompound cell = cells.getCompound(i);
            NbtList xyz = cell.getList("pos", NbtElement.INT_TYPE);
            if (xyz.size() != 3 || !cell.contains("state", NbtElement.INT_TYPE)) {
                throw new IOException("Imported SkyWars map has malformed block position/state");
            }
            BlockPos pos = new BlockPos(xyz.getInt(0), xyz.getInt(1), xyz.getInt(2));
            int index = cell.getInt("state");
            if (!insideBounds(pos, maxRadius) || index < 0 || index >= palette.size() || !seen.add(pos)) {
                throw new IOException("Imported SkyWars map has duplicate/out-of-bounds block: " + pos);
            }
            BlockState state = palette.get(index);
            if (!state.isAir()) result.put(pos, state);
        }
        if (result.isEmpty()) throw new IOException("Imported SkyWars map contains no playable terrain");
        return result;
    }

    private static BlockState blockState(NbtCompound encoded) throws IOException {
        String name = encoded.getString("Name");
        Identifier id = Identifier.tryParse(name);
        if (id == null || !Registries.BLOCK.containsId(id)) throw new IOException("Unknown imported map block: " + name);
        Block block = Registries.BLOCK.get(id);
        if (EXCLUDED_BLOCKS.contains(block)) return Blocks.AIR.getDefaultState();
        BlockState state = block.getDefaultState();
        NbtCompound properties = encoded.getCompound("Properties");
        for (String key : properties.getKeys()) {
            Property<?> property = block.getStateManager().getProperty(key);
            if (property == null) throw new IOException("Unknown imported map block property: " + name + "." + key);
            state = withProperty(state, property, properties.getString(key));
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property,
                                                                    String value) throws IOException {
        T parsed = property.parse(value).orElseThrow(() -> new IOException("Invalid imported map property: "
                + property.getName() + "=" + value));
        return state.with(property, parsed);
    }

    private static boolean insideBounds(BlockPos pos, int maxRadius) {
        // 地图以竞技场区域中心为锚点，范围保留在世界高度限制内。
        return Math.hypot(pos.getX(), pos.getZ()) <= maxRadius && pos.getY() >= -128 && pos.getY() <= 160;
    }

    private static BlockState at(Map<BlockPos, BlockState> blocks, BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.getDefaultState());
    }

    private static boolean clear(Map<BlockPos, BlockState> blocks, BlockPos pos) {
        BlockState state = at(blocks, pos);
        return state.getFluidState().isEmpty() && state.getCollisionShape(EmptyBlockView.INSTANCE, pos).isEmpty()
                && !state.isOf(Blocks.FIRE) && !state.isOf(Blocks.SOUL_FIRE)
                && !state.isOf(Blocks.POWDER_SNOW) && !state.isOf(Blocks.SWEET_BERRY_BUSH);
    }

    private static boolean floor(Map<BlockPos, BlockState> blocks, BlockPos pos) {
        BlockState state = at(blocks, pos);
        return state.isFullCube(EmptyBlockView.INSTANCE, pos) && state.getFluidState().isEmpty()
                && !state.isOf(Blocks.MAGMA_BLOCK) && !state.isOf(Blocks.CACTUS);
    }

    private static void validate(Map<BlockPos, BlockState> blocks, List<BlockPos> spawns,
                                 List<Chest> chests, int maxRadius) throws IOException {
        if (spawns.size() < 2 || spawns.size() > 32 || new HashSet<>(spawns).size() != spawns.size()) {
            throw new IOException("Imported SkyWars map needs 2..32 distinct spawn slots");
        }
        for (BlockPos spawn : spawns) {
            if (!insideBounds(spawn, maxRadius - 2) || !floor(blocks, spawn.down())) {
                throw new IOException("Imported SkyWars spawn lacks a safe floor: " + spawn);
            }
            for (int dy = 0; dy <= 12; dy++) {
                if (!clear(blocks, spawn.up(dy))) throw new IOException("Imported SkyWars spawn/drop column is blocked: " + spawn);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 9; dy <= 12; dy++) {
                        if (!at(blocks, spawn.add(dx, dy, dz)).isAir()) {
                            throw new IOException("Imported SkyWars spawn cage overlaps map: " + spawn);
                        }
                    }
                }
            }
        }
        Set<BlockPos> registered = new HashSet<>();
        for (Chest chest : chests) {
            BlockState state = at(blocks, chest.pos());
            if (!registered.add(chest.pos()) || !isChest(state) || !floor(blocks, chest.pos().down())
                    || chest.tier() == SkyWarsLootPolicy.Tier.TRANSIT
                    || (chest.tier() == SkyWarsLootPolicy.Tier.SPAWN
                        && (chest.spawnIndex() < 0 || chest.spawnIndex() >= spawns.size()))) {
                throw new IOException("Invalid imported SkyWars chest registration: " + chest);
            }
            if (isChest(at(blocks, chest.pos().up()))) {
                throw new IOException("Imported SkyWars chests cannot be stacked: " + chest.pos());
            }
        }
        Set<BlockPos> actual = new HashSet<>();
        for (Map.Entry<BlockPos, BlockState> block : blocks.entrySet()) {
            if (isChest(block.getValue())) actual.add(block.getKey());
        }
        if (registered.isEmpty() || !registered.equals(actual)) {
            throw new IOException("Imported SkyWars metadata must register every chest exactly once");
        }
        for (int i = 0; i < spawns.size(); i++) {
            int slot = i;
            if (chests.stream().noneMatch(chest -> chest.tier() == SkyWarsLootPolicy.Tier.SPAWN && chest.spawnIndex() == slot)) {
                throw new IOException("Imported SkyWars spawn has no supply chest: " + i);
            }
        }
        if (chests.stream().noneMatch(chest -> chest.tier() == SkyWarsLootPolicy.Tier.CENTER)) {
            throw new IOException("Imported SkyWars map has no central supply chest");
        }
    }

    private static boolean isChest(BlockState state) {
        return state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST);
    }

    /** 首刷与之后的补货共用 layout 的真实箱位，不读取地图原始箱子内容。 */
    public void paste(ArenaWorld world, SkyWarsLayout layout, List<ServerPlayerEntity> players, boolean gunLoot) {
        BlockPos offset = layout.mapCenter();
        for (Map.Entry<BlockPos, BlockState> block : this.orderedBlocks) {
            world.setBlockState(block.getKey().add(offset), block.getValue(), Block.NOTIFY_ALL);
        }
        for (Chest configured : this.chests) {
            BlockPos pos = configured.pos().add(offset);
            world.setBlockState(pos.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            if (world.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                chest.setLootTable(null);
                chest.setLootTableSeed(0L);
                chest.clear();
                chest.markDirty();
            }
        }
        SkyWarsMapGenerator.populateChests(world, layout, SkyWarsTheme.OVERWORLD, players, false, gunLoot);
    }
}
