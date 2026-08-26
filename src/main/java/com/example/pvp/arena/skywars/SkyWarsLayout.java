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
    private final Island middle;
    private final List<BlockPos> spawns;

    private SkyWarsLayout(BlockPos mapCenter, int maxRadius, List<Island> spawnIslands,
                          List<Island> midIslands, Island middle, List<BlockPos> spawns) {
        this.mapCenter = mapCenter;
        this.maxRadius = maxRadius;
        this.spawnIslands = List.copyOf(spawnIslands);
        this.midIslands = List.copyOf(midIslands);
        this.middle = middle;
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

    public Island middle() {
        return this.middle;
    }

    public List<BlockPos> spawns() {
        return this.spawns;
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
            Island island = buildIsland(random, new BlockPos(x, mapCenter.getY(), z), islandRadius, cfg.skywarsChestsPerIsland);
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
            Island island = buildIsland(random, new BlockPos(x, mapCenter.getY(), z), midRadius, cfg.skywarsMidIslandChests);
            midIslands.add(island);
        }

        Island middle = buildIsland(random, mapCenter, Math.max(4, cfg.skywarsMiddleRadius), cfg.skywarsMiddleChests);

        int maxRadius = spawnDist + maxIslandRadius + MAX_RADIUS_MARGIN;
        return new SkyWarsLayout(mapCenter, maxRadius, spawnIslands, midIslands, middle, spawns);
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
}
