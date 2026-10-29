package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Source witnesses reject a click-total shortcut, clipped scenery or a generic building race. */
final class PowerTowersRoundTest {
    private static JsonObject resource(String name, boolean compressed) throws Exception {
        try (var raw = PowerTowersRoundTest.class.getResourceAsStream("/pvp/party/" + name)) {
            assertNotNull(raw);
            try (var input = compressed ? new GZIPInputStream(raw) : raw) {
                return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
    }

    @Test void originalScoringUsesNativeTuningDeltasAndActualPowderCollapse() throws Exception {
        JsonObject rules = resource("power_towers_rules.json",false);
        assertEquals(419,rules.get("sourceId").getAsInt());
        assertEquals("minecraft:the_end",rules.get("dimension").getAsString());
        assertEquals(9,rules.getAsJsonObject("functionSha256").size());
        assertEquals(200,rules.getAsJsonObject("timing").get("mashingTicks").getAsInt());
        assertFalse(rules.getAsJsonObject("timing").get("emptyCounterResetsOnFalling").getAsBoolean());
        String start = rules.getAsJsonObject("sourceFunctions").get("power_towers_start").getAsString();
        String tick = rules.getAsJsonObject("sourceFunctions").get("power_towers_tick").getAsString();
        assertTrue(start.contains("minecraft.custom:minecraft.tune_noteblock"));
        assertTrue(tick.contains("tag=!counted,scores={end_ypos=..56}"));
        assertTrue(tick.indexOf("run kill @e[type=minecraft:marker,tag=power_towers]") < tick.indexOf("tag=east"));
        assertTrue(tick.contains("minecraft:tripwire replace minecraft:reinforced_deepslate"));
        assertEquals(52,PowerTowersRound.movedMarker(52,0));
        assertEquals(59,PowerTowersRound.movedMarker(52,7));
        assertEquals(52,PowerTowersRound.movedMarker(52,8),"Source ignores this tick's whole delta; it does not clamp it to seven");
        assertEquals(0,PowerTowersRound.compensation(0));
        assertEquals(1,PowerTowersRound.compensation(1));
        assertEquals(2,PowerTowersRound.compensation(2));
        assertEquals(2,PowerTowersRound.compensation(100),"The other98 points must come from actual native entities");
        assertTrue(PowerTowersRound.validOriginalFill(256));
        assertFalse(PowerTowersRound.validOriginalFill(257),"Original End rejects the entire out-of-height /fill");
    }

    @Test void allTenOriginalSeatsAndEightPlayerOuterSeatRemapArePreserved() throws Exception {
        JsonArray seats = resource("power_towers_rules.json",false).getAsJsonArray("seats");
        assertEquals(10,seats.size());
        Set<String> towers = new HashSet<>(), notes = new HashSet<>();
        for (int seat = 0; seat < 10; seat++) {
            JsonObject source = seats.get(seat).getAsJsonObject();
            assertEquals(seat+1,source.get("platform").getAsInt());
            assertTrue(towers.add(source.get("tower").toString()));
            assertTrue(notes.add(source.get("note").toString()));
            assertEquals(seat+1,PowerTowersRound.platformFor(seat,10));
        }
        int[] eight = {1,2,3,4,9,10,7,8};
        for (int seat = 0; seat < eight.length; seat++) assertEquals(eight[seat],PowerTowersRound.platformFor(seat,8));
        assertEquals("[-500,52,-1]",seats.get(0).getAsJsonObject().get("tower").toString());
        assertEquals("[-500.5,52,-0.5]",seats.get(0).getAsJsonObject().get("spawn").toString());
        assertEquals("[-502,52,-1]",seats.get(0).getAsJsonObject().get("note").toString());
    }

    @Test void completeTallSceneryAndBothCloneSourcesKeepNativeGeometry() throws Exception {
        JsonObject scene = resource("geometry/power_towers.json.gz",true);
        assertEquals("[-632,0,-107]",scene.getAsJsonObject("bounds").get("min").toString());
        assertEquals("[-375,255,121]",scene.getAsJsonObject("bounds").get("max").toString());
        assertEquals(68871,scene.getAsJsonArray("blocks").size());
        assertEquals(53,scene.getAsJsonArray("palette").size());
        Set<Integer> extrema = new HashSet<>();
        for (var entry : scene.getAsJsonArray("blocks")) {
            JsonArray cell = entry.getAsJsonArray();
            if (cell.get(0).getAsInt()==0) extrema.add(0);
            if (cell.get(0).getAsInt()==257) extrema.add(1);
            if (cell.get(1).getAsInt()==242) extrema.add(2);
            if (cell.get(2).getAsInt()==228) extrema.add(3);
        }
        assertEquals(Set.of(0,1,2,3),extrema,"Keep distant structures and the high source stone witness");
        for (String side : new String[]{"west","east"}) {
            JsonObject template = resource("geometry/power_towers_" + side + ".json.gz",true);
            assertEquals("[4,3,3]",template.get("size").toString());
            assertEquals(20,template.getAsJsonArray("blocks").size(),"The remaining16 clone cells are real air and must overwrite destinations");
            assertTrue(template.getAsJsonArray("palette").asList().stream().map(e -> e.getAsJsonObject().get("Name").getAsString())
                    .anyMatch("minecraft:reinforced_deepslate"::equals));
            assertTrue(template.getAsJsonArray("palette").asList().stream().map(e -> e.getAsJsonObject().get("Name").getAsString())
                    .anyMatch("minecraft:note_block"::equals));
        }
    }
}
