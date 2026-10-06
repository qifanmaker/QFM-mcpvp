package com.example.pvp.arena.race;

import java.util.ArrayList;
import java.util.List;

/**
 * 赛道校验 + 评分。纯几何，不依赖 Minecraft，可离线对成百上千个 Seed 批量跑。
 *
 * <p><b>为什么必须有校验</b>：随机生成的闭环中心线虽然按构造就"简单"（极坐标 {@code r(θ) > 0}
 * 保证每条射线只交一次，天然不自交），但"简单"不等于"能开" —— 可能弯太急、可能两段赛道贴太近
 * 导致看起来像一条路、可能直道太短起步就撞墙。校验器把这些都变成硬性拒绝条件，
 * 生成器就能"生成 → 校验 → 换 Seed 重来"。
 *
 * <p>评分器则用来在多个合法候选里挑最好的那张（而不是第一个合法的就用），
 * 让随机地图的驾驶体验稳定高于"生一张算一张"。
 */
public final class RaceTrackValidator {

    /**
     * 赛道外沿（含缓冲带与护栏）允许的最大半径。
     *
     * <p>ArenaTemplate.REGION_SPACING = 384，每个区域宽 {@code boatRaceSize}=336、中心在区域中点，
     * 所以赛道外沿半径必须 &lt; 168；相邻区域的实体清扫盒半径是 {@code max(size/2, 外沿半径) + 16}
     * = max(168, 165) + 16 = 184，两个相邻盒 2*184 = 368 &lt; 384 才不会互相清掉对方场上的实体。
     * 这里取 165 留出 3 格余量。
     */
    public static final double MAX_BOUNDING_RADIUS = 165.0;

    /** 总绝对转角上限（以 2π 为单位）：弯曲总量太夸张的赛道船开不完，直接拒绝。 */
    private static final double MAX_TOTAL_TURNING_FACTOR = 3.2;
    /** 曲率符号变化次数上限（相对赛道长度的密度）：防止锯齿状"搓板赛道"。 */
    private static final double MAX_DIRECTION_CHANGES_PER_BLOCK = 1.0 / 22.0;
    /** 起跑区（格位 + 起步 30 格）要求的最小曲率半径：保证发车是直道。 */
    private static final double MIN_START_STRAIGHT_RADIUS = 30.0;
    /** Checkpoint 数量的合法区间。 */
    private static final int MIN_CHECKPOINTS = 4;
    private static final int MAX_CHECKPOINTS = 24;
    /** 反向弯的判定倍数：两侧的弯都要 ≤ 该倍数 × 最小弯半径，否则算"只有一个转向"。 */
    public static final double OPPOSITE_CORNER_FACTOR = 4.0;
    /** 平直段判定阈值（曲率半径 ≥ 该值算"几乎直线"）。 */
    private static final double STRAIGHT_RADIUS = 150.0;

    // ---------- 原版船在冰面上的物理模型（用于把"弯半径"折算成"能不能开过去"） ----------
    /** 船的前进加速度（格/tick²），原版 BoatEntity 常量。 */
    private static final double BOAT_THRUST = 0.04;
    /** 刹车（按后退键）的减速度（格/tick²），原版常量。 */
    private static final double BOAT_BRAKE = 0.005;
    /**
     * 冰面的速度保持率（= 方块 slipperiness）。packed_ice / ice = 0.98，blue_ice = 0.989。
     * 用原版物理：每 tick 先 {@code v *= c}，再加推力，所以冰面极速 = thrust / (1 - c)：
     * 0.98 → 2.0 格/tick（40 格/秒），0.989 → 3.64 格/tick（72.7 格/秒）。
     */
    private static final double ICE_DECAY = 0.98;
    /** 冰面极速（格/tick），作为速度剖面的上界。 */
    private static final double ICE_TOP_SPEED = BOAT_THRUST / (1.0 - ICE_DECAY);
    /**
     * 过弯时速度方向能被推力扭转的最大速率给出最小可行路径半径：{@code r_min = v² / a}。
     * 反过来，半径 R 的弯最多能带 {@code v = sqrt(a * R)} 的速度过去（格/tick）。
     * R = 25 格 → 1.0 格/tick（20 格/秒）；R = 100 格 → 2.0 格/tick（冰面极速）。
     */
    private static final double MIN_FEASIBLE_CORNER_SPEED = 0.45;

    /** 由曲率半径换算该点的速度上限（格/tick）。 */
    private static double cornerSpeedLimit(double radius) {
        double v = Math.sqrt(BOAT_THRUST * Math.max(1.0, radius));
        return Math.min(ICE_TOP_SPEED, v);
    }

    // ---------- 开放折线（分岔支路 / 内线段）的曲率与通行时间 ----------

    /**
     * 开放折线上最小的曲率半径（格）。
     *
     * <p>与主线的 {@code profile} 同一口径：逐点转角 = 相邻航向之差，±3 点滑动平均后
     * {@code κ = |平均转角| / STEP}，半径 = 1/κ。分岔的两条路线都必须满足最小弯半径，
     * 否则船在岔路上会直接被甩进护栏。
     */
    public static double routeMinRadius(double[] xs, double[] zs) {
        double[] radius = openRadii(xs, zs);
        double min = Double.MAX_VALUE;
        for (double r : radius) {
            min = Math.min(min, r);
        }
        return min == Double.MAX_VALUE ? 1.0e6 : min;
    }

    /**
     * 开放折线的通行时间估计（tick）：逐段 {@code 段长 / 该段速度上限}。
     *
     * <p>这是"平衡两条岔路"的度量：模型忽略加减速（只看逐点速度上限），所以它是一个
     * <b>相对</b>指标 —— 只要两条路线用同一把尺子量，谁占便宜就看得出来。分岔生成器用它
     * 搜索内线 S 弯的振幅，让内外线的估计时间差控制在 {@code FORK_BALANCE_TOLERANCE} 以内。
     */
    public static double routeTime(double[] xs, double[] zs) {
        double[] radius = openRadii(xs, zs);
        double time = 0;
        for (int i = 0; i + 1 < xs.length; i++) {
            double d = Math.hypot(xs[i + 1] - xs[i], zs[i + 1] - zs[i]);
            double r = Math.min(radius[i], radius[i + 1]);
            double v = Math.max(0.2, cornerSpeedLimit(r));
            time += d / v;
        }
        return time;
    }

    /** 开放折线逐点的曲率半径（1e6 = 直线）。 */
    private static double[] openRadii(double[] xs, double[] zs) {
        int n = xs.length;
        double[] radius = new double[n];
        if (n < 3) {
            java.util.Arrays.fill(radius, 1.0e6);
            return radius;
        }
        double[] heading = new double[n - 1];
        for (int i = 0; i + 1 < n; i++) {
            heading[i] = Math.atan2(zs[i + 1] - zs[i], xs[i + 1] - xs[i]);
        }
        double[] turn = new double[n - 1];
        for (int j = 1; j < n - 1; j++) {
            double d = heading[j] - heading[j - 1];
            while (d > Math.PI) {
                d -= 2 * Math.PI;
            }
            while (d < -Math.PI) {
                d += 2 * Math.PI;
            }
            turn[j] = d;
        }
        int window = 3;
        for (int i = 0; i < n; i++) {
            double sum = 0;
            int count = 0;
            for (int o = -window; o <= window; o++) {
                int j = i + o;
                if (j < 1 || j >= n - 1) {
                    continue;
                }
                sum += turn[j];
                count++;
            }
            double kappa = count == 0 ? 0.0 : Math.abs(sum / count) / RaceTrack.STEP;
            radius[i] = kappa < 1.0e-9 ? 1.0e6 : 1.0 / kappa;
        }
        return radius;
    }

    private RaceTrackValidator() {
    }

    /**
     * 校验结果。
     *
     * @param valid     是否合法
     * @param problems  非法原因（为空表示合法）
     * @param minRadius 中心线最小曲率半径
     * @param clearance 非相邻赛道段之间的最小净空
     * @param score     质量评分（仅当 valid 时有意义）
     */
    public record Result(boolean valid, List<String> problems,
                         double minRadius, double clearance,
                         double minSpeed, double avgSpeed, double score,
                         double longestStraight, double startStraightLength) {
        public String problemText() {
            return String.join("; ", this.problems);
        }
    }

    /**
     * 硬性约束集合。生成器用它把配置项传进来，校验器本身不读配置（保持纯函数、可离线跑）。
     *
     * @param minLength       赛道长度下限
     * @param maxLength       赛道长度上限
     * @param targetLength    目标长度（评分里越接近越高）
     * @param minCornerRadius 中心线最小曲率半径下限
     * @param minClearance    非相邻段最小净空下限
     * @param width           赛道宽度
     * @param gridDepth       起跑格位占用的赛道长度（有效值 + 余量）
     */
    public record Limits(double minLength, double maxLength, double targetLength,
                         double minCornerRadius, double minClearance,
                         double width, double gridDepth,
                         double minStraightLength, double minOppositeCornerRadius) {
    }

    /**
     * 曲率剖面 + 由原版船物理推出的速度可行性剖面。
     *
     * @param radius          逐点曲率半径
     * @param minRadius       最小曲率半径
     * @param totalTurning    累计绝对转角
     * @param directionChanges 曲率变号次数
     * @param straightFraction 平直段占比
     * @param classFraction   五类弯型占比（直道/高速弯/中速弯/慢弯/发卡）
     * @param minSpeed        全程可行的最低速度（格/tick）——"最慢的那个弯有多慢"
     * @param avgSpeed        全程可行的平均速度（格/tick）——"这条赛道跑起来顺不顺"
     * @param fullThrottleFraction 能全油门（达到冰面极速）的路段占比
     * @param longestStraight 最长的一段连续"接近直线"（曲率半径 ≥ 250）的长度（格）
     */
    private record Profile(double[] radius, double minRadius, double totalTurning,
                           int directionChanges, double straightFraction,
                           double[] classFraction,
                           double minSpeed, double avgSpeed, double fullThrottleFraction,
                           double longestStraight, double startStraightLength) {
    }

    /**
     * 速度可行性双通道（标准赛车线算法，用的全是原版船物理常量）。
     *
     * <p>逐点速度上限由曲率给出（{@code v_limit = sqrt(a·R)}，封顶冰面极速）。
     * 然后：
     * <ol>
     *   <li><b>正向加速</b>：{@code v[i] = min(v_limit[i], c·v[i-1] + a)} —— 从上一格能加速到多少；</li>
     *   <li><b>反向刹车</b>：{@code v[i] = min(v_limit[i], (v[i+1] + brake) / c)} —— 为了在下一格降到目标速度，
     *       这一格最多能有多快。</li>
     * </ol>
     * 两次取小得到的才是"真正跑得出来"的速度。若某处只能跑到很低的速度，说明赛道在那一段
     * 逼玩家几乎停车 —— 这正是"直道尽头突然接一个 90° 弯"的典型症状。
     */
    private static double[] speedProfile(double[] radius) {
        int n = radius.length;
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = cornerSpeedLimit(radius[i]);
        }
        // 正向加速（绕两圈让闭环收敛）
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                int p = (i - 1 + n) % n;
                double reachable = ICE_DECAY * v[p] + BOAT_THRUST;
                v[i] = Math.min(v[i], reachable);
            }
        }
        // 反向刹车（同样绕两圈）
        for (int pass = 0; pass < 2; pass++) {
            for (int i = n - 1; i >= 0; i--) {
                int nx = (i + 1) % n;
                double allowed = (v[nx] + BOAT_BRAKE) / ICE_DECAY;
                v[i] = Math.min(v[i], allowed);
            }
        }
        return v;
    }

    public static Result validate(RaceTrack track, Limits limits) {
        List<String> problems = new ArrayList<>();

        int n = track.sampleCount();
        double length = track.length();

        // ---- 1. 闭环完整性：相邻采样点必须都在 STEP 附近（一次算出来，断路/跳点都能抓到）----
        double maxStepError = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double dx = track.sampleX(j) - track.sampleX(i);
            double dz = track.sampleZ(j) - track.sampleZ(i);
            double d = Math.sqrt(dx * dx + dz * dz);
            maxStepError = Math.max(maxStepError, Math.abs(d - RaceTrack.STEP));
        }
        if (maxStepError > 0.25) {
            problems.add(String.format("闭环断裂：相邻采样点间距偏差 %.3f 格", maxStepError));
        }

        // ---- 2. 长度 ----
        if (length < limits.minLength() || length > limits.maxLength()) {
            problems.add(String.format("长度 %.0f 不在 [%.0f, %.0f]",
                    length, limits.minLength(), limits.maxLength()));
        }

        // ---- 3. 区域容纳 ----
        if (track.boundingRadius() > MAX_BOUNDING_RADIUS) {
            problems.add(String.format("外沿半径 %.1f > %.0f（会串到相邻竞技场）",
                    track.boundingRadius(), MAX_BOUNDING_RADIUS));
        }

        // ---- 4. 宽度 ----
        if (limits.width() < 9.0) {
            problems.add(String.format("赛道宽度 %.1f < 9（船在冰上无法走线）", limits.width()));
        }

        // ---- 5. 曲率剖面 ----
        Profile profile = profile(track);
        if (profile.minRadius() < limits.minCornerRadius()) {
            problems.add(String.format("最小曲率半径 %.1f < %.1f（弯太急，船转不过来）",
                    profile.minRadius(), limits.minCornerRadius()));
        }
        if (profile.minSpeed() < MIN_FEASIBLE_CORNER_SPEED) {
            problems.add(String.format("最慢可行的弯速 %.2f 格/tick < %.2f（有必须近乎停车的死弯）",
                    profile.minSpeed(), MIN_FEASIBLE_CORNER_SPEED));
        }
        double maxTurning = 2.0 * Math.PI * MAX_TOTAL_TURNING_FACTOR;
        if (profile.totalTurning() > maxTurning) {
            problems.add(String.format("累计转角 %.2fπ 过大（赛道过于扭曲）", profile.totalTurning() / Math.PI));
        }
        int maxDirectionChanges = (int) Math.floor(length * MAX_DIRECTION_CHANGES_PER_BLOCK);
        if (profile.directionChanges() > maxDirectionChanges) {
            problems.add(String.format("曲率变号 %d 次 > %d（搓板赛道，左右反复甩）",
                    profile.directionChanges(), maxDirectionChanges));
        }

        // ---- 6. 净空 / 自交 / 捷径 ----
        // 两段弧长距离很远的赛道如果空间上贴得很近，玩家会分不清该走哪条，也等于开了一条捷径。
        double clearance = minClearance(track, limits.minClearance());
        if (clearance < limits.minClearance()) {
            problems.add(String.format("非相邻赛道段最小净空 %.1f < %.1f（自贴/捷径）",
                    clearance, limits.minClearance()));
        }

        // ---- 6.5 分岔：支路本身也要合法，且不能被门/箱子"截断" ----
        // 生成器在造支路时已经逐项筛过（半径、长度比、通行时间平衡、分隔带、净空、界内），
        // 这里再独立复核一遍关键的几条 —— 生成器的判据以后被改动时，校验器仍然拦得住坏图。
        for (RaceTrack.Branch branch : track.branches()) {
            double[] bx = new double[branch.sampleCount()];
            double[] bz = new double[branch.sampleCount()];
            for (int k = 0; k < branch.sampleCount(); k++) {
                bx[k] = branch.x(k);
                bz[k] = branch.z(k);
            }
            double radius = routeMinRadius(bx, bz);
            if (radius < limits.minCornerRadius()) {
                problems.add(String.format("分岔支路最小曲率半径 %.1f < %.1f",
                        radius, limits.minCornerRadius()));
            }
            double innerLength = branch.progressSpan();
            if (branch.length() > innerLength * 1.4 || branch.length() < innerLength) {
                problems.add(String.format("分岔支路长度 %.0f 与内线 %.0f 不成比例（外线应略长）",
                        branch.length(), innerLength));
            }
            int innerStart = (int) Math.round(branch.startProgress() / RaceTrack.STEP);
            int innerCount = Math.max(2, (int) Math.round((branch.endProgress() - branch.startProgress())
                    / RaceTrack.STEP) + 1);
            double[] ix = new double[innerCount];
            double[] iz = new double[innerCount];
            for (int k = 0; k < innerCount; k++) {
                int index = Math.floorMod(innerStart + k, track.sampleCount());
                ix[k] = track.sampleX(index);
                iz[k] = track.sampleZ(index);
            }
            double innerTime = routeTime(ix, iz);
            double outerTime = routeTime(bx, bz);
            double imbalance = Math.abs(innerTime - outerTime) / Math.max(1.0, Math.min(innerTime, outerTime));
            if (imbalance > 0.25) {
                problems.add(String.format("分岔内外线通行时间偏差 %.0f%% 过大（内 %.0f / 外 %.0f tick）",
                        imbalance * 100.0, innerTime, outerTime));
            }
        }
        for (RaceTrack.Checkpoint gate : track.checkpoints()) {
            if (track.isInsideForkSpan(gate.progress())) {
                problems.add(String.format("Checkpoint %d 落在岔口内部（两条路线上只有一个门）",
                        gate.index()));
                break;
            }
        }

        // ---- 7. Checkpoint ----
        int cp = track.checkpointCount();
        if (cp < MIN_CHECKPOINTS || cp > MAX_CHECKPOINTS) {
            problems.add("Checkpoint 数量 " + cp + " 不在 [" + MIN_CHECKPOINTS + ", " + MAX_CHECKPOINTS + "]");
        }
        if (track.checkpoints().isEmpty() || !track.checkpoints().get(0).isFinishLine()) {
            problems.add("缺少起终点线");
        }
        // 门的弧长必须严格递增（起终点线 = 0，其后依次 +Δ），且方向向量为单位向量
        double prev = -1;
        for (RaceTrack.Checkpoint gate : track.checkpoints()) {
            if (gate.index() != 0 && gate.progress() <= prev) {
                problems.add("Checkpoint 顺序错误（arc " + String.format("%.1f", gate.progress()) + "）");
                break;
            }
            prev = gate.index() == 0 ? 0 : gate.progress();
            double norm = Math.hypot(gate.dirX(), gate.dirZ());
            if (Math.abs(norm - 1.0) > 1.0e-6) {
                problems.add("Checkpoint " + gate.index() + " 方向向量未归一化");
                break;
            }
            if (Math.hypot(gate.normalX(), gate.normalZ()) < 0.99) {
                problems.add("Checkpoint " + gate.index() + " 法向向量非法");
                break;
            }
        }

        // ---- 8. 起跑区：格位必须落在冰面内，且这一段必须是直道 ----
        if (track.grid().isEmpty()) {
            problems.add("缺少起跑格位");
        }
        for (RaceTrack.GridSlot slot : track.grid()) {
            double lateral = Math.abs(lateralOffsetOf(track, slot.x(), slot.z()));
            if (lateral > track.halfWidth() - 1.0) {
                problems.add(String.format("格位横向偏移 %.1f 超出赛道半宽 %.1f",
                        lateral, track.halfWidth()));
                break;
            }
            if (track.distanceToCenterline(slot.x(), slot.z()) > track.halfWidth() - 0.5) {
                problems.add("格位不在赛道上");
                break;
            }
        }
        double startMinRadius = minRadiusInWindow(track, profile.radius(),
                track.length() - limits.gridDepth(), limits.gridDepth() + 30.0);
        if (startMinRadius < MIN_START_STRAIGHT_RADIUS) {
            problems.add(String.format("起跑区最小曲率半径 %.1f < %.0f（起步就是弯道）",
                    startMinRadius, MIN_START_STRAIGHT_RADIUS));
        }

        // ---- 9. 必须有一条大直道：氮气（1.82 倍极速＝72 格/秒）只能在直道上用，
        //         没有直道的图这个道具就是废的。起点直道还要单独够长（发车 + 第一条加速跑道）。
        if (limits.minStraightLength() > 0 && profile.longestStraight() < limits.minStraightLength()) {
            problems.add(String.format("最长直道 %.0f < %.0f 格（缺少大直道）",
                    profile.longestStraight(), limits.minStraightLength()));
        }
        // 起点直道不单独设硬门槛：发车区已经有 MIN_START_STRAIGHT_RADIUS 兜着，
        // 而"曲线从哪儿开始算直"在极坐标曲线上是渐变的，再卡一道只会把好图也拒掉。
        // 大直道落在整圈哪个位置由生成器的取点窗口负责往起点方向偏。

        // ---- 10. 左右两侧都必须有"真弯" ----
        // 极坐标曲线天生"一个方向转弯紧、另一个方向转弯松"，不卡这一条就会出现
        // 整圈只往一个方向转的图（玩家反馈"只有右转弯"）。要求两个转向各自都存在
        // 半径不超过 OPPOSITE_CORNER_FACTOR × 最小弯半径 的弯。
        double[] dirRadii = directionRadii(track);
        double oppositeLimit = limits.minOppositeCornerRadius();
        if (oppositeLimit > 0 && (dirRadii[0] > oppositeLimit || dirRadii[1] > oppositeLimit)) {
            problems.add(String.format("缺少另一侧的弯（两侧最小曲率半径 %.0f / %.0f，要求都 ≤ %.0f）",
                    dirRadii[0], dirRadii[1], oppositeLimit));
        }

        boolean valid = problems.isEmpty();
        double score = valid ? score(track, limits, profile, clearance) : 0;
        return new Result(valid, List.copyOf(problems), profile.minRadius(), clearance,
                profile.minSpeed(), profile.avgSpeed(), score,
                profile.longestStraight(), profile.startStraightLength());
    }

    // ---------- 评分 ----------

    /**
     * 质量评分（满分 100）。
     *
     * <p>设计意图：<b>不要选"最顺的那张"</b>。只奖励"流畅"会挑出一堆接近正圆的赛道 ——
     * 全程 34 格/秒 不用刹车，开起来像绕圈而不是跑圈。真正像赛道的图要同时具备：
     * 有足够长的直道能把冰面速度冲满，又要有必须刹车的慢弯，两者之间还要有合理的过渡。
     *
     * <pre>
     * 长度贴合度  34   越接近 targetLength 越高
     * 弯型丰富度  12   直道/高速弯/中速弯/慢弯/发卡弯 出现过几种
     * 最长直道    10   连续"接近直线"路段最长能到多少（≈150 格满分，够把速度冲到 ~35 格/秒）
     * 直道占比     8   低曲率采样点占比
     * 速度落差     8   全程可行最高速 - 最低速：有落差才叫"有直道有弯"（用原版船物理算）
     * 平顺度       8   曲率逐点变化越小越高（不甩尾）
     * 净空余量    10   越宽裕越高（越不容易看错路）
     * 弯道余量     5   最小曲率半径超出下限越多越高（只作小幅加分，不能盖过多样性）
     * 平均速度     3   全程平均可行速度 / 冰面极速（小幅奖励"跑得开"）
     * 宽度余量     2   越宽越好（封顶）
     * </pre>
     */
    private static double score(RaceTrack track, Limits limits, Profile profile, double clearance) {
        double lengthRatio = 1.0 - Math.min(1.0, Math.abs(track.length() - limits.targetLength())
                / Math.max(1.0, limits.targetLength()));
        double variety = 0;
        for (double f : profile.classFraction()) {
            if (f >= 0.03) {
                variety += 1.0;
            }
        }
        variety /= profile.classFraction().length;

        double clearanceMargin = Math.min(1.0, Math.max(0.0,
                (clearance - limits.minClearance()) / Math.max(1.0, limits.minClearance())));
        double radiusMargin = Math.min(1.0, Math.max(0.0,
                (profile.minRadius() - limits.minCornerRadius()) / Math.max(1.0, limits.minCornerRadius())));
        double smoothness = Math.max(0.0, 1.0 - profile.directionChanges()
                / Math.max(1.0, track.length() * MAX_DIRECTION_CHANGES_PER_BLOCK * 0.5));
        double widthMargin = Math.min(1.0, Math.max(0.0, (limits.width() - 9.0) / 6.0));
        // 平均可行速度越接近冰面极速，说明这条赛道越"跑得开"（不是一路点刹）
        double flow = Math.min(1.0, profile.avgSpeed() / ICE_TOP_SPEED);
        // 最长直道：150 格 ≈ 能把速度从 0 冲到 ~35 格/秒（0.04 推力 / 0.02 阻力）
        double straightMax = Math.min(1.0, profile.longestStraight() / 150.0);
        // 速度落差：全程可行速度的跨度。全绿的一条圆环落差接近 0，分数就低。
        double speedRange = Math.min(1.0, Math.max(0.0,
                (ICE_TOP_SPEED - profile.minSpeed()) / 1.0));

        return 34.0 * lengthRatio
                + 12.0 * variety
                + 10.0 * straightMax
                + 8.0 * profile.straightFraction()
                + 8.0 * speedRange
                + 8.0 * smoothness
                + 10.0 * clearanceMargin
                + 5.0 * radiusMargin
                + 3.0 * flow
                + 2.0 * widthMargin;
    }

    // ---------- 曲率 ----------

    /**
     * 逐点曲率剖面。
     *
     * <p>曲率用航向角的一阶差分算：{@code κ_i = wrap(ψ_i - ψ_{i-1}) / STEP}，再做 ±3 采样点的
     * 滑动平均抑制极坐标离散化带来的锯齿。半径 {@code ρ = 1/|κ|}，κ≈0 时用 1e6 代表直线。
     */
    private static Profile profile(RaceTrack track) {
        int n = track.sampleCount();
        double[] heading = new double[n];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double dx = track.sampleX(j) - track.sampleX(i);
            double dz = track.sampleZ(j) - track.sampleZ(i);
            heading[i] = Math.atan2(dz, dx);
        }
        double[] raw = new double[n];
        double totalTurning = 0;
        for (int i = 0; i < n; i++) {
            int p = (i - 1 + n) % n;
            double d = heading[i] - heading[p];
            while (d > Math.PI) {
                d -= 2 * Math.PI;
            }
            while (d < -Math.PI) {
                d += 2 * Math.PI;
            }
            raw[i] = d / RaceTrack.STEP;
            totalTurning += Math.abs(d);
        }

        int window = 3;
        double[] smooth = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int o = -window; o <= window; o++) {
                sum += raw[(i + o + n) % n];
            }
            smooth[i] = sum / (2 * window + 1);
        }

        double[] radius = new double[n];
        double minRadius = Double.MAX_VALUE;
        int[] classes = new int[5];
        int straight = 0;
        int directionChanges = 0;
        int prevSign = 0;
        for (int i = 0; i < n; i++) {
            double abs = Math.abs(smooth[i]);
            double r = abs < 1.0e-9 ? 1.0e6 : 1.0 / abs;
            radius[i] = r;
            minRadius = Math.min(minRadius, r);

            int cls;
            if (r >= STRAIGHT_RADIUS) {
                cls = 0;
                straight++;
            } else if (r >= 80) {
                cls = 1;
            } else if (r >= 35) {
                cls = 2;
            } else if (r >= 20) {
                cls = 3;
            } else {
                cls = 4;
            }
            classes[cls]++;

            int sign = abs < 1.0e-4 ? 0 : (smooth[i] > 0 ? 1 : -1);
            if (sign != 0) {
                if (prevSign != 0 && sign != prevSign) {
                    directionChanges++;
                }
                prevSign = sign;
            }
        }
        double[] fraction = new double[classes.length];
        for (int i = 0; i < classes.length; i++) {
            fraction[i] = classes[i] / (double) n;
        }
        if (minRadius == Double.MAX_VALUE) {
            minRadius = 1.0e6;
        }
        double[] speed = speedProfile(radius);
        double minSpeed = Double.MAX_VALUE;
        double sumSpeed = 0;
        int fullThrottle = 0;
        for (int i = 0; i < n; i++) {
            minSpeed = Math.min(minSpeed, speed[i]);
            sumSpeed += speed[i];
            if (speed[i] >= ICE_TOP_SPEED * 0.98) {
                fullThrottle++;
            }
        }
        return new Profile(radius, minRadius, totalTurning, directionChanges,
                straight / (double) n, fraction,
                minSpeed, sumSpeed / n, fullThrottle / (double) n,
                longestRun(radius, STRAIGHT_RADIUS), runFrom(radius, STRAIGHT_RADIUS));
    }

    /**
     * 两个转向各自的最小曲率半径：{@code [0]} 是三点叉积为正的一侧，{@code [1]} 为负的一侧。
     * 某一侧若整圈都没出现过（值为 {@code MAX_VALUE}），说明这张图<b>只有一个转向</b>。
     */
    private static double[] directionRadii(RaceTrack track) {
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE};
        for (int s = 2; s < (int) track.length(); s++) {
            double ax = track.sampleX(s - 1) - track.sampleX(s - 2);
            double az = track.sampleZ(s - 1) - track.sampleZ(s - 2);
            double bx = track.sampleX(s) - track.sampleX(s - 1);
            double bz = track.sampleZ(s) - track.sampleZ(s - 1);
            double cross = ax * bz - az * bx;
            if (Math.abs(cross) < 1.0e-9) {
                continue;
            }
            double angle = Math.abs(cross) / (Math.hypot(ax, az) * Math.hypot(bx, bz));
            if (angle < 1.0e-9) {
                continue;
            }
            int index = cross > 0 ? 0 : 1;
            min[index] = Math.min(min[index], RaceTrack.STEP / angle);
        }
        return min;
    }

    /** 从 index 0（起终点线）开始、连续满足 {@code radius >= threshold} 的长度（格）。 */
    private static double runFrom(double[] radius, double threshold) {
        int count = 0;
        while (count < radius.length && radius[count] >= threshold) {
            count++;
        }
        return count * RaceTrack.STEP;
    }

    /** 连续满足 {@code radius >= threshold} 的最长段长度（格）。 */
    private static double longestRun(double[] radius, double threshold) {
        int n = radius.length;
        int best = 0;
        int run = 0;
        // 从某个不满足阈值的点开始扫，避免把跨越首尾的一段算成两段
        int start = 0;
        for (int i = 0; i < n; i++) {
            if (radius[i] < threshold) {
                start = (i + 1) % n;
                break;
            }
        }
        for (int k = 0; k < n; k++) {
            if (radius[(start + k) % n] >= threshold) {
                run++;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
        }
        return best * RaceTrack.STEP;
    }

    /** 弧长窗口 [from, from + span) 内的最小曲率半径。 */
    private static double minRadiusInWindow(RaceTrack track, double[] radius, double from, double span) {
        int n = track.sampleCount();
        int start = (int) Math.floor(track.wrapProgress(from) / RaceTrack.STEP);
        int count = Math.max(1, (int) Math.ceil(span / RaceTrack.STEP));
        double min = Double.MAX_VALUE;
        for (int k = 0; k < count; k++) {
            min = Math.min(min, radius[Math.floorMod(start + k, n)]);
        }
        return min == Double.MAX_VALUE ? 1.0e6 : min;
    }

    // ---------- 净空 ----------

    /**
     * 非相邻赛道段之间的最小净空（有上界）。
     *
     * <p>"非相邻"的定义：弧长差 &gt; {@code requiredClearance * 1.6}（同一段赛道前后几格的邻近段不算自贴）。
     * 只在 {@code requiredClearance * 2} 的半径内找：找不到就返回这个上界，
     * 表示"至少隔了这么远"（避免返回 Double.MAX_VALUE 让评分函数失去意义）。
     *
     * <p>实现：采样点按 {@code cell = 搜索半径} 分桶，只比较同桶/邻桶内的点（3×3 邻域足以覆盖搜索半径），
     * 避免 O(n²) —— 800 个采样点约 10 万次比较。
     */
    private static double minClearance(RaceTrack track, double requiredClearance) {
        int n = track.sampleCount();
        double length = track.length();
        double minArc = Math.max(requiredClearance * 1.6, 8.0);
        double searchRadius = requiredClearance * 2.0;
        if (length <= minArc * 2.0) {
            return searchRadius;
        }

        double cell = Math.max(8.0, searchRadius);
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, track.sampleX(i));
            minZ = Math.min(minZ, track.sampleZ(i));
            maxX = Math.max(maxX, track.sampleX(i));
            maxZ = Math.max(maxZ, track.sampleZ(i));
        }
        int cx0 = (int) Math.floor(minX / cell);
        int cz0 = (int) Math.floor(minZ / cell);
        int w = (int) Math.floor(maxX / cell) - cx0 + 1;
        int h = (int) Math.floor(maxZ / cell) - cz0 + 1;
        int cells = w * h;

        int[] counts = new int[cells + 1];
        int[] cellOf = new int[n];
        for (int i = 0; i < n; i++) {
            cellOf[i] = cellIndex(track, i, cx0, cz0, cell, w);
            counts[cellOf[i] + 1]++;
        }
        for (int c = 0; c < cells; c++) {
            counts[c + 1] += counts[c];
        }
        int[] start = counts;
        int[] items = new int[n];
        int[] cursor = new int[cells];
        for (int i = 0; i < n; i++) {
            items[start[cellOf[i]] + cursor[cellOf[i]]++] = i;
        }

        double best = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int c = cellOf[i];
            int cx = c % w;
            int cz = c / w;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = cx + dx;
                    int nz = cz + dz;
                    if (nx < 0 || nz < 0 || nx >= w || nz >= h) {
                        continue;
                    }
                    int nc = nz * w + nx;
                    for (int k = start[nc]; k < start[nc + 1]; k++) {
                        int j = items[k];
                        if (j == i) {
                            continue;
                        }
                        // 弧长差（闭环取最短方向）
                        double arc = Math.abs(i - j) * RaceTrack.STEP;
                        arc = Math.min(arc, length - arc);
                        if (arc <= minArc) {
                            continue;
                        }
                        double ddx = track.sampleX(i) - track.sampleX(j);
                        double ddz = track.sampleZ(i) - track.sampleZ(j);
                        double d = Math.sqrt(ddx * ddx + ddz * ddz);
                        if (d < best) {
                            best = d;
                        }
                    }
                }
            }
        }
        return Math.min(best, searchRadius);
    }

    private static int cellIndex(RaceTrack track, int i, int cx0, int cz0, double cell, int w) {
        int cx = (int) Math.floor(track.sampleX(i) / cell) - cx0;
        int cz = (int) Math.floor(track.sampleZ(i) / cell) - cz0;
        cx = Math.max(0, Math.min(w - 1, cx));
        return cz * w + cx;
    }

    /** 采样位置相对中心线的横向偏移。 */
    private static double lateralOffsetOf(RaceTrack track, double x, double z) {
        int i = track.nearestSample(x, z);
        double dx = x - track.sampleX(i);
        double dz = z - track.sampleZ(i);
        return dx * track.sampleNormalX(i) + dz * track.sampleNormalZ(i);
    }
}
