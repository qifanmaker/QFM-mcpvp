package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Source-derived geometry/physics inputs, not a second generated race implementation. */
final class SkyFlyRoundTest {
    private static JsonObject read(String name) throws Exception {
        try (var input = SkyFlyRoundTest.class.getResourceAsStream("/pvp/party/geometry/" + name + ".json.gz")) {
            assertNotNull(input);
            try (var reader = new InputStreamReader(new GZIPInputStream(input), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static Map<String, String> cells(JsonObject geometry) {
        Map<String, String> result = new HashMap<>();
        JsonArray minimum = geometry.getAsJsonObject("bounds").getAsJsonArray("min");
        JsonArray palette = geometry.getAsJsonArray("palette");
        for (var value : geometry.getAsJsonArray("blocks")) {
            JsonArray cell = value.getAsJsonArray();
            String key = (cell.get(0).getAsInt() + minimum.get(0).getAsInt()) + ","
                    + (cell.get(1).getAsInt() + minimum.get(1).getAsInt()) + ","
                    + (cell.get(2).getAsInt() + minimum.get(2).getAsInt());
            assertNull(result.put(key, palette.get(cell.get(3).getAsInt()).getAsJsonObject().get("Name").getAsString()));
        }
        return result;
    }

    @Test void retainsTheWholeSpiralAndOriginalObservationSupports() throws Exception {
        JsonObject geometry = read("sky_fly_base");
        assertEquals("[-183,-47,-5]", geometry.getAsJsonObject("bounds").get("min").toString());
        assertEquals("[-22,122,139]", geometry.getAsJsonObject("bounds").get("max").toString());
        assertTrue(geometry.getAsJsonObject("replacements").isEmpty(), "Base must not claim chains from a different resource");
        Map<String, String> blocks = cells(geometry);
        assertTrue(blocks.size() > 400_000);
        for (int x : new int[]{-183, -165, -147, -129, -111, -93, -75, -57}) {
            assertEquals("minecraft:quartz_block", blocks.get(x + ",117,70"));
        }
        assertEquals("minecraft:quartz_block", blocks.get("-84,-47,42"));
        assertEquals("minecraft:quartz_block", blocks.get("-154,-47,112"));
        assertEquals("minecraft:quartz_block", blocks.get("-120,-47,76"));
        assertEquals("minecraft:dead_bubble_coral_block", blocks.get("-100,-41,75"));
        Set<Integer> scores = new HashSet<>();
        blocks.forEach((position, material) -> {
            if (position.split(",")[1].equals("-42")) {
                int score = SkyFlyRound.pointsForMaterial(material.substring("minecraft:".length()));
                if (score >= 0) scores.add(score);
            }
        });
        assertEquals(37, scores.size());
        assertEquals("minecraft:white_concrete", blocks.get("-24,-42,4"));
        assertEquals("minecraft:yellow_terracotta", blocks.get("-100,-42,75"));
        assertEquals(-1, SkyFlyRound.pointsForMaterial("quartz_block"), "Unrecognized material keeps the previous score");
    }

    @Test void ringTemplatesCarryAirAndProgressivelyNarrowNativeCollision() throws Exception {
        String[] names = {"large", "medium", "medium_chain", "small", "small_chain"};
        int[] holes = {15, 12, 8, 6, 3};
        for (int i = 0; i < names.length; i++) {
            JsonObject geometry = read("sky_fly_ring_" + names[i]);
            Map<String, String> blocks = cells(geometry);
            assertEquals(343, blocks.size(), "Full template includes air so the source clone opens quartz walls");
            JsonArray minimum = geometry.getAsJsonObject("bounds").getAsJsonArray("min");
            int x = minimum.get(0).getAsInt();
            long air = blocks.entrySet().stream().filter(entry -> entry.getKey().startsWith(x + ",")
                    && entry.getValue().equals("minecraft:air")).count();
            assertEquals(holes[i], air, "Original ring aperture cross section");
            assertTrue(blocks.containsValue("minecraft:iron_bars"));
            assertFalse(blocks.containsValue("minecraft:iron_chain"));
            assertEquals(names[i].endsWith("chain"), blocks.containsValue("minecraft:chain"));
            var replacements = geometry.getAsJsonObject("replacements");
            String chainRename = "minecraft:iron_chain -> minecraft:chain";
            assertEquals(blocks.values().stream().filter("minecraft:chain"::equals).count(),
                    replacements.has(chainRename) ? replacements.get(chainRename).getAsLong() : 0,
                    "Compatibility counts belong to this resource alone");
        }
    }

    @Test void keepsAllThirtySixOriginalTurnsDropAndCornerOffsets() {
        var low = SkyFlyRound.rings(bound -> 0);
        var high = SkyFlyRound.rings(bound -> bound - 1);
        assertEquals(36, low.size());
        int[] numbers = {1,8,9,16,20,22,27,31,34,36};
        int[][] expected = {{-39,111,-4},{-165,83,-4},{-182,79,13},{-165,51,132},
                {-93,35,131},{-64,27,121},{-75,7,32},{-146,-9,49},{-129,-21,96},{-100,-29,85}};
        for (int i = 0; i < numbers.length; i++) {
            var ring = low.get(numbers[i] - 1);
            assertArrayEquals(expected[i], new int[]{ring.x(), ring.y(), ring.z()}, "Source ring " + ring.number());
        }
        for (int i = 0; i < 36; i++) {
            var ring = low.get(i);
            assertEquals(111 - i * 4, ring.y());
            assertEquals(ring.minimumChoice(), ring.choice());
            assertEquals(ring.maximumChoice(), high.get(i).choice());
            for (var variant : new SkyFlyRound.Ring[]{ring, high.get(i)}) {
                assertTrue(variant.x() > -183 && variant.z() > -5);
                int maxX = variant.x() + (variant.direction() % 2 == 0 ? 6 : 0);
                int maxZ = variant.z() + (variant.direction() % 2 == 1 ? 6 : 0);
                assertTrue(maxX < -22 && maxZ < 139, "A random opening must not breach an exterior wall");
            }
        }
        assertEquals(0, low.get(7).template());
        assertEquals(1, low.get(8).template());
        assertEquals(1, low.get(18).template());
        assertEquals(2, low.get(19).template());
        assertEquals(3, low.get(30).template());
        assertEquals(4, low.get(35).template());
    }
}
