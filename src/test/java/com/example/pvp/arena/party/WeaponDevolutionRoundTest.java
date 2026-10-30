package com.example.pvp.arena.party;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Source witnesses prevent accidentally substituting the obsolete gladiators arena/rules. */
final class WeaponDevolutionRoundTest {
    private static JsonObject resource(String name,boolean gzip)throws Exception {
        try(var input=WeaponDevolutionRoundTest.class.getResourceAsStream("/pvp/party/"+name)) {
            assertNotNull(input);
            try(var reader=new InputStreamReader(gzip?new GZIPInputStream(input):input,StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }
    @Test void retainsTheArenaAndAllFourOriginalTowerCloneSources()throws Exception {
        var arena=resource("geometry/weapon_devolution_arena.json.gz",true);
        assertEquals("[-295,17,-418]",arena.getAsJsonObject("bounds").get("min").toString());
        assertEquals("[-212,50,-335]",arena.getAsJsonObject("bounds").get("max").toString());
        assertEquals(42492,arena.getAsJsonArray("blocks").size());
        assertEquals(1,arena.getAsJsonObject("replacements").get("minecraft:iron_chain -> minecraft:chain").getAsInt());
        int[] counts={923,924,923,914};
        for(int i=1;i<=4;i++) {
            var tower=resource("geometry/weapon_devolution_tower_"+i+".json.gz",true);
            assertEquals(counts[i-1],tower.getAsJsonArray("blocks").size());
            assertEquals(-21,tower.getAsJsonObject("bounds").getAsJsonArray("min").get(1).getAsInt());
            assertEquals(i==1?17:15,tower.getAsJsonObject("bounds").getAsJsonArray("max").get(1).getAsInt());
        }
    }
    @Test void preservesWeaponParametersAndCompleteNativeGateDisplays()throws Exception {
        var source=resource("weapon_devolution_rules.json",false);
        assertEquals(410,source.get("sourceId").getAsInt());
        assertEquals(19,source.getAsJsonObject("sourceFunctionSha256").size());
        assertFalse(source.getAsJsonObject("sourceFunctionSha256").has("data/mcp/function/gladiators.mcfunction"));
        var rule=source.getAsJsonObject("rules");
        assertEquals(2400,rule.get("durationTicks").getAsInt());
        assertEquals(19,rule.get("laserCooldownTicks").getAsInt());
        assertEquals(1.25,rule.get("laserArrowDamage").getAsDouble());
        assertEquals(10,rule.get("laserSpeed").getAsInt());
        assertEquals(30,rule.get("snowballSupplyTicks").getAsInt());
        assertEquals(100,rule.get("snowArrowPierceLevel").getAsInt());
        assertEquals("[16,32,48]",rule.get("gateLayerTicks").toString());
        var bars=source.getAsJsonArray("bars");assertEquals(48,bars.size());
        for(var item:bars) {
            var bar=item.getAsJsonObject();var p=bar.getAsJsonArray("position");
            assertEquals(.5,Math.abs(p.get(0).getAsDouble()%1));
            assertEquals(.5,Math.abs(p.get(2).getAsDouble()%1));
            assertTrue(Set.of(25,26,27).contains(p.get(1).getAsInt()));
            assertEquals(Set.of("north","south","east","west"),bar.getAsJsonObject("properties").keySet());
        }
    }
}
