package com.example.pvp.config;

/**
 * 隐藏分（Elo/MMR）计算（纯函数，不依赖任何游戏类，便于离线验证）。
 *
 * <p><b>这是内部数值，不展示给玩家</b>：玩家界面里出现的一律是胜/负/场次与胜率。
 * 隐藏分只用于系统侧的强度估计，目前唯一用途是<b>空岛弱势补偿</b>——
 * 每局仅给明显低于本局中位分的玩家少量食物和方块，同分待遇一致。
 *
 * <p><b>补偿为什么需要隐藏分而不是胜率</b>：胜率把"打得多"和"打得好"混在一起
 * （10 场 8 胜和 100 场 80 胜看起来一样），而且强者总被排在一起、胜率天然趋近 50%，
 * 用它算强弱会把补偿发错人。隐藏分用"赢该赢的加分少、赢不该赢的加分多"来刻画水平，
 * 关键是<b>对手是谁</b>，与打了多少场无关；新号从 1600 起步，也不会被当成"胜率 0"的绝对弱者。
 *
 * <p><b>局面不止两人</b>：本服大量模式是 FFA / 空岛 / 起床（多队）／竞速。
 * 如果照搬两人公式（每人一组、胜者 +K、负者 −K），12 人乱斗里每局只有 1 人加分、
 * 11 人扣分，全服分数会持续通缩，平均玩家越打越低。所以这里用
 * <b>Bradley–Terry 多项 softmax</b> 的期望胜率：
 * <pre>
 *   p_i = e^(R_i/s) / Σ_j e^(R_j/s)      （s = 400/ln10）
 *   ΔR_i = K_i × (S_i − p_i)             （S_i = 1 胜 / 0 负）
 * </pre>
 * 两人对局时它<b>恰好退化成标准 Elo</b> {@code p = 1/(1+10^((Rb−Ra)/400))}；
 * 多队时"赢下一场 N 人局"按 1/N 的期望来结算，赢家拿 {@code K(1−1/N)}、
 * 每家输家只扣 {@code K/N}，总变化仍为 0——乱斗冠军能加分，垫底也不会被重罚，
 * 同时全服平均分长期稳定在 1600 附近（不会因为"必须有人输"而整体下沉）。
 *
 * <p><b>队伍</b>：同一队的玩家不互相结算，结算时用<b>队内平均分</b>代表这支队伍，
 * 队伍得到的 ΔR 全队每人各拿一份。因此 2v2 里"队友掉线"不会变成队友对彼此的零和。
 */
public final class EloRating {
    /** 所有新玩家的初始分（也是全服平均分的长期稳态）。 */
    public static final double INITIAL = 1600.0;

    /** 常规 K 值：对手越强，同样的胜负带来的变化越小/越大，符合 Elo 惯例。 */
    public static final double K = 32.0;

    /** 定级期 K 值：新号前若干场变化更大，避免 1600 附近排队挤成一团。 */
    public static final double K_PROVISIONAL = 48.0;

    /** 定级期场次（含本场；即第 1~10 场用 K_PROVISIONAL）。 */
    public static final int PROVISIONAL_MATCHES = 10;

    /** 400 分制换算成自然对数尺度：e^(Δ/SCALE) = 10^(Δ/400)。 */
    private static final double SCALE = 400.0 / Math.log(10.0);

    private EloRating() {
    }

    /** 根据已打场次取 K 值（定级期更大）。 */
    public static double kFactor(int matchesPlayed) {
        return matchesPlayed <= PROVISIONAL_MATCHES ? K_PROVISIONAL : K;
    }

    /**
     * 按当前 rating 计算各组的期望得分（和为 1）。
     *
     * @param ratings 每个参赛组的平均 rating（至少 2 组）
     * @return 与 {@code ratings} 等长的期望得分数组
     */
    public static double[] expected(double[] ratings) {
        int n = ratings.length;
        double[] expected = new double[n];
        if (n == 0) {
            return expected;
        }
        // 先减去最大值再取指数：rating 可能上千，直接 exp 会溢出成 Infinity
        double max = Double.NEGATIVE_INFINITY;
        for (double rating : ratings) {
            max = Math.max(max, rating);
        }
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            expected[i] = Math.exp((ratings[i] - max) / SCALE);
            sum += expected[i];
        }
        if (sum <= 0.0 || Double.isNaN(sum)) {
            // 理论上不可达（至少一组为 1），兜底成均分，避免把 NaN 写进战绩
            java.util.Arrays.fill(expected, 1.0 / n);
            return expected;
        }
        for (int i = 0; i < n; i++) {
            expected[i] /= sum;
        }
        return expected;
    }

    /**
     * 结算一场比赛，返回每个参赛组的 rating 变化量（保留 1 位小数）。
     *
     * <p>全胜（合作模式守住）或全负（合作模式失败）时没有对手可比，
     * 返回全 0：rating 不变，只记录胜负场次。
     *
     * @param ratings       每个参赛组的平均 rating
     * @param won           该组是否获胜
     * @param matchesPlayed 该组已打场次（含本场，用于定级期 K 值）
     */
    public static double[] deltas(double[] ratings, boolean[] won, int[] matchesPlayed) {
        int n = ratings.length;
        double[] deltas = new double[n];
        if (n < 2) {
            return deltas;
        }
        boolean anyWin = false;
        boolean anyLoss = false;
        for (boolean flag : won) {
            if (flag) {
                anyWin = true;
            } else {
                anyLoss = true;
            }
        }
        if (!anyWin || !anyLoss) {
            return deltas; // 没有胜负对立就没有可比的一局（例如村庄保卫战全员同胜/同败）
        }
        double[] expected = expected(ratings);
        double[] k = new double[n];
        boolean sameK = true;
        double deltasSum = 0.0;
        int largestDelta = 0;
        for (int i = 0; i < n; i++) {
            double score = won[i] ? 1.0 : 0.0;
            k[i] = kFactor(matchesPlayed[i]);
            if (Math.abs(k[i] - k[0]) > 1e-9) {
                sameK = false;
            }
            deltas[i] = round1(k[i] * (score - expected[i]));
            deltasSum += deltas[i];
            if (Math.abs(deltas[i]) > Math.abs(deltas[largestDelta])) {
                largestDelta = i;
            }
        }
        // 保留 1 位小数会带来系统性偏差：12 人局里 +29.3 与 11×(−2.7) = −29.7，
        // 每局净蒸发 0.4 分，长期会让全服分数缓缓下沉。K 值相同的场次
        // 把这点残差补给 |Δ| 最大的一组，保证总变化严格为 0；定级期 K 不同，
        // 本来就是"分数从定级者流向对手"，不做此修正。
        if (sameK && Math.abs(deltasSum) > 1e-9) {
            deltas[largestDelta] = round1(deltas[largestDelta] - deltasSum);
        }
        return deltas;
    }

    /** rating 四舍五入到 1 位小数，避免浮点误差在 stats.json 里越滚越脏。 */
    public static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

}
