package com.example.pvp.arena.luckypillar;

import com.example.pvp.config.PvPConfig;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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

    /**
     * 底部大平台（掉落的"安全楼层"）表面风格：每局随机抽取一种。
     * 由比赛 ID（seed）决定，同一局固定、不同局不同。
     */
    public enum PlatformStyle {
        /** 整片岩浆块：踩上持续烫伤。 */
        MAGMA,
        /** 整片装有岩浆的炼药锅：站上面安全，视觉醒目。 */
        LAVA_CAULDRON,
        /** 雪块 / 细雪随机间隔：细雪格陷入减速（不会掉穿）。 */
        SNOW_POWDER,
        /** 整片蜘蛛网作为地板（无底部支撑方块）：掉进网里减速被困，安全但行动迟缓。 */
        COBWEB,
        /** 沙子底（下方铺线防止沙子下落）+ 仙人掌间隔放置（1~2 格高，可自然生长）：碰触受伤。 */
        SAND_CACTUS,
        /** 整片常绿橡树叶（持久，不会消散）。 */
        LEAVES,
        /** 整片关闭的橡木活版门：薄地板。 */
        TRAPDOOR,
        /** 整片平滑石台阶（下半砖）。 */
        SLAB,
        /** 粘液块 / 蜂蜜块随机间隔：粘液格弹跳、蜂蜜格粘滞减速。 */
        SLIME_HONEY,
        /** 虚空地板：稀疏散布的地板砖，其余为虚空（洞）——需踩砖行动，掉进洞会下落。 */
        VOID;

        /** 随机抽一种风格。 */
        public static PlatformStyle pick(Random random) {
            PlatformStyle[] values = values();
            return values[random.nextInt(values.length)];
        }
    }

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
    private final PlatformStyle platformStyle;        // 本局平台风格（由 seed 随机）
    /** 平台表面方块映射（platformY 层每格 → 方块；AIR 表示虚空洞/无方块）。 */
    private final Map<BlockPos, Block> surface;
    /** 平台支撑方块（SAND_CACTUS 的线，铺在 platformY-1 防止沙子下落）。 */
    private final Set<BlockPos> support;
    /** 平台装饰方块（仙人掌，platformY 上方 1~2 格）。 */
    private final Set<BlockPos> decorations;

    private LuckyPillarLayout(BlockPos mapCenter, int platformY, int maxRadius,
                              List<Pillar> pillars, List<BlockPos> spawns,
                              PlatformStyle platformStyle, Map<BlockPos, Block> surface,
                              Set<BlockPos> support, Set<BlockPos> decorations) {
        this.mapCenter = mapCenter;
        this.platformY = platformY;
        this.platformRadius = maxRadius;
        this.maxRadius = maxRadius;
        this.pillars = List.copyOf(pillars);
        this.spawns = List.copyOf(spawns);
        this.platformStyle = platformStyle;
        this.surface = Map.copyOf(surface);
        this.support = Set.copyOf(support);
        this.decorations = Set.copyOf(decorations);
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

    /** 本局平台风格。 */
    public PlatformStyle platformStyle() {
        return this.platformStyle;
    }

    /** 平台表面方块映射（platformY 层每格 → 方块；AIR 表示虚空洞/无方块）。 */
    public Map<BlockPos, Block> surface() {
        return this.surface;
    }

    /** 平台支撑方块位置（SAND_CACTUS 的线，platformY-1 层）。 */
    public Set<BlockPos> support() {
        return this.support;
    }

    /** 平台装饰方块位置（仙人掌）。 */
    public Set<BlockPos> decorations() {
        return this.decorations;
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

        // 每局随机抽一种平台风格
        PlatformStyle style = PlatformStyle.pick(random);

        // 预计算平台表面方块映射（platformY 层每个在圆盘内的格子 → 方块）：
        // 随机分布类（雪/细雪、粘液/蜂蜜）用 Random 逐格决定，不规律交错；
        // VOID 为稀疏砖块（其余虚空洞）；其余风格整片统一方块。
        Map<BlockPos, Block> surface = new HashMap<>();
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                if (Math.sqrt(dx * dx + dz * dz) > maxRadius) {
                    continue;
                }
                Block block;
                switch (style) {
                    case MAGMA -> block = Blocks.MAGMA_BLOCK;
                    case LAVA_CAULDRON -> block = Blocks.LAVA_CAULDRON;
                    case SNOW_POWDER -> block = random.nextBoolean() ? Blocks.SNOW_BLOCK : Blocks.POWDER_SNOW;
                    case COBWEB -> block = Blocks.COBWEB; // 蜘蛛网直接作为地板，无底部支撑
                    case SAND_CACTUS -> block = Blocks.SAND;
                    case LEAVES -> block = Blocks.OAK_LEAVES;
                    case TRAPDOOR -> block = Blocks.OAK_TRAPDOOR;
                    case SLAB -> block = Blocks.SMOOTH_STONE_SLAB;
                    case SLIME_HONEY -> block = random.nextBoolean() ? Blocks.SLIME_BLOCK : Blocks.HONEY_BLOCK;
                    default -> { // VOID：整片虚空，完全没有任何方块（全是洞）
                        block = Blocks.AIR;
                    }
                }
                surface.put(new BlockPos(mapCenter.getX() + dx, platformY, mapCenter.getZ() + dz), block);
            }
        }

        // 支撑方块（SAND_CACTUS）：沙子下方 platformY-1 整片铺线，防止受重力沙子下落
        Set<BlockPos> support = new HashSet<>();
        if (style == PlatformStyle.SAND_CACTUS) {
            for (int dx = -maxRadius; dx <= maxRadius; dx++) {
                for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                    if (Math.sqrt(dx * dx + dz * dz) > maxRadius) {
                        continue;
                    }
                    support.add(new BlockPos(mapCenter.getX() + dx, platformY - 1, mapCenter.getZ() + dz));
                }
            }
        }

        // 装饰方块（SAND_CACTUS）：仙人掌按棋盘格放置——(dx+dz) 偶数的格子放 1~2 格高仙人掌，
        // 形成规律的棋盘格图案（奇数格为空可通行），避开柱子脚下
        Set<BlockPos> decorations = new HashSet<>();
        if (style == PlatformStyle.SAND_CACTUS) {
            for (int dx = -maxRadius; dx <= maxRadius; dx++) {
                for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                    if (((dx + dz) & 1) != 0) {
                        continue; // 只放棋盘格一半的格子
                    }
                    if (Math.sqrt(dx * dx + dz * dz) > maxRadius) {
                        continue;
                    }
                    int x = mapCenter.getX() + dx;
                    int z = mapCenter.getZ() + dz;
                    // 避开柱子脚下 2 格范围，防止仙人掌贴柱阻挡
                    boolean nearPillar = false;
                    for (Pillar p : pillars) {
                        int pdx = x - p.center().getX();
                        int pdz = z - p.center().getZ();
                        if (pdx * pdx + pdz * pdz <= 4) {
                            nearPillar = true;
                            break;
                        }
                    }
                    if (nearPillar) {
                        continue;
                    }
                    int height = 1 + random.nextInt(2); // 1~2 格高
                    for (int dy = 1; dy <= height; dy++) {
                        decorations.add(new BlockPos(x, platformY + dy, z));
                    }
                }
            }
        }

        return new LuckyPillarLayout(mapCenter, platformY, maxRadius, pillars, spawns, style,
                surface, support, decorations);
    }

    /** 柱子保护判定：仅各根基岩棍（柱身 1 格宽，从平台到柱顶）不可破坏；平台方块可自由破坏。 */
    public boolean contains(BlockPos pos) {
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
