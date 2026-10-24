package com.example.pvp.mygo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 导入原图必须同时携带其真实出生、朝向和包点，不能沿用旧仓储场的平面硬编码。
 * 此蓝图只读取随 Mod 分发的静态方块状态与相对坐标，不执行原世界的实体、命令或函数。
 */
public final class MyGoMapBlueprint {
    private static final String RESOURCE = "/pvp/mygo/maps/dust2.json.gz";
    private static final int GEOMETRY_HALF_EXTENT = 160;
    private static final int REGION_HALF_EXTENT = 176;
    private static final int MAX_CELLS = 1_000_000;
    private static final Set<String> EXECUTABLE_BLOCKS = Set.of(
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:jigsaw", "minecraft:spawner");
    private static MyGoMapBlueprint cached;

    public record Bounds(BlockPos min, BlockPos max) {
        public Bounds {
            min = min.toImmutable();
            max = max.toImmutable();
            if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
                throw new IllegalArgumentException("Inverted MyGO bounds");
            }
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= this.min.getX() && pos.getX() <= this.max.getX()
                    && pos.getY() >= this.min.getY() && pos.getY() <= this.max.getY()
                    && pos.getZ() >= this.min.getZ() && pos.getZ() <= this.max.getZ();
        }

        public boolean contains(Bounds other) {
            return contains(other.min()) && contains(other.max());
        }
    }

    public record Spawn(BlockPos pos, float yaw) {
        public Spawn { pos = pos.toImmutable(); }
    }

    public record Site(String name, BlockPos pos, Bounds bounds) {
        public Site { pos = pos.toImmutable(); }
    }

    public record Cell(BlockPos pos, BlockState state) {
        public Cell { pos = pos.toImmutable(); }
    }

    private final String id;
    private final String name;
    private final Bounds bounds;
    private final Bounds playBounds;
    private final List<Cell> cells;
    private final List<Spawn> attackerSpawns;
    private final List<Spawn> defenderSpawns;
    private final Bounds attackerBuyZone;
    private final Bounds defenderBuyZone;
    private final List<Site> sites;
    private final BlockPos spectator;
    private final Map<String, BlockPos> testAnchors;

    private MyGoMapBlueprint(String id, String name, Bounds bounds, Bounds playBounds, List<Cell> cells,
                             List<Spawn> attackerSpawns, List<Spawn> defenderSpawns,
                             Bounds attackerBuyZone, Bounds defenderBuyZone, List<Site> sites,
                             BlockPos spectator, Map<String, BlockPos> testAnchors) {
        this.id = id;
        this.name = name;
        this.bounds = bounds;
        this.playBounds = playBounds;
        this.cells = List.copyOf(cells);
        this.attackerSpawns = List.copyOf(attackerSpawns);
        this.defenderSpawns = List.copyOf(defenderSpawns);
        this.attackerBuyZone = attackerBuyZone;
        this.defenderBuyZone = defenderBuyZone;
        this.sites = List.copyOf(sites);
        this.spectator = spectator.toImmutable();
        this.testAnchors = Map.copyOf(testAnchors);
    }

    public static synchronized MyGoMapBlueprint load() throws IOException {
        if (cached != null) return cached;
        try (InputStream input = MyGoMapBlueprint.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Missing MyGO map blueprint: " + RESOURCE);
            try (InputStreamReader reader = new InputStreamReader(new GZIPInputStream(input), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                cached = decode(root);
                return cached;
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid MyGO map blueprint: " + RESOURCE, exception);
        }
    }

    private static MyGoMapBlueprint decode(JsonObject root) throws IOException {
        if (integer(root.get("formatVersion")) != 1) throw new IOException("Unsupported MyGO map format");
        String id = root.get("id").getAsString();
        String name = root.get("name").getAsString();
        if (!id.matches("[a-z0-9_]+") || name.isBlank()) throw new IOException("Invalid MyGO map identity");
        Bounds bounds = bounds(root.getAsJsonObject("bounds"));
        Bounds playBounds = bounds(root.getAsJsonObject("playBounds"));
        validateExtent(bounds, GEOMETRY_HALF_EXTENT);
        validateExtent(playBounds, GEOMETRY_HALF_EXTENT);
        // 旧版低层黑名单与窄高度框会把可步行的地面、台阶和屋顶判为出界。
        // 整张建筑及其跳跃净空必须合法；这里只保留隔离场次和虚空救援的外框。
        if (!playBounds.contains(bounds) || playBounds.max().getY() < bounds.max().getY() + 3)
            throw new IOException("MyGO playable area must include all geometry and rooftop headroom");
        List<BlockState> palette = palette(root.getAsJsonArray("palette"));
        JsonArray encodedCells = root.getAsJsonArray("blocks");
        if (encodedCells.isEmpty() || encodedCells.size() > MAX_CELLS) throw new IOException("Invalid MyGO block count");
        List<Cell> cells = new ArrayList<>(encodedCells.size());
        LongOpenHashSet positions = new LongOpenHashSet(encodedCells.size());
        for (JsonElement element : encodedCells) {
            JsonArray encoded = element.getAsJsonArray();
            if (encoded.size() != 4) throw new IOException("MyGO cell requires x, y, z and palette index");
            BlockPos pos = new BlockPos(integer(encoded.get(0)), integer(encoded.get(1)), integer(encoded.get(2)));
            int state = integer(encoded.get(3));
            if (!bounds.contains(pos) || state < 0 || state >= palette.size() || !positions.add(pos.asLong())) {
                throw new IOException("Invalid, duplicate or out-of-bounds MyGO cell: " + pos);
            }
            cells.add(new Cell(pos, palette.get(state)));
        }
        JsonObject encodedBuyZones = root.getAsJsonObject("buyZones");
        Bounds attackerBuyZone = bounds(encodedBuyZones.getAsJsonObject("T"));
        Bounds defenderBuyZone = bounds(encodedBuyZones.getAsJsonObject("CT"));
        if (!playBounds.contains(attackerBuyZone) || !playBounds.contains(defenderBuyZone)) {
            throw new IOException("MyGO buy zones must be inside the playable area");
        }
        JsonObject encodedSpawns = root.getAsJsonObject("spawns");
        LongOpenHashSet uniqueSpawns = new LongOpenHashSet(10);
        List<Spawn> attackerSpawns = spawns(encodedSpawns.getAsJsonArray("T"), attackerBuyZone, uniqueSpawns);
        List<Spawn> defenderSpawns = spawns(encodedSpawns.getAsJsonArray("CT"), defenderBuyZone, uniqueSpawns);
        List<Site> sites = new ArrayList<>();
        JsonArray encodedSites = root.getAsJsonArray("sites");
        if (encodedSites.size() != 2) throw new IOException("MyGO map requires bomb sites A and B");
        for (JsonElement element : encodedSites) {
            JsonObject encoded = element.getAsJsonObject();
            String siteName = encoded.get("name").getAsString();
            if (!(sites.isEmpty() ? "A" : "B").equals(siteName)) throw new IOException("MyGO bomb sites must be ordered A, B");
            BlockPos pos = position(encoded.getAsJsonArray("pos"));
            Bounds siteBounds = bounds(encoded);
            if (!playBounds.contains(siteBounds) || !siteBounds.contains(pos)) throw new IOException("MyGO bomb site is outside its bounds");
            sites.add(new Site(siteName, pos, siteBounds));
        }
        BlockPos spectator = position(root.getAsJsonArray("spectator"));
        validateExtent(new Bounds(spectator, spectator), REGION_HALF_EXTENT);
        if (playBounds.contains(spectator)) throw new IOException("MyGO spectator room must be outside the playable area");
        Map<String, BlockPos> testAnchors = new LinkedHashMap<>();
        if (root.has("testAnchors")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("testAnchors").entrySet()) {
                BlockPos pos = position(entry.getValue().getAsJsonArray());
                if (!playBounds.contains(pos)) throw new IOException("MyGO test anchor is outside the playable area: " + entry.getKey());
                testAnchors.put(entry.getKey(), pos);
            }
        }
        return new MyGoMapBlueprint(id, name, bounds, playBounds, cells, attackerSpawns, defenderSpawns,
                attackerBuyZone, defenderBuyZone, sites, spectator, testAnchors);
    }

    private static List<Spawn> spawns(JsonArray encodedSpawns, Bounds buyZone, LongOpenHashSet unique) throws IOException {
        if (encodedSpawns.size() != 5) throw new IOException("MyGO map requires exactly five spawns per side");
        List<Spawn> result = new ArrayList<>(5);
        for (JsonElement element : encodedSpawns) {
            JsonObject encoded = element.getAsJsonObject();
            BlockPos pos = position(encoded.getAsJsonArray("pos"));
            float yaw = encoded.get("yaw").getAsFloat();
            if (!buyZone.contains(pos) || !unique.add(pos.asLong()) || !Float.isFinite(yaw) || yaw < -180 || yaw > 180) {
                throw new IOException("Invalid or overlapping MyGO spawn: " + pos);
            }
            result.add(new Spawn(pos, yaw));
        }
        return result;
    }

    private static List<BlockState> palette(JsonArray encodedPalette) throws IOException {
        if (encodedPalette.isEmpty() || encodedPalette.size() > 4096) throw new IOException("Invalid MyGO palette size");
        List<BlockState> result = new ArrayList<>(encodedPalette.size());
        for (JsonElement element : encodedPalette) {
            JsonObject encoded = element.getAsJsonObject();
            Identifier id = Identifier.of(encoded.get("Name").getAsString());
            if (!Registries.BLOCK.containsId(id) || EXECUTABLE_BLOCKS.contains(id.toString())) {
                throw new IOException("Unavailable or executable MyGO map block: " + id);
            }
            Block block = Registries.BLOCK.get(id);
            BlockState state = block.getDefaultState();
            if (encoded.has("Properties")) {
                for (Map.Entry<String, JsonElement> property : encoded.getAsJsonObject("Properties").entrySet()) {
                    Property<?> key = block.getStateManager().getProperty(property.getKey());
                    if (key == null) throw new IOException("Unknown MyGO block property: " + id + "." + property.getKey());
                    state = withProperty(state, key, property.getValue().getAsString());
                }
            }
            result.add(state);
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> key, String value) throws IOException {
        T parsed = key.parse(value).orElseThrow(() -> new IOException("Invalid MyGO block property: " + key.getName() + "=" + value));
        return state.with(key, parsed);
    }

    private static Bounds bounds(JsonObject encoded) throws IOException {
        return new Bounds(position(encoded.getAsJsonArray("min")), position(encoded.getAsJsonArray("max")));
    }

    private static BlockPos position(JsonArray encoded) throws IOException {
        if (encoded.size() != 3) throw new IOException("MyGO position must have three integers");
        return new BlockPos(integer(encoded.get(0)), integer(encoded.get(1)), integer(encoded.get(2)));
    }

    private static int integer(JsonElement encoded) throws IOException {
        try {
            return encoded.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IOException("MyGO position and palette values must be integers", exception);
        }
    }

    private static void validateExtent(Bounds bounds, int horizontalLimit) throws IOException {
        if (bounds.min().getX() < -horizontalLimit || bounds.max().getX() > horizontalLimit
                || bounds.min().getZ() < -horizontalLimit || bounds.max().getZ() > horizontalLimit
                || bounds.min().getY() < -32 || bounds.max().getY() > 128) {
            throw new IOException("MyGO map exceeds its isolated region");
        }
    }

    public String id() { return this.id; }
    public String name() { return this.name; }
    public Bounds bounds() { return this.bounds; }
    public Bounds playBounds() { return this.playBounds; }
    public boolean isPlayable(BlockPos pos) {
        return this.playBounds.contains(pos);
    }
    public List<Cell> cells() { return this.cells; }
    public List<Spawn> spawns(boolean attackers) { return attackers ? this.attackerSpawns : this.defenderSpawns; }
    public Spawn spawn(boolean attackers, int index) { return spawns(attackers).get(index); }
    public Bounds buyZone(boolean attackers) { return attackers ? this.attackerBuyZone : this.defenderBuyZone; }
    public List<Site> sites() { return this.sites; }
    public BlockPos spectator() { return this.spectator; }
    public Map<String, BlockPos> testAnchors() { return this.testAnchors; }
}
