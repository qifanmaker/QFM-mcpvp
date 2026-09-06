package com.example.pvp.arena.villagedefense;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.mojang.logging.LogUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 把 Village Defense 的 1.12 Anvil 世界（maps/villagedefense/&lt;map&gt;/region/*.mca）粘贴进竞技场世界。
 *
 * <p>解码 1.8~1.12 经典 Section：每个 Section 存 {@code Blocks[4096]}（块 id）+ {@code Data[2048]}
 * （meta 半字节，偶低奇高）；块 id+meta 经 {@link LegacyBlockMap} 映射成现代 {@link BlockState}。
 *
 * <p>平移：世界坐标 (0,?,0) 对齐到竞技场区域中心，y 用 groundWorldY 对齐到 PLATFORM_Y；
 * 坐标整体加常量平移，结构相对位置不变。每列在低于已贴内容处补实心基座，避免悬空露底。
 */
public final class VillageWorldImporter {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 竞技场地面方块 Y。 */
    private static final int FLOOR_Y = ArenaTemplate.PLATFORM_Y;

    private VillageWorldImporter() {
    }

    /** 导入结果：平移后竞技场坐标的游戏点 + 已粘贴区域包围盒。 */
    public static final class Layout {
        public final String mapName;
        public final BlockPos center;
        public final List<BlockPos> villagerSpawns = new ArrayList<>();
        public final List<BlockPos> zombieSpawns = new ArrayList<>();
        public final List<BlockPos> doors = new ArrayList<>();
        public BlockPos shop;
        public BlockPos minCorner;
        public BlockPos maxCorner;

        Layout(String mapName, BlockPos center) {
            this.mapName = mapName;
            this.center = center;
        }
    }

    /** 世界 (0,?,0) → 竞技场 center；世界 groundY → 竞技场 FLOOR_Y。 */
    private static int dxOf(BlockPos center) {
        return center.getX();
    }

    private static int dyOf(int groundWorldY) {
        return FLOOR_Y - groundWorldY;
    }

    /**
     * 从地图目录导入并粘贴。
     *
     * @param world    竞技场世界
     * @param mapFolder maps/villagedefense/&lt;mapName&gt;/
     * @param mapName   地图名（仅日志/展示）
     * @param center    竞技场粘贴中心
     */
    public static Layout importMap(ArenaWorld world, Path mapFolder, String mapName, BlockPos center) {
        VillageDefenseMeta meta = VillageDefenseMeta.load(mapFolder);
        int dx = center.getX();
        int dy = FLOOR_Y - meta.groundWorldY;
        int dz = center.getZ();

        // 由游戏坐标点 + 边距推导需要导入的包围盒（世界坐标）
        List<double[]> points = new ArrayList<>();
        points.addAll(meta.villagerSpawns);
        points.addAll(meta.zombieSpawns);
        points.addAll(meta.doors);
        if (meta.shop != null) {
            points.add(meta.shop);
        }
        if (points.isEmpty()) {
            for (int i = 0; i < 8; i++) {
                double a = i / 8.0 * 2 * Math.PI;
                points.add(new double[]{Math.cos(a) * 12, 80, Math.sin(a) * 12});
            }
        }
        int minWx = Integer.MAX_VALUE, maxWx = Integer.MIN_VALUE;
        int minWz = Integer.MAX_VALUE, maxWz = Integer.MIN_VALUE;
        int minWy = Integer.MAX_VALUE, maxWy = Integer.MIN_VALUE;
        for (double[] p : points) {
            minWx = Math.min(minWx, (int) Math.floor(p[0]));
            maxWx = Math.max(maxWx, (int) Math.floor(p[0]));
            minWy = Math.min(minWy, (int) Math.floor(p[1]));
            maxWy = Math.max(maxWy, (int) Math.floor(p[1]));
            minWz = Math.min(minWz, (int) Math.floor(p[2]));
            maxWz = Math.max(maxWz, (int) Math.floor(p[2]));
        }
        int margin = 10;
        int minX = minWx - margin, maxX = maxWx + margin;
        int minZ = minWz - margin, maxZ = maxWz + margin;
        int lowY = Math.min(meta.groundWorldY, minWy) - 6;
        int highY = Math.max(meta.groundWorldY, maxWy) + 26;
        if (highY - lowY > 96) {
            highY = lowY + 96;
        }

        Path regionDir = mapFolder.resolve("region");
        if (!Files.isDirectory(regionDir)) {
            LOGGER.error("[VD] 地图目录缺少 region/: {}", regionDir);
            return new Layout(mapName, center);
        }

        Layout layout = new Layout(mapName, center);
        int placed = 0;
        int unmapped = 0;
        Map<Long, Integer> columnLowest = new HashMap<>();

        int c0x = Math.floorDiv(minX, 16), c1x = Math.floorDiv(maxX, 16);
        int c0z = Math.floorDiv(minZ, 16), c1z = Math.floorDiv(maxZ, 16);
        List<ChunkSections> chunks = readChunks(regionDir, c0x, c1x, c0z, c1z, lowY, highY);
        for (ChunkSections chunk : chunks) {
            int baseX = chunk.cx * 16;
            int baseZ = chunk.cz * 16;
            for (SectionData sd : chunk.sections) {
                for (int index = 0; index < 4096; index++) {
                    int id = sd.blocks[index] & 0xFF;
                    if (sd.add != null) {
                        id |= nibble(sd.add, index) << 8;
                    }
                    if (id == 0) {
                        continue; // 空气
                    }
                    int md = sd.data != null ? nibble(sd.data, index) : 0;
                    int wx = baseX + (index & 15);
                    int wz = baseZ + ((index >> 4) & 15);
                    int wy = sd.y * 16 + (index >> 8);
                    if (wx < minX || wx > maxX || wz < minZ || wz > maxZ || wy < lowY || wy > highY) {
                        continue;
                    }
                    BlockState state;
                    try {
                        state = LegacyBlockMap.stateFor(id, md);
                    } catch (Exception e) {
                        LOGGER.warn("[VD] 方块 id={} meta={} 映射异常，跳过: {}", id, md, e.toString());
                        unmapped++;
                        continue;
                    }
                    if (state == null || state.isAir()) {
                        unmapped++;
                        continue;
                    }
                    int ax = wx + dx;
                    int az = wz + dz;
                    int ay = wy + dy;
                    if (ay < world.getBottomY() || ay > world.getTopY() - 1) {
                        continue;
                    }
                    world.setBlockState(new BlockPos(ax, ay, az), state, 3);
                    placed++;
                    int prev = columnLowest.getOrDefault(pack(ax, az), Integer.MAX_VALUE);
                    if (ay < prev) {
                        columnLowest.put(pack(ax, az), ay);
                    }
                }
            }
        }

        int pad = 0;
        for (Map.Entry<Long, Integer> e : columnLowest.entrySet()) {
            int x = (int) (e.getKey() >>> 32);
            int z = (int) (e.getKey().intValue());
            int lowest = e.getValue();
            if (lowest <= FLOOR_Y + 1) {
                continue;
            }
            for (int y = FLOOR_Y; y < lowest; y++) {
                world.setBlockState(new BlockPos(x, y, z), Blocks.COBBLESTONE.getDefaultState(), 3);
                pad++;
            }
        }

        LOGGER.info("[VD] 导入 {}：放置 {} 方块，垫底 {}，未映射 {}。范围 x[{},{}] y[{},{}] z[{},{}]",
                mapName, placed, pad, unmapped, minX, maxX, lowY, highY, minZ, maxZ);

        for (double[] p : meta.villagerSpawns) {
            layout.villagerSpawns.add(new BlockPos((int) Math.floor(p[0]) + dx, (int) Math.floor(p[1]) + dy,
                    (int) Math.floor(p[2]) + dz));
        }
        for (double[] p : meta.zombieSpawns) {
            layout.zombieSpawns.add(new BlockPos((int) Math.floor(p[0]) + dx, (int) Math.floor(p[1]) + dy,
                    (int) Math.floor(p[2]) + dz));
        }
        for (double[] p : meta.doors) {
            layout.doors.add(new BlockPos((int) Math.floor(p[0]) + dx, (int) Math.floor(p[1]) + dy,
                    (int) Math.floor(p[2]) + dz));
        }
        if (meta.shop != null) {
            layout.shop = new BlockPos((int) Math.floor(meta.shop[0]) + dx, (int) Math.floor(meta.shop[1]) + dy,
                    (int) Math.floor(meta.shop[2]) + dz);
        }
        layout.minCorner = new BlockPos(minX + dx, lowY + dy, minZ + dz);
        layout.maxCorner = new BlockPos(maxX + dx, highY + dy, maxZ + dz);
        return layout;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int nibble(byte[] arr, int index) {
        int b = arr[index >> 1] & 0xFF;
        return (index & 1) == 0 ? b & 0xF : b >> 4;
    }

    // ---------------- region / chunk / section 读取 ----------------

    private static final class SectionData {
        int y;
        byte[] blocks; // 4096（可为 null，若 keep=false 不读）
        byte[] data;   // 2048 nibble meta
        byte[] add;    // 可选：Add 半字节（块 id 高位）
    }

    private static final class ChunkSections {
        int cx;
        int cz;
        final List<SectionData> sections = new ArrayList<>();
    }

    private static List<ChunkSections> readChunks(Path regionDir, int c0x, int c1x, int c0z, int c1z,
                                                  int lowY, int highY) {
        List<ChunkSections> out = new ArrayList<>();
        int minSec = Math.floorDiv(lowY, 16);
        int maxSec = Math.floorDiv(highY, 16);
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                Path f = regionDir.resolve("r." + Math.floorDiv(cx, 32) + "." + Math.floorDiv(cz, 32) + ".mca");
                if (!Files.isRegularFile(f)) {
                    continue;
                }
                byte[] data;
                try {
                    data = Files.readAllBytes(f);
                } catch (IOException e) {
                    LOGGER.warn("[VD] 读 region 失败 {}: {}", f, e.toString());
                    continue;
                }
                int entryOff = ((cx & 31) + (cz & 31) * 32) * 4;
                if (entryOff + 4 > data.length) {
                    continue;
                }
                int v = be32(data, entryOff);
                if (v == 0) {
                    continue;
                }
                int off = (v >> 8) * 4096;
                if (off + 5 > data.length) {
                    continue;
                }
                int len = be32(data, off);
                int ctype = data[off + 4] & 0xFF;
                if (off + 5 + len > data.length) {
                    continue;
                }
                byte[] compressed = Arrays.copyOfRange(data, off + 5, off + 5 + len - 1);
                byte[] nbtBytes;
                try {
                    InputStream in = switch (ctype) {
                        case 1 -> new GZIPInputStream(new ByteArrayInputStream(compressed));
                        case 2 -> new InflaterInputStream(new ByteArrayInputStream(compressed));
                        default -> new ByteArrayInputStream(compressed);
                    };
                    nbtBytes = readAll(in);
                } catch (IOException e) {
                    LOGGER.warn("[VD] 解压 chunk {}:{} 失败: {}", cx, cz, e.toString());
                    continue;
                }
                ChunkSections chunk = parseChunk(cx, cz, nbtBytes, minSec, maxSec);
                if (chunk != null && !chunk.sections.isEmpty()) {
                    out.add(chunk);
                }
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    // ---------------- 最小 NBT 解析：只需 Sections[] 的 Y/Blocks/Data/Add ----------------

    private static final class Reader {
        final byte[] b;
        int i;

        Reader(byte[] b) {
            this.b = b;
        }

        int u8() {
            return b[i++] & 0xFF;
        }

        int i32() {
            int v = ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            i += 4;
            return v;
        }

        void skipString() {
            int len = ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            i += 2 + len;
        }

        String readString() {
            int len = ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            i += 2;
            String s = new String(b, i, len, StandardCharsets.UTF_8);
            i += len;
            return s;
        }

        byte[] bytes(int n) {
            byte[] out = Arrays.copyOfRange(b, i, i + n);
            i += n;
            return out;
        }
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static ChunkSections parseChunk(int cx, int cz, byte[] b, int minSec, int maxSec) {
        Reader r = new Reader(b);
        if (r.u8() != 10) {
            return null;
        }
        r.skipString();
        ChunkSections out = new ChunkSections();
        out.cx = cx;
        out.cz = cz;
        while (true) {
            int t = r.u8();
            if (t == 0) {
                break;
            }
            String name = r.readString();
            if (t == 10 && name.equals("Level")) {
                readLevel(r, out, minSec, maxSec);
            } else {
                skipValue(r, t);
            }
        }
        return out;
    }

    private static void readLevel(Reader r, ChunkSections out, int minSec, int maxSec) {
        while (true) {
            int t = r.u8();
            if (t == 0) {
                return;
            }
            String name = r.readString();
            if (t == 9 && name.equals("Sections")) {
                int et = r.u8();
                int n = r.i32();
                for (int i = 0; i < n; i++) {
                    if (et != 10) {
                        skipValue(r, et);
                    } else {
                        SectionData sd = readSection(r, minSec, maxSec);
                        if (sd != null) {
                            out.sections.add(sd);
                        }
                    }
                }
            } else {
                skipValue(r, t);
            }
        }
    }

    /** 读一个 Section 复合体；只返回 [minSec,maxSec] 内的段。 */
    private static SectionData readSection(Reader r, int minSec, int maxSec) {
        SectionData sd = new SectionData();
        sd.y = -1;
        while (true) {
            int t = r.u8();
            if (t == 0) {
                break;
            }
            String name = r.readString();
            switch (t) {
                case 1 -> { // TAG_Byte
                    int v = r.u8();
                    if (name.equals("Y")) {
                        sd.y = v;
                    }
                }
                case 7 -> { // TAG_Byte_Array
                    int n = r.i32();
                    byte[] arr = r.bytes(n);
                    if (name.equals("Blocks") && n == 4096) {
                        sd.blocks = arr;
                    } else if (name.equals("Data") && n == 2048) {
                        sd.data = arr;
                    } else if (name.equals("Add") && n == 2048) {
                        sd.add = arr;
                    }
                }
                default -> skipValue(r, t);
            }
        }
        if (sd.y >= minSec && sd.y <= maxSec && sd.blocks != null) {
            return sd;
        }
        return null;
    }

    private static void skipValue(Reader r, int t) {
        switch (t) {
            case 1 -> r.i += 1;
            case 2 -> r.i += 2;
            case 3, 5 -> r.i += 4;
            case 4, 6 -> r.i += 8;
            case 7 -> {
                int n = r.i32();
                r.i += n;
            }
            case 8 -> r.skipString();
            case 9 -> {
                int et = r.u8();
                int n = r.i32();
                for (int i = 0; i < n; i++) {
                    skipValue(r, et);
                }
            }
            case 10 -> {
                while (true) {
                    int tt = r.u8();
                    if (tt == 0) {
                        break;
                    }
                    r.skipString();
                    skipValue(r, tt);
                }
            }
            case 11 -> {
                int n = r.i32();
                r.i += 4 * n;
            }
            case 12 -> {
                int n = r.i32();
                r.i += 8 * n;
            }
            default -> throw new IllegalStateException("unknown NBT type " + t);
        }
    }
}
