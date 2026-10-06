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
 * <p><b>分岔（{@link Branch}）为什么能塞进这套几何</b>：分岔不改变"主线是一条闭环"这个前提，
 * 而是把主线的某一段<b>替换</b>成"内线"（一段带 S 形减速弯的更短路线），另外附一条从同一起点
 * 到同终点的"外线"支路。支路采样点自带<b>投影进度</b>：它是"岔口 A 到汇合口 B 这条赛段上的
 * 位置"，所以走内线还是外线，{@link #arcLengthAt} 返回的都是同一个赛段进度 —— 排名、过门、
 * 圈数全都天然公平，{@code RaceProgressTracker} 一行都不用改。
 *
 * <p>查询分两层：
 * <ul>
 *   <li>{@link #nearestSample} / {@link #sampleX} 等只认<b>主线</b>（下标 × STEP = 弧长），
 *       校验器、Checkpoint、道具箱布局继续用它们；</li>
 *   <li>{@link #nearestSurface} / {@link #surfaceX} 等认<b>主线 ∪ 支路</b>，
 *       地图栅格化、离道判定、卡边/缓冲带/速冻胶铺放用它们。</li>
 * </ul>
 * 两套查询各自带一个均匀网格（cell = 8 格）做最近点加速：地图生成要按包围盒逐列栅格化
 * （十万级列），逐列线性扫全部采样点会到 10^8 次量级；网格把每次查询降到常数级。
 */
public final class RaceTrack {
    /** 中心线等距重采样的步长（格）。 */
    public static final double STEP = 1.0;

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

    /**
     * 一条分岔支路（"外线"）：
     * 从主线赛段 {@code startProgress}（岔口 A）到 {@code endProgress}（汇合口 B）的另一条路线，
     * 与主线在 A 点分开、B 点合回。
     *
     * <p>每个采样点带 {@code progress}（<b>投影进度</b>，即"这条赛段上走到哪了"）。支路按弧长
     * 等距重采样，但进度取自构造参数 u（同一个横截面 → 同一个进度），所以两条路线上"进度相同"
     * 就代表"在同一个横截面上"，走哪条路排名都公平。
     */
    public static final class Branch {
        private final int index;
        private final double[] xs;
        private final double[] zs;
        private final double[] dirXs;
        private final double[] dirZs;
        private final double[] normalXs;
        private final double[] normalZs;
        private final double[] progress;
        private final double length;
        private final double startProgress;
        private final double endProgress;

        Branch(int index, double[] xs, double[] zs,
               double[] dirXs, double[] dirZs, double[] normalXs, double[] normalZs,
               double[] progress, double length, double startProgress, double endProgress) {
            this.index = index;
            this.xs = xs;
            this.zs = zs;
            this.dirXs = dirXs;
            this.dirZs = dirZs;
            this.normalXs = normalXs;
            this.normalZs = normalZs;
            this.progress = progress;
            this.length = length;
            this.startProgress = startProgress;
            this.endProgress = endProgress;
        }

        /** 支路序号（0 起）。 */
        public int index() {
            return this.index;
        }

        public int sampleCount() {
            return this.xs.length;
        }

        public double x(int k) {
            return this.xs[k];
        }

        public double z(int k) {
            return this.zs[k];
        }

        public double dirX(int k) {
            return this.dirXs[k];
        }

        public double dirZ(int k) {
            return this.dirZs[k];
        }

        public double normalX(int k) {
            return this.normalXs[k];
        }

        public double normalZ(int k) {
            return this.normalZs[k];
        }

        /** 采样点 k 的投影进度（赛段坐标）。 */
        public double progressAt(int k) {
            return this.progress[k];
        }

        /** 支路的真实几何长度（比内线长，这正是"外线绕远"的来源）。 */
        public double length() {
            return this.length;
        }

        /** 岔口 A 的赛段进度。 */
        public double startProgress() {
            return this.startProgress;
        }

        /** 汇合口 B 的赛段进度。 */
        public double endProgress() {
            return this.endProgress;
        }

        public double progressSpan() {
            return this.endProgress - this.startProgress;
        }
    }

    /**
     * 并集路面上的一点：
     *
     * @param index    并集采样下标（喂给 {@link #shiftSurface} / {@link #surfaceX} 等）
     * @param path     -1 = 主线；>= 0 = 支路序号
     * @param local    在所属路径内的局部下标
     * @param progress 赛段进度（主线 = 弧长；支路 = 投影进度）
     * @param lateral  该点相对所属路径中心线的带符号横向偏移
     */
    public record SurfacePoint(int index, int path, int local, double progress, double lateral,
                              double x, double z,
                              double dirX, double dirZ, double normalX, double normalZ) {
        public boolean onBranch() {
            return this.path >= 0;
        }
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
    private final List<GridSlot> gridSlots;
    private final List<Branch> branches;
    /** 分岔的说明行（构造时的平衡/净距数据），供日志与调试命令打印。 */
    private final List<String> forkNotes;

    /** 主线最近点网格。 */
    private final Grid primaryGrid;

    // ---- 并集路面（主线 + 支路）----
    private final double[] surfaceXs;
    private final double[] surfaceZs;
    private final double[] surfaceDirXs;
    private final double[] surfaceDirZs;
    private final double[] surfaceNormalXs;
    private final double[] surfaceNormalZs;
    private final double[] surfaceProgress;
    /** 每个并集采样点属于哪条路径（-1 = 主线，>= 0 = 支路序号）。 */
    private final int[] surfacePath;
    /** 每条路径在并集数组里的区间：下标 0 = 主线，下标 b+1 = 支路 b；[start, end)。 */
    private final int[] pathStart;
    private final int[] pathEnd;
    private final Grid surfaceGrid;

    RaceTrack(long seed, int attempt,
              double centerX, double centerZ, int surfaceY,
              double halfWidth, double runoffWidth, int barrierHeight,
              double[] xs, double[] zs,
              double[] dirXs, double[] dirZs,
              double[] normalXs, double[] normalZs,
              List<Branch> branches, List<String> forkNotes,
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
        this.gridSlots = List.copyOf(grid);
        this.branches = List.copyOf(branches);
        this.forkNotes = List.copyOf(forkNotes);
        this.checkpointCount = checkpointCount;
        this.minCornerRadius = minCornerRadius;
        this.score = score;
        this.trackLength = xs.length * STEP;

        // ---- 并集路面（主线在前，支路依次追加）----
        int n = xs.length;
        int total = n;
        for (Branch b : this.branches) {
            total += b.sampleCount();
        }
        this.surfaceXs = new double[total];
        this.surfaceZs = new double[total];
        this.surfaceDirXs = new double[total];
        this.surfaceDirZs = new double[total];
        this.surfaceNormalXs = new double[total];
        this.surfaceNormalZs = new double[total];
        this.surfaceProgress = new double[total];
        this.surfacePath = new int[total];
        this.pathStart = new int[this.branches.size() + 1];
        this.pathEnd = new int[this.branches.size() + 1];
        this.pathStart[0] = 0;
        for (int i = 0; i < n; i++) {
            this.surfaceXs[i] = xs[i];
            this.surfaceZs[i] = zs[i];
            this.surfaceDirXs[i] = dirXs[i];
            this.surfaceDirZs[i] = dirZs[i];
            this.surfaceNormalXs[i] = normalXs[i];
            this.surfaceNormalZs[i] = normalZs[i];
            this.surfaceProgress[i] = i * STEP;
            this.surfacePath[i] = -1;
        }
        int cursor = n;
        for (int b = 0; b < this.branches.size(); b++) {
            Branch branch = this.branches.get(b);
            this.pathStart[b + 1] = cursor;
            for (int k = 0; k < branch.sampleCount(); k++) {
                this.surfaceXs[cursor] = branch.x(k);
                this.surfaceZs[cursor] = branch.z(k);
                this.surfaceDirXs[cursor] = branch.dirX(k);
                this.surfaceDirZs[cursor] = branch.dirZ(k);
                this.surfaceNormalXs[cursor] = branch.normalX(k);
                this.surfaceNormalZs[cursor] = branch.normalZ(k);
                this.surfaceProgress[cursor] = branch.progressAt(k);
                this.surfacePath[cursor] = b;
                cursor++;
            }
            this.pathEnd[b + 1] = cursor;
        }
        this.pathEnd[0] = n;
        this.surfaceGrid = new Grid(this.surfaceXs, this.surfaceZs);
        this.primaryGrid = new Grid(xs, zs);

        // 包围半径：主线与支路都要算进去（缓冲带 + 护栏 + 1 格余量），供区域隔离与清场使用
        double maxR = 0;
        for (int i = 0; i < total; i++) {
            double dx = this.surfaceXs[i] - centerX;
            double dz = this.surfaceZs[i] - centerZ;
            maxR = Math.max(maxR, Math.sqrt(dx * dx + dz * dz));
        }
        this.boundingRadius = maxR + halfWidth + runoffWidth + 1.0;
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
        return this.gridSlots;
    }

    /** 赛道上"走廊"的总半宽：冰面 + 缓冲带。超出这个范围就算离开赛道。 */
    public double corridorHalfWidth() {
        return this.halfWidth + this.runoffWidth;
    }

    /** 分岔支路（没有分岔时为空）。 */
    public List<Branch> branches() {
        return this.branches;
    }

    public int branchCount() {
        return this.branches.size();
    }

    /** 分岔的说明行（每条一行；没有分岔时为空，也可能是"本张图没配出分岔"的说明）。 */
    public List<String> forkNotes() {
        return this.forkNotes;
    }

    /** 这个赛段进度是否落在某条分岔支路的区间内（严格内部：岔口与汇合口本身不算）。 */
    public boolean isInsideForkSpan(double progress) {
        for (Branch b : this.branches) {
            if (progress > b.startProgress() && progress < b.endProgress()) {
                return true;
            }
        }
        return false;
    }

    /** 并集路面的采样点总数（主线 + 支路）。 */
    public int surfaceCount() {
        return this.surfaceXs.length;
    }

    public double surfaceX(int i) {
        return this.surfaceXs[i];
    }

    public double surfaceZ(int i) {
        return this.surfaceZs[i];
    }

    public double surfaceDirX(int i) {
        return this.surfaceDirXs[i];
    }

    public double surfaceDirZ(int i) {
        return this.surfaceDirZs[i];
    }

    public double surfaceNormalX(int i) {
        return this.surfaceNormalXs[i];
    }

    public double surfaceNormalZ(int i) {
        return this.surfaceNormalZs[i];
    }

    /** 并集采样点的赛段进度。 */
    public double surfaceProgress(int i) {
        return this.surfaceProgress[i];
    }

    /** 并集采样点属于哪条路径（-1 = 主线，>= 0 = 支路序号）。 */
    public int surfacePath(int i) {
        return this.surfacePath[i];
    }

    // ---------- 主线查询 ----------

    /**
     * 离 (x, z) 最近的<b>主线</b>采样点索引。
     *
     * <p>从查询点所在格开始逐圈外扩，一旦"已找到的最近距离 ≤ 当前圈内边界"就停：更外圈的格子
     * 里不可能有更近的点。空网格时退化为返回 0（赛道采样点不可能为空）。
     */
    public int nearestSample(double x, double z) {
        return this.primaryGrid.nearest(x, z);
    }

    /**
     * 离 (x, z) 最近的<b>路面</b>采样点（主线 ∪ 支路）的并集下标。
     *
     * <p>配套访问器是 {@link #surfaceX} / {@link #surfaceProgress} 等；沿同一条路前后移动用
     * {@link #shiftSurface}（主线闭环、支路夹住，不会从支路跳回主线）。
     */
    public int nearestSurface(double x, double z) {
        return this.surfaceGrid.nearest(x, z);
    }

    /**
     * (x, z) 在赛道上的弧长位置（0..length），带亚格精度。
     *
     * <p>主线点用"最近采样点 + 两侧折线段投影"；支路点返回它的<b>投影进度</b>（赛段坐标），
     * 所以内外线在同一横截面上会得到同一个进度 —— 这是分岔排名公平的关键。
     */
    public double arcLengthAt(double x, double z) {
        int i = this.surfaceGrid.nearest(x, z);
        if (this.surfacePath[i] < 0) {
            return primaryArcLengthAt(x, z);
        }
        int path = this.surfacePath[i];
        Branch b = this.branches.get(path);
        int k = i - this.pathStart[path + 1];
        double best = b.progressAt(k);
        double bestD2 = Double.MAX_VALUE;
        for (int o = -1; o <= 0; o++) {
            int a = k + o;
            if (a < 0 || a + 1 >= b.sampleCount()) {
                continue;
            }
            double ax = b.x(a);
            double az = b.z(a);
            double ex = b.x(a + 1) - ax;
            double ez = b.z(a + 1) - az;
            double len2 = ex * ex + ez * ez;
            double t = len2 <= 1.0e-9 ? 0.0 : ((x - ax) * ex + (z - az) * ez) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            double dx = x - (ax + ex * t);
            double dz = z - (az + ez * t);
            double d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = b.progressAt(a) + t * (b.progressAt(a + 1) - b.progressAt(a));
            }
        }
        return best;
    }

    /** 主线上的弧长位置（原来的实现，供 {@link #arcLengthAt} 与校验器复用）。 */
    public double primaryArcLengthAt(double x, double z) {
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
     * (x, z) 到<b>最近路面</b>（主线或任一支路）的距离。
     *
     * <p>离道判定、卡边判定、缓冲带结冰、道具箱布局都用它 —— 分岔的两条路都算"在赛道上"。
     */
    public double distanceToCenterline(double x, double z) {
        int i = this.surfaceGrid.nearest(x, z);
        return distanceToPath(this.surfacePath[i], pathLocal(i), x, z);
    }

    /** 主线的最近距离（不含支路；校验器里"格位必须在主线冰面上"之类的判定用它）。 */
    public double primaryDistanceToCenterline(double x, double z) {
        return distanceToPath(-1, this.nearestSample(x, z), x, z);
    }

    /**
     * 最近路面点的完整几何（位置、切向、法向、赛段进度、横向偏移）。
     *
     * <p>速冻胶铺放、缓冲带结冰的格子计算都需要"船所在那条路的局部法向"，
     * 在支路上必须取支路的采样点，不能拿主线的。
     */
    public SurfacePoint surfacePoint(double x, double z) {
        int i = this.surfaceGrid.nearest(x, z);
        int path = this.surfacePath[i];
        int local = pathLocal(i);
        // 投影到所属路径的相邻线段上，拿横向偏移
        double[] seg = nearestSegment(path, local, x, z);
        double ax = seg[0];
        double az = seg[1];
        double ex = seg[2];
        double ez = seg[3];
        double t = seg[4];
        double nx = seg[5];
        double nz = seg[6];
        double px = ax + ex * t;
        double pz = az + ez * t;
        double lateral = (x - px) * nx + (z - pz) * nz;
        double progress = progressOnSegment(path, (int) seg[9], t);
        return new SurfacePoint(i, path, local, progress, lateral,
                px, pz, seg[7], seg[8], nx, nz);
    }

    /**
     * 沿<b>同一条路径</b>移动 {@code steps} 个采样点：主线按闭环绕回，支路夹在两端
     * （支路不能绕回主线 —— 那会让"身后若干格"的判定穿到另一条路上去）。
     */
    public int shiftSurface(int index, int steps) {
        int path = this.surfacePath[index];
        int lo = this.pathStart[path + 1];
        int hi = this.pathEnd[path + 1];
        int count = hi - lo;
        if (count <= 0) {
            return index;
        }
        if (path < 0) {
            return lo + Math.floorMod(index - lo + steps, count);
        }
        int k = index - lo + steps;
        return lo + Math.max(0, Math.min(count - 1, k));
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

    /**
     * 点到线段的距离（平面）。
     *
     * <p>道具箱的拾取判定用它：把箱子看成一个立着的圆柱，"线段到圆心的距离 ≤ 半径"
     * 就等价于"这一 tick 船划过的线段穿过了圆柱"。用线段而不是点，是因为氮气时
     * 一 tick 能跑 3.64 格，逐 tick 的点判定会整段跳过箱子（Checkpoint 判定踩过同一个坑）。
     *
     * <p>放在这里而不是 {@code RaceItemBoxes} 里，是为了保持"赛道几何不依赖 Minecraft 类型"，
     * 好让它能被离线批量校验。
     */
    public static double distanceToSegment(double px, double pz,
                                           double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double length2 = dx * dx + dz * dz;
        double t = length2 < 1.0e-9 ? 0.0 : ((px - ax) * dx + (pz - az) * dz) / length2;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(px - (ax + dx * t), pz - (az + dz * t));
    }

    // ---------- 内部 ----------

    /** 并集下标 → 在所属路径内的局部下标。 */
    private int pathLocal(int unionIndex) {
        int path = this.surfacePath[unionIndex];
        return unionIndex - this.pathStart[path + 1];
    }

    /**
     * 路径 {@code path} 上第 {@code a} 条线段内、参数 {@code t} 处的进度。
     *
     * <p>注意 {@code a} 必须是<b>线段起点</b>的下标：投影可能落在最近点前面那条线段上，
     * 用"最近点下标 + 这条线段的 t"会算错进度（支路的进度是按构造参数存的，不是按弧长线性）。
     */
    private double progressOnSegment(int path, int a, double t) {
        if (path < 0) {
            return (a + t) * STEP;
        }
        Branch b = this.branches.get(path);
        int lo = Math.max(0, Math.min(b.sampleCount() - 2, a));
        return b.progressAt(lo) + t * (b.progressAt(lo + 1) - b.progressAt(lo));
    }

    /**
     * 路径 {@code path} 上离 (x, z) 最近线段的几何，返回
     * {@code [ax, az, ex, ez, t, nx, nz, dirX, dirZ, a]}（{@code a} = 线段起点下标）。
     */
    private double[] nearestSegment(int path, int local, double x, double z) {
        int count = path < 0 ? this.xs.length : this.branches.get(path).sampleCount();
        double bestD2 = Double.MAX_VALUE;
        double[] best = null;
        for (int o = -1; o <= 0; o++) {
            int a = path < 0 ? Math.floorMod(local + o, count) : local + o;
            if (a < 0 || a + 1 >= count) {
                continue;
            }
            int b = path < 0 ? (a + 1) % count : a + 1;
            double ax = pointX(path, a);
            double az = pointZ(path, a);
            double bx = pointX(path, b);
            double bz = pointZ(path, b);
            double ex = bx - ax;
            double ez = bz - az;
            double len = Math.hypot(ex, ez);
            if (len <= 1.0e-9) {
                continue;
            }
            double t = ((x - ax) * ex + (z - az) * ez) / (len * len);
            t = Math.max(0.0, Math.min(1.0, t));
            double dx = x - (ax + ex * t);
            double dz = z - (az + ez * t);
            double d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                double dirX = ex / len;
                double dirZ = ez / len;
                best = new double[]{ax, az, ex, ez, t, -dirZ, dirX, dirX, dirZ, a};
            }
        }
        if (best == null) {
            double px = pointX(path, local);
            double pz = pointZ(path, local);
            best = new double[]{px, pz, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, local};
        }
        return best;
    }

    private double pointX(int path, int k) {
        return path < 0 ? this.xs[k] : this.branches.get(path).x(k);
    }

    private double pointZ(int path, int k) {
        return path < 0 ? this.zs[k] : this.branches.get(path).z(k);
    }

    /** 路径 {@code path} 上局部下标 {@code local} 附近的最近距离。 */
    private double distanceToPath(int path, int local, double x, double z) {
        int count = path < 0 ? this.xs.length : this.branches.get(path).sampleCount();
        double best = Double.MAX_VALUE;
        for (int o = -2; o <= 1; o++) {
            int a = path < 0 ? Math.floorMod(local + o, count) : local + o;
            int b = path < 0 ? (a + 1) % count : a + 1;
            if (a < 0 || b >= count) {
                continue;
            }
            best = Math.min(best, pointSegmentDistance(x, z,
                    pointX(path, a), pointZ(path, a), pointX(path, b), pointZ(path, b)));
        }
        if (best == Double.MAX_VALUE) {
            best = Math.hypot(x - pointX(path, local), z - pointZ(path, local));
        }
        return best;
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

    /**
     * 均匀网格（cell = {@link #CELL} 格）加速的最近采样点查询，CSR 存储。
     * 主线与"主线 ∪ 支路"各建一个，所以抽成内部类复用。
     */
    private static final class Grid {
        private static final int CELL = 8;

        private final double[] xs;
        private final double[] zs;
        private final double originX;
        private final double originZ;
        private final int cellsX;
        private final int cellsZ;
        private final int[] cellStart;
        private final int[] cellSamples;

        Grid(double[] xs, double[] zs) {
            this.xs = xs;
            this.zs = zs;
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
            this.originX = Math.floor(minX / CELL) * CELL;
            this.originZ = Math.floor(minZ / CELL) * CELL;
            this.cellsX = Math.max(1, (int) Math.floor(maxX / CELL) - (int) Math.floor(minX / CELL) + 1);
            this.cellsZ = Math.max(1, (int) Math.floor(maxZ / CELL) - (int) Math.floor(minZ / CELL) + 1);

            int cellCount = this.cellsX * this.cellsZ;
            int[] counts = new int[cellCount + 1];
            for (int i = 0; i < xs.length; i++) {
                counts[cellOf(xs[i], zs[i]) + 1]++;
            }
            for (int c = 0; c < cellCount; c++) {
                counts[c + 1] += counts[c];
            }
            this.cellStart = counts;
            int[] cursor = new int[cellCount];
            this.cellSamples = new int[xs.length];
            for (int i = 0; i < xs.length; i++) {
                int c = cellOf(xs[i], zs[i]);
                this.cellSamples[counts[c] + cursor[c]] = i;
                cursor[c]++;
            }
        }

        int nearest(double x, double z) {
            int gx = cellCoordX(x);
            int gz = cellCoordZ(z);
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

        private int cellOf(double x, double z) {
            int cx = Math.max(0, Math.min(this.cellsX - 1, cellCoordX(x)));
            int cz = Math.max(0, Math.min(this.cellsZ - 1, cellCoordZ(z)));
            return cz * this.cellsX + cx;
        }

        private int cellCoordX(double x) {
            return (int) Math.floor((x - this.originX) / CELL);
        }

        private int cellCoordZ(double z) {
            return (int) Math.floor((z - this.originZ) / CELL);
        }
    }

}
