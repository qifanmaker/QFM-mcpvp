package com.example.pvp.arena.race;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 随机赛道生成器（纯几何，不依赖 Minecraft，可离线批量跑）。
 *
 * <p><b>为什么用极坐标傅里叶曲线而不是"随机折线"</b>
 * <ul>
 *   <li><b>不会自交</b>：{@code r(θ) > 0} 且 θ 单调，则从原点出发的每条射线只与曲线相交一次，
 *       曲线必然是简单闭环。随机折线要靠事后检测 + 重试，命中率低，而且很容易出"两段贴在一起、
 *       看起来像一条路"的贴边段。</li>
 *   <li><b>曲率可控</b>：{@code κ = (r² + 2r'² - r·r'') / (r² + r'²)^{3/2}} 只由几个谐波决定。
 *       谐波振幅为正 → 局部鼓起 → 曲率半径小（慢弯 / 发卡）；振幅为负 → 局部压平 → 曲率半径大
 *       （直道 / 高速弯）。"直道 / 长弯 / S 弯 / 发卡弯"因此都能用同一套参数表达。</li>
 *   <li><b>长度可调</b>：同样的包围盒里，谐波频率越高、振幅越大，闭环长度越长。这是把
 *       600~1000 格赛道塞进半径 150 的竞技场区域的关键。</li>
 * </ul>
 *
 * <p>生成流程：
 * <pre>
 *   样式 + 谐波随机 → 极坐标采样 → 按弧长等距重采样（顺带完成"平滑中心线"）
 *   → 取最平直处作为起终点线（旋转采样数组）→ 切向/法向
 *   → Checkpoint 沿弧长等距 → 起跑格位 → RaceTrack（含最近点网格）
 *   → RaceTrackValidator 校验 + 评分
 * </pre>
 *
 * <p>校验失败就 {@code seed + 1} 重来；在 {@code maxAttempts} 个候选里挑评分最高的那张，
 * 而不是"生一张算一张"。全部失败时退回确定性的正圆赛道（保证比赛永远开得起来，并记录告警）。
 */
public final class RaceTrackGenerator {
    /** 随机赛道的基准半径上下限（配合最大振幅，保证外沿半径 ≤ RaceTrackValidator.MAX_BOUNDING_RADIUS）。 */
    private static final double MIN_BASE_RADIUS = 76.0;
    private static final double MAX_BASE_RADIUS = 108.0;
    /** 兜底正圆可以更大（没有振幅，外沿半径 ≈ R0 + 半宽 + 缓冲带）。 */
    private static final double MAX_CIRCLE_RADIUS = 154.0;
    /**
     * 风格谐波的总振幅相对 R0 的范围：决定"有多波浪"，同时决定包围半径。
     * 太小 → 生成出来都是"接近正圆"，没有直道也没有明显弯；太大 → 弯太急被校验器拒掉。
     * 实测区间取 0.12~0.30（配合"造直道"谐波，总振幅最高约 0.55 R0）。
     */
    private static final double MIN_AMPLITUDE = 0.12;
    private static final double MAX_AMPLITUDE = 0.30;
    /**
     * 起跑格位：首行离起终点线的距离、行距、单格横向间距（格）。
     *
     * <p>首行留 {@link #GRID_FIRST_ROW_BACK} 格是为了给起跑线上的<b>发车挡板</b>留出余量
     * （挡板就立在起终点线上，2 格高，GO 时撤掉）—— 船头离挡板约 3 格，不会一出生就顶上去。
     * 人数超出一排时<b>只往后加排</b>，不改变赛道宽度，所以列数与列距是固定的。
     */
    private static final double GRID_FIRST_ROW_BACK = 4.0;
    private static final double GRID_ROW_SPACING = 5.0;
    private static final double GRID_COLUMN_SPACING = 3.5;

    /**
     * 生成参数（由调用方从 PvPConfig 组装；生成器本身不读配置，便于离线测试）。
     *
     * @param checkpointCount 普通 Checkpoint 数量；&lt;= 0 表示按赛道长度自动
     * @param playerCount     本场玩家数（决定起跑格位数量）
     */
    public record Settings(double minLength, double maxLength, double targetLength,
                           double width, double minCornerRadius, double minClearance,
                           int runoffWidth, int barrierHeight,
                           int checkpointCount, int maxAttempts, boolean randomTrack,
                           int playerCount, double minStraightLength,
                           double centerX, double centerZ, int surfaceY) {
    }

    /** 生成结果：最终赛道 + 尝试次数 + 是否退回兜底 + 日志行。 */
    public record Outcome(RaceTrack track, int attempts, boolean usedFallback, List<String> notes) {
    }

    /**
     * 单个候选的诊断结果（不经过多候选筛选）。
     *
     * <p>给 {@code /pvp debug boatrace} 用：可以指定 Seed 看这张图到底合不合法、为什么被拒；
     * 也让离线调参脚本能直接拿到"逐候选"的通过率和拒绝原因。
     */
    public record Candidate(RaceTrack track, RaceTrackValidator.Result result) {
    }

    /** 只生成一个候选（Seed 不偏移）并校验，不做多候选筛选、也不退回兜底。 */
    public static Candidate probe(long seed, Settings settings, boolean circular) {
        RaceTrack track = build(seed, 0, settings, circular);
        return new Candidate(track, RaceTrackValidator.validate(track, limits(settings)));
    }

    /** 中心线在某个弧长处的几何（门与格位复用同一份插值逻辑）。 */
    private record Sample(double x, double z, double dirX, double dirZ,
                          double normalX, double normalZ) {
    }

    private RaceTrackGenerator() {
    }

    /**
     * 主入口：围绕 {@code baseSeed} 尝试生成合法赛道，返回其中评分最高的那张。
     *
     * <p>相同 {@code baseSeed} + 相同 {@link Settings} 必得完全相同的结果（纯函数：只用
     * {@link Random} 且种子完全由参数决定），所以日志里打出 Seed 就能精确复现出问题的那张图。
     */
    public static Outcome generate(long baseSeed, Settings settings) {
        List<String> notes = new ArrayList<>();
        RaceTrackValidator.Limits limits = limits(settings);

        if (!settings.randomTrack()) {
            RaceTrack fixed = build(baseSeed, 0, settings, true);
            RaceTrackValidator.Result result = RaceTrackValidator.validate(fixed, limits);
            notes.add("boatRaceEnableRandomTrack=false：使用确定性正圆赛道（调试用）");
            if (!result.valid()) {
                notes.add("确定性赛道未通过校验: " + result.problemText());
            }
            return new Outcome(fixed, 1, true, List.copyOf(notes));
        }

        RaceTrack best = null;
        RaceTrackValidator.Result bestResult = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestAttempt = -1;
        String lastProblem = null;
        int attempts = Math.max(1, settings.maxAttempts());

        for (int attempt = 0; attempt < attempts; attempt++) {
            RaceTrack candidate;
            try {
                candidate = build(baseSeed + attempt, attempt, settings, false);
            } catch (RuntimeException e) {
                lastProblem = "生成异常 " + e;
                continue;
            }
            RaceTrackValidator.Result result = RaceTrackValidator.validate(candidate, limits);
            if (!result.valid()) {
                lastProblem = result.problemText();
                continue;
            }
            if (result.score() > bestScore) {
                bestScore = result.score();
                best = candidate;
                bestResult = result;
                bestAttempt = attempt;
            }
        }

        if (best != null) {
            notes.add(String.format("已在 %d 个候选中选定 Seed=%d（第 %d 次尝试；评分 %.1f，长度 %.0f，"
                            + "大直道 %.0f，起点直道 %.0f，最小弯半径 %.1f，宽度 %.0f，Checkpoint %d）",
                    attempts, best.seed(), bestAttempt + 1, bestScore, best.length(),
                    bestResult == null ? 0.0 : bestResult.longestStraight(),
                    bestResult == null ? 0.0 : bestResult.startStraightLength(),
                    best.minCornerRadius(), best.width(), best.checkpointCount()));
            return new Outcome(best, attempts, false, List.copyOf(notes));
        }

        // 全部候选都不合法：先如实报告失败，再退回确定性正圆，保证对局仍然能开起来。
        notes.add("随机赛道生成失败：已尝试 " + attempts + " 次，最后一次原因：" + lastProblem);
        RaceTrack fallback = build(baseSeed, attempts, settings, true);
        RaceTrackValidator.Result fallbackResult = RaceTrackValidator.validate(fallback, limits);
        notes.add("已退回确定性正圆赛道"
                + (fallbackResult.valid() ? "（校验通过）" : "（仍未通过：" + fallbackResult.problemText() + "）"));
        return new Outcome(fallback, attempts, true, List.copyOf(notes));
    }

    /** 校验器用的硬性约束；起跑区长度按实际格位排布算出。 */
    private static RaceTrackValidator.Limits limits(Settings s) {
        int columns = columnsFor(s.width());
        int rows = (int) Math.ceil(Math.max(1, s.playerCount()) / (double) columns);
        double gridDepth = GRID_FIRST_ROW_BACK + rows * GRID_ROW_SPACING + 8.0;
        return new RaceTrackValidator.Limits(s.minLength(), s.maxLength(), s.targetLength(),
                s.minCornerRadius(), s.minClearance(), s.width(), gridDepth, s.minStraightLength());
    }

    /**
     * 赛道宽度能放下几列起跑格位。
     *
     * <p>船体碰撞箱 1.375×1.375，互相推挤的冲量只有 0.05 格/tick，所以列距 ≥3 格就基本不会
     * 在发车时互相顶开；左右各留 1.5 格余量避免压到缓冲带（原版船的摩擦取船底方块的
     * <b>平均值</b>，压到 0.6 的雪块会立刻掉速）。12 格宽 → 4 列；9 格宽 → 2 列。
     */
    private static int columnsFor(double width) {
        double usable = Math.max(0.0, width / 2.0 - 1.5);
        return Math.max(1, Math.min(4, 1 + (int) Math.floor(2.0 * usable / 3.0)));
    }

    // ---------- 单次生成 ----------

    /**
     * 生成一张候选赛道。
     *
     * @param circular true = 确定性正圆（兜底 / 调试），false = 按 Seed 随机
     */
    private static RaceTrack build(long seed, int attempt, Settings settings, boolean circular) {
        Random random = new Random(seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L);

        double targetLength = clamp(settings.targetLength(), settings.minLength(), settings.maxLength());
        double idealRadius = targetLength / (2.0 * Math.PI);
        double maxRadius = circular ? MAX_CIRCLE_RADIUS : MAX_BASE_RADIUS;
        double baseRadius = clamp(idealRadius, MIN_BASE_RADIUS, maxRadius);

        int[] harmonics;
        double[] amplitudes;
        double[] phases;
        if (circular) {
            harmonics = new int[]{2};
            amplitudes = new double[]{0.0};
            phases = new double[]{0.0};
        } else {
            // ---- 先放一根"造直道"的负振幅谐波 ----
            // 极坐标曲率 κ = 1/r + 2r'²/r³ - r''/r²。取 r = R0 + A·cos(kθ)，谷底（θ = π/k 处
            // r = R0 - A、r' = 0、r'' = +A k²）有 κ = 1/(R0-A) - A k²/(R0-A)²。
            // 令 κ = 0 得 A k² = R0 - A，即 A = R0 / (k² + 1)：这个振幅下谷底被压成真正的直道，
            // 船能在这里把冰面高速跑满。振幅再大一点曲率就反号，变成 S 弯的拐点。
            // 系数 0.9~1.6：约等于 1 时谷底被压成真直道；明显大于 1 时谷底曲率反号，
            // 那一处就变成 S 弯的拐点（连续左右弯），这正是"连续弯 / S 弯"的来源。
            int kStraight = 2 + random.nextInt(2);
            double aStraight = baseRadius / (kStraight * kStraight + 1.0)
                    * (0.9 + random.nextDouble() * 0.7);
            // ---- 再叠风格谐波，正负随机（负 = 压平，正 = 急弯）----
            int style = random.nextInt(STYLES.length);
            int[] styleHarmonics = STYLES[style];
            harmonics = new int[styleHarmonics.length + 1];
            amplitudes = new double[harmonics.length];
            phases = new double[harmonics.length];
            harmonics[0] = kStraight;
            amplitudes[0] = -aStraight;
            phases[0] = random.nextDouble() * 2.0 * Math.PI;

            double totalAmp = MIN_AMPLITUDE + random.nextDouble() * (MAX_AMPLITUDE - MIN_AMPLITUDE);
            double sum = 0;
            double[] raw = new double[styleHarmonics.length];
            for (int i = 0; i < styleHarmonics.length; i++) {
                raw[i] = (random.nextBoolean() ? 1.0 : -1.0) * (0.55 + random.nextDouble() * 0.45);
                sum += Math.abs(raw[i]);
            }
            for (int i = 0; i < styleHarmonics.length; i++) {
                harmonics[i + 1] = styleHarmonics[i];
                amplitudes[i + 1] = totalAmp * baseRadius * raw[i] / sum;
                phases[i + 1] = random.nextDouble() * 2.0 * Math.PI;
            }
            // 半径本身也抖一下，避免所有赛道都是"同一个圆加波浪"
            baseRadius *= 0.92 + random.nextDouble() * 0.16;
        }

        // ---- 1. 极坐标采样 ----
        int samples = (int) Math.round(2.0 * Math.PI * baseRadius / 0.5);
        samples = Math.max(512, Math.min(2048, samples));
        double[] px = new double[samples];
        double[] pz = new double[samples];
        for (int i = 0; i < samples; i++) {
            double theta = 2.0 * Math.PI * i / samples;
            double r = baseRadius;
            for (int h = 0; h < harmonics.length; h++) {
                r += amplitudes[h] * Math.cos(harmonics[h] * theta + phases[h]);
            }
            // 保底：r > 0 是"曲线简单"的前提
            r = Math.max(baseRadius * 0.35, r);
            px[i] = settings.centerX() + r * Math.cos(theta);
            pz[i] = settings.centerZ() + r * Math.sin(theta);
        }

        // ---- 2. 按弧长等距重采样（顺带完成"平滑中心线"） ----
        double[] cum = new double[samples + 1];
        for (int i = 0; i < samples; i++) {
            int j = (i + 1) % samples;
            cum[i + 1] = cum[i] + Math.hypot(px[j] - px[i], pz[j] - pz[i]);
        }
        double total = cum[samples];
        if (total < 120) {
            throw new IllegalStateException("生成的闭环过短: " + total);
        }
        int n = (int) Math.max(120, Math.round(total / RaceTrack.STEP));
        double step = total / n; // 微调步长，保证首尾精确闭合

        double[] xs = new double[n];
        double[] zs = new double[n];
        int seg = 0;
        for (int i = 0; i < n; i++) {
            double target = i * step;
            while (seg < samples - 1 && cum[seg + 1] < target) {
                seg++;
            }
            double segLen = cum[seg + 1] - cum[seg];
            double t = segLen <= 1.0e-9 ? 0.0 : (target - cum[seg]) / segLen;
            int j = (seg + 1) % samples;
            xs[i] = px[seg] + t * (px[j] - px[seg]);
            zs[i] = pz[seg] + t * (pz[j] - pz[seg]);
        }

        // ---- 3. 起终点线放在最平直处（窗口要够长到容下整个起跑格位阵） ----
        int pivot = straightestIndex(xs, zs, settings.width(), Math.max(1, settings.playerCount()),
                settings.minStraightLength());
        xs = rotate(xs, pivot);
        zs = rotate(zs, pivot);

        // ---- 4. 切向 / 法向 ----
        int count = n;
        double[] dirXs = new double[count];
        double[] dirZs = new double[count];
        double[] normalXs = new double[count];
        double[] normalZs = new double[count];
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            double dx = xs[j] - xs[i];
            double dz = zs[j] - zs[i];
            double len = Math.hypot(dx, dz);
            if (len <= 1.0e-9) {
                dirXs[i] = i > 0 ? dirXs[i - 1] : 1.0;
                dirZs[i] = i > 0 ? dirZs[i - 1] : 0.0;
            } else {
                dirXs[i] = dx / len;
                dirZs[i] = dz / len;
            }
            // 法向 = 切向在水平面内旋转 90°（用于算横向偏移）
            normalXs[i] = -dirZs[i];
            normalZs[i] = dirXs[i];
        }

        double length = count * RaceTrack.STEP;
        Geometry geo = new Geometry(xs, zs, dirXs, dirZs, normalXs, normalZs, length);

        // ---- 5. Checkpoint：沿弧长等距，index 0 = 起终点线 ----
        int checkpointCount = settings.checkpointCount() > 0
                ? settings.checkpointCount()
                : clampInt((int) Math.round(length / 60.0), 6, 18);
        List<RaceTrack.Checkpoint> checkpoints = new ArrayList<>(checkpointCount + 1);
        checkpoints.add(gate(geo, 0, 0.0, settings.width()));
        for (int k = 1; k <= checkpointCount; k++) {
            checkpoints.add(gate(geo, k, length * k / (checkpointCount + 1.0), settings.width()));
        }

        // ---- 6. 起跑格位 ----
        List<RaceTrack.GridSlot> grid = buildGrid(geo, settings.width(),
                Math.max(1, settings.playerCount()));

        return new RaceTrack(seed, attempt, settings.centerX(), settings.centerZ(), settings.surfaceY(),
                settings.width() / 2.0, settings.runoffWidth(), settings.barrierHeight(),
                xs, zs, dirXs, dirZs, normalXs, normalZs,
                checkpoints, grid, checkpointCount, minCornerRadius(xs, zs), -1);
    }

    /** 采样数组的只读视图 + 弧长插值。 */
    private record Geometry(double[] xs, double[] zs, double[] dirXs, double[] dirZs,
                            double[] normalXs, double[] normalZs, double length) {
        int size() {
            return this.xs.length;
        }

        /** 弧长 {@code arc}（自动按闭环取模）、横向偏移 {@code lateral} 处的几何。 */
        Sample at(double arc, double lateral) {
            int n = size();
            double wrapped = arc % this.length;
            if (wrapped < 0) {
                wrapped += this.length;
            }
            double sample = wrapped / RaceTrack.STEP;
            int i = Math.floorMod((int) Math.floor(sample), n);
            int j = (i + 1) % n;
            double t = sample - Math.floor(sample);
            double x = lerp(this.xs[i], this.xs[j], t);
            double z = lerp(this.zs[i], this.zs[j], t);
            double dvx = lerp(this.dirXs[i], this.dirXs[j], t);
            double dvz = lerp(this.dirZs[i], this.dirZs[j], t);
            double nx = lerp(this.normalXs[i], this.normalXs[j], t);
            double nz = lerp(this.normalZs[i], this.normalZs[j], t);
            double dl = Math.hypot(dvx, dvz);
            if (dl <= 1.0e-9) {
                dvx = this.dirXs[i];
                dvz = this.dirZs[i];
                dl = Math.hypot(dvx, dvz);
            }
            dvx /= dl;
            dvz /= dl;
            double nl = Math.hypot(nx, nz);
            if (nl <= 1.0e-9) {
                nx = -dvz;
                nz = dvx;
                nl = Math.hypot(nx, nz);
            }
            nx /= nl;
            nz /= nl;
            return new Sample(x + nx * lateral, z + nz * lateral, dvx, dvz, nx, nz);
        }
    }

    private static RaceTrack.Checkpoint gate(Geometry geo, int index, double progress, double width) {
        Sample s = geo.at(progress, 0.0);
        return new RaceTrack.Checkpoint(index, s.x(), s.z(), s.dirX(), s.dirZ(),
                s.normalX(), s.normalZ(), width / 2.0, progress);
    }

    private static List<RaceTrack.GridSlot> buildGrid(Geometry geo, double width, int playerCount) {
        double halfWidth = width / 2.0;
        // 只用得上的列数：2 个人时不该让他们挤在赛道左边两列，而应该左右对称站位
        int usedColumns = Math.max(1, Math.min(columnsFor(width), playerCount));
        int rows = (int) Math.ceil(playerCount / (double) usedColumns);
        double spacing = usedColumns > 1
                ? Math.min(GRID_COLUMN_SPACING, 2.0 * (halfWidth - 1.5) / (usedColumns - 1))
                : 0.0;

        List<RaceTrack.GridSlot> grid = new ArrayList<>(rows * usedColumns);
        for (int row = 0; row < rows; row++) {
            // 格位排在起终点线"后面"：弧长接近 length 的那一侧
            double arc = geo.length() - (GRID_FIRST_ROW_BACK + row * GRID_ROW_SPACING);
            for (int col = 0; col < usedColumns; col++) {
                if (grid.size() >= playerCount) {
                    break;
                }
                // 该排实际几个人就按几个人居中（最后一排可能不满）
                int inRow = Math.min(usedColumns, playerCount - row * usedColumns);
                double lateral = (col - (inRow - 1) / 2.0) * spacing;
                Sample s = geo.at(arc, lateral);
                grid.add(new RaceTrack.GridSlot(s.x(), s.z(), yawOf(s.dirX(), s.dirZ()), row, col));
            }
        }
        return grid;
    }

    /**
     * 朝向 {@code (dirX, dirZ)} 的 Minecraft yaw。
     *
     * <p>原版约定：yaw=0 面向 +Z（南），yaw=90 面向 -X（西），yaw=-90 面向 +X（东）。
     * 与 {@code LivingEntity.lookAt} 用的 {@code atan2(dz, dx) - 90°} 一致。
     */
    public static float yawOf(double dirX, double dirZ) {
        return (float) (Math.toDegrees(Math.atan2(dirZ, dirX)) - 90.0);
    }

    /**
     * "最平直的一段"的末端索引：让该索引之前的 {@code window} 格累计转角最小。
     * 起终点线设在这里，发车区就是直道 —— 前排不会一出发就撞弯，后排也不会被弯道挤成一团。
     */
    private static int straightestIndex(double[] xs, double[] zs, double width, int playerCount,
                                        double minStraightLength) {
        int n = xs.length;
        // 窗口 = max(格位阵长度 + 起步 30 格, 要求的大直道长度)：
        // 前者保证人数再多整排格位都落在直道上，后者让"起终点线正好钉在大直道头上"，
        // 也就是每张图的发车直道就是那条大直道 —— 氮气有地方用。
        int rows = (int) Math.ceil(playerCount / (double) columnsFor(width));
        double gridNeed = GRID_FIRST_ROW_BACK + rows * GRID_ROW_SPACING + 30;
        int window = Math.max(24, (int) Math.round(
                Math.max(gridNeed, minStraightLength) / RaceTrack.STEP));
        window = Math.max(1, Math.min(window, n / 2));

        double[] turn = new double[n];
        double[] heading = new double[n];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            heading[i] = Math.atan2(zs[j] - zs[i], xs[j] - xs[i]);
        }
        for (int i = 0; i < n; i++) {
            int p = (i - 1 + n) % n;
            turn[i] = Math.abs(wrapAngle(heading[i] - heading[p]));
        }
        // 双倍前缀和，窗口 [i-window+1, i] 的累计转角 = ext[i+n+1] - ext[i+n+1-window]
        double[] ext = new double[n * 2 + 1];
        for (int i = 0; i < n * 2; i++) {
            ext[i + 1] = ext[i] + turn[i % n];
        }
        int best = 0;
        double bestTotal = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double sum = ext[i + n + 1] - ext[i + n + 1 - window];
            if (sum < bestTotal) {
                bestTotal = sum;
                best = i;
            }
        }
        return best;
    }

    /** 与校验器保持一致的最小曲率半径算法（±3 采样点滑动平均后的最大 |κ| 的倒数）。 */
    private static double minCornerRadius(double[] xs, double[] zs) {
        int n = xs.length;
        double[] heading = new double[n];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            heading[i] = Math.atan2(zs[j] - zs[i], xs[j] - xs[i]);
        }
        int window = 3;
        double minRadius = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int o = -window; o <= window; o++) {
                int a = Math.floorMod(i + o, n);
                int b = (a - 1 + n) % n;
                sum += wrapAngle(heading[a] - heading[b]);
            }
            double kappa = Math.abs(sum / (2 * window + 1)) / RaceTrack.STEP;
            if (kappa > 1.0e-9) {
                minRadius = Math.min(minRadius, 1.0 / kappa);
            }
        }
        return minRadius == Double.MAX_VALUE ? 1.0e6 : minRadius;
    }

    /**
     * 赛道样式：谐波频率组合决定"弯型骨架"。
     * <ul>
     *   <li>{2,3} 2 个长弯 + 3 个普通弯 —— 高速赛道</li>
     *   <li>{3,4} 连续弯 / S 弯 —— 技术赛道</li>
     *   <li>{2,5} 2 个大回头弯 + 5 个小弯</li>
     *   <li>{4,5} 密集连续弯</li>
     *   <li>{2,3,5} 混合</li>
     *   <li>{3,5,7} S 弯链 + 发卡</li>
     * </ul>
     */
    private static final int[][] STYLES = {
            {2, 3},
            {3, 4},
            {2, 5},
            {4, 5},
            {2, 3, 5},
            {3, 5, 7},
            {2, 4, 7},
            {4, 6},
    };

    private static double wrapAngle(double angle) {
        double a = angle;
        while (a > Math.PI) {
            a -= 2 * Math.PI;
        }
        while (a < -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }

    private static double[] rotate(double[] arr, int pivot) {
        int n = arr.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = arr[(pivot + i) % n];
        }
        return out;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
