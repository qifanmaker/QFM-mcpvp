package com.example.pvp.arena.race;

import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.mixin.TextDisplayEntityInvoker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * 把一张 {@link RaceTrack} 铺成真实的竞技场地形，并负责精确清场。
 *
 * <p><b>为什么要"栅格化"而不是"沿中心线扫掠"</b>：沿中心线每格画一条横截面，在切线是 45° 时
 * 相邻两条横截面之间会留下菱形空洞（必须把采样步长降到 1/√2 才能保证无缝，方块数直接翻倍）。
 * 反过来，遍历包围盒里的每个方块列、用最近采样点算它的横向偏移，再决定它是冰面 / 缓冲带 /
 * 护栏 / 场外 —— 既不会漏格也不会重复放，而且天然给出"跑宽了就掉速"的边界。
 *
 * <p><b>清场为什么用"重放"而不是"记下所有方块坐标"</b>：地形完全由 {@code track.seed()} 决定，
 * 环境随机数也是从同一个 Seed 派生的，所以清场只要用同样的算法再跑一遍、把方块换成空气即可，
 * 不用为每场比赛在内存里留几万个 {@link BlockPos}。
 *
 * <p>结构（对应需求里的"冰面 ↓ 缓冲 ↓ 冰墙 ↓ 环境"）：
 * <pre>
 *   冰面      packed_ice，|横向| ≤ 半宽
 *   缓冲      snow_block，半宽 &lt; |横向| ≤ 半宽+缓冲宽度（0.6 摩擦，冲出赛道立刻掉速）
 *   护栏      ice，缓冲带外沿 1 格厚 × barrierHeight 高（原版船 maxUpStep=0，1 格就是硬墙）
 *   环境      内场雪原/冰湖 + 雪山 + 冰柱 + 雪松 + 岩石 + 起终点看台（都在赛道走廊之外）
 * </pre>
 */
public final class RaceMapGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 门架高度（格）：船 + 玩家约 2.2 格，4 格高可以从下面正常通过。 */
    private static final int GATE_HEIGHT = 4;
    /** 内场地面相对冰面的高度差（格）：低一层平台，掉下去 = 离开赛道 → 触发回位。 */
    private static final int GROUND_DROP = 10;
    /**
     * 发车挡板高度（格）：立在起终点线上、横跨整个走廊（冰面 + 两侧缓冲带），
     * 把船挡在起跑线之后。GO 时被 {@link #clearStartBarrier} 撤掉。
     *
     * <p>1 格其实就够（原版船 {@code maxUpStep = 0}，1 格也是硬墙），2 格纯粹是为了看得清
     * —— 它同时正好填在起终点门架下方，视觉上就是一道"发车闸门"。
     */
    private static final int START_BARRIER_HEIGHT = 2;

    /**
     * 环境装饰离赛道走廊的最小净空（格）。
     *
     * <p>必须<b>大于</b> {@code BoatRaceSession} 的"离开赛道"判定阈值（走廊半宽 + 3 格），
     * 否则玩家掉到内场地面上时既不算"在赛道上"也不触发回位，会被永久卡在环境里。
     */
    private static final double FEATURE_CLEARANCE = 6.0;

    /**
     * 铺方块用的 setBlockState 标志位。
     *
     * <p>3 = 通知邻居 + 通知客户端；2 = 只通知客户端。赛道与环境是纯静态地形（冰/雪/石/木，
     * 没有红石、流体、重力方块），所以不需要邻居更新；关掉能明显减少大批量放置的开销。
     * 具体取值由实测决定（见 {@code /pvp debug boatrace build} 的耗时报告）。
     */
    private static final int PLACE_FLAGS = 3;

    private RaceMapGenerator() {
    }

    // ==================== 对外接口 ====================

    /** 铺设赛道 + 环境。返回放置的方块数（日志与性能观测用）。 */
    public static int build(ArenaWorld arena, RaceTrack track) {
        return pass(arena, track, false);
    }

    /** 精确清除 {@link #build} 放下的所有方块（用同一个 Seed 重放同一套布局）。 */
    public static int clear(ArenaWorld arena, RaceTrack track) {
        return pass(arena, track, true);
    }

    /**
     * 兜底清场：按区域中心 + 半径清一条 Y 带。
     *
     * <p>正常<b>不会</b>走到（{@code Match.cleanupArenaAndRelease} 会用 {@link #clear} 精确清），
     * 这里只是防止将来有别的调用方走到 {@code ArenaWorldManager.clearArena} 的通用分支 ——
     * 那个分支会按 size×size×高度扫，336×336×320 是三千多万次 setBlockState，会卡服。
     */
    public static int clearFallbackBand(ArenaWorld arena, BlockPos center, int radius, int y0, int y1) {
        int removed = 0;
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > r2) {
                    continue;
                }
                for (int y = y0; y <= y1; y++) {
                    BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    if (!arena.getBlockState(pos).isAir()) {
                        arena.setBlockState(pos, Blocks.AIR.getDefaultState(), PLACE_FLAGS);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    // ==================== 主流程 ====================

    private static int pass(ArenaWorld arena, RaceTrack track, boolean clearing) {
        PvPConfig cfg = PvPConfig.INSTANCE;
        BlockState surface = cfg.getBoatRaceSurfaceBlock().getDefaultState();
        BlockState runoff = cfg.getBoatRaceRunoffBlock().getDefaultState();
        BlockState barrier = cfg.getBoatRaceBarrierBlock().getDefaultState();
        // 装饰主题由 Seed 推导：铺场与清场拿到同一个主题，清场不会漏掉主题化的方块。
        RaceDecorTheme theme = RaceDecorTheme.pick(track.seed());
        if (!clearing) {
            LOGGER.info("[PvP] 亦可赛艇 seed {} 装饰主题：{}", track.seed(), theme.displayName());
        }

        int placed = 0;
        placed += rasterizeRibbon(arena, track, cleaning(clearing, surface), cleaning(clearing, runoff),
                cleaning(clearing, barrier));
        placed += buildForkIslands(arena, track, cleaning(clearing, barrier), clearing);
        placed += buildGates(arena, track, clearing, theme);
        placed += buildStartBarrier(arena, track, clearing);
        placed += buildEnvironment(arena, track, clearing, theme);
        return placed;
    }

    /** 清场时把任何方块都换成空气（而不是"原方块"），铺场时用真实方块。 */
    private static BlockState cleaning(boolean clearing, BlockState state) {
        return clearing ? Blocks.AIR.getDefaultState() : state;
    }

    /**
     * 冰面 / 缓冲带 / 护栏的栅格化。
     *
     * <p>只遍历赛道包围圆内的方块列，逐列取最近采样点算横向偏移 {@code lat}：
     * <pre>
     *   |lat| ≤ 半宽 - 0.5            冰面（顶面 + 底面，2 层）
     *   |lat| ≤ 半宽 + 缓冲 - 0.5     缓冲带（高摩擦方块）
     *   |lat| ≤ 半宽 + 缓冲 + 0.5     护栏（1 格厚 × barrierHeight 高）
     * </pre>
     * 超过护栏的列直接跳过（场外不放任何东西）。
     */
    private static int rasterizeRibbon(ArenaWorld arena, RaceTrack track,
                                      BlockState surface, BlockState runoff, BlockState barrier) {
        int y = track.surfaceY();
        double half = track.halfWidth();
        double halfRunoff = half + track.runoffWidth();
        double bound = track.boundingRadius() + 1.0;
        double bound2 = bound * bound;
        int cx = (int) Math.round(track.centerX());
        int cz = (int) Math.round(track.centerZ());
        int r = (int) Math.ceil(bound);

        int placed = 0;
        for (int x = cx - r; x <= cx + r; x++) {
            double dxc = x + 0.5 - track.centerX();
            for (int z = cz - r; z <= cz + r; z++) {
                double dzc = z + 0.5 - track.centerZ();
                if (dxc * dxc + dzc * dzc > bound2) {
                    continue;
                }
                double px = x + 0.5;
                double pz = z + 0.5;
                // 取"主线 ∪ 分岔支路"里最近的那条路：岔口两条走廊的带子自然融成一片扇面，
                // 中段两条走廊各自成路、中间剩下的部分由 buildForkIslands 填成中央分隔岛。
                int i = track.nearestSurface(px, pz);
                double lat = (px - track.surfaceX(i)) * track.surfaceNormalX(i)
                        + (pz - track.surfaceZ(i)) * track.surfaceNormalZ(i);
                double al = Math.abs(lat);
                if (al <= half - 0.5) {
                    arena.setBlockState(new BlockPos(x, y, z), surface, PLACE_FLAGS);
                    arena.setBlockState(new BlockPos(x, y - 1, z), surface, PLACE_FLAGS);
                    placed += 2;
                } else if (al <= halfRunoff - 0.5) {
                    // 缓冲带只放表层：底面省掉（下面看到的是冰面的底面 + 一圈雪檐），
                    // 每场少 ~7000 次 setBlockState，对铺图耗时影响很明显。
                    arena.setBlockState(new BlockPos(x, y, z), runoff, PLACE_FLAGS);
                    placed += 1;
                } else if (al <= halfRunoff + 0.5) {
                    for (int h = 0; h < track.barrierHeight(); h++) {
                        arena.setBlockState(new BlockPos(x, y + h, z), barrier, PLACE_FLAGS);
                        placed++;
                    }
                }
            }
        }
        return placed;
    }

    /**
     * 分岔的中段：两条路线之间的楔形区填成"中央分隔岛"。
     *
     * <p>并集栅格化只能保证"每条走廊自己的冰面 + 缓冲带 + 护栏"，两条走廊中间那块
     * （离两条中心线都超过"冰面 + 缓冲带 + 半格"）不属于任何一条走廊，会留成空档。
     * 这里按<b>同一横截面</b>配对（支路采样点的投影进度 ↔ 主线同进度的采样点），把两者之间
     * 除缓冲带以外的部分铺成护栏材质的实心岛：宽度随岔口张开、在岔口/汇合口自然收成 0，
     * 所以岔口处不会有墙横在路中间。
     */
    private static int buildForkIslands(ArenaWorld arena, RaceTrack track, BlockState barrier,
                                        boolean clearing) {
        if (track.branchCount() == 0) {
            return 0;
        }
        int y = track.surfaceY();
        int height = Math.max(1, track.barrierHeight());
        double halfRunoff = track.halfWidth() + track.runoffWidth();
        double keep = halfRunoff - 0.5;
        int placed = 0;
        for (RaceTrack.Branch branch : track.branches()) {
            for (int k = 0; k < branch.sampleCount(); k++) {
                // 支路采样点的投影进度 → 主线同进度的采样点（主线进度 = 下标 × STEP）
                double progress = branch.progressAt(k);
                int inner = Math.floorMod((int) Math.round(progress / RaceTrack.STEP), track.sampleCount());
                double ax = track.sampleX(inner);
                double az = track.sampleZ(inner);
                double bx = branch.x(k);
                double bz = branch.z(k);
                double dx = bx - ax;
                double dz = bz - az;
                double distance = Math.hypot(dx, dz);
                double from = keep;
                double to = distance - keep;
                if (to <= from) {
                    continue;
                }
                int steps = (int) Math.ceil(to - from);
                for (int s = 0; s <= steps; s++) {
                    double t = (from + s) / distance;
                    if (t > 1.0) {
                        break;
                    }
                    int px = (int) Math.floor(ax + dx * t);
                    int pz = (int) Math.floor(az + dz * t);
                    // 往下多铺 2 层，看起来是一块实心岛而不是悬空的一层墙
                    for (int h = -2; h < height; h++) {
                        arena.setBlockState(new BlockPos(px, y + h, pz), barrier, PLACE_FLAGS);
                        placed++;
                    }
                }
            }
        }
        return placed;
    }

    // ==================== Checkpoint 门架 / 起终点门 ====================

    /**
     * 每道门一个门架：轨道两侧各一根立柱 + 顶上一根横梁 + 悬空的门号文字。
     * 立柱正好压在护栏线上，不会伸进赛道；横梁在 {@link #GATE_HEIGHT} 高度，船从下面过。
     */
    private static int buildGates(ArenaWorld arena, RaceTrack track, boolean clearing,
                                  RaceDecorTheme theme) {
        Block pillar = theme.pillar();
        Block beam = theme.beam();
        Block finishBeam = theme.finishBeamA();
        Block finishBeamAlt = theme.finishBeamB();
        int placed = 0;
        int y = track.surfaceY();
        double offset = track.halfWidth() + track.runoffWidth();

        List<RaceTrack.Checkpoint> gates = track.checkpoints();
        for (int g = 0; g < gates.size(); g++) {
            RaceTrack.Checkpoint gate = gates.get(g);
            boolean finish = gate.isFinishLine();
            double pillarOffset = finish ? offset + 1.0 : offset;
            int pillarTop = y + (finish ? GATE_HEIGHT + 2 : GATE_HEIGHT);
            for (int side = -1; side <= 1; side += 2) {
                int bx = (int) Math.floor(gate.x() + gate.normalX() * pillarOffset * side);
                int bz = (int) Math.floor(gate.z() + gate.normalZ() * pillarOffset * side);
                for (int yy = y; yy <= pillarTop; yy++) {
                    setIfReplaceable(arena, new BlockPos(bx, yy, bz), clearing, pillar);
                    placed++;
                }
            }
            // 横梁：沿法向铺满两侧立柱之间
            int steps = (int) Math.ceil(pillarOffset * 2.0) + 1;
            for (int s = 0; s <= steps; s++) {
                double t = -pillarOffset + (2.0 * pillarOffset) * s / steps;
                int bx = (int) Math.floor(gate.x() + gate.normalX() * t);
                int bz = (int) Math.floor(gate.z() + gate.normalZ() * t);
                Block block = finish
                        ? (((s / 2) % 2 == 0) ? finishBeam : finishBeamAlt)
                        : beam;
                setIfReplaceable(arena, new BlockPos(bx, pillarTop, bz), clearing, block);
                placed++;
            }
            if (!clearing) {
                spawnGateLabel(arena, track, gate, pillarTop + 1);
            }
        }
        return placed;
    }

    /** 门架只压在护栏/空中，绝对不能覆盖赛道冰面：铺场时只在空气位置放。 */
    private static void setIfReplaceable(ArenaWorld arena, BlockPos pos, boolean clearing, Block block) {
        if (clearing) {
            arena.setBlockState(pos, Blocks.AIR.getDefaultState(), PLACE_FLAGS);
            return;
        }
        if (arena.getBlockState(pos).isAir()) {
            arena.setBlockState(pos, block.getDefaultState(), PLACE_FLAGS);
        }
    }

    /** 门号文字（原版展示实体）：起终点线显示"🏁 起终点"，普通门显示"CP n"。 */
    private static void spawnGateLabel(ArenaWorld arena, RaceTrack track, RaceTrack.Checkpoint gate, int y) {
        try {
            DisplayEntity.TextDisplayEntity display =
                    new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, arena);
            display.refreshPositionAndAngles(gate.x(), y, gate.z(), 0.0F, 0.0F);
            Text text = gate.isFinishLine()
                    ? Text.literal("§6§l🏁 起终点")
                    : Text.literal("§b§lCP " + gate.index() + "§r §7/ " + track.checkpointCount());
            TextDisplayEntityInvoker textInvoker = (TextDisplayEntityInvoker) display;
            textInvoker.pvp$setText(text);
            textInvoker.pvp$setDisplayFlags(DisplayEntity.TextDisplayEntity.SHADOW_FLAG);
            // 展示实体的配置 setter 在 1.21.1 全是 private，必须走项目已有的 @Invoker
            ((com.example.pvp.mixin.DisplayEntityInvoker) display)
                    .pvp$setBillboardMode(DisplayEntity.BillboardMode.CENTER);
            if (!arena.spawnEntity(display)) {
                LOGGER.warn("[PvP] 亦可赛艇：门号展示实体生成失败（gate {}，位置 {}, {}, {}）",
                        gate.index(), gate.x(), y, gate.z());
            }
        } catch (Exception ignored) {
            // 展示实体只是锦上添花，失败不影响比赛
        }
    }

    // ==================== 发车挡板 ====================

    /**
     * 发车挡板的方块坐标（确定性，铺场与清场共用同一份）。
     *
     * <p>沿<b>起终点线的平面</b>横跨整个走廊：从 -走廊半宽 到 +走廊半宽，两端正好顶到护栏，
     * 所以没有任何缝可以绕过去（冰面 + 两侧缓冲带全被挡住）。高度 {@link #START_BARRIER_HEIGHT} 格。
     *
     * <p>玩家侧表现：船停在挡板后面，倒计时期间可以在格位附近自由划动但过不去线；
     * GO 那一 tick 挡板消失，所有人一起冲出去。
     */
    public static List<BlockPos> startBarrierPositions(RaceTrack track) {
        RaceTrack.Checkpoint line = track.checkpoint(0);
        double half = track.corridorHalfWidth();
        int y = track.surfaceY();
        int steps = (int) Math.ceil(half * 2.0) + 1;
        List<BlockPos> positions = new ArrayList<>();
        LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
        for (int s = 0; s <= steps; s++) {
            double lateral = -half + (2.0 * half) * s / steps;
            int bx = (int) Math.floor(line.x() + line.normalX() * lateral);
            int bz = (int) Math.floor(line.z() + line.normalZ() * lateral);
            for (int h = 1; h <= START_BARRIER_HEIGHT; h++) {
                long key = BlockPos.asLong(bx, y + h, bz);
                if (seen.add(key)) {
                    positions.add(BlockPos.fromLong(key));
                }
            }
        }
        return positions;
    }

    /** 铺发车挡板（红白相间，看起来就是一条赛车道闸门；约 40 个方块）。 */
    private static int buildStartBarrier(ArenaWorld arena, RaceTrack track, boolean clearing) {
        RaceTrack.Checkpoint line = track.checkpoint(0);
        double half = track.corridorHalfWidth();
        int y = track.surfaceY();
        int steps = (int) Math.ceil(half * 2.0) + 1;
        int placed = 0;
        LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
        for (int s = 0; s <= steps; s++) {
            double lateral = -half + (2.0 * half) * s / steps;
            int bx = (int) Math.floor(line.x() + line.normalX() * lateral);
            int bz = (int) Math.floor(line.z() + line.normalZ() * lateral);
            BlockState state = (s % 2 == 0)
                    ? Blocks.RED_CONCRETE.getDefaultState() : Blocks.WHITE_CONCRETE.getDefaultState();
            for (int h = 1; h <= START_BARRIER_HEIGHT; h++) {
                long key = BlockPos.asLong(bx, y + h, bz);
                if (!seen.add(key)) {
                    continue;
                }
                arena.setBlockState(BlockPos.fromLong(key), cleaning(clearing, state), PLACE_FLAGS);
                placed++;
            }
        }
        return placed;
    }

    /**
     * 撤掉发车挡板（GO 那一 tick 调用）。
     *
     * <p>这是本模式唯一一处"开赛后还会改地形"的地方，就 40 来个方块，直接写世界即可
     * （此时已经退出暂存阶段）。清场时 {@link #clear} 会把它当空气再重放一遍，不冲突。
     *
     * @return 实际撤掉的方块数
     */
    public static int clearStartBarrier(ArenaWorld arena, RaceTrack track) {
        int removed = 0;
        for (BlockPos pos : startBarrierPositions(track)) {
            if (!arena.getBlockState(pos).isAir()) {
                arena.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                removed++;
            }
        }
        return removed;
    }

    // ==================== 环境 ====================

    /**
     * 围绕赛道的雪原 / 冰湖环境。所有装饰都先检查"离中心线的距离 &gt; 走廊半宽 + 净空"，
     * 保证永远不会长到赛道上；环境随机数由 {@code track.seed()} 派生，所以清场时能精确重放。
     */
    private static int buildEnvironment(ArenaWorld arena, RaceTrack track, boolean clearing,
                                        RaceDecorTheme theme) {
        Random random = new Random(track.seed() * 0x2545F4914F6CDD1DL + 0x9E3779B97F4A7C15L);
        int placed = 0;
        int y = track.surfaceY();
        int groundY = y - GROUND_DROP;

        // ---- 内场：低于冰面的雪原（破碎的冰湖地块），给整个场景一个"底" ----
        double innerRadius = innerRadius(track);
        double fieldRadius = Math.max(8.0, Math.min(innerRadius - 8.0, 42.0));
        if (fieldRadius > 6) {
            placed += groundField(arena, track, groundY, fieldRadius,
                    track.centerX(), track.centerZ(), clearing, theme);
        }

        // ---- 内场雪山（背景） ----
        // 数量/位置随机：1~3 座，配合主题的雪/岩方块，让每张图的远景也不同。
        int mountains = 1 + random.nextInt(3);
        for (int m = 0; m < mountains; m++) {
            double angle = random.nextDouble() * 2.0 * Math.PI;
            double dist = random.nextDouble() * Math.max(1.0, fieldRadius * 0.55);
            int mx = (int) Math.round(track.centerX() + Math.cos(angle) * dist);
            int mz = (int) Math.round(track.centerZ() + Math.sin(angle) * dist);
            int baseRadius = 7 + random.nextInt(6);
            int height = 9 + random.nextInt(9);
            placed += mountain(arena, track, mx, groundY, mz, baseRadius, height, clearing, theme);
        }

        // ---- 内场冰柱 / 雪松 / 岩石 ----
        int features = 12 + random.nextInt(16);
        for (int f = 0; f < features; f++) {
            double angle = random.nextDouble() * 2.0 * Math.PI;
            double dist = random.nextDouble() * fieldRadius * 0.92;
            int fx = (int) Math.round(track.centerX() + Math.cos(angle) * dist);
            int fz = (int) Math.round(track.centerZ() + Math.sin(angle) * dist);
            if (!clearOfTrack(track, fx, fz)) {
                continue;
            }
            placed += scatterFeature(arena, random, fx, groundY, fz, clearing, theme);
        }

        // ---- 外圈：赛道外侧到区域边缘之间（只有几格到二十几格宽），放冰柱/雪松/岩石 ----
        double outerRingMax = track.boundingRadius() + 24.0;
        int outerFeatures = 12 + random.nextInt(18);
        for (int f = 0; f < outerFeatures; f++) {
            double angle = random.nextDouble() * 2.0 * Math.PI;
            double dist = track.boundingRadius() + 3.0 + random.nextDouble()
                    * Math.max(1.0, outerRingMax - track.boundingRadius() - 3.0);
            int fx = (int) Math.round(track.centerX() + Math.cos(angle) * dist);
            int fz = (int) Math.round(track.centerZ() + Math.sin(angle) * dist);
            if (!clearOfTrack(track, fx, fz) || !insideRegion(track, fx, fz, 6.0)) {
                continue;
            }
            placed += scatterFeature(arena, random, fx, groundY, fz, clearing, theme);
        }

        // ---- 起终点看台（观众区） ----
        placed += grandstand(arena, track, clearing, theme);
        return placed;
    }

    /** 内场地面：雪原 / 冰湖混合的地块，随机留出一些空洞让边缘看起来是碎裂的浮冰。 */
    private static int groundField(ArenaWorld arena, RaceTrack track,
                                   int groundY, double radius, double cx, double cz, boolean clearing,
                                   RaceDecorTheme theme) {
        BlockState snow = theme.groundSnow().getDefaultState();
        BlockState ice = theme.groundIce().getDefaultState();
        int placed = 0;
        int ir = (int) Math.ceil(radius);
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > radius) {
                    continue;
                }
                if (!clearOfTrack(track, (int) Math.round(cx + dx), (int) Math.round(cz + dz))) {
                    continue;
                }
                // 边缘留出 30% 的空洞，看起来像碎裂的冰面而不是一块圆饼
                double edge = d / radius;
                double noise = noise2(track.seed(), (int) Math.round(cx + dx) >> 2, (int) Math.round(cz + dz) >> 2);
                if (edge > 0.6 && noise < 0.45) {
                    continue;
                }
                BlockPos pos = new BlockPos((int) Math.round(cx + dx), groundY, (int) Math.round(cz + dz));
                // 只放一层：地面纯粹是"底"和装饰的落脚点，铺两层要多一倍方块却看不出来
                arena.setBlockState(pos, cleaning(clearing, noise > 0.6 ? ice : snow), PLACE_FLAGS);
                placed += 1;
            }
        }
        return placed;
    }

    /** 锥形雪山：主题雪块 + 主题岩石，顶部收尖。 */
    private static int mountain(ArenaWorld arena, RaceTrack track, int cx, int baseY, int cz,
                               int baseRadius, int height, boolean clearing, RaceDecorTheme theme) {
        int placed = 0;
        for (int yy = 0; yy < height; yy++) {
            double t = yy / (double) height;
            int r = (int) Math.round(baseRadius * (1.0 - t * t * 0.95));
            if (r <= 0) {
                r = 1;
            }
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) {
                        continue;
                    }
                    int x = cx + dx;
                    int z = cz + dz;
                    if (!clearOfTrack(track, x, z)) {
                        continue;
                    }
                    // 只放外壳，内部空着（省方块）
                    boolean shell = (yy == 0) || (r - Math.hypot(dx, dz) < 1.4);
                    if (!shell) {
                        continue;
                    }
                    BlockState state = (t > 0.62 && noise2(track.seed(), x, z) > 0.55)
                            ? theme.rock().getDefaultState()
                            : theme.groundSnow().getDefaultState();
                    arena.setBlockState(new BlockPos(x, baseY + yy, z), cleaning(clearing, state), PLACE_FLAGS);
                    placed++;
                }
            }
        }
        return placed;
    }

    /** 内/外圈的单体装饰：冰柱 / 雪松 / 岩石（随机挑一种，方块随主题）。 */
    private static int scatterFeature(ArenaWorld arena, Random random, int x, int baseY, int z,
                                     boolean clearing, RaceDecorTheme theme) {
        int kind = random.nextInt(3);
        int placed = 0;
        if (kind == 0) {
            // 冰柱：主题"冰"或"岩"柱，顶部插一根浮冰尖
            int height = 4 + random.nextInt(9);
            BlockState body = random.nextBoolean()
                    ? theme.groundIce().getDefaultState() : theme.rock().getDefaultState();
            for (int h = 0; h < height; h++) {
                arena.setBlockState(new BlockPos(x, baseY + h, z), cleaning(clearing, body), PLACE_FLAGS);
                placed++;
            }
            arena.setBlockState(new BlockPos(x, baseY + height, z),
                    cleaning(clearing, Blocks.ICE.getDefaultState()), PLACE_FLAGS);
            placed++;
        } else if (kind == 1) {
            // 雪松：云杉原木 + 两层针叶
            int height = 5 + random.nextInt(4);
            for (int h = 0; h < height; h++) {
                arena.setBlockState(new BlockPos(x, baseY + h, z),
                        cleaning(clearing, Blocks.SPRUCE_LOG.getDefaultState()), PLACE_FLAGS);
                placed++;
            }
            int leafBottom = baseY + height - 3;
            for (int layer = 0; layer < 3; layer++) {
                int r = Math.max(0, 2 - layer / 2);
                int yy = leafBottom + layer + 1;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dz * dz > r * r + 1) {
                            continue;
                        }
                        arena.setBlockState(new BlockPos(x + dx, yy, z + dz),
                                cleaning(clearing, Blocks.SPRUCE_LEAVES.getDefaultState()), PLACE_FLAGS);
                        placed++;
                    }
                }
            }
        } else {
            // 岩石：小石堆
            int r = 1 + random.nextInt(2);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = 0; dy <= r; dy++) {
                        if (dx * dx + dz * dz + dy * dy > r * r + 1) {
                            continue;
                        }
                        arena.setBlockState(new BlockPos(x + dx, baseY + dy, z + dz),
                                cleaning(clearing, theme.rock().getDefaultState()), PLACE_FLAGS);
                        placed++;
                    }
                }
            }
        }
        return placed;
    }

    /** 起终点看台：赛道外侧一小片主题木料平台 + 台阶 + 栏杆。 */
    private static int grandstand(ArenaWorld arena, RaceTrack track, boolean clearing,
                                  RaceDecorTheme theme) {
        RaceTrack.Checkpoint line = track.checkpoint(0);
        // 起终点线的法向正侧、缓冲带之外
        double offset = track.halfWidth() + track.runoffWidth() + 8.0;
        double ox = line.x() + line.normalX() * offset;
        double oz = line.z() + line.normalZ() * offset;
        int y = track.surfaceY();
        int placed = 0;
        // 平台 11（沿切线）× 7（沿法向）
        for (int along = -5; along <= 5; along++) {
            for (int out = 0; out < 7; out++) {
                int x = (int) Math.floor(ox + line.dirX() * along + line.normalX() * out);
                int z = (int) Math.floor(oz + line.dirZ() * along + line.normalZ() * out);
                if (!clearOfTrack(track, x, z)) {
                    continue;
                }
                int step = out / 2;
                arena.setBlockState(new BlockPos(x, y + step, z),
                        cleaning(clearing, theme.standPlanks().getDefaultState()), PLACE_FLAGS);
                arena.setBlockState(new BlockPos(x, y + step - 1, z),
                        cleaning(clearing, theme.standPlanks().getDefaultState()), PLACE_FLAGS);
                placed += 2;
            }
        }
        // 两侧栅栏
        for (int along = -5; along <= 5; along += 5) {
            for (int out = 0; out < 7; out++) {
                int x = (int) Math.floor(ox + line.dirX() * along + line.normalX() * out);
                int z = (int) Math.floor(oz + line.dirZ() * along + line.normalZ() * out);
                if (!clearOfTrack(track, x, z)) {
                    continue;
                }
                int step = out / 2;
                arena.setBlockState(new BlockPos(x, y + step + 1, z),
                        cleaning(clearing, theme.standFence().getDefaultState()), PLACE_FLAGS);
                placed++;
            }
        }
        return placed;
    }

    // ==================== 几何辅助 ====================

    /**
     * 该方块列是否远离赛道走廊（环境装饰的准入条件）。
     * 走廊半宽之外再留 {@link #FEATURE_CLEARANCE} 格净空。
     */
    private static boolean clearOfTrack(RaceTrack track, int x, int z) {
        return track.distanceToCenterline(x + 0.5, z + 0.5)
                > track.corridorHalfWidth() + FEATURE_CLEARANCE;
    }

    /** 是否还在本场分配的区域方块内（避免装饰溢到相邻竞技场）。 */
    private static boolean insideRegion(RaceTrack track, int x, int z, double margin) {
        double half = PvPConfig.INSTANCE.boatRaceSize / 2.0 - margin;
        return Math.abs(x + 0.5 - track.centerX()) <= half && Math.abs(z + 0.5 - track.centerZ()) <= half;
    }

    /** 中心到中心线的最近距离（内场半径）。 */
    private static double innerRadius(RaceTrack track) {
        double cx = track.centerX();
        double cz = track.centerZ();
        double min = Double.MAX_VALUE;
        for (int i = 0; i < track.sampleCount(); i++) {
            min = Math.min(min, Math.hypot(track.sampleX(i) - cx, track.sampleZ(i) - cz));
        }
        return min == Double.MAX_VALUE ? 40.0 : min;
    }

    /** 确定性 2D 值噪声（0..1），用于地面空洞与山体材质——不依赖 Math.random，方便复现。 */
    private static double noise2(long seed, int x, int z) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
