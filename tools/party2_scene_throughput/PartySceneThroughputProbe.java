package com.example.pvp.validation;

import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.MapBuildQueue;
import com.example.pvp.arena.party.PartyChunkTickets;
import com.example.pvp.arena.party.PartyMapBlueprint;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reason: a small synthetic run cannot expose the 13M-cell original course's
 * queue latency. Measure complete source terrain and cleanup in native ticks,
 * then independently visit every saved cell outside the timed flush interval.
 * This entrypoint is compiled only into a disposable validation snapshot.
 */
public final class PartySceneThroughputProbe implements ModInitializer {
    private final ArrayList<Map<String, Object>> checks = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final ArrayList<Double> flushMillis = new ArrayList<>();
    private final ArrayList<Double> tickMillis = new ArrayList<>();
    private final ArrayList<Map<String, Object>> phases = new ArrayList<>();
    private ArenaWorld world;
    private PartyMapBlueprint blueprint;
    private PartyChunkTickets tickets;
    private MapBuildQueue queue;
    private Method adaptiveFlush;
    private BlockPos translation;
    private String phase = "STARTING";
    private int budgetMillis, loadMillis, measuredTicks, drainTicks, chunkWaitTicks;
    private boolean adaptive, finished, waitChunks;
    private long started, tickStarted, phaseStarted;

    @Override public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            this.tickStarted = System.nanoTime();
            if ((this.phase.equals("BUILD") || this.phase.equals("CLEANUP")) && this.loadMillis > 0) {
                // Explicit CPU fixture: consume part of the real server tick
                // before END_SERVER_TICK. No sleeps or simulated queue clocks.
                long end = this.tickStarted + this.loadMillis * 1_000_000L;
                while (System.nanoTime() < end) Thread.onSpinWait();
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { this.setup(server); }
            catch (Throwable error) { this.finish(server, error); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (this.finished) return;
            try { this.tick(server); }
            catch (Throwable error) { this.finish(server, error); }
        });
    }

    private void setup(MinecraftServer server) throws Exception {
        JsonObject config = JsonParser.parseString(Files.readString(Path.of("party-scene-throughput-config.json"))).getAsJsonObject();
        String scene = config.get("scene").getAsString();
        String budget = config.get("budgetMillis").getAsString();
        this.adaptive = budget.equals("adaptive");
        this.budgetMillis = this.adaptive ? 0 : Integer.parseInt(budget);
        this.loadMillis = config.get("loadMillis").getAsInt();
        this.waitChunks = config.has("waitChunks") && config.get("waitChunks").getAsBoolean();
        if (this.adaptive) this.adaptiveFlush = MapBuildQueue.class.getMethod("flushAdaptive");
        this.started = System.nanoTime();
        long decodeStarted = System.nanoTime();
        this.blueprint = PartyMapBlueprint.load(scene);
        this.report.put("decodeMillis", millisSince(decodeStarted));
        this.world = ArenaWorldManager.get(server).createWorld();
        // The source datapack uses randomTickSpeed=0. The normal Party session
        // scopes that rule to its scene; this queue-only fixture has no session.
        this.world.getGameRules().get(GameRules.RANDOM_TICK_SPEED).set(0, server);
        this.translation = switch (scene) {
            case "rocket_launch" -> new BlockPos(150, 0, -32);
            case "take_flight" -> new BlockPos(393, 0, 728);
            default -> throw new IllegalArgumentException("Unsupported complete-scene benchmark: " + scene);
        };
        this.report.put("scene", scene);
        this.report.put("budgetMillis", budget);
        this.report.put("loadMillis", this.loadMillis);
        this.report.put("waitChunks", this.waitChunks);
        this.report.put("blocks", this.blueprint.blockCount());
        this.report.put("checks", this.checks);
        this.report.put("phases", this.phases);
        BlockPos minimum = this.blueprint.minimum().add(this.translation);
        BlockPos maximum = this.blueprint.maximum().add(this.translation);
        this.tickets = new PartyChunkTickets(this.world);
        this.tickets.retain(minimum, maximum);
        this.stage(false);
        this.phase = "BUILD";
    }

    private void stage(boolean cleanup) {
        long stageStarted = System.nanoTime();
        this.queue = new MapBuildQueue(this.world, this.blueprint.minimum().add(this.translation), this.blueprint.maximum().add(this.translation));
        this.blueprint.forEachRun(this.translation, (position, state, axis, length) ->
                this.queue.stageSnapshotRun(position, cleanup ? Blocks.AIR.getDefaultState() : state, axis, length));
        this.queue.close();
        this.check((cleanup ? "Cleanup" : "Build") + " stages every source cell", this.queue.totalStaged() == this.blueprint.blockCount(), this.queue.totalStaged());
        this.report.put(cleanup ? "cleanupStageMillis" : "buildStageMillis", millisSince(stageStarted));
        this.flushMillis.clear();
        this.tickMillis.clear();
        this.measuredTicks = 0;
        this.chunkWaitTicks = 0;
        this.phaseStarted = System.nanoTime();
    }

    private void tick(MinecraftServer server) throws Exception {
        if (this.phase.equals("BUILD") || this.phase.equals("CLEANUP")) {
            // Explicit option: let native ticket loading progress between ticks
            // before asking the map queue to write. Wall time includes waiting;
            // zero-duration samples keep queue CPU separate from chunk latency.
            if (this.waitChunks && !this.tickets.ready()) {
                this.chunkWaitTicks++;
                this.measuredTicks++;
                this.flushMillis.add(0.0);
                this.tickMillis.add(millisSince(this.tickStarted));
                if (this.measuredTicks % 200 == 0) System.out.println("PARTY_SCENE_THROUGHPUT " + this.phase + " waitingChunks " + this.chunkWaitTicks);
                return;
            }
            long before = System.nanoTime();
            boolean done;
            if (this.adaptive) {
                try { done = (boolean)this.adaptiveFlush.invoke(this.queue); }
                catch (InvocationTargetException error) { throw new IllegalStateException("Adaptive flush failed", error.getCause()); }
            } else done = this.queue.flush(this.budgetMillis);
            this.flushMillis.add(millisSince(before));
            this.tickMillis.add(millisSince(this.tickStarted));
            this.measuredTicks++;
            if (this.measuredTicks % 200 == 0) System.out.println("PARTY_SCENE_THROUGHPUT " + this.phase + " " + this.queue.placedCount() + "/" + this.queue.totalStaged());
            if (done && this.tickets.ready()) {
                var row = new LinkedHashMap<String, Object>();
                row.put("phase", this.phase);
                row.put("wallMillis", millisSince(this.phaseStarted));
                row.put("nativeTicks", this.measuredTicks);
                row.put("chunkWaitTicks", this.chunkWaitTicks);
                row.put("placed", this.queue.placedCount());
                row.put("flush", stats(this.flushMillis));
                row.put("tickUntilProbeEnd", stats(this.tickMillis));
                this.phases.add(row);
                this.check(this.phase + " places every source cell exactly once", this.queue.placedCount() == this.blueprint.blockCount(), this.queue.placedCount());
                this.phase = this.phase.equals("BUILD") ? "BUILD_DRAIN" : "CLEANUP_DRAIN";
                this.drainTicks = 0;
            }
        } else if (this.phase.equals("BUILD_DRAIN") || this.phase.equals("CLEANUP_DRAIN")) {
            if (++this.drainTicks < 20) return;
            boolean cleanup = this.phase.equals("CLEANUP_DRAIN");
            this.verify(cleanup);
            if (cleanup) this.finish(server, null);
            else { this.stage(true); this.phase = "CLEANUP"; }
        }
    }

    private void verify(boolean cleanup) {
        long verifyStarted = System.nanoTime();
        long[] visited = {0};
        this.blueprint.forEachRun(this.translation, (start, expected, axis, length) -> {
            BlockPos.Mutable position = new BlockPos.Mutable();
            for (int index = 0; index < length; index++) {
                position.set(start.getX() + (axis == net.minecraft.util.math.Direction.Axis.X ? index : 0),
                        start.getY() + (axis == net.minecraft.util.math.Direction.Axis.Y ? index : 0),
                        start.getZ() + (axis == net.minecraft.util.math.Direction.Axis.Z ? index : 0));
                var actual = this.world.getBlockState(position);
                if (cleanup ? !actual.isAir() : actual != expected)
                    throw new IllegalStateException((cleanup ? "Cleanup" : "Build") + " differs at " + position + ": " + actual + " expected " + (cleanup ? "AIR" : expected));
                visited[0]++;
            }
        });
        this.report.put(cleanup ? "cleanupVerifyMillis" : "buildVerifyMillis", millisSince(verifyStarted));
        this.check((cleanup ? "Cleanup" : "Build") + " independently checks every real native block after20 drain ticks", visited[0] == this.blueprint.blockCount(), visited[0]);
    }

    private static Map<String, Object> stats(ArrayList<Double> values) {
        ArrayList<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double total = values.stream().mapToDouble(Double::doubleValue).sum();
        return Map.of("count", values.size(), "totalMillis", total, "meanMillis", total / values.size(),
                "p95Millis", sorted.get(Math.min(sorted.size() - 1, (int)Math.ceil(sorted.size() * .95) - 1)),
                "maxMillis", sorted.getLast(), "above50Millis", values.stream().filter(value -> value > 50).count());
    }

    private void check(String name, boolean pass, Object actual) {
        this.checks.add(Map.of("name", name, "pass", pass, "actual", actual));
        if (!pass) throw new IllegalStateException(name + ": " + actual);
    }
    private static double millisSince(long since) { return (System.nanoTime() - since) / 1_000_000.0; }

    private void finish(MinecraftServer server, Throwable error) {
        if (this.finished) return;
        this.finished = true;
        if (this.tickets != null) this.tickets.close();
        this.report.put("ok", error == null);
        this.report.put("totalMillisIncludingVerification", millisSince(this.started));
        if (error != null) { this.report.put("error", error.toString()); error.printStackTrace(); }
        try { Files.writeString(Path.of("party-scene-throughput-report.json"), new Gson().toJson(this.report) + "\n"); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        server.stop(false);
    }
}
