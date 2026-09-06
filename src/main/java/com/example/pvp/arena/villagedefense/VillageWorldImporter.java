package com.example.pvp.arena.villagedefense;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.mojang.logging.LogUtils;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 把 Village Defense 的 1.12 Anvil 世界（maps/villagedefense/&lt;map&gt;/region/*.mca）粘贴进竞技场世界。
 *
 * <p><b>整片加载</b>：读全部现存 chunk，非空气方块全数粘贴（相对世界坐标平移），保证完整还原。
 *
 * <p><b>原版商店</b>：读取地图中"商店箱子"（arenas.yml 的 shop 坐标）里每个物品及其 lore 价格
 * （`N orbs`），作为对局商店内容——与原版插件行为一致。
 *
 * <p>chunk 用 MC 自带 {@link NbtIo} 解析；1.12 经典 Section 用 Blocks[4096]+Data[2048]。
 */
public final class VillageWorldImporter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int FLOOR_Y = ArenaTemplate.PLATFORM_Y;
    private static final int WORLD_MIN_Y = 0;
    private static final int WORLD_MAX_Y = 160;
    private static final Pattern PRICE = Pattern.compile("(\\d+)");

    private VillageWorldImporter() {
    }

    /** 商店条目：一个商品（含购买图标/价格/召唤类型）。 */
    public static final class ShopItem {
        public final ItemStack icon;
        public final String name;
        public final int cost;
        public final boolean golem;
        public final boolean wolf;

        ShopItem(ItemStack icon, String name, int cost, boolean golem, boolean wolf) {
            this.icon = icon;
            this.name = name;
            this.cost = cost;
            this.golem = golem;
            this.wolf = wolf;
        }
    }

    /** 导入结果：平移后游戏点 + 实际范围 + 商店内容。 */
    public static final class Layout {
        public final String mapName;
        public final BlockPos center;
        /** 世界→竞技场平移量（arena = world + off）。 */
        public int offX;
        public int offY;
        public int offZ;
        public final List<BlockPos> villagerSpawns = new ArrayList<>();
        public final List<BlockPos> zombieSpawns = new ArrayList<>();
        public final List<BlockPos> doors = new ArrayList<>();
        public BlockPos shop;
        public BlockPos minCorner;
        public BlockPos maxCorner;
        public final List<ShopItem> shopItems = new ArrayList<>();

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
        layout.offX = dx;
        layout.offY = dy;
        layout.offZ = dz;
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
                byte[] blocks = sd.blocks.getByteArray("Blocks");
                byte[] data = sd.data != null ? sd.data.getByteArray("Data") : null;
                byte[] add = sd.data2 != null ? sd.data2.getByteArray("Add") : null;
                int sy = sd.blocks.getByte("Y") & 0xFF;
                if (blocks.length != 4096) {
                    continue;
                }
                for (int index = 0; index < 4096; index++) {
                    int id = blocks[index] & 0xFF;
                    if (add != null && add.length == 2048) {
                        id |= nibble(add, index) << 8;
                    }
                    if (id == 0) {
                        continue;
                    }
                    int md = data != null && data.length == 2048 ? nibble(data, index) : 0;
                    int wx = baseX + (index & 15);
                    int wz = baseZ + ((index >> 4) & 15);
                    int wy = sy * 16 + (index >> 8);
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

        // 底部补基座
        int pad = 0;
        for (Map.Entry<Long, Integer> e : columnLowest.entrySet()) {
            int x = (int) (e.getKey() >>> 32);
            int z = (int) (e.getKey().intValue());
            int lowest = e.getValue();
            if (lowest <= FLOOR_Y + 1) {
                continue;
            }
            for (int y = FLOOR_Y; y < lowest; y++) {
                world.setBlockState(new BlockPos(x, y, z), net.minecraft.block.Blocks.COBBLESTONE.getDefaultState(), 3);
                pad++;
            }
        }

        // 应用手动修复（fixes.json）：世界坐标 → 覆盖成指定方块（可置空气）
        int fixed = VillageDefenseFixes.apply(world, mapFolder, layout);

        LOGGER.info("[VD] 导入 {}：放置 {} 方块，垫底 {}，未映射 {}，手动修复 {}。范围 x[{},{}] y[{},{}] z[{},{}]",
                mapName, placed, pad, unmapped, fixed,
                minWx == Integer.MAX_VALUE ? 0 : minWx, maxWx == Integer.MIN_VALUE ? 0 : maxWx,
                minWy == Integer.MAX_VALUE ? 0 : minWy, maxWy == Integer.MIN_VALUE ? 0 : maxWy,
                minWz == Integer.MAX_VALUE ? 0 : minWz, maxWz == Integer.MIN_VALUE ? 0 : maxWz);

        // 平移游戏坐标
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
            BlockPos shop = new BlockPos((int) Math.floor(meta.shop[0]) + dx,
                    (int) Math.floor(meta.shop[1]) + dy, (int) Math.floor(meta.shop[2]) + dz);
            layout.shop = shop;
        }
        if (minWx != Integer.MAX_VALUE) {
            layout.minCorner = new BlockPos(minWx + dx, minWy + dy, minWz + dz);
            layout.maxCorner = new BlockPos(maxWx + dx, maxWy + dy, maxWz + dz);
        }

        // 原版商店：取商店箱子内容
        if (meta.shop != null) {
            int shopX = (int) Math.floor(meta.shop[0]);
            int shopY = (int) Math.floor(meta.shop[1]);
            int shopZ = (int) Math.floor(meta.shop[2]);
            NbtCompound chest = findChest(chunks, shopX, shopY, shopZ);
            if (chest != null) {
                NbtList items = chest.getList("Items", NbtElement.COMPOUND_TYPE);
                for (NbtElement el : items) {
                    NbtCompound it = (NbtCompound) el;
                    ShopItem offer = toShopItem(it);
                    if (offer != null) {
                        layout.shopItems.add(offer);
                    }
                }
                LOGGER.info("[VD] 商店读取到 {} 件商品（原版商店箱子）", layout.shopItems.size());
            } else {
                LOGGER.warn("[VD] 未找到商店箱子 @{} {} {}，商店将用内置兜底清单", shopX, shopY, shopZ);
            }
        }
        return layout;
    }

    private static NbtCompound findChest(List<ChunkSections> chunks, int x, int y, int z) {
        for (ChunkSections chunk : chunks) {
            for (NbtCompound te : chunk.tileEntities) {
                if (te.getInt("x") == x && te.getInt("y") == y && te.getInt("z") == z) {
                    return te;
                }
            }
        }
        return null;
    }

    private static ShopItem toShopItem(NbtCompound it) {
        String id = it.getString("id");
        int count = it.getByte("Count") & 0xFF;
        NbtCompound tag = it.getCompound("tag");
        String displayName = null;
        String lore0 = null;
        if (!tag.isEmpty()) {
            NbtCompound display = tag.getCompound("display");
            displayName = display.getString("Name");
            NbtList lore = display.getList("Lore", NbtElement.STRING_TYPE);
            if (!lore.isEmpty()) {
                lore0 = lore.getString(0);
            }
        }
        int cost = 0;
        if (lore0 != null) {
            Matcher m = PRICE.matcher(lore0);
            if (m.find()) {
                cost = Integer.parseInt(m.group(1));
            }
        }
        if (cost <= 0) {
            return null; // 无价格不卖
        }
        boolean golem = false;
        boolean wolf = false;
        net.minecraft.item.Item iconItem;
        String name = displayName != null ? stripColor(displayName) : id;
        if (id.equals("minecraft:name_tag") && displayName != null) {
            if (displayName.contains("Golem")) {
                golem = true;
                iconItem = net.minecraft.item.Items.IRON_INGOT;
                name = "召唤铁傀儡";
            } else if (displayName.contains("Wolf")) {
                wolf = true;
                iconItem = net.minecraft.item.Items.BONE;
                name = "召唤狼";
            } else {
                iconItem = net.minecraft.item.Items.NAME_TAG;
            }
        } else {
            net.minecraft.util.Identifier ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null) {
                return null;
            }
            if (!net.minecraft.registry.Registries.ITEM.containsId(ident)) {
                return null;
            }
            iconItem = net.minecraft.registry.Registries.ITEM.get(ident);
        }
        ItemStack icon = new ItemStack(iconItem, Math.max(1, Math.min(count == 0 ? 1 : count, iconItem.getMaxCount())));
        return new ShopItem(icon, name, cost, golem, wolf);
    }

    private static String stripColor(String s) {
        return s.replace("§f", "").replace("§6", "").replace("§r", "").replace("§a", "").trim();
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int nibble(byte[] arr, int index) {
        int b = arr[index >> 1] & 0xFF;
        return (index & 1) == 0 ? b & 0xF : b >> 4;
    }

    // ---------------- region / chunk 读取（MC NbtIo 解析） ----------------

    private static final class SectionData {
        NbtCompound blocks;
        NbtCompound data;   // Data
        NbtCompound data2;  // Add
    }

    private static final class ChunkSections {
        int cx;
        int cz;
        final List<SectionData> sections = new ArrayList<>();
        final List<NbtCompound> tileEntities = new ArrayList<>();
    }

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
                int[] rParts = parseRegionName(f);
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
                    byte[] compressed = java.util.Arrays.copyOfRange(data, off + 5, off + 5 + len - 1);
                    byte[] raw;
                    try {
                        InputStream in = switch (ctype) {
                            case 1 -> new GZIPInputStream(new ByteArrayInputStream(compressed));
                            case 2 -> new InflaterInputStream(new ByteArrayInputStream(compressed));
                            default -> new ByteArrayInputStream(compressed);
                        };
                        raw = readAll(in);
                    } catch (IOException e) {
                        continue;
                    }
                    NbtCompound root;
                    try {
                        root = NbtIo.readCompound(new DataInputStream(new ByteArrayInputStream(raw)),
                                NbtSizeTracker.ofUnlimitedBytes());
                    } catch (IOException e) {
                        continue;
                    }
                    NbtCompound level = root.contains("Level")
                            ? root.getCompound("Level") : root;
                    int cx = rParts[0] * 32 + (idx % 32);
                    int cz = rParts[1] * 32 + (idx / 32);
                    ChunkSections chunk = new ChunkSections();
                    chunk.cx = cx;
                    chunk.cz = cz;
                    if (level.contains("Sections")) {
                        NbtList list = level.getList("Sections", NbtElement.COMPOUND_TYPE);
                        for (NbtElement el : list) {
                            NbtCompound sec = (NbtCompound) el;
                            if (sec.contains("Blocks")) {
                                SectionData sd = new SectionData();
                                sd.blocks = sec;
                                sd.data = sec.contains("Data") ? sec : null;
                                sd.data2 = sec.contains("Add") ? sec : null;
                                chunk.sections.add(sd);
                            }
                        }
                    }
                    for (String key : new String[]{"TileEntities", "BlockEntities"}) {
                        if (level.contains(key)) {
                            NbtList te = level.getList(key, NbtElement.COMPOUND_TYPE);
                            for (NbtElement el : te) {
                                chunk.tileEntities.add((NbtCompound) el);
                            }
                        }
                    }
                    if (!chunk.sections.isEmpty() || !chunk.tileEntities.isEmpty()) {
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
            String core = base.substring(2, base.length() - 4);
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
}
