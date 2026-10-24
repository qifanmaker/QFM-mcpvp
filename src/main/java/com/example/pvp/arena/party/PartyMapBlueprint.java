package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * 将原世界中经过白名单提取的静态场景平移到本场区域。
 * 原包包含跨版本数据和全服命令，不能执行；此格式只允许现版本已知方块状态，
 * 不加载实体、命令方块 NBT、函数或任何外部路径。
 */
public final class PartyMapBlueprint {
    private static final Map<String, PartyMapBlueprint> CACHE = new HashMap<>();
    private record Cell(BlockPos sourcePosition, BlockState state) { }
    private final List<Cell> cells;
    private final BlockPos minimum;
    private final BlockPos maximum;

    private PartyMapBlueprint(List<Cell> cells, BlockPos minimum, BlockPos maximum) {
        this.cells = List.copyOf(cells);
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public static PartyMapBlueprint load(String name) throws IOException {
        if (!name.matches("[a-z_]+")) {
            throw new IOException("Invalid Party 2 map name: " + name);
        }
        PartyMapBlueprint cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        String path = "/pvp/party/geometry/" + name + ".json.gz";
        try (InputStream input = PartyMapBlueprint.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing Party 2 geometry: " + path);
            }
            try (InputStreamReader reader = new InputStreamReader(new GZIPInputStream(input), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.get("formatVersion").getAsInt() != 1) {
                    throw new IOException("Unsupported Party 2 geometry format");
                }
                JsonArray minimum = root.getAsJsonObject("bounds").getAsJsonArray("min");
                JsonArray maximum = root.getAsJsonObject("bounds").getAsJsonArray("max");
                BlockPos sourceMin = new BlockPos(minimum.get(0).getAsInt(), minimum.get(1).getAsInt(), minimum.get(2).getAsInt());
                BlockPos sourceMax = new BlockPos(maximum.get(0).getAsInt(), maximum.get(1).getAsInt(), maximum.get(2).getAsInt());
                if (sourceMax.getX() < sourceMin.getX() || sourceMax.getY() < sourceMin.getY()
                        || sourceMax.getZ() < sourceMin.getZ()) {
                    throw new IOException("Inverted Party 2 geometry bounds");
                }
                List<BlockState> palette = new ArrayList<>();
                for (JsonElement element : root.getAsJsonArray("palette")) {
                    JsonObject encoded = element.getAsJsonObject();
                    Identifier id = Identifier.of(encoded.get("Name").getAsString());
                    if (!Registries.BLOCK.containsId(id)) {
                        throw new IOException("Party 2 geometry uses an unavailable block: " + id);
                    }
                    Block block = Registries.BLOCK.get(id);
                    BlockState state = block.getDefaultState();
                    if (encoded.has("Properties")) {
                        for (Map.Entry<String, JsonElement> property : encoded.getAsJsonObject("Properties").entrySet()) {
                            Property<?> key = block.getStateManager().getProperty(property.getKey());
                            if (key == null) {
                                throw new IOException("Unknown block property: " + id + "." + property.getKey());
                            }
                            state = withProperty(state, key, property.getValue().getAsString());
                        }
                    }
                    palette.add(state);
                }
                List<Cell> cells = new ArrayList<>();
                for (JsonElement element : root.getAsJsonArray("blocks")) {
                    JsonArray cell = element.getAsJsonArray();
                    cells.add(new Cell(sourceMin.add(cell.get(0).getAsInt(), cell.get(1).getAsInt(), cell.get(2).getAsInt()),
                            palette.get(cell.get(3).getAsInt())));
                }
                PartyMapBlueprint result = new PartyMapBlueprint(cells, sourceMin, sourceMax);
                CACHE.put(name, result);
                return result;
            }
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException exception) {
            throw new IOException("Invalid Party 2 geometry: " + path, exception);
        }
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> key, String value) throws IOException {
        T parsed = key.parse(value).orElseThrow(() -> new IOException("Invalid property value: " + key.getName() + "=" + value));
        return state.with(key, parsed);
    }

    /** 接收者可以直接写入 Match 的 MapBuildQueue，避免在主线程同步粘贴整张图。 */
    public void forEachTranslated(BlockPos sourceOffset, BiConsumer<BlockPos, BlockState> writer) {
        for (Cell cell : this.cells) {
            writer.accept(cell.sourcePosition().add(sourceOffset), cell.state());
        }
    }

    public long volume() {
        return ((long) this.maximum.getX() - this.minimum.getX() + 1)
                * ((long) this.maximum.getY() - this.minimum.getY() + 1)
                * ((long) this.maximum.getZ() - this.minimum.getZ() + 1);
    }

    /** 含原本为空气的格子，用于回收运行时流体/落沙等填入的格子；调用者须先限制体积。 */
    public void forEachPosition(BlockPos sourceOffset, Consumer<BlockPos> visitor) {
        for (BlockPos position : BlockPos.iterate(this.minimum.add(sourceOffset), this.maximum.add(sourceOffset))) {
            visitor.accept(position);
        }
    }
}
