package com.example.pvp.mygo;

/**
 * MyGO 的脚步由实际位移驱动，避免原版方块脚步在潜行/慢速时泄露位置。
 * 按 Minecraft 的移动速度适配 CS 的跑动、静步、起跳和落地；传送必须清空步距。
 */
public final class MyGoFootsteps {
    public enum Cue { NONE, STEP, JUMP, LAND }
    private int lastTick = -1, nextStep;
    private double x, y, z, peak, distance;
    private boolean grounded, wet, climbing;

    public void reset() { this.lastTick = -1; this.distance = 0; }

    public Cue update(int tick, double x, double y, double z, boolean onGround,
                      boolean sneaking, boolean sprinting, boolean inWater) {
        return update(tick, x, y, z, onGround, sneaking, sprinting, inWater, false);
    }

    public Cue update(int tick, double x, double y, double z, boolean onGround,
                      boolean sneaking, boolean sprinting, boolean inWater, boolean climbing) {
        int elapsed = tick - this.lastTick;
        double horizontal = Math.hypot(x - this.x, z - this.z);
        double rise = y - this.y;
        boolean supported = onGround || inWater || climbing;
        if (this.lastTick < 0 || elapsed <= 0 || elapsed > 5 || horizontal > 2.5 || Math.abs(rise) > 3) {
            this.lastTick = tick; this.x = x; this.y = y; this.z = z;
            this.grounded = supported; this.wet = inWater; this.climbing = climbing; this.peak = y; this.distance = 0;
            this.nextStep = tick;
            return Cue.NONE;
        }
        boolean wasGrounded = this.grounded, wasWet = this.wet, wasClimbing = this.climbing;
        this.lastTick = tick; this.x = x; this.y = y; this.z = z;
        this.grounded = supported; this.wet = inWater; this.climbing = climbing;
        this.peak = Math.max(this.peak, y);
        if (inWater && !wasWet && this.peak - y > .7) {
            this.distance = 0; this.peak = y; this.nextStep = tick + 7;
            return Cue.LAND;
        }
        if (!supported) {
            this.distance = 0;
            if (wasGrounded && !wasClimbing && rise > .05 && !inWater) { this.nextStep = tick + 7; return Cue.JUMP; }
            return Cue.NONE;
        }
        double fall = this.peak - y;
        this.peak = y;
        if (!wasGrounded) {
            this.distance = 0; this.nextStep = tick + 7;
            return fall > .7 ? Cue.LAND : Cue.NONE;
        }
        // 正常 W / 疾跑可听见，潜行和低于正常移速约一半的静步不产生脚步。
        // 梯子/藤蔓主要产生竖直位移，不能因为屏蔽原版梯子声而让快速攀爬完全静音。
        double travel = climbing ? Math.hypot(horizontal, rise) : horizontal;
        if (sneaking || travel / elapsed < (inWater || climbing ? .025 : .11)) { this.distance = 0; return Cue.NONE; }
        this.distance += travel;
        double stride = inWater || climbing ? .65 : 1.2;
        if (tick < this.nextStep || this.distance < stride) return Cue.NONE;
        this.distance %= stride; this.nextStep = tick + (inWater || climbing ? 9 : sprinting ? 6 : 7);
        return Cue.STEP;
    }
}
