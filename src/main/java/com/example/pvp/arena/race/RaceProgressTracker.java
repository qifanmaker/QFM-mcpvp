package com.example.pvp.arena.race;

import java.util.ArrayList;
import java.util.List;

/**
 * 一名选手的竞速进度状态机（纯逻辑，不依赖 Minecraft，可离线用模拟轨迹回放测试）。
 *
 * <p>参考 MightyRacingMod 的 "sector / lap 守卫"思路（只接受按顺序前进的门、已过门不再计、
 * 跳门忽略、倒车忽略），但把它从"命令方块点判定 + 全局静态状态"改成：
 * <ul>
 *   <li><b>扫掠式平面穿越</b>：用上一 tick 与当前 tick 的位置对门的平面做符号变化判定，
 *       再插值出精确穿越点。船速再快也不会像"每 tick 点判定"那样漏过门（MightyRacingMod 的
 *       wiki 要求判定区至少 4 格长，就是点判定的后遗症）。</li>
 *   <li><b>连续进度标量</b>：{@code progress = 已完成圈数 × 赛道长 + 本圈内弧长}。
 *       MightyRacingMod 用 {@code lap*100 + sector} 做排序键，同门内的选手全部并列、
 *       而且只在过门事件时重排，导致"超了车名次不变"。这里把"当前门内的距离"也算进去，
 *       名次是连续可比的（对应需求里的"完成圈数 + Checkpoint 进度 + 当前 Checkpoint 内的距离"）。</li>
 *   <li><b>滚动发车</b>：起跑格位在起终点线之后，所以起终点线的第一次穿越不算圈
 *       （{@code nextGate} 初始为 1，只有 {@code nextGate == 0} 时穿线才计圈）。</li>
 * </ul>
 */
public final class RaceProgressTracker {
    /** 门的横向判定余量（格）：门半宽之外再放宽，避免贴边过门被漏判。 */
    private static final double GATE_LATERAL_SLACK = 2.5;
    /** 门的竖直判定余量（格）：船被弹到高空擦过门时仍算过门。 */
    private static final double GATE_VERTICAL_SLACK = 10.0;
    /** 起跑区允许的负进度（格）：格位在起终点线之后，进度得是负数才排得对。 */
    private static final double START_SLACK = 40.0;
    /** 兜底补过门的余量（格）：确认已沿赛道前进到该门之后这么多格，就直接补上，避免卡死。 */
    private static final double FORCED_GATE_MARGIN = 6.0;
    /** 认定"接近过某个门"的距离（格）：兜底补门必须以此为前提，防止瞬移刷门。 */
    private static final double GATE_APPROACH_MARGIN = 10.0;
    /**
     * 单 tick 允许的最大位移（格）。超过就认为是瞬移/回位，本 tick 不做扫掠穿越判定，
     * 也不允许兜底补门 —— 否则一次大传送会被当成"穿过了中间所有的门"。
     * 船在蓝冰上的极限速度远低于此值（≈1.5 格/tick）。
     */
    private static final double MAX_TICK_MOVE = 4.0;

    /** {@link #update} 的结果。 */
    public enum Event {
        /** 什么都没发生 */
        NONE,
        /** 按顺序过了一个 Checkpoint */
        CHECKPOINT,
        /** 完成一圈（还有下一圈） */
        LAP,
        /** 跑完全部圈数（冲线） */
        FINISH
    }

    private final RaceTrack track;
    private final int totalLaps;
    private final double length;

    private int lapsCompleted;
    private int nextGate = 1;
    private int lastGate;
    private boolean running;
    private boolean finished;
    private long startTick = -1L;
    private long finishTick = -1L;
    private long lapStartTick = -1L;
    private long lastLapTicks = -1L;
    private long bestLapTicks = -1L;
    private final List<Long> lapTicks = new ArrayList<>();

    private double prevX;
    private double prevY;
    private double prevZ;
    private boolean hasPrev;

    private double progress;
    private double distanceToNextGate = Double.MAX_VALUE;
    private int place;
    private int recoveries;
    private int forcedGateAdvances;
    private boolean wrongWay;
    /** 本圈内是否已经贴近过 nextGate（兜底补门的前提条件）。 */
    private boolean gateApproached;

    public RaceProgressTracker(RaceTrack track, int totalLaps) {
        this.track = track;
        this.totalLaps = Math.max(1, totalLaps);
        this.length = track.length();
    }

    // ---------- 生命周期 ----------

    /** 发车（GO 那一 tick）：开始计时、把起终点线记为"已通过"，<b>不</b>算一圈。 */
    public void arm(double x, double z, long tick) {
        this.lapsCompleted = 0;
        this.lastGate = 0;
        this.nextGate = 1;
        this.running = true;
        this.finished = false;
        this.startTick = tick;
        this.lapStartTick = tick;
        this.finishTick = -1L;
        this.lastLapTicks = -1L;
        this.bestLapTicks = -1L;
        this.lapTicks.clear();
        this.recoveries = 0;
        this.forcedGateAdvances = 0;
        this.gateApproached = false;
        this.snapTo(x, z);
    }

    /** 传送（回位）之后调用：重置扫掠起点，避免瞬移被误判成"穿门"。 */
    public void snapTo(double x, double z) {
        this.prevX = x;
        this.prevY = this.track.surfaceY() + 1.0;
        this.prevZ = z;
        this.hasPrev = true;
        this.recomputeProgress(x, z);
    }

    // ---------- 推进 ----------

    /**
     * 每 tick 用玩家当前位置推进一次。
     *
     * @param y 玩家（船）的 Y，用于竖直方向的过门判定
     * @return 本次推进产生的事件
     */
    public Event update(double x, double y, double z, long tick) {
        if (!this.hasPrev) {
            this.snapTo(x, z);
            return Event.NONE;
        }
        double px = this.prevX;
        double py = this.prevY;
        double pz = this.prevZ;
        this.prevX = x;
        this.prevY = y;
        this.prevZ = z;

        if (!this.running || this.finished) {
            this.recomputeProgress(x, z);
            return Event.NONE;
        }

        RaceTrack.Checkpoint gate = this.track.checkpoint(this.nextGate);
        double prevSide = sideOf(gate, px, pz);
        double curSide = sideOf(gate, x, z);
        this.distanceToNextGate = Math.abs(curSide);
        // 朝向：还在门前（prevSide < 0）却越走越远（curSide 更负）→ 逆行提示
        this.wrongWay = prevSide < 0 && curSide < prevSide - 1.0e-6;

        double moved = Math.hypot(x - px, z - pz);
        boolean teleported = moved > MAX_TICK_MOVE;

        boolean crossed = !teleported && prevSide < 0.0 && curSide >= 0.0
                && withinGateAt(gate, px, py, pz, x, y, z, prevSide, curSide);

        Event event = Event.NONE;
        if (crossed) {
            event = this.advanceGate(tick);
        } else {
            double arc = this.track.arcLengthAt(x, z);
            double rel = relativeArc(arc, this.lastGate);
            double span = this.spanTo(this.nextGate);
            boolean inCorridor = this.track.distanceToCenterline(x, z)
                    <= this.track.corridorHalfWidth() + 6.0;
            if (inCorridor && rel >= span - GATE_APPROACH_MARGIN) {
                this.gateApproached = true;
            }
            // 兜底补门：只有"确实贴近过这个门"（gateApproached）且现在已经沿赛道走到门后足够远，
            // 才补上。这样被撞飞/弹高导致漏判的玩家不会永久卡死，而瞬移刷门也刷不出来。
            if (!teleported && this.gateApproached && inCorridor && rel > span + FORCED_GATE_MARGIN) {
                this.forcedGateAdvances++;
                event = this.advanceGate(tick);
            }
        }

        this.recomputeProgress(x, z);
        return event;
    }

    /**
     * 过门后的状态推进：普通门 → {@code nextGate + 1}；起终点线 → 计一圈（或冲线）。
     */
    private Event advanceGate(long tick) {
        this.gateApproached = false;
        if (this.nextGate != 0) {
            this.lastGate = this.nextGate;
            this.nextGate = (this.nextGate + 1) % this.track.checkpoints().size();
            return Event.CHECKPOINT;
        }
        // 起终点线：完成一圈
        long lapTicksValue = Math.max(0L, tick - this.lapStartTick);
        this.lapTicks.add(lapTicksValue);
        this.lastLapTicks = lapTicksValue;
        if (this.bestLapTicks < 0 || lapTicksValue < this.bestLapTicks) {
            this.bestLapTicks = lapTicksValue;
        }
        this.lapsCompleted++;
        this.lastGate = 0;
        this.nextGate = 1;
        this.lapStartTick = tick;
        if (this.lapsCompleted >= this.totalLaps) {
            this.finished = true;
            this.finishTick = tick;
            return Event.FINISH;
        }
        return Event.LAP;
    }

    /** 回位后重新计时这一圈（回位是"重新出发"，不能让玩家因为回位白捡/白亏圈时间）。 */
    public void onRecovered(long tick) {
        this.recoveries++;
        if (this.running && !this.finished) {
            this.lapStartTick = tick;
        }
    }

    // ---------- 进度 / 排名 ----------

    /**
     * 连续进度标量：{@code 已完成圈数 × 赛道长 + 本圈已跑的弧长}。
     *
     * <p>本圈已跑的弧长以"最后穿过的门"为基准，只在到下一个门的窗口内取值（弧长超窗说明
     * 绕到了赛道另一侧，会被夹住），起跑区落在起终点线之后的部分取负值，所以发车瞬间
     * 后排选手的进度确实低于前排。
     */
    private void recomputeProgress(double x, double z) {
        double arc = this.track.arcLengthAt(x, z);
        double rel = relativeArc(arc, this.lastGate);
        double span = this.spanTo(this.nextGate);
        double clamped = Math.max(Math.min(rel, span), -START_SLACK);
        this.progress = this.lapsCompleted * this.length
                + this.track.checkpoint(this.lastGate).progress() + clamped;
    }

    /** 弧长 {@code arc} 相对门 {@code fromGate} 的窗口内偏移（可为负 = 在门之前）。 */
    private double relativeArc(double arc, int fromGate) {
        double base = this.track.checkpoint(fromGate).progress();
        double rel = arc - base;
        if (rel < 0) {
            rel += this.length;
        }
        if (rel > this.length / 2.0) {
            rel -= this.length;
        }
        return rel;
    }

    /** 从 {@code lastGate} 到 {@code nextGate} 的弧长跨度（恒为正）。 */
    private double spanTo(int gate) {
        double span = this.track.checkpoint(gate).progress() - this.track.checkpoint(this.lastGate).progress();
        if (span <= 0) {
            span += this.length;
        }
        return span;
    }

    /**
     * 排名比较器（升序 = 名次靠前）。参考 MightyRacingMod 的"（圈数, Checkpoint）"字典序思想，
     * 但补上了它缺的东西：
     * <ol>
     *   <li>已完赛的永远排在未完赛的前面，按冲线 tick 升序；</li>
     *   <li>未完赛的按连续进度降序（含"当前门内距离"）；</li>
     *   <li>进度相同 → 距下一个门更近的在前；</li>
     *   <li>再相同 → 圈数多者在前，然后门序号大者在前。</li>
     * </ol>
     * 这样"同圈同门"的选手也能分出先后，不会像原版那样整体并列。
     */
    public static int compareForRanking(RaceProgressTracker a, RaceProgressTracker b) {
        if (a.finished != b.finished) {
            return a.finished ? -1 : 1;
        }
        if (a.finished && b.finished && a.finishTick != b.finishTick) {
            return Long.compare(a.finishTick, b.finishTick);
        }
        int c = Double.compare(b.progress, a.progress);
        if (c != 0) {
            return c;
        }
        c = Double.compare(a.distanceToNextGate, b.distanceToNextGate);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(b.lapsCompleted, a.lapsCompleted);
        if (c != 0) {
            return c;
        }
        return Integer.compare(b.nextGate, a.nextGate);
    }

    /** 门 {@code gate} 的平面对 (x, z) 的有符号距离：正 = 已过门，负 = 尚未到门。 */
    private static double sideOf(RaceTrack.Checkpoint gate, double x, double z) {
        return (x - gate.x()) * gate.dirX() + (z - gate.z()) * gate.dirZ();
    }

    /**
     * 穿越点是否落在门的有效范围内。
     *
     * <p>横向：把穿越点插值到门的平面上再量横向偏移，允许 {@link #GATE_LATERAL_SLACK} 的余量
     * （船体有宽度，贴边过门时玩家中心可能略微超出门框）。
     * 竖直：船被撞飞/弹起时 Y 会短暂离地，给 {@link #GATE_VERTICAL_SLACK} 的宽容度，
     * 但不允许从赛道下方很远的地方"穿"过门。
     */
    private boolean withinGateAt(RaceTrack.Checkpoint gate,
                                double px, double py, double pz,
                                double x, double y, double z,
                                double prevSide, double curSide) {
        double denom = curSide - prevSide;
        double t = denom <= 1.0e-9 ? 1.0 : (-prevSide) / denom;
        double cxp = px + t * (x - px);
        double czp = pz + t * (z - pz);
        double cyp = py + t * (y - py);
        double lateral = (cxp - gate.x()) * gate.normalX() + (czp - gate.z()) * gate.normalZ();
        if (Math.abs(lateral) > gate.halfWidth() + GATE_LATERAL_SLACK) {
            return false;
        }
        return Math.abs(cyp - (this.track.surfaceY() + 1.0)) <= GATE_VERTICAL_SLACK;
    }

    // ---------- 只读访问 ----------

    public RaceTrack track() {
        return this.track;
    }

    public int totalLaps() {
        return this.totalLaps;
    }

    /** 已完成圈数。 */
    public int lapsCompleted() {
        return this.lapsCompleted;
    }

    /** 显示用圈号（1-based，完赛后等于总圈数）。 */
    public int currentLap() {
        return Math.min(this.totalLaps, this.lapsCompleted + 1);
    }

    /** 下一个必须穿过的门索引（0 = 起终点线）。 */
    public int nextGate() {
        return this.nextGate;
    }

    /**
     * 最后穿过的门索引（0 = 起终点线；发车瞬间就是 0）。
     * 回位点 = 这个门的位置，所以 Session 需要它来算"传送回最近 Checkpoint"。
     */
    public int lastGate() {
        return this.lastGate;
    }

    /** 已通过的普通 Checkpoint 数量（用于 "CP 7/15" 显示）。 */
    public int checkpointsPassed() {
        int gateCount = this.track.checkpoints().size();
        if (this.nextGate == 0) {
            return gateCount - 1;
        }
        return Math.max(0, this.nextGate - 1);
    }

    public boolean running() {
        return this.running;
    }

    public boolean finished() {
        return this.finished;
    }

    public long startTick() {
        return this.startTick;
    }

    public long finishTick() {
        return this.finishTick;
    }

    /** 完赛用时（tick）；未完赛返回 -1。 */
    public long finishTicks() {
        return this.finished ? Math.max(0L, this.finishTick - this.startTick) : -1L;
    }

    /** 当前已跑时间（tick）。 */
    public long elapsedTicks(long now) {
        if (!this.running) {
            return 0L;
        }
        if (this.finished) {
            return this.finishTicks();
        }
        return Math.max(0L, now - this.startTick);
    }

    public long lastLapTicks() {
        return this.lastLapTicks;
    }

    public long bestLapTicks() {
        return this.bestLapTicks;
    }

    public List<Long> lapTicks() {
        return List.copyOf(this.lapTicks);
    }

    public double progress() {
        return this.progress;
    }

    public double distanceToNextGate() {
        return this.distanceToNextGate;
    }

    public int place() {
        return this.place;
    }

    public void setPlace(int place) {
        this.place = place;
    }

    public int recoveries() {
        return this.recoveries;
    }

    public int forcedGateAdvances() {
        return this.forcedGateAdvances;
    }

    public boolean wrongWay() {
        return this.wrongWay;
    }

    /**
     * 把 tick 数格式化成 {@code mm:ss.mmm}（1 tick = 50 ms）。
     * 用 server tick 而不是 wall clock：单调、不受系统时间调整影响，也不会出现
     * MightyRacingMod 那种"10 分钟以上被夹到 9:59:95"的边界问题。
     */
    public static String formatTicks(long ticks) {
        if (ticks < 0) {
            return "--:--.---";
        }
        long millis = ticks * 50L;
        long minutes = millis / 60000L;
        long seconds = (millis % 60000L) / 1000L;
        long ms = millis % 1000L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, ms);
    }
}
