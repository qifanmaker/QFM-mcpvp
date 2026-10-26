package com.example.pvp.mygo;

/**
 * A bounded server-side lease for click inference.
 *
 * Vanilla 1.21.1 does not send an explicit mouse-button release packet for an
 * air/entity target.  A long lease based on a weapon's cycle therefore makes
 * a player fire after they have already released the mouse.  The lease is
 * deliberately short and fixed: a new left-click packet refreshes it, while
 * an absent packet expires it independently of weapon RPM.
 */
public final class MyGoInputLease {
    public static final int DEFAULT_TICKS = 4;

    private final int duration;
    private int expiresAt = Integer.MIN_VALUE;

    public MyGoInputLease() {
        this(DEFAULT_TICKS);
    }

    public MyGoInputLease(int duration) {
        if (duration < 1) throw new IllegalArgumentException("duration must be positive");
        this.duration = duration;
    }

    /** Start or refresh the lease at the server tick carrying a click packet. */
    public void refresh(int tick) {
        this.expiresAt = tick + this.duration;
    }

    public boolean active(int tick) {
        return tick < this.expiresAt;
    }

    public int expiresAt() {
        return this.expiresAt;
    }

    public int duration() {
        return this.duration;
    }

    public void clear() {
        this.expiresAt = Integer.MIN_VALUE;
    }
}
