package com.example.pvp.arena.skywars;

import com.example.pvp.config.PvPConfig;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 空岛战争地图布局：纯计算、确定性（由 seed = 比赛 ID 决定）。
 * 出生岛围绕中间主岛等角分布，每岛若干箱子；出生点/箱子位置在 {@link SkyWarsMapGenerator}
 * 铺方块时使用同一份布局，保证出生点与地图一致。
 */
public final class SkyWarsLayout {

    /** 地图最大半径额外边距，缩圈从该半径开始。 */
    public static final int MAX_RADIUS_MARGIN = 4;

    /** 一座岛：中心、半径、箱子水平位置（Y 由生成器决定）。 */
    public static final class Island {
        public final BlockPos center;
        public final int radius;
        public final List<BlockPos> chests;

        Island(BlockPos center, int radius, List<BlockPos> chests) {
            this.center = center;
            this.radius = radius;
            this.chests = List.copyOf(chests);
        }

        public BlockPos center() {
            return this.center;
        }

        public int radius() {
            return this.radius;
        }

        public List<BlockPos> chests() {
            return this.chests;
        }
    }

    private final BlockPos mapCenter;
    private final int maxRadius;
    private final List<Island> spawnIslands;
    private final List<Island> midIslands;
    /** 中岛群（岛群）：第一位是中央主岛，其余为卫星岛。 */
    private final List<Island> middleIslands;
    private final Island middle; // = middleIslands.get(0)，中央主岛（救回点/缩圈参照）
    private final List<BlockPos> spawns;

    private SkyWarsLayout(BlockPos mapCenter, int maxRadius, List<Island> spawnIslands,
                          List<Island> midIslands, List<Island> middleIslands, List<BlockPos> spawns) {
        this.mapCenter = mapCenter;
        this.maxRadius = maxRadius;
        this.spawnIslands = List.copyOf(spawnIslands);
        this.midIslands = List.copyOf(midIslands);
        this.middleIslands = List.copyOf(middleIslands);
        this.middle = middleIslands.isEmpty() ? null : middleIslands.get(0);
        this.spawns = List.copyOf(spawns);
    }

    public BlockPos mapCenter() {
        return this.mapCenter;
    }

    public int maxRadius() {
        return this.maxRadius;
    }

    public List<Island> spawnIslands() {
        return this.spawnIslands;
    }

    /** 中途岛：每个玩家岛对应一座，位于玩家岛与中间主岛之间（同角度，作为跳板）。 */
    public List<Island> midIslands() {
        return this.midIslands;
    }

    /** 中央主岛（中岛群的中心，救回点/缩圈/主题参照）。 */
    public Island middle() {
        return this.middle;
    }

    /** 整个中岛群（中央主岛 + 卫星岛）。 */
    public List<Island> middleIslands() {
        return this.middleIslands;
    }

    public List<BlockPos> spawns() {
        return this.spawns;
    }

    /** 当前配置下的地图最大半径（生成与清场共用，保证能清到所有岛屿与箱子）。 */
    public static int computeMaxRadius() {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int maxIslandRadius = Math.max(3, cfg.skywarsIslandRadius) + 1;
        int spawnDist = cfg.skywarsMiddleRadius + maxIslandRadius + 1 + cfg.skywarsIslandGap;
        return spawnDist + maxIslandRadius + MAX_RADIUS_MARGIN;
    }

    /**
     * 由比赛 ID 与人数计算确定性的空岛布局。
     *
     * @param mapCenter   地图中心（平台中心，缩圈圆心）
     * @param seed        比赛 ID（单调递增，每次重赛地图不同）
     * @param playerCount 玩家人数（决定出生岛数量）
     */
    public static SkyWarsLayout compute(BlockPos mapCenter, int seed, int playerCount) {
        PvPConfig cfg = PvPConfig.INSTANCE;
        Random random = new Random(seed * 31L + playerCount * 17L);

        // 出生岛与中间岛的距离按两者半径动态算，保证最小有 skywarsIslandGap 格空隙、不连片
        int maxIslandRadius = Math.max(3, cfg.skywarsIslandRadius) + 1; // 布局随机可到配置+1
        int spawnDist = cfg.skywarsMiddleRadius + maxIslandRadius + 1 + cfg.skywarsIslandGap; // +1 抵消下方 ±1 抖动

        // 每个玩家的角度与距离（玩家岛与其对应中途岛共用同一角度，形成直达中岛的跳板）
        double[] angles = new double[playerCount];
        int[] distances = new int[playerCount];
        for (int i = 0; i < playerCount; i++) {
            angles[i] = i * 2.0 * Math.PI / playerCount + (random.nextDouble() - 0.5) * 0.7;
            distances[i] = spawnDist + random.nextInt(3) - 1;
        }

        List<Island> spawnIslands = new ArrayList<>();
        List<BlockPos> spawns = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            int x = mapCenter.getX() + (int) Math.round(Math.cos(angles[i]) * distances[i]);
            int z = mapCenter.getZ() + (int) Math.round(Math.sin(angles[i]) * distances[i]);
            int islandRadius = Math.max(3, cfg.skywarsIslandRadius + random.nextInt(3) - 1);
            int height = random.nextInt(7) - 3; // 岛屿间 ±3 高低差
            Island island = buildIsland(random, new BlockPos(x, mapCenter.getY() + height, z), islandRadius, cfg.skywarsChestsPerIsland);
            spawnIslands.add(island);
            spawns.add(island.center.up(1)); // 出生点在岛中心地表
        }

        // 中途岛：半径 1.5×玩家岛、skywarsMidIslandChests 个箱子，位于玩家岛与中岛之间
        int midRadius = Math.max(3, (int) Math.round(cfg.skywarsIslandRadius * 1.5));
        int playerInnerEdge = spawnDist - 1 - maxIslandRadius; // 玩家岛离中岛最近的内缘
        int midDist = (cfg.skywarsMiddleRadius + playerInnerEdge) / 2;
        int midMin = cfg.skywarsMiddleRadius + midRadius + 1;
        int midMax = playerInnerEdge - midRadius - 1;
        midDist = Math.max(midMin, Math.min(midDist, midMax)); // 保证不与中岛/玩家岛重叠

        List<Island> midIslands = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            int x = mapCenter.getX() + (int) Math.round(Math.cos(angles[i]) * midDist);
            int z = mapCenter.getZ() + (int) Math.round(Math.sin(angles[i]) * midDist);
            int height = random.nextInt(7) - 3; // 岛屿间 ±3 高低差
            Island island = buildIsland(random, new BlockPos(x, mapCenter.getY() + height, z), midRadius, cfg.skywarsMidIslandChests);
            midIslands.add(island);
        }

        // 中岛群：中央主岛 + 内外两环卫星岛（"岛群"而非单一大圆盘），障碍物由生成器按岛添加
        int middleHeight = random.nextInt(7) - 3;
        List<Island> middleIslands = buildMiddleIslandGroup(random,
                new BlockPos(mapCenter.getX(), mapCenter.getY() + middleHeight, mapCenter.getZ()),
                Math.max(4, cfg.skywarsMiddleRadius), cfg.skywarsMiddleChests);

        return new SkyWarsLayout(mapCenter, computeMaxRadius(), spawnIslands, midIslands, middleIslands, spawns);
    }

    /** 计算一座岛的箱子位置（离岛心 2~半径-2 格、随机角度，保证落在岛面上）。 */
    private static Island buildIsland(Random random, BlockPos center, int radius, int chestCount) {
        List<BlockPos> chests = new ArrayList<>();
        for (int i = 0; i < chestCount; i++) {
            double angle = random.nextDouble() * 2.0 * Math.PI;
            int dist = 2 + random.nextInt(Math.max(1, radius - 2));
            int x = center.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * dist);
            chests.add(new BlockPos(x, center.getY() + 1, z));
        }
        return new Island(center, radius, chests);
    }

    /**
     * 构建中岛群：中央主岛 + 内环 4 座小卫星岛 + 外环 8 座卫星岛。
     * 中央岛半径最大、箱子最多；卫星岛半径较小、各 1 箱。总占地明显大于单一大圆盘。
     */
    private static List<Island> buildMiddleIslandGroup(Random random, BlockPos center, int radius, int chestCount) {
        List<Island> islands = new ArrayList<>();
        // 中央主岛
        int centralR = Math.max(6, (int) Math.round(radius * 0.38));
        int centralChests = Math.max(2, chestCount / 2);
        islands.add(buildIsland(random, center, centralR, centralChests));
        // 内环小卫星岛
        int innerR = Math.max(4, (int) Math.round(radius * 0.1));
        int innerDist = (int) Math.round(radius * 0.55);
        int innerCount = 4;
        for (int i = 0; i < innerCount; i++) {
            double angle = i * 2.0 * Math.PI / innerCount + (random.nextDouble() - 0.5) * 0.5;
            int x = center.getX() + (int) Math.round(Math.cos(angle) * innerDist);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * innerDist);
            islands.add(buildIsland(random, new BlockPos(x, center.getY(), z), innerR, 1));
        }
        // 外环卫星岛
        int outerR = Math.max(5, (int) Math.round(radius * 0.16));
        int outerDist = (int) Math.round(radius * 0.8);
        int outerCount = 8;
        int restChests = Math.max(0, chestCount - centralChests - innerCount);
        for (int i = 0; i < outerCount; i++) {
            double angle = i * 2.0 * Math.PI / outerCount + (random.nextDouble() - 0.5) * 0.4;
            int x = center.getX() + (int) Math.round(Math.cos(angle) * outerDist);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * outerDist);
            int chests = restChests > 0 ? 1 : 0;
            islands.add(buildIsland(random, new BlockPos(x, center.getY(), z), outerR, chests));
        }
        return islands;
    }
}
