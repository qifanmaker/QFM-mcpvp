package com.example.pvp.arena.bedwars;

import com.mojang.logging.LogUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bed Wars 地图布局：从加载的方块表自动探测队伍信息（床位置等）。
 * 每队：床位置（自动探测红床聚簇）、出生点（床上方）、生成器（床附近岛面高处）、商店点。
 */
public final class BedWarsLayout {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 队伍颜色（8 队：红蓝黄绿青白粉黑）。 */
    public static final Formatting[] TEAM_COLORS = {
            Formatting.RED, Formatting.BLUE, Formatting.YELLOW, Formatting.GREEN,
            Formatting.AQUA, Formatting.WHITE, Formatting.LIGHT_PURPLE, Formatting.BLACK
    };

    /** 队伍颜色名（用于队伍显示）。 */
    public static final String[] TEAM_NAMES = {"红队", "蓝队", "黄队", "绿队", "青队", "白队", "粉队", "黑队"};

    /** 一支队伍的布局数据。 */
    public static final class Team {
        public final int index;
        public BlockPos bed;        // 床位置（foot 部分）
        public BlockPos spawn;      // 出生点（床上方）
        public BlockPos iron;       // 铁生成点
        public BlockPos gold;       // 金生成点
        public BlockPos shop;       // 普通商店点
        public BlockPos upgradeShop; // 团队升级商店点

        Team(int index, BlockPos bed, BlockPos spawn, BlockPos iron, BlockPos gold,
             BlockPos shop, BlockPos upgradeShop) {
            this.index = index;
            this.bed = bed;
            this.spawn = spawn;
            this.iron = iron;
            this.gold = gold;
            this.shop = shop;
            this.upgradeShop = upgradeShop;
        }
    }

    private final String mapName;
    private final List<Team> teams = new ArrayList<>();
    private final BlockPos lobbySpawn;
    private final BlockPos mapCenter;
    private final int teamCount;
    private final List<BlockPos> diamonds;   // 中央岛钻石生成点
    private final List<BlockPos> emeralds;   // 中央岛绿宝石生成点

    private BedWarsLayout(String mapName, int teamCount, BlockPos lobbySpawn, BlockPos mapCenter,
                          List<Team> teams, List<BlockPos> diamonds, List<BlockPos> emeralds) {
        this.mapName = mapName;
        this.teamCount = teamCount;
        this.lobbySpawn = lobbySpawn;
        this.mapCenter = mapCenter;
        this.teams.addAll(teams);
        this.diamonds = List.copyOf(diamonds);
        this.emeralds = List.copyOf(emeralds);
    }

    /** 中央岛钻石生成点。 */
    public List<BlockPos> diamonds() {
        return this.diamonds;
    }

    /** 中央岛绿宝石生成点。 */
    public List<BlockPos> emeralds() {
        return this.emeralds;
    }

    public String mapName() {
        return this.mapName;
    }

    public List<Team> teams() {
        return this.teams;
    }

    public int teamCount() {
        return this.teamCount;
    }

    public BlockPos lobbySpawn() {
        return this.lobbySpawn;
    }

    public BlockPos mapCenter() {
        return this.mapCenter;
    }

    /** 从加载的地图数据探测布局。teamCount = 实际启用队伍数（≤8）。优先读 map.json（有精确点位），否则自动推断。 */
    public static BedWarsLayout detect(String mapName, int teamCount, BedWarsMapLoader.MapData data, java.nio.file.Path mapDir) {
        // 1. 收集所有红床方块（foot 部分，排除 head 避免重复）
        List<BlockPos> bedFeet = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : data.blocks.entrySet()) {
            BlockState state = e.getValue();
            if (state.isOf(Blocks.RED_BED) && state.get(net.minecraft.block.BedBlock.PART)
                    == net.minecraft.block.enums.BedPart.HEAD) {
                bedFeet.add(e.getKey()); // head 相邻的 foot
            }
        }
        // 若没有 head 信息，直接用所有床位置
        if (bedFeet.isEmpty()) {
            for (Map.Entry<BlockPos, BlockState> e : data.blocks.entrySet()) {
                if (e.getValue().isOf(Blocks.RED_BED)) {
                    bedFeet.add(e.getKey());
                }
            }
        }

        // 2. 聚簇：把相邻（水平距离 < 4）的床归为一队
        List<BlockPos> clusters = clusterBeds(bedFeet);

        // 3. 按角度排序（绕地图中心，顺时针），保证队伍顺序稳定
        BlockPos center = data.centerXZ();
        clusters.sort(Comparator.comparingDouble(b -> Math.atan2(b.getZ() - center.getZ(), b.getX() - center.getX())));

        // 4. 尝试读 map.json（精确点位）
        BedWarsMapConfig config = mapDir != null ? BedWarsMapConfig.load(mapDir) : null;
        List<BlockPos> diamonds = new ArrayList<>();
        List<BlockPos> emeralds = new ArrayList<>();
        if (config != null) {
            diamonds = config.diamonds.stream().map(BedWarsMapConfig.Pos::toBlockPos).toList();
            emeralds = config.emeralds.stream().map(BedWarsMapConfig.Pos::toBlockPos).toList();
        }

        // 5. 取前 teamCount 队，计算各队附属点
        List<Team> teams = new ArrayList<>();
        int n = Math.min(teamCount, Math.max(2, clusters.size()));
        for (int i = 0; i < n; i++) {
            BlockPos bed = clusters.get(i);
            BlockPos spawn = new BlockPos(bed.getX(), bed.getY() + 2, bed.getZ());
            BlockPos iron;
            BlockPos gold;
            BlockPos shop;
            BlockPos upgradeShop;
            if (config != null && i < config.irons.size() && i < config.golds.size()
                    && i < config.shops.size() && i < config.upgradeShops.size()) {
                iron = config.irons.get(i).toBlockPos();
                gold = config.golds.get(i).toBlockPos();
                shop = config.shops.get(i).toBlockPos();
                upgradeShop = config.upgradeShops.get(i).toBlockPos();
            } else {
                BlockPos fallback = findGeneratorPoint(data, bed, center);
                iron = fallback;
                gold = fallback;
                shop = new BlockPos(bed.getX() + 2, bed.getY() + 1, bed.getZ());
                upgradeShop = new BlockPos(bed.getX() - 2, bed.getY() + 1, bed.getZ());
            }
            teams.add(new Team(i, bed, spawn, iron, gold, shop, upgradeShop));
        }

        if (teams.size() < 2) {
            LOGGER.warn("[PvP] BedWars 地图 {} 只探测到 {} 个床位置，无法开赛", mapName, teams.size());
        }
        LOGGER.info("[PvP] BedWars 地图 {} 布局: {} 队, 大厅 {}, 地图中心 {}, 钻石点 {}, 绿宝石点 {}（配置{}）",
                mapName, teams.size(), data.lobbySpawn, center, diamonds.size(), emeralds.size(),
                config != null ? "精确" : "自动推断");
        return new BedWarsLayout(mapName, teams.size(), data.lobbySpawn, center, teams, diamonds, emeralds);
    }

    /** 床聚簇：水平距离 < 4 的床归为一组，返回每组平均位置。 */
    private static List<BlockPos> clusterBeds(List<BlockPos> beds) {
        List<BlockPos> result = new ArrayList<>();
        boolean[] used = new boolean[beds.size()];
        for (int i = 0; i < beds.size(); i++) {
            if (used[i]) {
                continue;
            }
            List<BlockPos> group = new ArrayList<>();
            group.add(beds.get(i));
            used[i] = true;
            for (int j = i + 1; j < beds.size(); j++) {
                if (used[j]) {
                    continue;
                }
                if (distSq(beds.get(i), beds.get(j)) < 16) {
                    group.add(beds.get(j));
                    used[j] = true;
                }
            }
            int sx = 0;
            int sz = 0;
            int sy = Integer.MAX_VALUE;
            for (BlockPos b : group) {
                sx += b.getX();
                sz += b.getZ();
                sy = Math.min(sy, b.getY());
            }
            result.add(new BlockPos(sx / group.size(), sy, sz / group.size()));
        }
        return result;
    }

    private static int distSq(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** 生成器位置：床附近（半径 8 格内）最高的非空气方块表面 +1（掉落物落在岛面上）。 */
    private static BlockPos findGeneratorPoint(BedWarsMapLoader.MapData data, BlockPos bed, BlockPos center) {
        int bestY = -64;
        BlockPos best = null;
        int cx = bed.getX();
        int cz = bed.getZ();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                if (dx * dx + dz * dz > 64) {
                    continue;
                }
                BlockPos p = new BlockPos(cx + dx, bed.getY(), cz + dz);
                BlockState s = data.blocks.get(p);
                if (s != null && !s.isAir() && p.getY() > bestY) {
                    bestY = p.getY();
                    best = p;
                }
            }
        }
        if (best != null) {
            return best.up();
        }
        return bed.up(3); // 兜底：床上方 3 格
    }

    /** 队伍颜色。 */
    public static Formatting color(int teamIndex) {
        return TEAM_COLORS[Math.min(teamIndex, TEAM_COLORS.length - 1)];
    }

    /** 队伍名。 */
    public static String name(int teamIndex) {
        return TEAM_NAMES[Math.min(teamIndex, TEAM_NAMES.length - 1)];
    }
}
