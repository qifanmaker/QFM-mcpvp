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

final class MatchMeNotRoundTest {
    private static JsonObject resource(String name,boolean gzip) throws Exception {
        try(var input=MatchMeNotRoundTest.class.getResourceAsStream("/pvp/party/"+name)) {
            assertNotNull(input,name);
            try(var reader=new InputStreamReader(gzip?new GZIPInputStream(input):input,StandardCharsets.UTF_8)) {return JsonParser.parseReader(reader).getAsJsonObject();}
        }
    }
    @Test void pureSourceRulesKeepMaterialCountsIntegerAnglesAndUniqueScoring() {
        int[] materials={4,4,4,5,5,6,6,7,7},resets={440,440,440,420,400,380,360,340,320};
        for(int n=2;n<=10;n++) {
            assertEquals(materials[n-2],MatchMeNotRound.materialCount(n));assertEquals(resets[n-2],MatchMeNotRound.revealReset(n));
            assertEquals(360/n,MatchMeNotRound.angleStep(n));assertEquals(n>=9?12:10,MatchMeNotRound.circleRadius(n));
        }
        assertEquals(51,MatchMeNotRound.angleStep(7));
        assertEquals(0,MatchMeNotRound.uniquePoints(0,1));assertEquals(0,MatchMeNotRound.uniquePoints(1,2));assertEquals(1,MatchMeNotRound.uniquePoints(7,1));
        int pistons=0;for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)if(MatchMeNotRound.pistonCell(x,z))pistons++;
        assertEquals(21,pistons);assertFalse(MatchMeNotRound.pistonCell(2,2));assertTrue(MatchMeNotRound.pistonCell(2,1));
    }
    @Test void completeOriginalSceneAndTenChipClonesKeepTheirActualColorsAndAirBounds() throws Exception {
        JsonObject scene=resource("geometry/match_me_not_arena.json.gz",true);
        assertEquals("[-350,-12,-385]",scene.getAsJsonObject("bounds").get("min").toString());
        assertEquals("[-298,5,-319]",scene.getAsJsonObject("bounds").get("max").toString());
        assertEquals("[53,18,67]",scene.get("size").toString());assertEquals(4654,scene.getAsJsonArray("blocks").size());
        assertEquals(2322,counts(scene).get("minecraft:moss_block"));assertEquals(40,counts(scene).get("minecraft:polished_blackstone_button"));
        String[] colors={"blue","red","lime","yellow","cyan","magenta","brown","green","purple","gray"};
        for(int index=1;index<=10;index++) {
            JsonObject chip=resource("geometry/match_me_not_chip_"+index+".json.gz",true);
            assertEquals("[7,3,7]",chip.get("size").toString());assertEquals(index==10?102:98,chip.getAsJsonArray("blocks").size());
            var counts=counts(chip);assertEquals(25,counts.get("minecraft:white_wool"));assertEquals(21,counts.get("minecraft:"+colors[index-1]+"_concrete"));
            assertEquals(20,counts.get("minecraft:white_carpet"));assertTrue(chip.getAsJsonObject("omitted").isEmpty());
        }
        JsonObject audit=resource("match_me_not_rules.json",false);
        assertEquals(13,audit.getAsJsonObject("functions").size());assertEquals(33,audit.getAsJsonArray("banners").size());
        var rules=audit.getAsJsonObject("rules");assertEquals(8,rules.get("internalRounds").getAsInt());assertEquals(200,rules.get("choiceTicks").getAsInt());
        String tick=audit.getAsJsonObject("functions").get("match_me_not_tick").getAsString();
        assertTrue(tick.contains("mmn_reveal variables matches 500"));assertTrue(tick.contains("~3 ~-4 ~3 ~-3 ~-4 ~-3 minecraft:redstone_block"));
        assertTrue(tick.contains("mmn_reveal variables matches 510"));assertTrue(tick.contains("schedule function mcp:match_me_not_end 2s"));
    }
    private static Map<String,Integer> counts(JsonObject geometry) {
        Map<String,Integer> result=new HashMap<>();var palette=geometry.getAsJsonArray("palette");
        geometry.getAsJsonArray("blocks").forEach(value->result.merge(palette.get(value.getAsJsonArray().get(3).getAsInt()).getAsJsonObject().get("Name").getAsString(),1,Integer::sum));return result;
    }
}
