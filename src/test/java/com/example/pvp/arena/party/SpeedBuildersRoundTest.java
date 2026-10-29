package com.example.pvp.arena.party;

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

/** Source witnesses prevent reducing417 to approximate models or target-only clicks. */
final class SpeedBuildersRoundTest {
    private static JsonObject resource(String name,boolean gzip) throws Exception {
        try(var input=SpeedBuildersRoundTest.class.getResourceAsStream("/pvp/party/"+name)) {
            assertNotNull(input,name);
            try(var reader=new InputStreamReader(gzip?new GZIPInputStream(input):input,StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test void allOriginalTemplatesKeepEverySixByFourBySixCell() throws Exception {
        int[] expected={22,24,7,24,32,8,42,28,16,32,26,12,16,36,24,12,16,48,44,40,24,24,36,16,4,40,27,20};
        Set<String> distinct=new HashSet<>();
        for(int id=1;id<=28;id++) {
            JsonObject model=resource("geometry/speed_builders_pattern_"+id+".json.gz",true);
            assertEquals("[6,4,6]",model.get("size").toString());
            assertEquals(expected[id-1],model.getAsJsonArray("blocks").size());
            assertTrue(model.getAsJsonObject("omitted").isEmpty());
            model.getAsJsonArray("palette").forEach(value->assertTrue(value.getAsJsonObject().get("Name").getAsString().endsWith("_planks")));
            distinct.add(model.get("blocks").toString()+model.get("palette"));
        }
        assertEquals(28,distinct.size());
        JsonObject arena=resource("geometry/speed_builders_arena.json.gz",true);
        assertEquals("[-389,27,-180]",arena.getAsJsonObject("bounds").get("min").toString());
        assertEquals("[-337,54,-100]",arena.getAsJsonObject("bounds").get("max").toString());
        assertEquals(26511,arena.getAsJsonArray("blocks").size());
        Map<String,Integer> counts=new HashMap<>();
        arena.getAsJsonArray("blocks").forEach(value->counts.merge(arena.getAsJsonArray("palette").get(value.getAsJsonArray().get(3).getAsInt())
                .getAsJsonObject().get("Name").getAsString(),1,Integer::sum));
        assertEquals(720,counts.get("minecraft:white_concrete_powder"));
        assertEquals(10,counts.get("minecraft:oak_button"));
        assertEquals(1698,counts.get("minecraft:barrier"));
        assertFalse(counts.containsKey("minecraft:command_block"));
    }

    @Test void auditPreservesNativeToolsButtonsAllSourceSummonsAndSafeDecorations() throws Exception {
        JsonObject audit=resource("speed_builders_rules.json",false);
        assertEquals(417,audit.get("sourceId").getAsInt());
        assertEquals(13,audit.getAsJsonObject("functionSha256").size());
        assertEquals("0c260e533165dbf2fa863c948ed8aaaaa2929c1d8b07cb626eca9f8319e32162",
                audit.getAsJsonObject("functionSha256").get("speed_builders").getAsString());
        assertEquals(3600,audit.getAsJsonObject("rules").get("timeLimitTicks").getAsInt());
        assertEquals(28,audit.getAsJsonArray("templates").size());
        assertEquals(40,audit.getAsJsonArray("flowerBlockers").size());
        int[] seats=new int[10];
        audit.getAsJsonArray("flowerBlockers").forEach(row->seats[row.getAsJsonObject().get("seat").getAsInt()]++);
        assertArrayEquals(new int[]{2,6,2,6,2,6,2,6,2,6},seats);
        assertEquals(10,audit.getAsJsonArray("resetCommands").size());
        audit.getAsJsonArray("resetCommands").forEach(row->assertEquals("fill ~-7 ~-1 ~-10 ~8 ~6 ~-3 minecraft:air",row.getAsJsonObject().get("source").getAsString()));
        assertEquals(37,audit.getAsJsonArray("signs").size());
        assertEquals(12,audit.getAsJsonArray("banners").size());
        assertFalse(audit.get("signs").toString().contains("click_event"));
        String tick=audit.getAsJsonObject("functions").get("speed_builders_tick").getAsString();
        assertTrue(tick.contains("minecraft:golden_pickaxe[minecraft:can_break="));
        assertTrue(tick.contains("minecraft:can_place_on={blocks:[\"minecraft:white_concrete_powder\""));
        assertTrue(tick.contains("unless blocks -336 28 -160 -331 31 -148"));
        assertTrue(audit.get("sourceCorrections").toString().contains("Player5"));
    }
}
