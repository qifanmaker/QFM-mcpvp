package com.example.pvp.arena.race;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 分岔路的几何构造（纯几何，不依赖 Minecraft，可离线批量跑）。
 *
 * <p><b>为什么内线必须人工加"减速弯"</b>：原版船过弯的速度上限是 {@code v = √(a·R)}，
 * 所以沿一段圆心角 θ、半径 R 的弯道走完的时间是 {@code t = Rθ / √(aR) = θ·√(R/a)} ——
 * <b>半径越小越快</b>。也就是说在弯道上"贴内侧"天生又短又快，纯几何做不出公平的内外取舍。
 * 所以这里把主线的一段（岔口 A → 汇合口 B）改造成<b>内线</b>：沿法向叠一个小振幅、净位移为 0
 * 的 S 形减速弯；外线则是一条沿另一个方向鼓出去的正弦包（更长、更顺）。振幅由
 * {@link #buildOne} 数值搜索，让内外线的估计通行时间之差控制在
 * {@link #BALANCE_TOLERANCE} 以内 —— 这样"内线短但要刹车 / 外线长但能全速"才成立。
 *
 * <p><b>为什么进度按"赛段"而不是"距离"</b>：内线长度 Li、外线长度 Lo 不同，如果按各自走过的
 * 距离排名，走外线的人会凭空领先。这里给支路每个采样点一个<b>投影进度</b>（构造参数 u 映射到
 * A→B 的赛段坐标），于是"同一个横截面 = 同一个进度"，排名、过门、圈数全都公平。
 */
final class RaceForkBuilder {
    /**
     * 内外线估计通行时间的相对偏差上限（<b>只作用于岔口这一小段</b>）。
     *
     * <p>为什么不卡到 5%：本模式的赛道是极坐标波形闭环，没有长直道（最长的"近似直线"约
     * 40~100 格），所以在 130 格以上的跨度上，往任一侧鼓 20 格都会让外线长出 15%~35%，
     * 而内线又常常紧到没有余量再叠减速弯（基准半径已经贴着 32 的下限）。18% 是实测能同时
     * 保证"分岔几乎每张图都有"与"两条路差别不致命"的折中：岔口约占一圈的 1/4，
     * 18% 的段内差距折算到单圈约 4%，而且人类玩家走内线还要扣掉刹车/擦墙的代价。
     */
    static final double BALANCE_TOLERANCE = 0.25;
    /**
     * 支路允许的最小曲率半径（相对主线下限的比例）。
     *
     * <p>主线（含内线，因为它就是主线的一段）必须守住 32 格的下限，但支路只在岔口这一小段
     * 存在、而且外面还有缓冲带兜着，可以放宽到 24 格（过弯速度 1.13 → 0.98 格/tick）。
     * 这条放宽把"有分岔"的比例从约 1/10 抬到可观的水平 —— 代价是外线的急弯稍紧一点，
     * 而"两条路快慢差"由通行时间平衡继续把关。
     */
    static final double BRANCH_RADIUS_FACTOR = 0.75;
    /** 外线相对跨度的长度比上限：再长就"不是岔路，是另一条赛道"了。 */
    private static final double MAX_LENGTH_RATIO = 1.45;

    /** 岔口区间长度（格）：按赛道长度取比例，再夹到上下限。 */
    private static final double SPAN_TARGET_FACTOR = 0.22;
    private static final double SPAN_MIN = 130.0;
    private static final double SPAN_MAX = 200.0;
    /**
     * 岔口区间离起终点线的净距（格）。
     *
     * <p>起点之后必须留够：起跑格位与发车挡板在长度侧，而道具箱摆在门后 12 格，
     * 所以线两侧各留 56 格不摆门；如果第一个岔口离起点太近（比如 30 格），
     * "线后 56 格"与"岔口前 22 格"之间就挤不下第一道门，门只能跳到岔口之后 ——
     * 实测那样会让"逆行提示"失去参照（第一道门跑到 200 格开外）。
     */
    private static final double START_GUARD_BEFORE = 108.0;
    private static final double START_GUARD_AFTER = 55.0;
    /** 两个岔口之间的最小间隔（格）。 */
    private static final double FORK_GAP = 30.0;
    /** 外线鼓出深度的候选步长（格）：从最深的候选往浅里试，直到所有判据都过。 */
    private static final double DEPTH_STEP = 2.0;
    /** sin²(πu) 的曲率峰值系数：κ_own ≤ 2π²·D/S² ≈ 19.7·D/S²。 */
    private static final double BUMP_CURVATURE_FACTOR = 2.0 * Math.PI * Math.PI;
    /** 减速弯振幅的搜索上限（格）与步长：上限按"半个跨度的 sin² 凸起"的曲率反推。 */
    private static final double CHICANE_AMPLITUDE_STEP = 0.5;
    private static final double CHICANE_MAX = 12.0;

    /** 一条支路的构造结果。 */
    static final class Spec {
        /** 内线（会替换掉主线那一段）的采样点，含首尾两个端点。 */
        final double[] innerXs;
        final double[] innerZs;
        /** 外线（支路）采样点，按弧长等距。 */
        final double[] xs;
        final double[] zs;
        /** 外线每个采样点的构造参数 u ∈ [0,1]（用来算投影进度）。 */
        final double[] us;
        final double length;
        /** 内线段在最终主线数组里的起点下标与采样点数。 */
        final int startIndex;
        final int innerCount;
        /** 原始区间末端下标（拼接时用来定位被替换掉的旧采样点）。 */
        final int branchTo;
        /** 内线段的真实几何长度。 */
        final double innerLength;
        /** 内外线估计通行时间（tick）。 */
        final double innerTime;
        final double outerTime;
        /** 两条路线中心线之间的最小净距（格，中段最窄处）。 */
        final double minSeparation;

        Spec(double[] innerXs, double[] innerZs, double[] xs, double[] zs, double[] us,
             double length, int startIndex, int innerCount, int branchTo, double innerLength,
             double innerTime, double outerTime, double minSeparation) {
            this.innerXs = innerXs;
            this.innerZs = innerZs;
            this.xs = xs;
            this.zs = zs;
            this.us = us;
            this.length = length;
            this.startIndex = startIndex;
            this.innerCount = innerCount;
            this.branchTo = branchTo;
            this.innerLength = innerLength;
            this.innerTime = innerTime;
            this.outerTime = outerTime;
            this.minSeparation = minSeparation;
        }

        double imbalance() {
            double min = Math.min(this.innerTime, this.outerTime);
            return min <= 1.0e-9 ? 1.0 : Math.abs(this.innerTime - this.outerTime) / min;
        }
    }

    /** 构造结果：改造后的主线采样数组 + 各条支路。 */
    static final class Result {
        final double[] xs;
        final double[] zs;
        final List<Spec> specs;
        final List<String> notes;

        Result(double[] xs, double[] zs, List<Spec> specs, List<String> notes) {
            this.xs = xs;
            this.zs = zs;
            this.specs = specs;
            this.notes = notes;
        }
    }

    /** 离线诊断开关：打开后 {@link #buildOne} 把每一步的判断写进 {@link #TRACE}（不影响产物）。 */
    static boolean traceEnabled = false;
    static final List<String> TRACE = new ArrayList<>();

    private static void trace(String message) {
        if (traceEnabled) {
            TRACE.add(message);
        }
    }

    private RaceForkBuilder() {
    }

    /**
     * 在 {@code xs/zs}（闭环主线，下标 × STEP = 弧长）上造至多 {@code forkCount} 条分岔。
     *
     * @param frame          主线的切向/法向，{@code [dirX, dirZ, normalX, normalZ]}
     * @param width          赛道宽度（格）
     * @param runoffWidth    缓冲带宽度（格）
     * @param minCornerRadius 允许的最小曲率半径
     * @param minClearance   外线与主线其它段之间的最小净空
     */
    static Result build(double[] xs, double[] zs, double[][] frame, double width, int runoffWidth,
                        int forkCount, double minCornerRadius, double minClearance,
                        double centerX, double centerZ) {
        List<String> notes = new ArrayList<>();
        if (forkCount <= 0) {
            return new Result(xs, zs, List.of(), notes);
        }
        double wanted = clamp(xs.length * RaceTrack.STEP * SPAN_TARGET_FACTOR, SPAN_MIN, SPAN_MAX);
        // 候选区间互不重叠、按"最直 + 外侧有空间"排序：某个区间配不出平衡的两条路就换下一个
        List<int[]> candidates = chooseSpans(xs, zs, wanted, Math.min(10, forkCount * 6),
                centerX, centerZ);
        if (candidates.isEmpty()) {
            notes.add("分岔：找不到合适的岔口区间（赛道太短或弯太密），本张图没有分岔");
            return new Result(xs, zs, List.of(), notes);
        }
        List<Spec> specs = new ArrayList<>();
        int rejected = 0;
        for (int[] span : candidates) {
            if (specs.size() >= forkCount) {
                break;
            }
            Spec spec = buildOne(xs, zs, frame, span[0], span[1], width, runoffWidth,
                    minCornerRadius, minClearance, centerX, centerZ);
            if (spec == null) {
                rejected++;
                continue;
            }
            specs.add(spec);
        }
        if (specs.isEmpty()) {
            notes.add("分岔：试了 " + rejected + " 个岔口区间都配不出平衡的两条路，本张图没有分岔");
            return new Result(xs, zs, List.of(), notes);
        }
        // 由后往前拼接：改动靠后的区间不会让靠前区间的下标失效
        specs.sort(Comparator.comparingInt((Spec sp) -> sp.startIndex).reversed());
        double[] curX = xs;
        double[] curZ = zs;
        for (Spec spec : specs) {
            int from = spec.startIndex;
            int to = spec.branchTo;
            int interior = spec.innerCount - 2;
            int newLength = curX.length + spec.innerCount - (to - from) - 1;
            double[] newX = new double[newLength];
            double[] newZ = new double[newLength];
            System.arraycopy(curX, 0, newX, 0, from + 1);
            System.arraycopy(curZ, 0, newZ, 0, from + 1);
            System.arraycopy(spec.innerXs, 1, newX, from + 1, interior);
            System.arraycopy(spec.innerZs, 1, newZ, from + 1, interior);
            System.arraycopy(curX, to, newX, from + 1 + interior, curX.length - to);
            System.arraycopy(curZ, to, newZ, from + 1 + interior, curZ.length - to);
            curX = newX;
            curZ = newZ;
        }
        List<Spec> ordered = new ArrayList<>(specs);
        ordered.sort(Comparator.comparingInt(sp -> sp.startIndex));
        ordered.sort(Comparator.comparingInt(a -> a.startIndex));
        for (int i = 0; i < ordered.size(); i++) {
            Spec spec = ordered.get(i);
            notes.add(String.format(
                    "分岔 %d：赛段 %.0f~%.0f（内线 %.0f 格 / %.0f tick，外线 %.0f 格 / %.0f tick，"
                            + "偏差 %.1f%%，中心线最窄处 %.0f 格）",
                    i + 1, spec.startIndex * RaceTrack.STEP,
                    (spec.startIndex + spec.innerCount - 1) * RaceTrack.STEP,
                    spec.innerLength, spec.innerTime, spec.length, spec.outerTime,
                    spec.imbalance() * 100.0, spec.minSeparation));
        }
        return new Result(curX, curZ, List.copyOf(ordered), notes);
    }

    /**
     * 一个候选区间的"直度"评分：区间内最小曲率半径（越大越好，直线给 1e6）。
     *
     * <p>用 ±3 点滑动平均的转角算 κ —— 与校验器、{@code routeMinRadius} 同一口径，
     * 免得出现"按这里的评分很直、按那里的评分很急"的错位。
     */
    private static double spanScore(double[] xs, double[] zs, double[] heading, double[] step,
                                    int start, int count, double centerX, double centerZ) {
        int n = xs.length;
        int window = 3;
        double min = 1.0e6;
        double signed = 0;
        double absolute = 0;
        double maxDistance = 0;
        for (int k = 0; k < count; k++) {
            int i = (start + k) % n;
            double sum = 0;
            int used = 0;
            for (int o = -window; o <= window; o++) {
                int j = Math.floorMod(i + o, n);
                int p = (j - 1 + n) % n;
                double d = heading[j] - heading[p];
                while (d > Math.PI) {
                    d -= 2 * Math.PI;
                }
                while (d < -Math.PI) {
                    d += 2 * Math.PI;
                }
                sum += d;
                used++;
            }
            double kappa = used == 0 ? 0.0 : (sum / used) / Math.max(1.0e-6, step[i]);
            signed += kappa;
            absolute += Math.abs(kappa);
            if (Math.abs(kappa) > 1.0e-9) {
                min = Math.min(min, 1.0 / Math.abs(kappa));
            }
            maxDistance = Math.max(maxDistance, Math.hypot(xs[i] - centerX, zs[i] - centerZ));
        }
        // 转向一致性：整段都往同一侧弯（干净圆弧）时接近 1；S 形来回弯时接近 0。
        // 只有干净圆弧才能保证"往外偏移 D 格 → 半径 R+D"（分岔的外线要靠这个才能全速）。
        double consistency = absolute <= 1.0e-9 ? 1.0 : Math.abs(signed) / absolute;
        if (consistency < 0.62) {
            return 0.0;   // S 形/来回弯的段不做分岔：外线一定会在某一侧被压到半径过小
        }
        // 外侧空间：外线要往"曲率中心的反方向"鼓，凸弯段就是往区域边界方向鼓，
        // 离区域边界太近的话再浅的鼓包也放不下（实测大量候选就是这样被否掉的）。
        double room = clamp((RaceTrackValidator.MAX_BOUNDING_RADIUS - 20.0 - maxDistance) / 60.0, 0.0, 1.0);
        return min * consistency * consistency * (0.25 + room);
    }

    /** 选岔口区间：累计转角最小（最直）的地方，且避开起跑区、彼此间隔足够。 */
    private static List<int[]> chooseSpans(double[] xs, double[] zs, double wanted, int count,
                                           double centerX, double centerZ) {
        int n = xs.length;
        int steps = (int) Math.round(wanted);
        int lo = (int) Math.ceil(START_GUARD_BEFORE);
        int hi = (int) Math.floor(n - START_GUARD_AFTER - steps);
        List<int[]> result = new ArrayList<>();
        if (hi <= lo) {
            return result;
        }
        // 候选区间按"区间内最小曲率半径"从大到小排：决定分岔能不能成立的
        // 不是总转角，而是这条岔路最急的那一处 —— 基准半径越小，往外鼓 D 格之后
        // 偏移线的半径就越紧（R ≈ R_base ± D），所以必须挑真正直的一段。
        double[] step = new double[n];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            step[i] = Math.hypot(xs[j] - xs[i], zs[j] - zs[i]);
        }
        double[] heading = new double[n];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            heading[i] = Math.atan2(zs[j] - zs[i], xs[j] - xs[i]);
        }
        List<int[]> candidates = new ArrayList<>();
        for (int start = lo; start <= hi; start++) {
            candidates.add(new int[]{start, (int) Math.round(
                    spanScore(xs, zs, heading, step, start, steps, centerX, centerZ) * 100.0)});
        }
        candidates.sort(Comparator.comparingInt((int[] c) -> c[1]).reversed());
        for (int[] candidate : candidates) {
            if (result.size() >= count) {
                break;
            }
            int start = candidate[0];
            boolean clash = false;
            for (int[] chosen : result) {
                double gap = Math.min(Math.abs(start - chosen[0]), n - Math.abs(start - chosen[0]));
                if (gap < steps + FORK_GAP) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                result.add(new int[]{start, start + steps});
            }
        }
        result.sort(Comparator.comparingInt(a -> a[0]));
        return result;
    }

    /**
     * 造一个岔口：搜索"外线鼓出深度 + 内线 S 弯振幅"，让两条路线的估计通行时间尽量相等。
     *
     * @return 合格的分岔；这个区间做不出平衡的两条路时返回 null（调用方换别的区间/Seed）
     */
    private static Spec buildOne(double[] xs, double[] zs, double[][] frame, int from, int to,
                                 double width, int runoffWidth, double minCornerRadius,
                                 double minClearance, double centerX, double centerZ) {
        double[] nx = frame[2];
        double[] nz = frame[3];
        int steps = to - from;
        if (steps < 20) {
            return null;
        }
        double span = steps * RaceTrack.STEP;
        // 两条路中间至少留 1 格（否则两条冰面直接连成一片"加宽的路"，不成其为分岔）
        double minDepth = Math.max(width + 1.0, 10.0);
        double branchMinRadius = minCornerRadius * BRANCH_RADIUS_FACTOR;
        // 鼓包自身的曲率：κ_own ≈ 5.77·D/S² → D ≤ S²/(5.77·R_min)
        double bulgeCap = span * span / (BUMP_CURVATURE_FACTOR * minCornerRadius);
        double baseRadius = RaceTrackValidator.routeMinRadius(
                java.util.Arrays.copyOfRange(xs, from, to + 1),
                java.util.Arrays.copyOfRange(zs, from, to + 1));
        double maxDepth = Math.min(bulgeCap, minDepth + 20.0);
        trace(String.format("区间 %d~%d：跨度 %.0f 格，基准最小半径 %.1f，深度区间 [%.1f, %.1f]",
                from, to, span, baseRadius, minDepth, maxDepth));
        if (maxDepth < minDepth) {
            return null;
        }
        // 往哪一侧鼓：这一侧必须在区域里还有空间。凸弯段往外鼓就是往区域边界鼓；
        // 而接近直线的段落两侧长度几乎一样（差不到 1%），所以方向只能靠空间定，不能靠长度定。
        double limit = RaceTrackValidator.MAX_BOUNDING_RADIUS - (width / 2.0 + runoffWidth + 1.0);
        double centerSign = roomFor(xs, zs, nx, nz, from, to, 1.0, minDepth, centerX, centerZ, limit)
                >= roomFor(xs, zs, nx, nz, from, to, -1.0, minDepth, centerX, centerZ, limit)
                ? 1.0 : -1.0;

        for (double bulgeSign : new double[]{centerSign, -centerSign}) {
            double chicaneSign = -bulgeSign;    // 短的那条：全程让到鼓包的相反侧
            // 深度只扫一遍，挑"最深的、长度比达标"的那一档；只对它做减速弯搜索。
            // （逐档都跑减速弯搜索会让生成从 ~16 ms 涨到 ~300 ms，对开赛卡顿是不可接受的。）
            double[][] outer = null;
            double outerLength = 0;
            double chosenDepth = 0;
            for (double depth = maxDepth; depth >= minDepth - 1.0e-9; depth -= DEPTH_STEP) {
                double[][] outerRaw = bulge(xs, zs, nx, nz, from, to, bulgeSign, depth);
                double[][] candidate = resampleOpen(outerRaw[0], outerRaw[1], outerRaw[2]);
                double candidateLength = polylineLength(candidate[0], candidate[1]);
                boolean radiusOk = RaceTrackValidator.routeMinRadius(candidate[0], candidate[1])
                        >= branchMinRadius;
                boolean ratioOk = candidateLength / span <= MAX_LENGTH_RATIO;
                boolean clear = radiusOk && ratioOk
                        && clearOfTrack(candidate[0], candidate[1], xs, zs, from, to, minClearance);
                boolean inside = clear && withinBounding(candidate[0], candidate[1], xs, zs,
                        centerX, centerZ, width / 2.0 + runoffWidth + 1.0);
                trace(String.format("  深度 %.1f：外线 %.0f 格（%.2fx），半径 %s，长度比 %s，净空 %s，界内 %s",
                        depth, candidateLength, candidateLength / span, radiusOk ? "OK" : "太紧",
                        ratioOk ? "OK" : "绕太远", clear ? "OK" : "撞车", inside ? "OK" : "超界"));
                if (!inside) {
                    continue;
                }
                outer = candidate;
                outerLength = candidateLength;
                chosenDepth = depth;
                break;
            }
            if (outer == null) {
                continue;
            }
            double outerTime = RaceTrackValidator.routeTime(outer[0], outer[1]);

            // 内线：双顶点减速弯，搜索振幅让通行时间贴近外线
            double[][] innerZero = resampleOpen2(innerRoute(xs, zs, nx, nz, from, to, chicaneSign, 0.0));
            double bestChicane = 0.0;
            double bestInnerTime = RaceTrackValidator.routeTime(innerZero[0], innerZero[1]);
            double bestImbalance = imbalance(bestInnerTime, outerTime);
            int radiusMisses = 0;
            for (double amp = CHICANE_AMPLITUDE_STEP;
                 amp <= CHICANE_MAX + 1.0e-9 && bestImbalance > 1.0e-3;
                 amp += CHICANE_AMPLITUDE_STEP) {
                double[][] trial = resampleOpen2(innerRoute(xs, zs, nx, nz, from, to, chicaneSign, amp));
                if (RaceTrackValidator.routeMinRadius(trial[0], trial[1]) < minCornerRadius) {
                    // 振幅越大越紧：连续两档都不达标就没必要再往上试
                    if (++radiusMisses >= 2) {
                        break;
                    }
                    continue;
                }
                radiusMisses = 0;
                double innerTime = RaceTrackValidator.routeTime(trial[0], trial[1]);
                double imbalance = imbalance(innerTime, outerTime);
                if (imbalance < bestImbalance) {
                    bestImbalance = imbalance;
                    bestChicane = amp;
                    bestInnerTime = innerTime;
                }
            }
            double[][] inner = bestChicane == 0.0
                    ? innerZero
                    : resampleOpen2(innerRoute(xs, zs, nx, nz, from, to, chicaneSign, bestChicane));
            double innerLength = polylineLength(inner[0], inner[1]);
            trace(String.format("    深度 %.1f：内线 %.0f 格 / %.0f tick，外线 %.0f tick，振幅 %.1f，偏差 %.1f%%",
                    chosenDepth, innerLength, bestInnerTime, outerTime, bestChicane, bestImbalance * 100.0));
            if (bestImbalance > BALANCE_TOLERANCE) {
                continue;
            }
            // 绕远的那条必须真的更长：否则"又短又快"的那条会碾压另一条
            if (outerLength <= innerLength * 1.005) {
                continue;
            }
            double[] separation = separations(inner[0], inner[1], inner[2],
                    outer[0], outer[1], outer[2]);
            if (separation[0] < width + 1.0 || separation[1] < width / 2.0 + 1.0) {
                trace(String.format("    分隔带不够：最宽 %.1f / 中段 %.1f", separation[0], separation[1]));
                continue;
            }
            return new Spec(inner[0], inner[1], outer[0], outer[1], outer[2], outerLength,
                    from, inner[0].length, to, innerLength, bestInnerTime, outerTime, separation[0]);
        }
        return null;
    }

    /**
     * 指定深度的鼓包：{@code w = D·smoothstep(u)}。
     *
     * <p><b>形状为什么重要</b>：偏移线的曲率里有一项是鼓包自身的曲率
     * {@code κ_own ≈ C·D/S²}，C 完全由形状决定 —— 正弦包 sin² 的 C ≈ 19.7、
     * sin⁴ 是 22.2，而五次平滑阶跃（6u⁵−15u⁴+10u³）只有 <b>5.77</b>。
     * 实测：同样是 165 格跨、22 格深，正弦包的最小半径只有 32（贴着下限），
     * 换成平滑阶跃之后鼓包自身的曲率几乎可以忽略，限制就只剩"区域空间"和"绕远比例"了。
     * 这个形状两端同样满足 w' = w'' = 0，所以岔口处的曲率是平滑接入的。
     */
    private static double[][] bulge(double[] xs, double[] zs, double[] nx, double[] nz,
                                    int from, int to, double sign, double depth) {
        return offsetRoute(xs, zs, nx, nz, from, to, sign, u -> depth * bump(u));
    }

    /**
     * 鼓包形状：{@code bump(u) = sin²(πu)}（0 → 1 → 0，两端斜率为 0）。
     *
     * <p>这里必须是"鼓包"（两端都回到基准线），否则支路在汇合口会停在离主线 D 格的地方 ——
     * 那就不是分岔，而是两条断头路。{@code sin²} 也是这类形状里曲率峰值最小的之一：
     * {@code κ_own ≤ 2π²D/S²}（≡ 19.7D/S²）。曾一度用五次平滑阶跃当过鼓包，那是<b>单调</b>
     * 的阶跃函数（u=1 处取满值），汇合口就差出整整一个 D —— 已由自检中的
     * "支路两端必须贴住主线"守住。
     */
    private static double bump(double u) {
        double t = Math.max(0.0, Math.min(1.0, u));
        double s = Math.sin(Math.PI * t);
        return s * s;
    }

    /**
     * 内线：往鼓包的<b>反方向</b>让开，并叠两个减速弯（双顶点）。
     *
     * <p>为什么不是"S 形来回"：S 形总有一个波峰朝着鼓包那一侧，那一段两条路会贴到一起
     * （实测最近只隔 3 格），分岔就"糊"了。改成同一侧的双顶点之后，内线全程都在鼓包的反侧，
     * 两条路之间的分隔带随岔口张开、在岔口/汇合口收敛到 0 —— 这才是分岔该有的样子。
     */
    private static double[][] innerRoute(double[] xs, double[] zs, double[] nx, double[] nz,
                                         int from, int to, double chicaneSign, double amplitude) {
        // 两个同向的 sin² 凸起（中间回到基准线）：单侧双顶点，曲率代价可控
        return offsetRoute(xs, zs, nx, nz, from, to, chicaneSign,
                u -> amplitude * bump(u < 0.5 ? 2.0 * u : 2.0 * (u - 0.5)));
    }

    /** 把基准线沿法向偏移 {@code sign · w(u)}，返回 {x, z, u}。 */
    private static double[][] offsetRoute(double[] xs, double[] zs, double[] nx, double[] nz,
                                          int from, int to, double sign, Shape shape) {
        int count = to - from + 1;
        double[] ox = new double[count];
        double[] oz = new double[count];
        double[] us = new double[count];
        for (int i = 0; i < count; i++) {
            double u = count <= 1 ? 0.0 : i / (double) (count - 1);
            double w = sign * shape.at(u);
            ox[i] = xs[from + i] + nx[from + i] * w;
            oz[i] = zs[from + i] + nz[from + i] * w;
            us[i] = u;
        }
        return new double[][]{ox, oz, us};
    }

    /** 按弧长等距重采样开放折线（首尾保留），同时线性插值 u。 */
    static double[][] resampleOpen(double[] xs, double[] zs, double[] us) {
        int n = xs.length;
        double[] cum = new double[n];
        for (int i = 1; i < n; i++) {
            cum[i] = cum[i - 1] + Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        double total = cum[n - 1];
        int m = Math.max(2, (int) Math.round(total / RaceTrack.STEP) + 1);
        double[] ox = new double[m];
        double[] oz = new double[m];
        double[] ou = new double[m];
        int seg = 0;
        for (int k = 0; k < m; k++) {
            double target = total * k / (m - 1.0);
            while (seg < n - 2 && cum[seg + 1] < target) {
                seg++;
            }
            double segLen = cum[seg + 1] - cum[seg];
            double t = segLen <= 1.0e-9 ? 0.0 : (target - cum[seg]) / segLen;
            t = Math.max(0.0, Math.min(1.0, t));
            ox[k] = xs[seg] + t * (xs[seg + 1] - xs[seg]);
            oz[k] = zs[seg] + t * (zs[seg + 1] - zs[seg]);
            ou[k] = us[seg] + t * (us[seg + 1] - us[seg]);
        }
        return new double[][]{ox, oz, ou};
    }

    private static double[][] resampleOpen2(double[][] route) {
        return resampleOpen(route[0], route[1], route[2]);
    }

    /**
     * 两条路线中心线之间的最小净距（只算中段：岔口与汇合口本来就要并到一起）。
     *
     * <p>按<b>构造参数 u</b> 对齐（外线采样点 u 相同的那个内线位置），不能按下标对齐 ——
     * 两条路线长度不同、采样点数也不同。
     */
    /**
     * 两条路线之间的分隔带宽度：{@code [0] = 最宽处（u∈[0.3,0.7] 的最大值）}，
     * {@code [1] = 中段最窄处（u∈[0.4,0.6] 的最小值）}。
     *
     * <p>岔口与汇合口两条路本来就要并到一起，所以只在<b>中段</b>量分隔带：最宽处决定中间
     * 到底能不能站住一条分隔带，中段最窄处防止两条路"贴着走"变成一条加宽的路。
     *
     * <p>配对按<b>构造参数 u</b>（同一个横截面），不是按下标：两条路长度不同、采样点数也不同，
     * 而且按弧长重采样会把 u 拉得不均匀，所以必须沿 u 走指针找配对点。
     */
    private static double[] separations(double[] ix, double[] iz, double[] iu,
                                        double[] ox, double[] oz, double[] ou) {
        double widest = 0;
        double middleMin = Double.MAX_VALUE;
        int j = 0;
        for (int i = 0; i < ox.length; i++) {
            double u = ou[i];
            if (u < 0.3 || u > 0.7) {
                continue;
            }
            while (j + 1 < iu.length - 1 && iu[j + 1] < u) {
                j++;
            }
            double fx;
            double fz;
            double span = iu[j + 1] - iu[j];
            if (span <= 1.0e-9) {
                fx = ix[j];
                fz = iz[j];
            } else {
                double t = Math.max(0.0, Math.min(1.0, (u - iu[j]) / span));
                fx = ix[j] + t * (ix[j + 1] - ix[j]);
                fz = iz[j] + t * (iz[j + 1] - iz[j]);
            }
            double d = Math.hypot(fx - ox[i], fz - oz[i]);
            widest = Math.max(widest, d);
            if (u >= 0.4 && u <= 0.6) {
                middleMin = Math.min(middleMin, d);
            }
        }
        return new double[]{widest, middleMin == Double.MAX_VALUE ? 0.0 : middleMin};
    }

    /** 外线是否离主线其它段足够远（岔口区间本身除外）。 */
    private static boolean clearOfTrack(double[] ox, double[] oz, double[] xs, double[] zs,
                                        int from, int to, double minClearance) {
        int n = xs.length;
        int guard = (to - from) + 40;
        for (int i = 0; i < ox.length; i += 3) {
            for (int j = 0; j < n; j += 2) {
                int arc = Math.abs(j - from);
                arc = Math.min(arc, n - arc);
                if (arc < guard) {
                    continue;
                }
                if (Math.hypot(ox[i] - xs[j], oz[i] - zs[j]) < minClearance) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 往某一侧鼓 {@code depth} 之后，还剩下多少"离区域边界"的余量（取最紧张的一处）。 */
    private static double roomFor(double[] xs, double[] zs, double[] nx, double[] nz,
                                  int from, int to, double sign, double depth,
                                  double centerX, double centerZ, double limit) {
        double worst = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            double u = (i - from) / (double) (to - from);
            double w = sign * depth * bump(u);
            double px = xs[i] + nx[i] * w;
            double pz = zs[i] + nz[i] * w;
            worst = Math.min(worst, limit - Math.hypot(px - centerX, pz - centerZ));
        }
        return worst;
    }

    /** 外线 + 主线是否仍在竞技场区域能装下的范围内。 */
    private static boolean withinBounding(double[] ox, double[] oz, double[] xs, double[] zs,
                                          double centerX, double centerZ, double margin) {
        double limit = RaceTrackValidator.MAX_BOUNDING_RADIUS - margin;
        for (int i = 0; i < ox.length; i++) {
            if (Math.hypot(ox[i] - centerX, oz[i] - centerZ) > limit) {
                return false;
            }
        }
        for (int i = 0; i < xs.length; i++) {
            if (Math.hypot(xs[i] - centerX, zs[i] - centerZ) > limit) {
                return false;
            }
        }
        return true;
    }

    private static double polylineLength(double[] xs, double[] zs) {
        double total = 0;
        for (int i = 0; i + 1 < xs.length; i++) {
            total += Math.hypot(xs[i + 1] - xs[i], zs[i + 1] - zs[i]);
        }
        return total;
    }

    private static double imbalance(double a, double b) {
        double min = Math.min(a, b);
        return min <= 1.0e-9 ? 1.0 : Math.abs(a - b) / min;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 法向偏移形状。 */
    private interface Shape {
        double at(double u);
    }
}
