package com.example.pvp.mygo;

/**
 * Fractional-tick firing deadline.
 *
 * Minecraft schedules the session at 20 Hz, but CS:GO intervals such as
 * 0.07 seconds are 1.4 Minecraft ticks.  Repeatedly rounding that interval
 * turns an MP9 into a 600 RPM weapon.  This helper keeps the deadline as a
 * double and advances from the previous deadline, producing alternating one
 * and two tick waits while preserving the long-run rate.
 */
public final class MyGoPreciseCadence {
    private static final double EPSILON = 1.0e-9;
    private double nextAt = Double.POSITIVE_INFINITY;

    public boolean ready(double tick) {
        return tick + EPSILON >= this.nextAt;
    }

    /** Make the first shot available immediately. */
    public void armNow(double tick) {
        this.nextAt = tick;
    }

    /**
     * Record a shot. The next deadline is advanced from the old deadline,
     * rather than recomputed from the current integer tick. If a packet was
     * delayed for several ticks, skip missed deadlines instead of emitting a
     * burst of backlogged bullets.
     */
    public void fired(double tick, double intervalTicks) {
        if (!(intervalTicks > 0.0) || !Double.isFinite(intervalTicks))
            throw new IllegalArgumentException("intervalTicks must be finite and positive");
        if (!Double.isFinite(this.nextAt)) {
            this.nextAt = tick + intervalTicks;
            return;
        }
        double candidate = this.nextAt + intervalTicks;
        while (candidate <= tick + EPSILON) candidate += intervalTicks;
        this.nextAt = candidate;
    }

    public double nextAt() {
        return this.nextAt;
    }

    public void reset() {
        this.nextAt = Double.POSITIVE_INFINITY;
    }
}
