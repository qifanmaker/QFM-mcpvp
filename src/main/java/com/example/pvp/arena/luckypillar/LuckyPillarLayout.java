package com.example.pvp.arena.luckypillar;

import com.example.pvp.config.PvPConfig;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 幸运之柱地图布局：纯计算、确定性（由 seed = 比赛 ID 决定）。
 * 柱顶下方 luckyPillarPlatformGap（默认 20）格有一整圈大平台（掉出平台下方 20 格即死亡）；
 * 每根柱子是 1 格宽基岩棍，从平台竖到柱顶，沿圆环等角排列。
 */
public final class LuckyPillarLayout {

    /** 柱宽：1 格（一根棍子，没有悬挑平台）。 */
    public static final int PILLAR_WIDTH = 1;

    /** 掉出平台下方多少格判定死亡（"掉下平台 20 格死亡"）。 */
    public static final int FALL_DEATH_BELOW_PLATFORM = 20;

    /** 地图最大半径额外边距。 */
    public static final int MAX_RADIUS_MARGIN = 4;

    /** 一根柱子：柱顶中心、柱身底 Y、柱顶表面 Y、出生点。 */
    public static final class Pillar {
        public final BlockPos center;   // 柱顶中心（Y = topY，站立面）
        public final int columnBaseY;   // 柱身底部 Y（= 平台表面）
        public final int topY;          // 柱顶表面 Y
        public final BlockPos spawn;    // 出生点 = 柱顶 up(1)

        Pillar(BlockPos center, int columnBaseY, int topY, BlockPos spawn) {
            this.center = center;
            this.columnBaseY = columnBaseY;
            this.topY = topY;
            this.spawn = spawn;
        }

        public BlockPos center() {
            return this.center;
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
    private final int platformY;       // 平台表面 Y（柱顶下方 20 格）
    private final int platformRadius;  // 平台圆盘半径（= 地图最大半径）
    private final int maxRadius;
    private final List<Pillar> pillars;
    private final List<BlockPos> spawns;

    private LuckyPillarLayout(BlockPos mapCenter, int platformY, int maxRadius,
                              List<Pillar> pillars, List<BlockPos> spawns) {
        this.mapCenter = mapCenter;
        this.platformY = platformY;
        this.platformRadius = maxRadius;
        this.maxRadius = maxRadius;
        this.pillars = List.copyOf(pillars);
        this.spawns = List.copyOf(spawns);
    }

    public BlockPos mapCenter() {
        return this.mapCenter;
    }

    /** 平台表面 Y（柱顶下方 luckyPillarPlatformGap 格，掉落的"安全楼层"）。 */
    public int platformY() {
        return this.platformY;
    }

    /** 平台圆盘半径（覆盖整张地图范围）。 */
    public int platformRadius() {
        return this.platformRadius;
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

    /** 当前配置下按最大人数计算的地图最大半径（清场兜底，避免柱子/平台落在清理范围外）。 */
    public static int computeMaxRadius() {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int centerDist = PILLAR_WIDTH + Math.max(1, cfg.luckyPillarGap); // 相邻柱心距 = 柱宽 + 间隙
        int n = Math.max(2, cfg.luckyPillarMaxPlayers);
        double ringR = centerDist / (2.0 * Math.sin(Math.PI / n));
        return (int) Math.ceil(ringR + PILLAR_WIDTH / 2.0 + MAX_RADIUS_MARGIN);
    }

    /**
     * 由比赛 ID 与人数计算确定性的柱子布局。
     *
     * @param mapCenter   地图中心（平台圆心）
     * @param seed        比赛 ID（单调递增，每次重赛柱子排布不同）
     * @param playerCount 玩家人数（决定柱子数量）
     */
    public static LuckyPillarLayout compute(BlockPos mapCenter, int seed, int playerCount) {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int centerDist = PILLAR_WIDTH + Math.max(1, cfg.luckyPillarGap);
        int n = Math.max(2, playerCount);
        double ringR = centerDist / (2.0 * Math.sin(Math.PI / n)); // 圆环半径，保证相邻柱子恰好隔 gap 格

        Random random = new Random(seed * 31L + playerCount * 17L);
        int topY = mapCenter.getY() + Math.max(4, cfg.luckyPillarHeight);
        int platformY = topY - Math.max(4, cfg.luckyPillarPlatformGap); // 柱顶下方的大平台表面

        List<Pillar> pillars = new ArrayList<>();
        List<BlockPos> spawns = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double angle = i * 2.0 * Math.PI / n + (random.nextDouble() - 0.5) * 0.35;
            double r = ringR + (random.nextDouble() - 0.5) * 2.0;
            int x = mapCenter.getX() + (int) Math.round(Math.cos(angle) * r);
            int z = mapCenter.getZ() + (int) Math.round(Math.sin(angle) * r);
            BlockPos center = new BlockPos(x, topY, z);
            pillars.add(new Pillar(center, platformY, topY, center.up(1)));
            spawns.add(center.up(1)); // 出生点在柱顶
        }

        int maxRadius = (int) Math.ceil(ringR + PILLAR_WIDTH / 2.0 + MAX_RADIUS_MARGIN);
        return new LuckyPillarLayout(mapCenter, platformY, maxRadius, pillars, spawns);
    }

    /** 柱子保护判定：平台圆盘 + 各根基岩棍（柱身 1 格宽，从平台到柱顶）。 */
    public boolean contains(BlockPos pos) {
        // 平台圆盘（整片不可拆，避免挖洞掉下去）
        if (pos.getY() == this.platformY) {
            double dx = pos.getX() - this.mapCenter.getX();
            double dz = pos.getZ() - this.mapCenter.getZ();
            if (Math.sqrt(dx * dx + dz * dz) <= this.platformRadius) {
                return true;
            }
        }
        // 基岩棍
        for (Pillar pillar : this.pillars) {
            if (pos.getX() != pillar.center.getX() || pos.getZ() != pillar.center.getZ()) {
                continue;
            }
            if (pos.getY() >= pillar.columnBaseY && pos.getY() <= pillar.topY) {
                return true;
            }
        }
        return false;
    }
}
