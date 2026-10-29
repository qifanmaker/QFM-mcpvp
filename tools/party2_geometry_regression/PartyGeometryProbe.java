package com.example.pvp.validation;

import com.example.pvp.arena.party.PartyMapBlueprint;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Test-only snapshot entrypoint: the streaming decoder must preserve every
 * original geometry cell and blockstate, including the million-cell cave.
 * Python computes the reference independently from the source JSON. This code
 * runs with Fabric's real registries and access wideners, never in the shipped mod.
 */
public final class PartyGeometryProbe implements ModInitializer {
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Map<String, Object> report = new LinkedHashMap<>();
            var checks = new ArrayList<Map<String, Object>>();
            report.put("checks", checks);
            try {
                var expected = JsonParser.parseString(Files.readString(Path.of("party-geometry-expected.json"))).getAsJsonArray();
                BlockPos offset = new BlockPos(601, 47, -777);
                Map<BlockState, String> stateText = new IdentityHashMap<>();
                for (var entry : expected) {
                    var reference = entry.getAsJsonObject();
                    String name = reference.get("name").getAsString();
                    long started = System.nanoTime();
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long[] count = {0};
                    PartyMapBlueprint.load(name).forEachTranslated(offset, (position, state) -> {
                        String suffix = stateText.computeIfAbsent(state, value -> {
                            var properties = new TreeMap<String, String>();
                            value.getEntries().forEach((key, item) -> properties.put(key.getName(), canonical(key, item)));
                            return "|" + Registries.BLOCK.getId(value.getBlock()) + "|"
                                    + String.join(",", properties.entrySet().stream().map(property -> property.getKey() + "=" + property.getValue()).toList()) + "\n";
                        });
                        digest.update((position.getX() + "," + position.getY() + "," + position.getZ() + suffix).getBytes(StandardCharsets.UTF_8));
                        count[0]++;
                    });
                    String actual = HexFormat.of().formatHex(digest.digest());
                    boolean pass = count[0] == reference.get("count").getAsLong()
                            && actual.equals(reference.get("sha256").getAsString());
                    var row = new LinkedHashMap<String, Object>();
                    row.put("name", name); row.put("pass", pass); row.put("count", count[0]);
                    row.put("sha256", actual); row.put("decodeAndVerifyMillis", (System.nanoTime() - started) / 1_000_000);
                    checks.add(row);
                    if (!pass) throw new IllegalStateException("Geometry projection differs from original JSON: " + name);
                }
                report.put("ok", true);
            } catch (Exception error) {
                report.put("ok", false); report.put("error", error.toString());
            }
            try { Files.writeString(Path.of("party-geometry-report.json"), new Gson().toJson(report) + "\n"); }
            catch (Exception error) { throw new IllegalStateException("Cannot save geometry evidence", error); }
            server.stop(false);
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String canonical(Property property, Comparable value) {
        return property.name(value);
    }
}
