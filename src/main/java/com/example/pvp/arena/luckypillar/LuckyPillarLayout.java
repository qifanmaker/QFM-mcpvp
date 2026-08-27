package com.example.pvp.arena.luckypillar;

import com.example.pvp.config.PvPConfig;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 幸运之柱地图布局：纯计算、确定性（由 seed = 比赛 ID 决定）。
 * 每名玩家一根高柱，柱子沿圆环等角排列；柱顶平台 + 下方柱身。
 */
public final class LuckyPillarLayout {

    /** 柱身半径（比平台小一圈，形成柱顶平台悬挑的塔形轮廓）。 */
    public static final int COLUMN_RADIUS = 2;

    /** 地图最大半径额外边距。 */
    public static final int MAX_RADIUS_MARGIN = 4;

    /** 一根柱子：柱顶平台中心、平台半径、柱身底 Y、平台表面 Y、出生点。 */
    public static final class Pillar {
        public final BlockPos center;   // 柱顶平台中心（Y = topY）
        public final int platformRadius;
        public final int columnBaseY;   // 柱身底部 Y
        public final int topY;          // 柱顶平台表面 Y
        public final BlockPos spawn;    // 出生点 = 平台中心 up(1)

        Pillar(BlockPos center, int platformRadius, int columnBaseY, int topY, BlockPos spawn) {
            this.center = center;
            this.platformRadius = platformRadius;
            this.columnBaseY = columnBaseY;
            this.topY = topY;
            this.spawn = spawn;
        }

        public BlockPos center() {
            return this.center;
        }

        public int platformRadius() {
            return this.platformRadius;
        }

        public int columnBaseY() {
            return this.columnBaseY;
        }

        public int topY() {
            return this.topY;
        }

        public BlockPos spawn() {
            return this.spawn;
        }
    }

    private final BlockPos mapCenter;
    private final int maxRadius;
    private final List<Pillar> pillars;
    private final List<BlockPos> spawns;

    private LuckyPillarLayout(BlockPos mapCenter, int maxRadius, List<Pillar> pillars, List<BlockPos> spawns) {
        this.mapCenter = mapCenter;
        this.maxRadius = maxRadius;
        this.pillars = List.copyOf(pillars);
        this.spawns = List.copyOf(spawns);
    }

    public BlockPos mapCenter() {
        return this.mapCenter;
    }

    public int maxRadius() {
        return this.maxRadius;
    }

    public List<Pillar> pillars() {
        return this.pillars;
    }

    public List<BlockPos> spawns() {
        return this.spawns;
    }

    /** 当前配置下按最大人数计算的地图最大半径（清场兜底，避免柱子落在清理范围外）。 */
    public static int computeMaxRadius() {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int platformRadius = Math.max(1, cfg.luckyPillarPlatformRadius);
        int centerDist = platformRadius * 2 + Math.max(1, cfg.luckyPillarGap);
        int n = Math.max(2, cfg.luckyPillarMaxPlayers);
        double ringR = centerDist / (2.0 * Math.sin(Math.PI / n));
        return (int) Math.ceil(ringR + platformRadius + MAX_RADIUS_MARGIN);
    }

    /**
     * 由比赛 ID 与人数计算确定性的柱子布局。
     *
     * @param mapCenter   地图中心（圆环圆心）
     * @param seed        比赛 ID（单调递增，每次重赛柱子排布不同）
     * @param playerCount 玩家人数（决定柱子数量）
     */
    public static LuckyPillarLayout compute(BlockPos mapCenter, int seed, int playerCount) {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int platformRadius = Math.max(1, cfg.luckyPillarPlatformRadius);
        int gap = Math.max(1, cfg.luckyPillarGap);
        int centerDist = platformRadius * 2 + gap; // 相邻平台圆心距 = 平台直径 + 间隙
        int n = Math.max(2, playerCount);
        double ringR = centerDist / (2.0 * Math.sin(Math.PI / n)); // 圆环半径，保证相邻平台恰好隔 gap 格

        Random random = new Random(seed * 31L + playerCount * 17L);
        int topY = mapCenter.getY() + Math.max(4, cfg.luckyPillarHeight);
        int columnBaseY = mapCenter.getY() - Math.max(4, cfg.luckyPillarColumnDepth);

        List<Pillar> pillars = new ArrayList<>();
        List<BlockPos> spawns = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double angle = i * 2.0 * Math.PI / n + (random.nextDouble() - 0.5) * 0.35;
            double r = ringR + (random.nextDouble() - 0.5) * 2.0;
            int x = mapCenter.getX() + (int) Math.round(Math.cos(angle) * r);
            int z = mapCenter.getZ() + (int) Math.round(Math.sin(angle) * r);
            BlockPos center = new BlockPos(x, topY, z);
            pillars.add(new Pillar(center, platformRadius, columnBaseY, topY, center.up(1)));
            spawns.add(center.up(1)); // 出生点在平台中心地表
        }

        int maxRadius = (int) Math.ceil(ringR + platformRadius + MAX_RADIUS_MARGIN);
        return new LuckyPillarLayout(mapCenter, maxRadius, pillars, spawns);
    }

    /** 柱子保护判定：该坐标是否属于某根柱子的平台或柱身（不可破坏）。 */
    public boolean contains(BlockPos pos) {
        for (Pillar pillar : this.pillars) {
            int dx = pos.getX() - pillar.center.getX();
            int dz = pos.getZ() - pillar.center.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (pos.getY() == pillar.topY && dist <= pillar.platformRadius) {
                return true; // 柱顶平台
            }
            if (pos.getY() >= pillar.columnBaseY && pos.getY() < pillar.topY && dist <= COLUMN_RADIUS) {
                return true; // 柱身
            }
        }
        return false;
    }
}
