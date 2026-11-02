package com.example.pvp.validation;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchState;
import com.example.pvp.match.MatchType;
import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-snapshot-only fixture: a completed slow build used to lose its watchdog
 * allowance and be cancelled before COUNTDOWN could reach ACTIVE. Age both
 * elapsed clocks equally, preserving actual geometry, countdown and lifecycle.
 */
public final class MapBuildLifecycleProbe implements ModInitializer {
    private static final Gson JSON = new Gson();
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final List<Map<String, Object>> events = new ArrayList<>();
    private Match watched;
    private boolean injected, active, complete, failed;
    private int injectionServerTick, initialRemaining;

    @Override public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (this.complete || this.failed || PvPMod.MATCH == null) return;
            try { this.observe(server); }
            catch (Throwable failure) {
                this.failed = true;
                failure.printStackTrace();
                this.write("failed", failure.toString());
            }
        });
    }

    private void observe(MinecraftServer server) throws Exception {
        if (this.watched == null) {
            this.watched = PvPMod.MATCH.getMatches().stream()
                    .filter(match -> match.getType() == MatchType.SKYWARS
                            && match.players().stream().map(player -> player.getName().getString()).toList()
                            .containsAll(List.of("MyGoCheckA", "MyGoCheckB")))
                    .findFirst().orElse(null);
        }
        if (this.watched == null) return;
        Match match = this.watched;
        if (!this.injected && match.getState() == MatchState.COUNTDOWN && bool(match, "setupFinished")) {
            this.check("A real SkyWars build completed before injecting elapsed clocks", field(match, "buildQueue").get(match) == null);
            this.initialRemaining = integer(match, "countdownTicks");
            this.check("The real post-build countdown still has time to run", this.initialRemaining > 0);
            int beforeTicks = integer(match, "ticks"), beforeBuild = integer(match, "buildTicks");
            field(match, "ticks").setInt(match, beforeTicks + 400);
            field(match, "buildTicks").setInt(match, beforeBuild + 400);
            this.injectionServerTick = server.getTicks();
            this.injected = true;
            this.check("The fixture exceeds the former post-build watchdog threshold",
                    integer(match, "ticks") > integer(match, "initialCountdownTicks") + 200);
            this.check("Aging both clocks leaves elapsed countdown time unchanged",
                    integer(match, "ticks") - integer(match, "buildTicks") == beforeTicks - beforeBuild);
            this.event(server, "clockFixture", "Add400 to ticks and buildTicks only after actual build completion; equivalent slow-build clock fixture, not400 real ticks of waiting.");
        }
        if (this.injected && !this.active && match.getState() == MatchState.ACTIVE) {
            this.active = true;
            this.check("The complete real countdown reaches ACTIVE after the aged build",
                    server.getTicks() - this.injectionServerTick >= this.initialRemaining);
            this.check("ACTIVATION retains the completed map and correct active clock",
                    bool(match, "setupFinished") && field(match, "buildQueue").get(match) == null
                            && integer(match, "activeStartTick") == integer(match, "ticks"));
            this.check("Both real protocol clients entered the arena", match.players().stream()
                    .allMatch(player -> player.getServerWorld().getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY));
            this.event(server, "active", "No state, countdown, queue or map mutation was used to start gameplay.");
        }
        if (this.injected && match.getState() == MatchState.ENDED && !this.active) {
            throw new IllegalStateException("Match ended before reaching ACTIVE after the slow-build clock fixture");
        }
        if (this.active && match.getState() == MatchState.ENDED
                && !PvPMod.MATCH.getMatches().contains(match)
                && match.players().stream().allMatch(player -> player.getServerWorld().getRegistryKey() == World.OVERWORLD)) {
            this.check("Normal finish detaches the match and restores both players to the lobby", true);
            this.complete = true;
            this.event(server, "complete", "Protocol client B left with /hub; the real winner/return lifecycle completed.");
        }
    }

    private void check(String name, boolean pass) {
        this.checks.add(Map.of("name", name, "pass", pass));
        if (!pass) throw new IllegalStateException(name);
    }

    private void event(MinecraftServer server, String phase, String detail) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phase", phase); row.put("detail", detail); row.put("serverTick", server.getTicks());
        row.put("state", this.watched.getState().name()); row.put("ticks", integer(this.watched, "ticks"));
        row.put("buildTicks", integer(this.watched, "buildTicks"));
        row.put("countdownTicks", integer(this.watched, "countdownTicks"));
        row.put("initialCountdownTicks", integer(this.watched, "initialCountdownTicks"));
        this.events.add(row); this.write(phase, null);
    }

    private void write(String phase, String error) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("phase", phase); report.put("ok", !this.failed); report.put("complete", this.complete);
        report.put("fixture", "Explicit+400 elapsed-build clock fixture after a real completed build; headless native lifecycle, not a rendered client test.");
        report.put("checks", this.checks); report.put("events", this.events);
        if (error != null) report.put("error", error);
        String data = JSON.toJson(report);
        System.out.println("MAP_BUILD_LIFECYCLE " + data);
        try { Files.writeString(Path.of("map-build-lifecycle-native.json"), data + "\n"); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static Field field(Match match, String name) throws ReflectiveOperationException {
        Field field = match.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static int integer(Match match, String name) throws ReflectiveOperationException { return field(match, name).getInt(match); }
    private static boolean bool(Match match, String name) throws ReflectiveOperationException { return field(match, name).getBoolean(match); }
}
