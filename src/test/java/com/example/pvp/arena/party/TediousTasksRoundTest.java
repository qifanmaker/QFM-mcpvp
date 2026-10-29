package com.example.pvp.arena.party;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Original-resource witnesses catch virtual-task substitutions and mismatched note goals. */
final class TediousTasksRoundTest {
    private static JsonObject resource(String name,boolean gzip) throws Exception {
        try (var input=TediousTasksRoundTest.class.getResourceAsStream("/pvp/party/"+name)) {
            assertNotNull(input,name);
            try (var reader=new InputStreamReader(gzip ? new GZIPInputStream(input) : input,StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test void fullFourPedestalFieldKeepsNativeMaterialsAndEveryClone() throws Exception {
        JsonObject field=resource("geometry/tedious_tasks.json.gz",true);
        assertEquals("[8,-17,-348]",field.getAsJsonObject("bounds").get("min").toString());
        assertEquals("[60,41,-296]",field.getAsJsonObject("bounds").get("max").toString());
        assertEquals(21765,field.getAsJsonArray("blocks").size());
        Map<String,Integer> counts=counts(field);
        assertEquals(357,counts.get("minecraft:lava"));
        assertEquals(9,counts.get("minecraft:gold_block"));
        assertEquals(12,counts.get("minecraft:note_block"));
        assertEquals(7,counts.get("minecraft:warped_stem"));
        assertFalse(counts.containsKey("minecraft:command_block"));
        JsonObject tree=resource("geometry/tedious_tree.json.gz",true);
        assertEquals("[5,8,5]",tree.get("size").toString());
        assertEquals(78,tree.getAsJsonArray("blocks").size());
        assertEquals(7,counts(tree).get("minecraft:warped_stem"));
        assertEquals(68,counts(tree).get("minecraft:warped_wart_block"));
        assertEquals(3,counts(tree).get("minecraft:shroomlight"));
        for (String direction : new String[]{"horizontal","vertical"}) {
            for (String type : new String[]{"notes","goal"}) {
                JsonObject template=resource("geometry/tedious_"+type+"_"+direction+".json.gz",true);
                assertEquals(6,template.getAsJsonArray("blocks").size());
                assertEquals(3,counts(template).get("minecraft:netherite_block"));
                assertEquals(3,counts(template).get("minecraft:note_block"));
                template.getAsJsonArray("palette").forEach(value -> {
                    var state=value.getAsJsonObject();
                    if (state.get("Name").getAsString().equals("minecraft:note_block")) {
                        var properties=state.getAsJsonObject("Properties");
                        assertEquals(type.equals("notes") ? "1" : "0",properties.get("note").getAsString());
                        assertEquals("harp",properties.get("instrument").getAsString());
                        assertEquals("false",properties.get("powered").getAsString());
                    }
                });
            }
        }
    }

    @Test void taskAuditIncludesActualTradingCraftingBellAndSourceBugs() throws Exception {
        JsonObject audit=resource("tedious_tasks_rules.json",false);
        assertEquals(411,audit.get("sourceId").getAsInt());
        assertEquals(2,audit.get("minimumPlayers").getAsInt());
        assertEquals(4,audit.get("maximumPlayers").getAsInt());
        assertEquals(29,audit.getAsJsonObject("functionSha256").size());
        assertEquals("9ce38cf46c6c28730bd1b86ba88672afe9c10dc6d79b9e446fc0de47e2f5c1d7",
                audit.getAsJsonObject("functionSha256").get("tedious_tasks").getAsString());
        assertEquals(4800,audit.getAsJsonObject("rules").get("timeLimitTicks").getAsInt());
        assertEquals(8,audit.getAsJsonObject("rules").getAsJsonArray("tasks").size());
        var order=audit.getAsJsonObject("rules").getAsJsonArray("tradeOrder");
        assertEquals(8,order.size());
        assertEquals("[\"lapis_lazuli\",\"copper_ingot\"]",order.get(0).toString());
        assertEquals("[\"amethyst_shard\",\"nether_star\"]",order.get(3).toString());
        String nativeTasks=audit.getAsJsonObject("functions").get("player_1_next_task").getAsString();
        assertTrue(nativeTasks.contains("summon minecraft:villager"));
        assertTrue(nativeTasks.contains("maxUses:999"));
        assertTrue(nativeTasks.contains("clone 56 15 -300 52 22 -304"));
        assertTrue(nativeTasks.contains("minecraft:bell[attachment=ceiling]"));
        assertTrue(audit.get("sourceCorrections").toString().contains("checkpoint"));
        assertTrue(audit.get("sourceCorrections").toString().contains("taskplayer"));
    }

    private static Map<String,Integer> counts(JsonObject geometry) {
        Map<String,Integer> counts=new HashMap<>();
        var palette=geometry.getAsJsonArray("palette");
        geometry.getAsJsonArray("blocks").forEach(value -> counts.merge(palette.get(value.getAsJsonArray().get(3).getAsInt())
                .getAsJsonObject().get("Name").getAsString(),1,Integer::sum));
        return counts;
    }
}
