package com.example.pvp.arena.bedwars;

import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.PackedIntegerArray;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Bed Wars 地图加载器：读取世界存档（level.dat + region/*.mca）中的方块数据。
 * 支持 1.13+ 扁平化区块格式（palette + 位压缩 block_states），即 1.21.1 原生格式。
 */
public final class BedWarsMapLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 加载结果：方块表 + 世界出生点（大厅）+ 统计信息。 */
    public static final class MapData {
        public final Map<BlockPos, BlockState> blocks = new HashMap<>();
        public final List<NbtCompound> blockEntities = new java.util.ArrayList<>();
        public BlockPos lobbySpawn = new BlockPos(0, 148, 0);
        public BlockPos min = null;
        public BlockPos max = null;

        public int size() {
            return this.blocks.size();
        }

        /** 地图中心（XZ，方块坐标空间）。 */
        public BlockPos centerXZ() {
            if (this.min == null || this.max == null) {
                return new BlockPos(0, 0, 0);
            }
            return new BlockPos((min.getX() + max.getX()) / 2, 0, (min.getZ() + max.getZ()) / 2);
        }

        /** 地图最低 Y（用于整体平移到竞技场平台高度）。 */
        public int minY() {
            return this.min == null ? 0 : this.min.getY();
        }
    }

    private BedWarsMapLoader() {
    }

    /** 加载一张床战地图（读 level.dat 与全部 region 区块）。 */
    public static MapData load(Path mapDir) {
        MapData data = new MapData();

        // 1. level.dat：世界出生点 = 等待大厅
        Path levelDat = mapDir.resolve("level.dat");
        if (Files.exists(levelDat)) {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(levelDat))) {
                NbtCompound root = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
                NbtCompound d = root.getCompound("Data");
                if (d.contains("SpawnX") && d.contains("SpawnZ")) {
                    data.lobbySpawn = new BlockPos(d.getInt("SpawnX"), d.getInt("SpawnY"), d.getInt("SpawnZ"));
                }
            } catch (Exception e) {
                LOGGER.warn("[PvP] BedWars 读取 level.dat 失败: {}", e.toString());
            }
        }

        // 2. region/*.mca：遍历所有区块
        Path regionDir = mapDir.resolve("region");
        if (!Files.isDirectory(regionDir)) {
            LOGGER.warn("[PvP] BedWars 地图无 region 目录: {}", mapDir);
            return data;
        }
        List<Path> mcaFiles;
        try (Stream<Path> s = Files.list(regionDir)) {
            mcaFiles = s.filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            LOGGER.warn("[PvP] BedWars 读取 region 目录失败: {}", e.toString());
            return data;
        }
        for (Path mca : mcaFiles) {
            readRegion(mca, data);
        }

        // 统一计算地图范围（min/max），一次 O(n) 而非每 chunk O(n²)
        for (BlockPos pos : data.blocks.keySet()) {
            if (data.min == null) {
                data.min = pos;
                data.max = pos;
            } else {
                data.min = new BlockPos(Math.min(data.min.getX(), pos.getX()),
                        Math.min(data.min.getY(), pos.getY()),
                        Math.min(data.min.getZ(), pos.getZ()));
                data.max = new BlockPos(Math.max(data.max.getX(), pos.getX()),
                        Math.max(data.max.getY(), pos.getY()),
                        Math.max(data.max.getZ(), pos.getZ()));
            }
        }

        // 删除原世界底部地面层（基岩/泥土/石头等自然方块），避免粘贴到竞技场后形成"地面"而非虚空
        if (data.min != null) {
            int groundMaxY = data.min.getY() + 2; // 地面层厚度最多 3 格
            List<BlockPos> toRemove = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockState> e : data.blocks.entrySet()) {
                BlockPos pos = e.getKey();
                if (pos.getY() > groundMaxY) {
                    continue;
                }
                BlockState s = e.getValue();
                if (s.isOf(Blocks.BEDROCK) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.GRASS_BLOCK)
                        || s.isOf(Blocks.STONE) || s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.SAND)
                        || s.isOf(Blocks.SANDSTONE) || s.isOf(Blocks.DEEPSLATE) || s.isOf(Blocks.TUFF)
                        || s.isOf(Blocks.CLAY) || s.isOf(Blocks.MUD) || s.isOf(Blocks.PODZOL)
                        || s.isOf(Blocks.MYCELIUM) || s.isOf(Blocks.COARSE_DIRT) || s.isOf(Blocks.ROOTED_DIRT)
                        || s.isOf(Blocks.POLISHED_DEEPSLATE) || s.isOf(Blocks.DEEPSLATE_BRICKS)
                        || s.isOf(Blocks.DEEPSLATE_TILES) || s.isOf(Blocks.CRACKED_DEEPSLATE_BRICKS)
                        || s.isOf(Blocks.CRACKED_DEEPSLATE_TILES) || s.isOf(Blocks.CHISELED_DEEPSLATE)
                        || s.isOf(Blocks.COBBLED_DEEPSLATE) || s.isOf(Blocks.POLISHED_BASALT)
                        || s.isOf(Blocks.BASALT) || s.isOf(Blocks.SMOOTH_BASALT)
                        || s.isOf(Blocks.ANDESITE) || s.isOf(Blocks.DIORITE) || s.isOf(Blocks.GRANITE)
                        || s.isOf(Blocks.POLISHED_ANDESITE) || s.isOf(Blocks.POLISHED_DIORITE)
                        || s.isOf(Blocks.POLISHED_GRANITE) || s.isOf(Blocks.CALCITE)
                        || s.isOf(Blocks.DRIPSTONE_BLOCK) || s.isOf(Blocks.MOSS_BLOCK)
                        || s.isOf(Blocks.MOSSY_COBBLESTONE) || s.isOf(Blocks.MOSSY_STONE_BRICKS)) {
                    toRemove.add(pos);
                }
            }
            for (BlockPos pos : toRemove) {
                data.blocks.remove(pos);
            }
            // 重新计算 min/max（地面层已删）
            if (!toRemove.isEmpty()) {
                data.min = null;
                data.max = null;
                for (BlockPos pos : data.blocks.keySet()) {
                    if (data.min == null) {
                        data.min = pos;
                        data.max = pos;
                    } else {
                        data.min = new BlockPos(Math.min(data.min.getX(), pos.getX()),
                                Math.min(data.min.getY(), pos.getY()),
                                Math.min(data.min.getZ(), pos.getZ()));
                        data.max = new BlockPos(Math.max(data.max.getX(), pos.getX()),
                                Math.max(data.max.getY(), pos.getY()),
                                Math.max(data.max.getZ(), pos.getZ()));
                    }
                }
                LOGGER.info("[PvP] BedWars 地图已删除底部地面层: {} 方块", toRemove.size());
            }
        }

        LOGGER.info("[PvP] BedWars 地图已加载: {} 方块, {} 方块实体, 大厅 {}", 
                data.size(), data.blockEntities.size(), data.lobbySpawn);
        return data;
    }

    /** 读取一个 region 文件的所有区块。 */
    private static void readRegion(Path mca, MapData data) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mca.toFile(), "r")) {
            if (raf.length() < 8192) {
                return; // 空 region
            }
            // 位置表：1024 项 × 4 字节（3 字节扇区偏移 + 1 字节长度）
            byte[] header = new byte[4096];
            raf.readFully(header);
            for (int i = 0; i < 1024; i++) {
                int offset = ((header[i * 4] & 0xFF) << 16) | ((header[i * 4 + 1] & 0xFF) << 8) | (header[i * 4 + 2] & 0xFF);
                int sectorCount = header[i * 4 + 3] & 0xFF;
                if (offset == 0 || sectorCount == 0) {
                    continue;
                }
                readChunk(raf, offset, sectorCount, data);
            }
        } catch (IOException e) {
            LOGGER.warn("[PvP] BedWars 读取 region 失败 {}: {}", mca, e.toString());
        }
    }

    /** 读取单个 chunk 数据（定位到扇区偏移，读长度 + 压缩类型 + 数据，NbtIo 解析）。 */
    private static void readChunk(java.io.RandomAccessFile raf, int sectorOffset, int sectorCount,
                                  MapData data) {
        try {
            raf.seek(sectorOffset * 4096L);
            int length = raf.readInt();
            int compressionType = raf.readUnsignedByte();
            if (length <= 0 || length > sectorCount * 4096 - 5) {
                return; // 损坏
            }
            byte[] raw = new byte[length - 1];
            raf.readFully(raw);
            NbtCompound chunk;
            switch (compressionType) {
                case 1 -> { // gzip
                    try (DataInputStream in = new DataInputStream(
                            new BufferedInputStream(new GZIPInputStream(new java.io.ByteArrayInputStream(raw))))) {
                        chunk = NbtIo.readCompound(in, NbtSizeTracker.ofUnlimitedBytes());
                    }
                }
                case 2 -> { // zlib
                    try (DataInputStream in = new DataInputStream(
                            new BufferedInputStream(new InflaterInputStream(new java.io.ByteArrayInputStream(raw))))) {
                        chunk = NbtIo.readCompound(in, NbtSizeTracker.ofUnlimitedBytes());
                    }
                }
                default -> { // 3 = 未压缩
                    try (DataInputStream in = new DataInputStream(
                            new BufferedInputStream(new java.io.ByteArrayInputStream(raw)))) {
                        chunk = NbtIo.readCompound(in, NbtSizeTracker.ofUnlimitedBytes());
                    }
                }
            }
            if (chunk == null) {
                return;
            }
            int chunkX = chunk.getInt("xPos");
            int chunkZ = chunk.getInt("zPos");
            NbtList sections = chunk.getList("sections", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < sections.size(); i++) {
                NbtCompound section = sections.getCompound(i);
                int sectionY = section.getInt("Y");
                decodeSection(data.blocks, chunkX, chunkZ, sectionY, section);
            }
            // 方块实体（箱子/熔炉等）：保存 NBT 供后续迁移
            NbtList blockEntities = chunk.getList("block_entities", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < blockEntities.size(); i++) {
                data.blockEntities.add(blockEntities.getCompound(i));
            }
        } catch (Exception e) {
            // 单个 chunk 损坏不影响其他
        }
    }

    // ---------- 辅助 ----------

    /** 解析 palette 项为 BlockState（Name + Properties）。 */
    static BlockState decodePaletteEntry(NbtCompound entry) {
        String name = entry.getString("Name");
        Block block = Registries.BLOCK.get(Identifier.tryParse(name));
        if (block == null || block == Blocks.AIR) {
            return Blocks.AIR.getDefaultState();
        }
        BlockState state = block.getDefaultState();
        if (entry.contains("Properties", NbtElement.COMPOUND_TYPE)) {
            NbtCompound props = entry.getCompound("Properties");
            for (String key : props.getKeys()) {
                Property<?> property = block.getStateManager().getProperty(key);
                if (property != null) {
                    String value = props.getString(key);
                    state = applyProperty(state, property, value);
                }
            }
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> property, String value) {
        return property.parse(value).map(v -> state.with(property, v)).orElse(state);
    }

    /** 解码一个 section 的 block_states（palette + 位压缩 data）。 */
    static void decodeSection(Map<BlockPos, BlockState> out, int chunkX, int chunkZ, int sectionY,
                              NbtCompound section) {
        if (!section.contains("block_states", NbtElement.COMPOUND_TYPE)) {
            return;
        }
        NbtCompound bs = section.getCompound("block_states");
        NbtList palette = bs.getList("palette", NbtElement.COMPOUND_TYPE);
        if (palette.isEmpty()) {
            return;
        }
        long[] data = bs.getLongArray("data");
        List<BlockState> states = new java.util.ArrayList<>(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            states.add(decodePaletteEntry(palette.getCompound(i)));
        }
        // 单 palette（整层同方块）：data 为空
        if (data.length == 0 && states.size() == 1) {
            BlockState state = states.get(0);
            if (state.isAir()) {
                return;
            }
            int baseX = chunkX * 16;
            int baseZ = chunkZ * 16;
            int baseY = sectionY * 16;
            for (int i = 0; i < 4096; i++) {
                // YZX 顺序：i = y*256 + z*16 + x
                out.put(new BlockPos(baseX + (i & 15), baseY + ((i >> 8) & 15), baseZ + ((i >> 4) & 15)), state);
            }
            return;
        }
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(states.size() - 1));
        PackedIntegerArray array = new PackedIntegerArray(bits, 4096, data);
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        int baseY = sectionY * 16;
        for (int i = 0; i < 4096; i++) {
            int idx = array.get(i);
            if (idx < 0 || idx >= states.size()) {
                continue;
            }
            BlockState state = states.get(idx);
            if (state.isAir()) {
                continue;
            }
            // YZX 顺序：i = y*256 + z*16 + x
            out.put(new BlockPos(baseX + (i & 15), baseY + ((i >> 8) & 15), baseZ + ((i >> 4) & 15)), state);
        }
    }
}
