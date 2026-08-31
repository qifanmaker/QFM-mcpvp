package com.example.pvp.arena.bedwars;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bed Wars 地图标记编辑器：OP 进入地图，用特定物品手动标记每队/中央岛的点位。
 * 标记点放在点击方块的上方一格，用粒子 + 可视化方块标出；左键标记、右键取消。
 * 结果写入 map.json，供正式对局使用。
 *
 * 标记物品（快捷栏）：
 *   木棍   = 普通商店（每队 1 个，队伍性质）
 *   铁剑   = 团队升级商店（每队 1 个，队伍性质）
 *   铁锭   = 铁生成点（每队 1 个，队伍性质）
 *   金锭   = 金生成点（每队 1 个，队伍性质）
 *   钻石   = 钻石生成点（中央岛，全局）
 *   绿宝石 = 绿宝石生成点（中央岛，全局）
 *   纸     = 保存并退出
 *
 * 队伍性质的标记点（商店/升级商店/铁/金）保存时按最近床自动认领队伍。
 */
public final class BedWarsEditor {

    /** 标记类型：可视化方块 + 是否队伍性质 + 中文名。 */
    public enum MarkType {
        SHOP(Blocks.SEA_LANTERN, true, "普通商店"),
        UPGRADE_SHOP(Blocks.BEACON, true, "团队升级商店"),
        IRON(Blocks.IRON_BLOCK, true, "铁生成点"),
        GOLD(Blocks.GOLD_BLOCK, true, "金生成点"),
        DIAMOND(Blocks.DIAMOND_BLOCK, false, "钻石生成点"),
        EMERALD(Blocks.EMERALD_BLOCK, false, "绿宝石生成点");

        public final Block marker;
        public final boolean perTeam;
        public final String displayName;

        MarkType(Block marker, boolean perTeam, String displayName) {
            this.marker = marker;
            this.perTeam = perTeam;
            this.displayName = displayName;
        }
    }

    /** 一次编辑会话。 */
    public static final class Session {
        public final String mapName;
        public final Path mapDir;
        public final BedWarsMapLoader.MapData data;
        public final List<BlockPos> beds;      // 自动探测的床位置（地图坐标，按角度排序）
        public final BlockPos center;          // 对称中心（床质心）
        public final BlockPos offset;          // 地图坐标 → 竞技场坐标平移量
        public final Map<BlockPos, MarkType> marks = new LinkedHashMap<>(); // 竞技场坐标 → 类型

        Session(String mapName, Path mapDir, BedWarsMapLoader.MapData data, List<BlockPos> beds,
                BlockPos center, BlockPos offset) {
            this.mapName = mapName;
            this.mapDir = mapDir;
            this.data = data;
            this.beds = beds;
            this.center = center;
            this.offset = offset;
        }

        /** 该类型已标记数量。 */
        public int count(MarkType type) {
            int c = 0;
            for (MarkType t : this.marks.values()) {
                if (t == type) {
                    c++;
                }
            }
            return c;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private BedWarsEditor() {
    }

    public static Session get(UUID uuid) {
        return SESSIONS.get(uuid);
    }

    public static void remove(UUID uuid) {
        SESSIONS.remove(uuid);
    }

    /** 判断手持物品是否为标记物品，返回对应标记类型；否则 null。 */
    public static MarkType markTypeOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (stack.isOf(Items.STICK)) {
            return MarkType.SHOP;
        }
        if (stack.isOf(Items.IRON_SWORD)) {
            return MarkType.UPGRADE_SHOP;
        }
        if (stack.isOf(Items.IRON_INGOT)) {
            return MarkType.IRON;
        }
        if (stack.isOf(Items.GOLD_INGOT)) {
            return MarkType.GOLD;
        }
        if (stack.isOf(Items.DIAMOND)) {
            return MarkType.DIAMOND;
        }
        if (stack.isOf(Items.EMERALD)) {
            return MarkType.EMERALD;
        }
        return null;
    }

    /** 开始编辑：加载地图、探测床、记录偏移。 */
    public static Session start(Path mapDir, BlockPos arenaCenter) {
        BedWarsMapLoader.MapData data = BedWarsMapLoader.load(mapDir);
        List<BlockPos> beds = detectBeds(data);
        BlockPos center = centroid(beds);
        BlockPos offset = BedWarsMapPaster.offsetFor(data, arenaCenter);
        Session session = new Session(mapDir.getFileName().toString(), mapDir, data, beds, center, offset);
        SESSIONS.put(UUID.randomUUID(), session);
        return session;
    }

    /** 标记：在点击方块上方记录一个标记点。返回是否成功（false = 该位置已标记）。 */
    public static boolean mark(Session session, MarkType type, BlockPos clickedBlock, World world) {
        BlockPos markPos = clickedBlock.up();
        if (session.marks.containsKey(markPos)) {
            return false;
        }
        session.marks.put(markPos, type);
        world.setBlockState(markPos, type.marker.getDefaultState(), 3);
        if (world instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    markPos.getX() + 0.5, markPos.getY() + 1.0, markPos.getZ() + 0.5,
                    15, 0.3, 0.3, 0.3, 0.05);
        }
        return true;
    }

    /** 取消：移除点击方块上方的标记（若存在）。返回被取消的类型，无则 null。 */
    public static MarkType unmark(Session session, BlockPos clickedBlock, World world) {
        BlockPos markPos = clickedBlock.up();
        MarkType removed = session.marks.remove(markPos);
        if (removed != null) {
            world.setBlockState(markPos, Blocks.AIR.getDefaultState(), 3);
        }
        return removed;
    }

    /** 检查是否已至少标记必要点位（普通商店/升级商店/铁/金各 ≥1）。 */
    public static boolean isReady(Session session) {
        return session.count(MarkType.SHOP) > 0
                && session.count(MarkType.UPGRADE_SHOP) > 0
                && session.count(MarkType.IRON) > 0
                && session.count(MarkType.GOLD) > 0;
    }

    /** 保存：队伍性质点按最近床认领队伍，全局点直接存，写入 map.json。 */
    public static boolean save(Session session) {
        if (!isReady(session)) {
            return false;
        }
        BedWarsMapConfig cfg = BedWarsMapConfig.create();
        cfg.name = session.mapName;
        cfg.teams = session.beds.size();
        cfg.shops = assignPerTeam(session, MarkType.SHOP);
        cfg.upgradeShops = assignPerTeam(session, MarkType.UPGRADE_SHOP);
        cfg.irons = assignPerTeam(session, MarkType.IRON);
        cfg.golds = assignPerTeam(session, MarkType.GOLD);
        cfg.diamonds = collectGlobal(session, MarkType.DIAMOND);
        cfg.emeralds = collectGlobal(session, MarkType.EMERALD);
        cfg.save(session.mapDir);
        return true;
    }

    /** 收集某类型所有标记（转地图坐标），按最近床认领队伍，输出 0..beds-1 顺序。 */
    private static List<BedWarsMapConfig.Pos> assignPerTeam(Session session, MarkType type) {
        List<BlockPos> marks = new ArrayList<>();
        for (Map.Entry<BlockPos, MarkType> e : session.marks.entrySet()) {
            if (e.getValue() == type) {
                marks.add(e.getKey().subtract(session.offset)); // 转地图坐标
            }
        }
        BedWarsMapConfig.Pos[] byTeam = new BedWarsMapConfig.Pos[session.beds.size()];
        for (BlockPos mark : marks) {
            int bedIdx = nearestBedIndex(session.beds, mark);
            if (bedIdx >= 0) {
                byTeam[bedIdx] = new BedWarsMapConfig.Pos(mark);
            }
        }
        List<BedWarsMapConfig.Pos> result = new ArrayList<>();
        for (int i = 0; i < session.beds.size(); i++) {
            result.add(byTeam[i] != null ? byTeam[i] : new BedWarsMapConfig.Pos(0, 0, 0));
        }
        return result;
    }

    /** 收集全局标记（钻石/绿宝石，转地图坐标）。 */
    private static List<BedWarsMapConfig.Pos> collectGlobal(Session session, MarkType type) {
        List<BedWarsMapConfig.Pos> result = new ArrayList<>();
        for (Map.Entry<BlockPos, MarkType> e : session.marks.entrySet()) {
            if (e.getValue() == type) {
                result.add(new BedWarsMapConfig.Pos(e.getKey().subtract(session.offset)));
            }
        }
        return result;
    }

    /** 探测 8 床位置（foot 部分聚簇，按角度排序）。 */
    private static List<BlockPos> detectBeds(BedWarsMapLoader.MapData data) {
        List<BlockPos> bedFeet = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : data.blocks.entrySet()) {
            if (e.getValue().isOf(Blocks.RED_BED)) {
                bedFeet.add(e.getKey());
            }
        }
        List<BlockPos> clusters = new ArrayList<>();
        boolean[] used = new boolean[bedFeet.size()];
        for (int i = 0; i < bedFeet.size(); i++) {
            if (used[i]) {
                continue;
            }
            int sx = 0;
            int sz = 0;
            int sy = Integer.MAX_VALUE;
            int cnt = 0;
            for (int j = 0; j < bedFeet.size(); j++) {
                if (used[j]) {
                    continue;
                }
                if (distSqXZ(bedFeet.get(i), bedFeet.get(j)) < 16) {
                    sx += bedFeet.get(j).getX();
                    sz += bedFeet.get(j).getZ();
                    sy = Math.min(sy, bedFeet.get(j).getY());
                    cnt++;
                    used[j] = true;
                }
            }
            if (cnt > 0) {
                clusters.add(new BlockPos(sx / cnt, sy, sz / cnt));
            }
        }
        BlockPos center = centroid(clusters);
        clusters.sort(Comparator.comparingDouble(b -> Math.atan2(b.getZ() - center.getZ(), b.getX() - center.getX())));
        return clusters;
    }

    /** 质心。 */
    private static BlockPos centroid(List<BlockPos> beds) {
        if (beds.isEmpty()) {
            return BlockPos.ORIGIN;
        }
        int sx = 0;
        int sz = 0;
        for (BlockPos b : beds) {
            sx += b.getX();
            sz += b.getZ();
        }
        return new BlockPos(sx / beds.size(), 0, sz / beds.size());
    }

    /** 最近床索引。 */
    private static int nearestBedIndex(List<BlockPos> beds, BlockPos pos) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < beds.size(); i++) {
            double d = distSqXZ(beds.get(i), pos);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private static int distSqXZ(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
