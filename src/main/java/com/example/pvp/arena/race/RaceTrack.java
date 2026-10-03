package com.example.pvp.arena.race;

import java.util.List;

/**
 * 一张随机生成的冰面赛道（纯几何数据，不依赖任何 Minecraft 类型，便于离线批量校验）。
 *
 * <p><b>为什么是"中心线 + 等距采样"</b>：赛道全程水平（船在冰面上竞速时，任何坡度都会让原版
 * 船的物理变得不可控），所以一张赛道可以被完全描述成平面上的闭环中心线，加上一个固定的
 * 赛道宽度。中心线按 {@link #STEP} 格等距重采样后，推进度、最近点、Checkpoint 位置、
 * 起跑格位全都退化成"沿弧长的一维索引"，实现和校验都简单且快。
 *
 * <p>采样点自带切向 {@code (dirX, dirZ)} 与法向 {@code (normalX, normalZ)}（水平面内逆时针 90°）。
 * 法向用来算"横向偏移"，也就是赛道栅格化时判断某一列是冰面 / 缓冲带 / 护栏 / 场外的依据。
 *
 * <p>本类还内建一个均匀网格（cell = {@link #CELL} 格）做最近采样点查询：地图生成要按包围盒
 * 逐列栅格化（十万级列），逐列线性扫全部采样点会到 10^8 次量级；网格把每次查询降到常数级。
 */
public final class RaceTrack {
    /** 中心线等距重采样的步长（格）。 */
    public static final double STEP = 1.0;
    /** 最近点查询网格的边长（格）。 */
    private static final int CELL = 8;

    /**
     * 一道门（Checkpoint）。
     *
     * @param index     0 = 起终点线；1..N = 普通 Checkpoint（按赛道顺序）
     * @param x         门中心 X（中心线上的点）
     * @param z         门中心 Z
     * @param dirX      门的正向（赛道前进方向）单位向量 X
     * @param dirZ      门的正向单位向量 Z
     * @param normalX   门的横向单位向量 X（水平面内，与正向垂直）
     * @param normalZ   门的横向单位向量 Z
     * @param halfWidth 门半宽（= 赛道半宽；判定时还会再加一点船体余量）
     * @param progress  该门在赛道上的弧长位置（0 表示起终点线）
     */
    public record Checkpoint(int index, double x, double z,
                             double dirX, double dirZ,
                             double normalX, double normalZ,
                             double halfWidth, double progress) {
        public boolean isFinishLine() {
            return this.index == 0;
        }
    }

    /**
     * 起跑格位。发车前所有船停在格位上；{@code row} 越大越靠后（离起终点线越远）。
     *
     * @param yaw 船头朝向（Minecraft yaw，正前方为直道切线方向）
     */
    public record GridSlot(double x, double z, float yaw, int row, int column) {
    }

    private final long seed;
    private final int attempt;
    private final double centerX;
    private final double centerZ;
    private final int surfaceY;
    private final double halfWidth;
    private final double trackLength;
    private final double boundingRadius;
    private final double minCornerRadius;
    private final double score;
    private final double runoffWidth;
    private final int barrierHeight;
    private final int checkpointCount;

    private final double[] xs;
    private final double[] zs;
    private final double[] dirXs;
    private final double[] dirZs;
    private final double[] normalXs;
    private final double[] normalZs;

    private final List<Checkpoint> checkpoints;
    private final List<GridSlot> grid;

    // ---- 最近采样点网格（CSR 存储） ----
    private final double gridOriginX;
    private final double gridOriginZ;
    private final int cellsX;
    private final int cellsZ;
    private final int[] cellStart;
    private final int[] cellSamples;

    RaceTrack(long seed, int attempt,
              double centerX, double centerZ, int surfaceY,
              double halfWidth, double runoffWidth, int barrierHeight,
              double[] xs, double[] zs,
              double[] dirXs, double[] dirZs,
              double[] normalXs, double[] normalZs,
              List<Checkpoint> checkpoints, List<GridSlot> grid,
              int checkpointCount, double minCornerRadius, double score) {
        this.seed = seed;
        this.attempt = attempt;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.surfaceY = surfaceY;
        this.halfWidth = halfWidth;
        this.runoffWidth = runoffWidth;
        this.barrierHeight = barrierHeight;
        this.xs = xs;
        this.zs = zs;
        this.dirXs = dirXs;
        this.dirZs = dirZs;
        this.normalXs = normalXs;
        this.normalZs = normalZs;
        this.checkpoints = List.copyOf(checkpoints);
        this.grid = List.copyOf(grid);
        this.checkpointCount = checkpointCount;
        this.minCornerRadius = minCornerRadius;
        this.score = score;
        this.trackLength = xs.length * STEP;

        // 包围半径：中心线最远点 + 缓冲带 + 护栏，供区域隔离与清场使用
        double maxR = 0;
        for (int i = 0; i < xs.length; i++) {
            double dx = xs[i] - centerX;
            double dz = zs[i] - centerZ;
            maxR = Math.max(maxR, Math.sqrt(dx * dx + dz * dz));
        }
        this.boundingRadius = maxR + halfWidth + runoffWidth + 1.0;

        // ---- 建网格 ----
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            minZ = Math.min(minZ, zs[i]);
            maxX = Math.max(maxX, xs[i]);
            maxZ = Math.max(maxZ, zs[i]);
        }
        this.gridOriginX = Math.floor(minX / CELL) * CELL;
        this.gridOriginZ = Math.floor(minZ / CELL) * CELL;
        this.cellsX = Math.max(1, (int) Math.floor(maxX / CELL) - (int) Math.floor(minX / CELL) + 1);
        this.cellsZ = Math.max(1, (int) Math.floor(maxZ / CELL) - (int) Math.floor(minZ / CELL) + 1);

        int cellCount = this.cellsX * this.cellsZ;
        int[] counts = new int[cellCount + 1];
        for (int i = 0; i < xs.length; i++) {
            counts[this.cellOf(xs[i], zs[i]) + 1]++;
        }
        for (int c = 0; c < cellCount; c++) {
            counts[c + 1] += counts[c];
        }
        this.cellStart = counts;
        int[] cursor = new int[cellCount];
        this.cellSamples = new int[xs.length];
        for (int i = 0; i < xs.length; i++) {
            int c = this.cellOf(xs[i], zs[i]);
            this.cellSamples[counts[c] + cursor[c]] = i;
            cursor[c]++;
        }
    }

    // ---------- 基本信息 ----------

    /** 生成这张图用的随机 Seed（同 Seed 必得同一张图，可用于复现 Bug）。 */
    public long seed() {
        return this.seed;
    }

    /** 第几次尝试就生成了合法赛道（0 = 第一次）。 */
    public int attempt() {
        return this.attempt;
    }

    public double centerX() {
        return this.centerX;
    }

    public double centerZ() {
        return this.centerZ;
    }

    /** 冰面顶层方块的 Y（船站在 surfaceY + 1）。 */
    public int surfaceY() {
        return this.surfaceY;
    }

    /** 赛道半宽（格）。 */
    public double halfWidth() {
        return this.halfWidth;
    }

    /** 赛道宽度（格）。 */
    public double width() {
        return this.halfWidth * 2;
    }

    /** 缓冲带宽度（格）。 */
    public double runoffWidth() {
        return this.runoffWidth;
    }

    /** 护栏高度（格）。 */
    public int barrierHeight() {
        return this.barrierHeight;
    }

    /** 中心线总长（约等于玩家单圈行驶距离）。 */
    public double length() {
        return this.trackLength;
    }

    /** 赛道外沿半径（含缓冲带与护栏）：必须小于竞技场区域半间距，否则会串场。 */
    public double boundingRadius() {
        return this.boundingRadius;
    }

    /** 中心线最小曲率半径（格）：越小弯越急。 */
    public double minCornerRadius() {
        return this.minCornerRadius;
    }

    /** 赛道质量评分（越高越好；生成器在多个合法候选里挑分最高的）。 */
    public double score() {
        return this.score;
    }

    /** 普通 Checkpoint 数量（不含起终点线）。 */
    public int checkpointCount() {
        return this.checkpointCount;
    }

    public int sampleCount() {
        return this.xs.length;
    }

    public double sampleX(int i) {
        return this.xs[i];
    }

    public double sampleZ(int i) {
        return this.zs[i];
    }

    public double sampleDirX(int i) {
        return this.dirXs[i];
    }

    public double sampleDirZ(int i) {
        return this.dirZs[i];
    }

    public double sampleNormalX(int i) {
        return this.normalXs[i];
    }

    public double sampleNormalZ(int i) {
        return this.normalZs[i];
    }

    /** 门的列表：index 0 = 起终点线，1..N = 普通 Checkpoint。 */
    public List<Checkpoint> checkpoints() {
        return this.checkpoints;
    }

    public Checkpoint checkpoint(int index) {
        return this.checkpoints.get(Math.floorMod(index, this.checkpoints.size()));
    }

    /** 起跑格位（按 row 从前往后、column 从左到右排列）。 */
    public List<GridSlot> grid() {
        return this.grid;
    }

    /** 赛道上"走廊"的总半宽：冰面 + 缓冲带。超出这个范围就算离开赛道。 */
    public double corridorHalfWidth() {
        return this.halfWidth + this.runoffWidth;
    }

    // ---------- 查询 ----------

    /**
     * 离 (x, z) 最近的采样点索引。
     *
     * <p>从查询点所在格开始逐圈外扩，一旦"已找到的最近距离 ≤ 当前圈内边界"就停：更外圈的格子
     * 里不可能有更近的点。空网格时退化为返回 0（赛道采样点不可能为空）。
     */
    public int nearestSample(double x, double z) {
        int gx = this.cellCoordX(x);
        int gz = this.cellCoordZ(z);
        int best = -1;
        double bestD2 = Double.MAX_VALUE;
        int maxRing = Math.max(this.cellsX, this.cellsZ);
        for (int r = 0; r <= maxRing; r++) {
            int x0 = gx - r;
            int x1 = gx + r;
            int z0 = gz - r;
            int z1 = gz + r;
            for (int cxx = x0; cxx <= x1; cxx++) {
                for (int czz = z0; czz <= z1; czz++) {
                    // 只扫第 r 圈（内圈上一轮已经扫过）
                    if (Math.max(Math.abs(cxx - gx), Math.abs(czz - gz)) != r) {
                        continue;
                    }
                    if (cxx < 0 || czz < 0 || cxx >= this.cellsX || czz >= this.cellsZ) {
                        continue;
                    }
                    int cell = czz * this.cellsX + cxx;
                    for (int k = this.cellStart[cell]; k < this.cellStart[cell + 1]; k++) {
                        int i = this.cellSamples[k];
                        double dx = this.xs[i] - x;
                        double dz = this.zs[i] - z;
                        double d2 = dx * dx + dz * dz;
                        if (d2 < bestD2) {
                            bestD2 = d2;
                            best = i;
                        }
                    }
                }
            }
            if (best >= 0) {
                double inner = (double) r * CELL;
                if (bestD2 <= inner * inner) {
                    break;
                }
            }
        }
        return best < 0 ? 0 : best;
    }

    /**
     * (x, z) 到中心线的精确距离（点到折线段的距离，不是到采样点的距离）。
     * 只检查最近采样点两侧各两段：赛道有最小净空约束，跨段的歧义不存在。
     */
    public double distanceToCenterline(double x, double z) {
        int n = this.xs.length;
        int i = this.nearestSample(x, z);
        double best = Double.MAX_VALUE;
        for (int o = -2; o <= 1; o++) {
            int a = Math.floorMod(i + o, n);
            int b = (a + 1) % n;
            best = Math.min(best, pointSegmentDistance(x, z, this.xs[a], this.zs[a], this.xs[b], this.zs[b]));
        }
        return best;
    }

    /**
     * (x, z) 在赛道上的弧长位置（0..length），带亚格精度。
     *
     * <p>做法：先找最近采样点，再投影到它两侧的折线段上取最近的那条，返回 {@code (段起点索引 + t) * STEP}。
     * 直接返回"最近采样点的弧长"会把进度量化到 1 格，排名就会出现大量并列。
     */
    public double arcLengthAt(double x, double z) {
        int n = this.xs.length;
        int i = this.nearestSample(x, z);
        double bestD2 = Double.MAX_VALUE;
        double bestS = i * STEP;
        for (int o = -1; o <= 0; o++) {
            int a = Math.floorMod(i + o, n);
            int b = (a + 1) % n;
            double ax = this.xs[a];
            double az = this.zs[a];
            double bx = this.xs[b];
            double bz = this.zs[b];
            double ex = bx - ax;
            double ez = bz - az;
            double len2 = ex * ex + ez * ez;
            if (len2 <= 1.0e-9) {
                continue;
            }
            double t = ((x - ax) * ex + (z - az) * ez) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            double qx = ax + t * ex;
            double qz = az + t * ez;
            double dx = x - qx;
            double dz = z - qz;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                bestS = (a + t) * STEP;
            }
        }
        return bestS;
    }

    /**
     * 中心线上某个弧长位置处的横向符号（正 = 法向正侧），用于把玩家位置折算成"跑了多少"。
     * 返回的弧长会先按闭环取模到 {@code [0, length)}。
     */
    public double wrapProgress(double progress) {
        double l = this.trackLength;
        double p = progress % l;
        return p < 0 ? p + l : p;
    }

    // ---------- 内部 ----------

    private int cellOf(double x, double z) {
        int cx = this.cellCoordX(x);
        int cz = this.cellCoordZ(z);
        cx = Math.max(0, Math.min(this.cellsX - 1, cx));
        cz = Math.max(0, Math.min(this.cellsZ - 1, cz));
        return cz * this.cellsX + cx;
    }

    private int cellCoordX(double x) {
        return (int) Math.floor((x - this.gridOriginX) / CELL);
    }

    private int cellCoordZ(double z) {
        return (int) Math.floor((z - this.gridOriginZ) / CELL);
    }

    private static double pointSegmentDistance(double px, double pz,
                                              double ax, double az, double bx, double bz) {
        double ex = bx - ax;
        double ez = bz - az;
        double len2 = ex * ex + ez * ez;
        double t = len2 <= 1.0e-9 ? 0.0 : ((px - ax) * ex + (pz - az) * ez) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double dx = px - (ax + t * ex);
        double dz = pz - (az + t * ez);
        return Math.sqrt(dx * dx + dz * dz);
    }
}
