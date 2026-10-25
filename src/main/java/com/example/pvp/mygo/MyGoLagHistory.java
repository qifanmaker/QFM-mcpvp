package com.example.pvp.mygo;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reason: testing only the latest server box penalized delayed shots at moving
 * players. Keep a bounded 200 ms pose history rather than trusting a client
 * position or arbitrary timestamp. Review: half RTT is capped at four ticks,
 * teleport/world discontinuities clear history, and blocked historical poses
 * fall back to the current pose. BulletTrace separately rejects rewinds through
 * current cover, so old open sightlines cannot bypass a newly reached wall.
 */
public final class MyGoLagHistory {
    public static final int MAX_REWIND_TICKS = 4;
    public record Sample(int tick, RegistryKey<World> world, MyGoHitboxes.Pose pose) { }
    private final Map<UUID, ArrayDeque<Sample>> samples = new HashMap<>();

    public void capture(int tick, Collection<ServerPlayerEntity> players) {
        Set<UUID> present = new HashSet<>();
        for (ServerPlayerEntity player : players) {
            present.add(player.getUuid());
            ArrayDeque<Sample> history = this.samples.computeIfAbsent(player.getUuid(), ignored -> new ArrayDeque<>());
            MyGoHitboxes.Pose pose = MyGoHitboxes.snapshot(player);
            if (!history.isEmpty()) {
                Sample last = history.getLast();
                if (last.world() != player.getWorld().getRegistryKey()
                        || discontinuity(last.pose().bounds(), pose.bounds()) || tick < last.tick()) history.clear();
                else if (last.tick() == tick) history.removeLast();
            }
            history.addLast(new Sample(tick, player.getWorld().getRegistryKey(), pose));
            while (!history.isEmpty() && history.getFirst().tick() < tick - MAX_REWIND_TICKS) history.removeFirst();
        }
        this.samples.keySet().retainAll(present);
    }

    public MyGoHitboxes.Pose poseFor(ServerPlayerEntity target, ServerPlayerEntity shooter, int tick) {
        MyGoHitboxes.Pose current = MyGoHitboxes.snapshot(target);
        ArrayDeque<Sample> history = this.samples.get(target.getUuid());
        if (history == null || history.isEmpty()) return current;
        int desired = tick - rewindTicks(shooter.networkHandler.getLatency());
        Sample best = null;
        for (Sample sample : history) {
            if (sample.tick() <= desired) best = sample;
            else if (best == null) { best = sample; break; }
            else break;
        }
        if (best == null || best.world() != target.getWorld().getRegistryKey()
                || tick - best.tick() > MAX_REWIND_TICKS || discontinuity(best.pose().bounds(), current.bounds())) return current;
        Box test = best.pose().bounds().contract(.01);
        if (target.getServerWorld().getBlockCollisions(target, test).iterator().hasNext()) return current;
        return best.pose();
    }

    /** Half round-trip latency at 50 ms/tick; never permit negative or unbounded rewind. */
    public static int rewindTicks(int latencyMilliseconds) {
        return Math.clamp(Math.round(Math.max(0, latencyMilliseconds) / 100.0), 0, MAX_REWIND_TICKS);
    }

    public static boolean discontinuity(Box before, Box after) {
        return before.getCenter().squaredDistanceTo(after.getCenter()) > 4;
    }

    public void clear() { this.samples.clear(); }
}
