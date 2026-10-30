package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Source-coordinate witnesses detect accidental cropping or using the obsolete Overworld race. */
final class RaftRacersRoundTest {
    private static JsonObject resource(String name, boolean gzip) throws Exception {
        try (var input = RaftRacersRoundTest.class.getResourceAsStream("/pvp/party/" + name)) {
            assertNotNull(input);
            try (var reader = new InputStreamReader(gzip ? new GZIPInputStream(input) : input, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test void retainsTheCompleteEndIceCourseAndNativeBubbleColumns() throws Exception {
        JsonObject map = resource("geometry/raft_racers.json.gz", true);
        assertEquals("[849852,47,-247]", map.getAsJsonObject("bounds").get("min").toString());
        assertEquals("[850164,117,135]", map.getAsJsonObject("bounds").get("max").toString());
        assertEquals("[313,71,383]", map.get("size").toString());
        JsonArray palette = map.getAsJsonArray("palette");
        Map<String, JsonObject> cells = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (var value : map.getAsJsonArray("blocks")) {
            JsonArray cell = value.getAsJsonArray();
            JsonObject state = palette.get(cell.get(3).getAsInt()).getAsJsonObject();
            String key = (cell.get(0).getAsInt() + 849852) + "," + (cell.get(1).getAsInt() + 47)
                    + "," + (cell.get(2).getAsInt() - 247);
            assertNull(cells.put(key, state));
            counts.merge(state.get("Name").getAsString(), 1, Integer::sum);
        }
        assertEquals(184625, cells.size());
        assertEquals(42131, counts.get("minecraft:packed_ice"));
        assertEquals(3431, counts.get("minecraft:blue_ice"));
        assertEquals(2445, counts.get("minecraft:bubble_column"));
        assertEquals(60, counts.get("minecraft:dead_horn_coral_block"));
        for (String position : new String[]{"849955,61,-247", "849932,53,135", "849852,53,17", "850164,54,-13"}) {
            assertEquals("minecraft:end_stone_bricks", cells.get(position).get("Name").getAsString());
        }
        assertEquals("minecraft:blue_ice", cells.get("850000,63,1").get("Name").getAsString());
        assertEquals("minecraft:dead_horn_coral_block", cells.get("850000,62,1").get("Name").getAsString());
        assertEquals("false", cells.get("849914,64,-198").getAsJsonObject("Properties").get("drag").getAsString());
        assertFalse(counts.containsKey("minecraft:command_block"));
    }

    @Test void auditsAllTwelveSourceFunctionsAndAllFourLiveBranches() throws Exception {
        JsonObject rules = resource("raft_racers_rules.json", false);
        assertEquals(405, rules.get("sourceId").getAsInt());
        assertEquals(12, rules.getAsJsonObject("sourceFunctionSha256").size());
        assertEquals("2f55a0fc9f8591ebb7e7844b46899ecc19685a9ff5802187e68d5f4d77ae78c4",
                rules.getAsJsonObject("sourceFunctionSha256").get("boat_race").getAsString());
        assertEquals("[\"boat_race_generate\",\"boat_race_generate2\",\"boat_race_generate3\"]", rules.get("obsoleteGenerators").toString());
        assertEquals(Set.of("11", "12", "13", "14"), rules.getAsJsonObject("routes").keySet());
        int[] expectedCounts = {15, 12, 13, 15};
        for (int path = 11; path <= 14; path++) {
            JsonArray operations = rules.getAsJsonObject("routes").getAsJsonArray(Integer.toString(path));
            assertEquals(expectedCounts[path - 11], operations.size());
            assertTrue(operations.toString().contains("\"hollow\":true"), "Original pregame start cage");
            assertEquals(path >= 13 ? 2 : 0, operations.asList().stream().filter(value ->
                    value.getAsJsonObject().get("op").getAsString().equals("clone")).count());
        }
        JsonObject firstArrow = rules.getAsJsonObject("routes").getAsJsonArray("11").get(3).getAsJsonObject();
        JsonObject secondArrow = rules.getAsJsonObject("routes").getAsJsonArray("11").get(4).getAsJsonObject();
        assertEquals("[850000.5,64.0,-73.0]", firstArrow.get("position").toString());
        assertEquals("[849957.5,75.0,73.5]", secondArrow.get("position").toString());
        JsonObject start = rules.getAsJsonArray("startOperations").get(0).getAsJsonObject();
        assertEquals("[849992,64,3]", start.get("from").toString());
        assertEquals("[850008,68,-2]", start.get("to").toString());
        assertEquals("minecraft:air", start.get("state").getAsString());
        assertEquals(3600, rules.getAsJsonObject("rules").get("ticks").getAsInt());
        assertEquals(2, rules.getAsJsonObject("rules").get("laps").getAsInt());
        assertEquals(200, rules.getAsJsonObject("rules").get("recallCooldownTicks").getAsInt());
    }
}
