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
import java.nio.file.DirectoryStream;
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
 * <p>解码经典 Section：{@code Blocks[4096]}（块 id）+ {@code Data[2048]}（meta），经
 * {@link LegacyBlockMap} 映射成现代 {@link BlockState}。
 *
 * <p><b>整片加载</b>：不依赖 arenas.yml 标记坐标去截取范围，而是把地图目录下所有 region 里
 * 现存的 chunk 全部解码，非空气方块全数粘贴（相对世界坐标平移），保证地图完整还原。
 *
 * <p>平移：世界 (0,?,0) 对齐竞技场区域中心，y 用 groundWorldY 对齐 PLATFORM_Y；
 * 每列在低于已贴内容且高于地面处补实心基座，避免悬空露底。
 */
public final class VillageWorldImporter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int FLOOR_Y = ArenaTemplate.PLATFORM_Y;
    /** 只粘贴此世界 y 范围内的方块（覆盖地表+建筑；0..160 足够，避免读更高空）。 */
    private static final int WORLD_MIN_Y = 0;
    private static final int WORLD_MAX_Y = 160;

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

    public static Layout importMap(ArenaWorld world, Path mapFolder, String mapName, BlockPos center) {
        VillageDefenseMeta meta = VillageDefenseMeta.load(mapFolder);
        int dx = center.getX();
        int dy = FLOOR_Y - meta.groundWorldY;
        int dz = center.getZ();

        Layout layout = new Layout(mapName, center);
        int placed = 0;
        int unmapped = 0;
        int minWx = Integer.MAX_VALUE, maxWx = Integer.MIN_VALUE;
        int minWz = Integer.MAX_VALUE, maxWz = Integer.MIN_VALUE;
        int minWy = Integer.MAX_VALUE, maxWy = Integer.MIN_VALUE;
        Map<Long, Integer> columnLowest = new HashMap<>();

        List<ChunkSections> chunks = readAllChunks(mapFolder.resolve("region"));
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
                    if (wy < WORLD_MIN_Y || wy > WORLD_MAX_Y) {
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
                    minWx = Math.min(minWx, wx);
                    maxWx = Math.max(maxWx, wx);
                    minWy = Math.min(minWy, wy);
                    maxWy = Math.max(maxWy, wy);
                    minWz = Math.min(minWz, wz);
                    maxWz = Math.max(maxWz, wz);
                    int prev = columnLowest.getOrDefault(pack(ax, az), Integer.MAX_VALUE);
                    if (ay < prev) {
                        columnLowest.put(pack(ax, az), ay);
                    }
                }
            }
        }

        // 底部补基座：内容最低方块高于地面线 → 从地面铺到其下，避免悬空露底
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
                mapName, placed, pad, unmapped,
                minWx == Integer.MAX_VALUE ? 0 : minWx, maxWx == Integer.MIN_VALUE ? 0 : maxWx,
                minWy == Integer.MAX_VALUE ? 0 : minWy, maxWy == Integer.MIN_VALUE ? 0 : maxWy,
                minWz == Integer.MAX_VALUE ? 0 : minWz, maxWz == Integer.MIN_VALUE ? 0 : maxWz);

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
        if (minWx != Integer.MAX_VALUE) {
            layout.minCorner = new BlockPos(minWx + dx, minWy + dy, minWz + dz);
            layout.maxCorner = new BlockPos(maxWx + dx, maxWy + dy, maxWz + dz);
        }
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
        byte[] blocks;
        byte[] data;
        byte[] add;
    }

    private static final class ChunkSections {
        int cx;
        int cz;
        final List<SectionData> sections = new ArrayList<>();
    }

    /** 读取 region 目录下所有现存 chunk（不设范围框，保证完整）。 */
    private static List<ChunkSections> readAllChunks(Path regionDir) {
        List<ChunkSections> out = new ArrayList<>();
        if (!Files.isDirectory(regionDir)) {
            LOGGER.error("[VD] 缺少 region 目录: {}", regionDir);
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionDir, "*.mca")) {
            for (Path f : ds) {
                byte[] data;
                try {
                    data = Files.readAllBytes(f);
                } catch (IOException e) {
                    LOGGER.warn("[VD] 读 region 失败 {}: {}", f, e.toString());
                    continue;
                }
                for (int idx = 0; idx < 1024; idx++) {
                    int v = be32(data, idx * 4);
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
                        continue;
                    }
                    int xLocal = idx % 32;
                    int zLocal = idx / 32;
                    int[] rParts = parseRegionName(f);
                    int cx = rParts[0] * 32 + xLocal;
                    int cz = rParts[1] * 32 + zLocal;
                    ChunkSections chunk = parseChunk(cx, cz, nbtBytes);
                    if (chunk != null && !chunk.sections.isEmpty()) {
                        out.add(chunk);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[VD] 扫描 region 失败: {}", e.toString());
        }
        return out;
    }

    private static int[] parseRegionName(Path f) {
        String base = f.getFileName().toString();
        try {
            String core = base.substring(2, base.length() - 4); // r.X.Z.mca
            int dot = core.indexOf('.');
            return new int[]{Integer.parseInt(core.substring(0, dot)),
                    Integer.parseInt(core.substring(dot + 1))};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
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

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
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

    private static ChunkSections parseChunk(int cx, int cz, byte[] b) {
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
                readLevel(r, out);
            } else {
                skipValue(r, t);
            }
        }
        return out;
    }

    private static void readLevel(Reader r, ChunkSections out) {
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
                        SectionData sd = readSection(r);
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

    private static SectionData readSection(Reader r) {
        SectionData sd = new SectionData();
        sd.y = -1;
        while (true) {
            int t = r.u8();
            if (t == 0) {
                break;
            }
            String name = r.readString();
            switch (t) {
                case 1 -> {
                    int v = r.u8();
                    if (name.equals("Y")) {
                        sd.y = v;
                    }
                }
                case 7 -> {
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
        if (sd.y >= 0 && sd.y * 16 + 15 >= WORLD_MIN_Y && sd.y * 16 <= WORLD_MAX_Y && sd.blocks != null) {
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
