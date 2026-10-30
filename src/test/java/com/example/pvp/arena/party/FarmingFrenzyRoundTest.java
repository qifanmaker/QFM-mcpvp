package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Protect the source's native crop scoring, whole island and finite growth window. */
final class FarmingFrenzyRoundTest {
    private static JsonObject resource(String name, boolean compressed) throws Exception {
        try (var input = FarmingFrenzyRoundTest.class.getResourceAsStream("/pvp/party/" + name)) {
            assertNotNull(input, name);
            try (var reader = new InputStreamReader(compressed ? new GZIPInputStream(input) : input, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test void eightMinedCropsKeepTheirMaterialPointsAndExactGiveConditions() {
        int[] points = {2, 6, 1, 2, 6, 2, 1, 4};
        for (int crop = 0; crop < points.length; crop++) {
            for (int mined = 0; mined <= 5; mined++) {
                assertEquals(points[crop] * mined, FarmingFrenzyRound.harvestPoints(crop, mined, 0));
                assertEquals(points[crop] * mined * 2, FarmingFrenzyRound.harvestPoints(crop, mined, crop + 1));
                int maximumGift = crop == 1 || crop == 4 || crop == 7 ? 2 : 4;
                assertEquals(mined == 1 || mined == 2 || mined == 4 && maximumGift == 4 ? mined : 0,
                        FarmingFrenzyRound.giftCount(crop, mined, 0));
                int doubled = mined * 2;
                assertEquals(doubled == 2 || doubled == 4 && maximumGift == 4 ? doubled : 0,
                        FarmingFrenzyRound.giftCount(crop, mined, crop + 1));
            }
        }
        // A batch of three is still scored, but the original exact selectors give no inventory reward.
        assertEquals(36, FarmingFrenzyRound.harvestPoints(1, 3, 2));
        assertEquals(0, FarmingFrenzyRound.giftCount(1, 3, 2));
    }

    @Test void introGrowthUsesSixtyActualTicksAndFreezesTheRemainingCountdown() {
        for (int players = 2; players <= 10; players++) {
            assertEquals(0, FarmingFrenzyRound.introSpeed(0, players));
            for (int tick = 1; tick <= 160; tick++) {
                assertEquals(tick <= 60 ? Math.max(1800, 450 * players) : 0,
                        FarmingFrenzyRound.introSpeed(tick, players));
            }
        }
    }

    @Test void completeFarmRetainsBarnRidgesBannersGravelSupportsAndLowerAirClone() throws Exception {
        JsonObject farm = resource("geometry/farming_frenzy.json.gz", true);
        assertEquals("[-191,27,-290]", farm.getAsJsonObject("bounds").get("min").toString());
        assertEquals("[-142,69,-251]", farm.getAsJsonObject("bounds").get("max").toString());
        assertEquals("[50,43,40]", farm.get("size").toString());
        assertEquals(9606, farm.getAsJsonArray("blocks").size());
        assertTrue(farm.getAsJsonObject("omitted").isEmpty());
        Map<String, Integer> counts = counts(farm);
        assertEquals(13, counts.get("minecraft:lime_wall_banner"));
        assertEquals(19, counts.get("minecraft:gravel"));
        assertEquals(8, counts.get("minecraft:ladder"));
        assertEquals(28, counts.get("minecraft:soul_sand"));
        assertEquals(12, counts.get("minecraft:jungle_log"));
        assertEquals(183, counts.get("minecraft:barrier"));
        assertFalse(counts.containsKey("minecraft:water"));
        assertFalse(counts.containsKey("minecraft:command_block"));

        Map<String, String> cells = new HashMap<>();
        JsonArray palette = farm.getAsJsonArray("palette");
        farm.getAsJsonArray("blocks").forEach(value -> {
            JsonArray row = value.getAsJsonArray();
            cells.put(row.get(0) + "," + row.get(1) + "," + row.get(2), palette.get(row.get(3).getAsInt()).getAsJsonObject().get("Name").getAsString());
        });
        // A player can reach this ridge. Its source boundary must continue well above the visible terrain.
        for (int y = 56; y <= 69; y++) assertEquals("minecraft:barrier", cells.get("1," + (y - 27) + ",35"));
        farm.getAsJsonArray("blocks").forEach(value -> {
            JsonArray row = value.getAsJsonArray();
            if (palette.get(row.get(3).getAsInt()).getAsJsonObject().get("Name").getAsString().equals("minecraft:gravel")) {
                assertEquals("minecraft:dirt", cells.get(row.get(0) + "," + (row.get(1).getAsInt() - 1) + "," + row.get(2)));
            }
        });
        JsonObject lower = resource("geometry/farming_frenzy_template.json.gz", true);
        assertEquals("[-183,28,-284]", lower.getAsJsonObject("bounds").get("min").toString());
        assertEquals("[24,4,26]", lower.get("size").toString());
        assertEquals(1717, lower.getAsJsonArray("blocks").size());
        assertEquals(779, 24 * 4 * 26 - lower.getAsJsonArray("blocks").size());
        assertEquals(363, counts(lower).get("minecraft:farmland"));
    }

    @Test void sourceWitnessesKeepAllOperationsMaturityPredicatesAndEightRepeatableSeasons() throws Exception {
        JsonObject audit = resource("farming_frenzy_rules.json", false);
        assertEquals(420, audit.get("sourceId").getAsInt());
        assertEquals(16, audit.getAsJsonObject("sourceFunctions").size());
        assertEquals("b0820d9d38a2939d1c016bc785666adb8dc7ac7285ea08bea2c3580e1d8cfb28",
                audit.getAsJsonObject("sourceFunctionSha256").get("farming_frenzy").getAsString());
        for (var source : audit.getAsJsonObject("sourceFunctions").entrySet()) {
            assertEquals(audit.getAsJsonObject("sourceFunctionSha256").get(source.getKey()).getAsString(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getValue().getAsString().getBytes(StandardCharsets.UTF_8))));
        }
        assertEquals(15, audit.getAsJsonArray("setupOperations").size());
        assertEquals(59, audit.getAsJsonArray("replantOperations").size());
        assertEquals(13, audit.getAsJsonArray("banners").size());
        assertEquals(10, audit.getAsJsonArray("nativeBreak").size());
        assertFalse(audit.get("nativeBreak").toString().contains("attached_"));
        assertEquals(8, audit.getAsJsonArray("crops").size());
        JsonObject timing = audit.getAsJsonObject("timing");
        assertEquals(1799, timing.get("actualProcessedTicks").getAsInt());
        assertEquals("[200,400,600,800,1000,1200,1400,1600]", timing.get("seasonTicks").toString());
        String constants = audit.getAsJsonObject("sourceFunctions").get("scoreboard_data").getAsString();
        assertTrue(constants.contains("scoreboard players set farmingFrenzyTime constants 1800"));
        assertTrue(constants.contains("scoreboard players set farmEventInterval constants 200"));
        String tick = audit.getAsJsonObject("sourceFunctions").get("farming_frenzy_tick").getAsString();
        assertTrue(tick.contains("sort=random,limit=7"));
        assertTrue(tick.contains("execute if score timeLeft variables matches 1 run function mcp:farming_frenzy_end"));
        assertTrue(audit.getAsJsonObject("sourceFunctions").get("4_4_all_tick").getAsString()
                .contains("fill ~ ~-1 ~ ~ ~-1 ~ minecraft:farmland replace minecraft:dirt"));
    }

    private static Map<String, Integer> counts(JsonObject geometry) {
        Map<String, Integer> result = new HashMap<>();
        JsonArray palette = geometry.getAsJsonArray("palette");
        geometry.getAsJsonArray("blocks").forEach(value -> result.merge(
                palette.get(value.getAsJsonArray().get(3).getAsInt()).getAsJsonObject().get("Name").getAsString(), 1, Integer::sum));
        return result;
    }
}
