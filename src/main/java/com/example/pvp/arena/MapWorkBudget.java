package com.example.pvp.arena;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shares spare server tick time between map construction and cleanup queues.
 * A fixed four milliseconds leaves large source maps waiting even on an idle
 * server; independent larger budgets would instead multiply with concurrent
 * matches. All queues share a 20 ms work budget with a deadline 40 ms from
 * the tick's start, leaving headroom in a 50 ms tick. Individual native writes
 * may overrun; their full elapsed time is charged before granting more work.
 * Call only from the server thread.
 */
public final class MapWorkBudget {
    private static final long MAX_WORK_NANOS = 20_000_000L;
    private static final long TICK_DEADLINE_NANOS = 40_000_000L;

    private final Map<Object, QueueUsage> queues = new IdentityHashMap<>();
    private boolean initialized;
    private long tick;
    private long tickStartedNanos;
    private long poolNanos = -1;
    private long spentNanos;

    /** Capture the beginning of the native server tick, before worlds tick. */
    public void beginTick(long tick, long startedNanos) {
        if (this.initialized && this.tick == tick) return;
        this.initialized = true;
        this.tick = tick;
        this.tickStartedNanos = startedNanos;
        this.poolNanos = -1;
        this.spentNanos = 0;
        this.queues.values().removeIf(usage -> usage.lastSeenTick != tick - 1);
        for (QueueUsage usage : this.queues.values()) usage.spentNanos = 0;
    }

    /**
     * Registers the queue even when no time remains, so it receives a share on
     * the next tick. The caller must not start a flush when this returns zero.
     * New queues can exhaust their first tick's pool before all peers are known;
     * on following ticks every still-active queue has its own equal share.
     */
    public long allowance(Object queue, long nowNanos) {
        Objects.requireNonNull(queue, "queue");
        if (!this.initialized) return 0;
        QueueUsage usage = this.queues.computeIfAbsent(queue, ignored -> new QueueUsage());
        usage.lastSeenTick = this.tick;
        long tickRemaining = Math.max(0, TICK_DEADLINE_NANOS - (nowNanos - this.tickStartedNanos));
        if (this.poolNanos < 0) this.poolNanos = Math.min(MAX_WORK_NANOS, tickRemaining);
        long remaining = Math.max(0, this.poolNanos - this.spentNanos);
        long share = Math.max(0, this.poolNanos / this.queues.size() - usage.spentNanos);
        return Math.min(tickRemaining, Math.min(remaining, share));
    }

    /** Charge actual elapsed time, including any native block-operation overrun. */
    public void spent(Object queue, long elapsedNanos) {
        if (elapsedNanos < 0) throw new IllegalArgumentException("Negative map work duration");
        QueueUsage usage = this.queues.get(Objects.requireNonNull(queue, "queue"));
        if (usage == null || usage.lastSeenTick != this.tick) {
            throw new IllegalStateException("Request a map work allowance before charging it");
        }
        this.spentNanos = saturatingAdd(this.spentNanos, elapsedNanos);
        usage.spentNanos = saturatingAdd(usage.spentNanos, elapsedNanos);
    }

    /** Release a finished or cancelled queue immediately, without retaining it. */
    public void completed(Object queue) {
        this.queues.remove(Objects.requireNonNull(queue, "queue"));
    }

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static final class QueueUsage {
        long lastSeenTick;
        long spentNanos;
    }
}
