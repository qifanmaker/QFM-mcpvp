package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
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
    private final long[] relativePositions;
    private final int[] stateIndices;
    private final List<BlockState> palette;
    private final BlockPos minimum;
    private final BlockPos maximum;

    private PartyMapBlueprint(long[] relativePositions, int[] stateIndices, List<BlockState> palette,
                              BlockPos minimum, BlockPos maximum) {
        this.relativePositions = relativePositions;
        this.stateIndices = stateIndices;
        this.palette = List.copyOf(palette);
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
            // 完整洞穴场景超过百万格。逐格读取并缓存原始数组，避免先构造数百万个 Gson
            // 数字/数组对象，再额外保留同等数量的 Cell 和 BlockPos，造成开赛时的内存尖峰。
            try (JsonReader reader = new JsonReader(new InputStreamReader(new GZIPInputStream(input), StandardCharsets.UTF_8))) {
                int version = -1;
                BlockPos sourceMin = null;
                BlockPos sourceMax = null;
                List<BlockState> palette = null;
                LongArrayList positions = new LongArrayList();
                IntArrayList indices = new IntArrayList();
                boolean sawBlocks = false;
                reader.beginObject();
                while (reader.hasNext()) {
                    switch (reader.nextName()) {
                        case "formatVersion" -> version = reader.nextInt();
                        case "bounds" -> {
                            JsonObject bounds = JsonParser.parseReader(reader).getAsJsonObject();
                            sourceMin = position(bounds.getAsJsonArray("min"));
                            sourceMax = position(bounds.getAsJsonArray("max"));
                        }
                        case "palette" -> palette = readPalette(JsonParser.parseReader(reader).getAsJsonArray());
                        case "blocks" -> {
                            sawBlocks = true;
                            reader.beginArray();
                            while (reader.hasNext()) {
                                reader.beginArray();
                                int x = reader.nextInt(), y = reader.nextInt(), z = reader.nextInt();
                                int index = reader.nextInt();
                                reader.endArray();
                                long packed = BlockPos.asLong(x, y, z);
                                if (x < 0 || y < 0 || z < 0 || BlockPos.unpackLongX(packed) != x
                                        || BlockPos.unpackLongY(packed) != y || BlockPos.unpackLongZ(packed) != z) {
                                    throw new IOException("Invalid relative Party 2 block position");
                                }
                                positions.add(packed);
                                indices.add(index);
                            }
                            reader.endArray();
                        }
                        default -> reader.skipValue();
                    }
                }
                reader.endObject();
                if (version != 1) {
                    throw new IOException("Unsupported Party 2 geometry format");
                }
                if (sourceMin == null || sourceMax == null || palette == null || !sawBlocks) {
                    throw new IOException("Incomplete Party 2 geometry metadata");
                }
                if (sourceMax.getX() < sourceMin.getX() || sourceMax.getY() < sourceMin.getY()
                        || sourceMax.getZ() < sourceMin.getZ()) {
                    throw new IOException("Inverted Party 2 geometry bounds");
                }
                for (int i = 0; i < positions.size(); i++) {
                    long packed = positions.getLong(i);
                    if (BlockPos.unpackLongX(packed) > (long) sourceMax.getX() - sourceMin.getX()
                            || BlockPos.unpackLongY(packed) > (long) sourceMax.getY() - sourceMin.getY()
                            || BlockPos.unpackLongZ(packed) > (long) sourceMax.getZ() - sourceMin.getZ()
                            || indices.getInt(i) < 0 || indices.getInt(i) >= palette.size()) {
                        throw new IOException("Party 2 geometry block outside bounds or palette");
                    }
                }
                PartyMapBlueprint result = new PartyMapBlueprint(positions.toLongArray(), indices.toIntArray(), palette,
                        sourceMin, sourceMax);
                CACHE.put(name, result);
                return result;
            }
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException exception) {
            throw new IOException("Invalid Party 2 geometry: " + path, exception);
        }
    }

    private static BlockPos position(JsonArray coordinates) throws IOException {
        if (coordinates == null || coordinates.size() != 3) throw new IOException("Invalid Party 2 geometry bounds");
        return new BlockPos(coordinates.get(0).getAsInt(), coordinates.get(1).getAsInt(), coordinates.get(2).getAsInt());
    }

    private static List<BlockState> readPalette(JsonArray encodedPalette) throws IOException {
        List<BlockState> palette = new ArrayList<>();
        for (JsonElement element : encodedPalette) {
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
        return palette;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> key, String value) throws IOException {
        T parsed = key.parse(value).orElseThrow(() -> new IOException("Invalid property value: " + key.getName() + "=" + value));
        return state.with(key, parsed);
    }

    /** 接收者可以直接写入 Match 的 MapBuildQueue，避免在主线程同步粘贴整张图。 */
    public void forEachTranslated(BlockPos sourceOffset, BiConsumer<BlockPos, BlockState> writer) {
        BlockPos translatedMinimum = this.minimum.add(sourceOffset);
        for (int i = 0; i < this.relativePositions.length; i++) {
            writer.accept(BlockPos.fromLong(this.relativePositions[i]).add(translatedMinimum),
                    this.palette.get(this.stateIndices[i]));
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
