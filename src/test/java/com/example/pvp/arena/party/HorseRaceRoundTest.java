package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Original source witnesses reject a flat/overworld replacement and accidental route collapse. */
final class HorseRaceRoundTest {
    private static JsonObject rules() throws Exception {
        try (var input = HorseRaceRoundTest.class.getResourceAsStream("/pvp/party/horse_race_rules.json")) {
            assertNotNull(input);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void keepsAllThreeRoutesAndTheOriginalAlternateCheckpointRules() throws Exception {
        JsonObject root = rules();
        assertEquals(416, root.get("sourceId").getAsInt());
        assertEquals(13, root.getAsJsonObject("sourceFunctionSha256").size());
        assertTrue(root.getAsJsonObject("sourceFunctions").get("horse_race1").getAsString().contains("in the_nether run function mcp:horse_race2"));
        JsonObject routes = root.getAsJsonObject("routes");
        assertEquals(Set.of("1", "2", "3"), routes.keySet());
        int[] operationCounts = {13, 22, 16}, detectorCounts = {13, 11, 9};
        for (int number = 1; number <= 3; number++) {
            JsonObject route = routes.getAsJsonObject(Integer.toString(number));
            assertEquals(number == 3 ? 3500 : 4200, route.get("ticks").getAsInt());
            assertEquals(number == 3 ? 10 : 12, route.get("unitsPerLap").getAsInt());
            assertEquals(operationCounts[number - 1], route.getAsJsonArray("operations").size());
            assertEquals(detectorCounts[number - 1], route.getAsJsonArray("checkpointsInSourceOrder").size());
            assertEquals(number == 3 ? 10 : 12, route.getAsJsonObject("respawns").size());
            assertEquals("[50.5,35.0,-1101.5]", route.getAsJsonObject("respawns").get("0").toString());
        }
        JsonArray high = routes.getAsJsonObject("1").getAsJsonArray("checkpointsInSourceOrder");
        assertEquals(10, high.get(0).getAsJsonObject().get("target").getAsInt());
        assertEquals("[10.0,41.0,-1065.0]", high.get(0).getAsJsonObject().get("center").toString());
        assertEquals(11, high.get(1).getAsJsonObject().get("target").getAsInt());
        assertEquals("[47.0,35.0,-1070.0]", high.get(1).getAsJsonObject().get("center").toString());
        JsonObject skip = high.get(4).getAsJsonObject();
        assertEquals(3, skip.get("target").getAsInt());
        assertEquals("[1,2]", skip.get("allowed").toString());
        assertEquals("[2,2]", skip.get("soundAllowed").toString(), "The source omits this sound when checkpoint two was skipped");
        JsonObject horse = root.getAsJsonObject("nativeHorse");
        assertEquals(.23, horse.get("movementSpeed").getAsDouble());
        assertEquals(.85, horse.get("jumpStrength").getAsDouble());
        assertTrue(horse.get("saddled").getAsBoolean());
        assertEquals(2, root.getAsJsonObject("finish").get("laps").getAsInt());
    }

    @Test void fullNetherSceneRetainsEveryGoldMarkerAndNaturalLavaWithoutHugeDomAllocation() throws Exception {
        Set<String> witnesses = Set.of("-6,62,-1147", "121,51,-1076", "58,70,-1053", "50,34,-1102",
                "31,41,-1149", "16,52,-1162", "94,37,-1125", "101,36,-1091", "73,43,-1032");
        Set<String> found = new HashSet<>();
        // A Y18 crop removed native gravel supports and all ten lower owner
        // signs fell off. Keep the original supports down to the bedrock floor.
        Map<String, String> foundation = Map.of("58,14,-1104", "minecraft:netherrack", "34,0,-1028", "minecraft:bedrock");
        Set<String> supported = new HashSet<>();
        Map<String, Integer> counts = new HashMap<>();
        JsonArray palette = null;
        int[] minimum = null;
        int cells = 0;
        try (var raw = HorseRaceRoundTest.class.getResourceAsStream("/pvp/party/geometry/horse_race.json.gz")) {
            assertNotNull(raw);
            try (JsonReader input = new JsonReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
                input.beginObject();
                while (input.hasNext()) switch (input.nextName()) {
                    case "bounds" -> {
                        JsonObject bounds = JsonParser.parseReader(input).getAsJsonObject();
                        assertEquals("[-32,0,-1190]", bounds.get("min").toString());
                        assertEquals("[145,96,-1008]", bounds.get("max").toString());
                        minimum = new int[]{-32,0,-1190};
                    }
                    case "palette" -> palette = JsonParser.parseReader(input).getAsJsonArray();
                    case "blocks" -> {
                        assertNotNull(palette); assertNotNull(minimum);
                        input.beginArray();
                        while (input.hasNext()) {
                            input.beginArray();
                            int x = input.nextInt() + minimum[0], y = input.nextInt() + minimum[1], z = input.nextInt() + minimum[2];
                            String block = palette.get(input.nextInt()).getAsJsonObject().get("Name").getAsString();
                            input.endArray();
                            counts.merge(block, 1, Integer::sum); cells++;
                            if (block.equals("minecraft:gold_block") && witnesses.contains(x + "," + y + "," + z)) found.add(x + "," + y + "," + z);
                            String point = x + "," + y + "," + z;
                            if (block.equals(foundation.get(point))) supported.add(point);
                        }
                        input.endArray();
                    }
                    default -> input.skipValue();
                }
                input.endObject();
            }
        }
        assertEquals(2_241_582, cells);
        assertEquals(202, counts.get("minecraft:gold_block"));
        assertEquals(53_352, counts.get("minecraft:nether_bricks"));
        assertEquals(238_835, counts.get("minecraft:lava"));
        assertEquals(97_665, counts.get("minecraft:bedrock"));
        assertEquals(witnesses, found);
        assertEquals(foundation.keySet(), supported);
        assertFalse(counts.containsKey("minecraft:command_block"));
    }

    @Test void originalNavigationTextIsCopiedWithEveryRouteInsteadOfLeavingBlankSigns() throws Exception {
        JsonObject root = rules();
        assertEquals(86, root.getAsJsonObject("navigation").get("sourceSignCount").getAsInt());
        int[] counts = {86, 86, 87};
        for (int route = 1; route <= 3; route++) {
            JsonArray signs = root.getAsJsonObject("routes").getAsJsonObject(Integer.toString(route)).getAsJsonArray("signs");
            assertEquals(counts[route - 1], signs.size());
            Set<String> locations = new HashSet<>();
            for (var entry : signs) {
                JsonObject sign = entry.getAsJsonObject();
                assertTrue(locations.add(sign.get("position").toString()));
                assertTrue(Set.of("position", "front", "back", "waxed", "ownerSeat").containsAll(sign.keySet()));
                for (String face : new String[]{"front", "back"}) {
                    JsonObject text = sign.getAsJsonObject(face);
                    assertEquals(Set.of("messages", "color", "glowing"), text.keySet());
                    assertEquals(4, text.getAsJsonArray("messages").size());
                    text.getAsJsonArray("messages").forEach(line -> assertTrue(line.isJsonPrimitive()));
                }
            }
            String witness = route == 2 ? "[98,38,-1060]" : "[50,61,-1066]";
            String arrow = route == 2 ? "----->" : "<-----";
            JsonObject copied = signs.asList().stream().map(e -> e.getAsJsonObject())
                    .filter(s -> s.get("position").toString().equals(witness)).findFirst().orElseThrow();
            assertEquals(arrow, copied.getAsJsonObject("front").getAsJsonArray("messages").get(1).getAsString());
            assertTrue(copied.getAsJsonObject("front").get("glowing").getAsBoolean());
        }
    }
}
