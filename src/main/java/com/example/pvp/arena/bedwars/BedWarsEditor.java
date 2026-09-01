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

    /** 标记类型：粒子颜色 + 是否队伍性质 + 中文名。 */
    public enum MarkType {
        SHOP(0x55FF55, true, "普通商店"),           // 绿色
        UPGRADE_SHOP(0xAA00AA, true, "团队升级商店"), // 紫色
        IRON(0xCCCCCC, true, "铁生成点"),           // 白色
        GOLD(0xFFAA00, true, "金生成点"),           // 金色
        DIAMOND(0x55FFFF, false, "钻石生成点"),      // 青色
        EMERALD(0x00AA00, false, "绿宝石生成点");    // 深绿

        public final int color;
        public final boolean perTeam;
        public final String displayName;

        MarkType(int color, boolean perTeam, String displayName) {
            this.color = color;
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
        if (stack.isOf(Items.BLAZE_ROD)) {
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

    /** 开始编辑：记录已加载的地图数据、探测床、记录偏移。 */
    public static Session start(UUID playerUuid, Path mapDir, BedWarsMapLoader.MapData data, BlockPos arenaCenter) {
        List<BlockPos> beds = detectBeds(data);
        BlockPos center = centroid(beds);
        BlockPos offset = BedWarsMapPaster.offsetFor(data, arenaCenter);
        Session session = new Session(mapDir.getFileName().toString(), mapDir, data, beds, center, offset);
        SESSIONS.put(playerUuid, session);
        return session;
    }

    /** 标记：在点击方块上方记录一个标记点。返回是否成功（false = 该位置已标记）。 */
    public static boolean mark(Session session, MarkType type, BlockPos clickedBlock, World world) {
        BlockPos markPos = clickedBlock.up();
        if (session.marks.containsKey(markPos)) {
            return false;
        }
        session.marks.put(markPos, type);
        spawnMarkParticles(world, markPos, type);
        return true;
    }

    /** 取消：移除点击方块上方的标记（若存在）。返回被取消的类型，无则 null。 */
    public static MarkType unmark(Session session, BlockPos clickedBlock, World world) {
        BlockPos markPos = clickedBlock.up();
        return session.marks.remove(markPos);
    }

    /** 在标记位置生成粒子框。 */
    private static void spawnMarkParticles(World world, BlockPos pos, MarkType type) {
        if (!(world instanceof ServerWorld sw)) {
            return;
        }
        // 生成一个 1x1x1 的粒子框（8 个顶点 + 12 条边）
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        // 8 个顶点
        spawnParticle(sw, type, x, y, z);
        spawnParticle(sw, type, x + 1, y, z);
        spawnParticle(sw, type, x, y, z + 1);
        spawnParticle(sw, type, x + 1, y, z + 1);
        spawnParticle(sw, type, x, y + 1, z);
        spawnParticle(sw, type, x + 1, y + 1, z);
        spawnParticle(sw, type, x, y + 1, z + 1);
        spawnParticle(sw, type, x + 1, y + 1, z + 1);
        // 12 条边（每边 3 个点）
        for (int i = 1; i <= 2; i++) {
            double t = i / 3.0;
            // 底面 4 条边
            spawnParticle(sw, type, x + t, y, z);
            spawnParticle(sw, type, x + t, y, z + 1);
            spawnParticle(sw, type, x, y, z + t);
            spawnParticle(sw, type, x + 1, y, z + t);
            // 顶面 4 条边
            spawnParticle(sw, type, x + t, y + 1, z);
            spawnParticle(sw, type, x + t, y + 1, z + 1);
            spawnParticle(sw, type, x, y + 1, z + t);
            spawnParticle(sw, type, x + 1, y + 1, z + t);
            // 4 条竖边
            spawnParticle(sw, type, x, y + t, z);
            spawnParticle(sw, type, x + 1, y + t, z);
            spawnParticle(sw, type, x, y + t, z + 1);
            spawnParticle(sw, type, x + 1, y + t, z + 1);
        }
    }

    private static void spawnParticle(ServerWorld world, MarkType type, double x, double y, double z) {
        // 使用彩色尘埃粒子
        world.spawnParticles(new net.minecraft.particle.DustParticleEffect(
                new org.joml.Vector3f(
                        ((type.color >> 16) & 0xFF) / 255.0f,
                        ((type.color >> 8) & 0xFF) / 255.0f,
                        (type.color & 0xFF) / 255.0f
                ), 1.0f),
                x, y, z, 1, 0, 0, 0, 0);
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

    /** 每 tick 为所有编辑会话的标记生成粒子（持续显示）。 */
    public static void tickParticles() {
        for (Session session : SESSIONS.values()) {
            if (session.data == null || session.marks.isEmpty()) {
                continue;
            }
            // 获取世界（从第一个标记点所在的世界）
            World world = null;
            for (BlockPos pos : session.marks.keySet()) {
                // 从服务器获取竞技场世界
                if (com.example.pvp.PvPMod.SERVER != null) {
                    world = com.example.pvp.PvPMod.SERVER.getWorld(
                            com.example.pvp.arena.ArenaWorldManager.ARENA_WORLD_KEY);
                }
                break;
            }
            if (world == null) {
                continue;
            }
            for (Map.Entry<BlockPos, MarkType> e : session.marks.entrySet()) {
                spawnMarkParticles(world, e.getKey(), e.getValue());
            }
        }
    }

    private static int distSqXZ(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
