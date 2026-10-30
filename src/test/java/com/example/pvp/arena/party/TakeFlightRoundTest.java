package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Keep native race mechanics and every source cave cell rather than a safe subset. */
final class TakeFlightRoundTest {
    @Test void scoresUseWholeLapsAndSourceCheckpointRecencyIncludingSameTickTies() {
        assertEquals(3,TakeFlightRound.rawScore(4,0,-1,List.of(-1,-1,-1,-1),false));
        assertEquals(4,TakeFlightRound.rawScore(4,1,20,List.of(20,-1,-1,-1),true));
        assertEquals(5,TakeFlightRound.rawScore(4,1,20,List.of(20,30,-1,-1),true));
        assertEquals(4,TakeFlightRound.rawScore(4,1,30,List.of(20,30,-1,-1),true));
        assertEquals(4,TakeFlightRound.rawScore(4,1,20,List.of(20,20,-1,-1),true));
        assertEquals(12,TakeFlightRound.rawScore(4,3,100,List.of(100,50,40,30),true));
        assertEquals(1,TakeFlightRound.averageGroup(0,0,10));
        assertEquals(2,TakeFlightRound.averageGroup(0,10,10));
        assertEquals(4,TakeFlightRound.averageGroup(5,10,10));
        assertEquals(1,TakeFlightRound.averageGroup(10,0,10));
    }

    @Test void boostsRetainNativeIntegerSlabRetriggersAndBothSourceCounters() {
        assertEquals(60,TakeFlightRound.contactBoost(81,false));
        assertEquals(39,TakeFlightRound.contactBoost(60,false));
        assertEquals(18,TakeFlightRound.contactBoost(39,false));
        assertEquals(0,TakeFlightRound.contactBoost(81,true));
        assertTrue(TakeFlightRound.insideAndCrossed(-301,24,-667,-667,-301,24,-667));
        assertTrue(TakeFlightRound.insideAndCrossed(-301,24,-670,-660,-301,24,-667));
        assertTrue(TakeFlightRound.insideAndCrossed(-301,24,-660,-670,-301,24,-667));
        assertFalse(TakeFlightRound.insideAndCrossed(-303,24,-667,-667,-301,24,-667));
        assertFalse(TakeFlightRound.insideAndCrossed(-301,26,-667,-667,-301,24,-667));
        assertFalse(TakeFlightRound.insideAndCrossed(-301,24,-671,-670,-301,24,-667));
    }

    @Test void exactRunsRetainEveryNativeTerrainCellAndEveryWaypointMaterial() throws Exception {
        try(var input=TakeFlightRoundTest.class.getResourceAsStream("/pvp/party/geometry/take_flight.json.gz")) {
            assertNotNull(input);
            try(var reader=new JsonReader(new InputStreamReader(new GZIPInputStream(input),StandardCharsets.UTF_8))) {
                JsonArray palette=null;long[] counts=null;
                long cells=0,runs=0;BitSet occupied=new BitSet(211*129*261);
                reader.beginObject();
                while(reader.hasNext())switch(reader.nextName()) {
                    case "formatVersion" -> assertEquals(2,reader.nextInt());
                    case "runAxis" -> assertEquals("x",reader.nextString());
                    case "nonAirBlocks" -> assertEquals(6_038_297,reader.nextLong());
                    case "bounds" -> {
                        JsonObject bounds=JsonParser.parseReader(reader).getAsJsonObject();
                        assertEquals("[-410,-64,-770]",bounds.get("min").toString());
                        assertEquals("[-200,64,-510]",bounds.get("max").toString());
                    }
                    case "size" -> assertEquals("[211,129,261]",JsonParser.parseReader(reader).toString());
                    case "blockCount" -> assertEquals(6_038_297,reader.nextLong());
                    case "palette" -> {palette=JsonParser.parseReader(reader).getAsJsonArray();counts=new long[palette.size()];assertEquals(138,palette.size());}
                    case "runs" -> {
                        assertNotNull(counts);reader.beginArray();
                        while(reader.hasNext()) {
                            reader.beginArray();int x=reader.nextInt(),y=reader.nextInt(),z=reader.nextInt(),length=reader.nextInt(),state=reader.nextInt();reader.endArray();
                            assertTrue(x>=0&&length>0&&x+length<=211&&y>=0&&y<129&&z>=0&&z<261);
                            assertTrue(state>=0&&state<counts.length);
                            int first=(y*261+z)*211+x,last=first+length,next=occupied.nextSetBit(first);
                            assertTrue(next<0||next>=last,"Exact runs must never overlap");occupied.set(first,last);
                            cells+=length;counts[state]+=length;runs++;
                        }
                        reader.endArray();
                    }
                    default -> reader.skipValue();
                }
                reader.endObject();assertEquals(6_038_297,cells);assertEquals(1_002_534,runs);assertEquals(cells,occupied.cardinality());
                Map<String,Long> materials=new HashMap<>();
                for(int index=0;index<counts.length;index++)materials.merge(palette.get(index).getAsJsonObject().get("Name").getAsString(),counts[index],Long::sum);
                assertEquals(165541L,materials.get("minecraft:bedrock"));
                assertEquals(2738483L,materials.get("minecraft:deepslate"));
                assertEquals(2025175L,materials.get("minecraft:stone"));
                assertEquals(484L,materials.get("minecraft:emerald_block"));
                assertEquals(589L,materials.get("minecraft:diamond_block"));
                assertEquals(476L,materials.get("minecraft:netherite_block"));
                assertEquals(270L,materials.get("minecraft:sponge"));
                assertEquals(94L,materials.get("minecraft:glass"));
                assertEquals(3L,materials.get("minecraft:chain"));
                assertFalse(materials.containsKey("minecraft:barrier"));
                assertFalse(materials.containsKey("minecraft:command_block"));
            }
        }
    }

    @Test void sourceWitnessesKeepAllSixCoreFunctionsAndSixteenRandomBoostCandidates() throws Exception {
        try(var input=TakeFlightRoundTest.class.getResourceAsStream("/pvp/party/take_flight_rules.json")) {
            assertNotNull(input);
            JsonObject rules=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(427,rules.get("sourceId").getAsInt());
            assertEquals("[-305,101,-640]",rules.get("anchor").toString());
            assertEquals(26,rules.getAsJsonObject("sourceFunctions").size());
            assertEquals(6,rules.getAsJsonArray("coreFunctionNames").size());
            assertEquals("43e9246e9f221d232e2b8b3e9408f68a56da674e3aeb83cc83d1a6f965dfbd72",rules.getAsJsonObject("sourceFunctionSha256").get("take_flight").getAsString());
            for(var entry:rules.getAsJsonObject("sourceFunctions").entrySet())assertEquals(
                    rules.getAsJsonObject("sourceFunctionSha256").get(entry.getKey()).getAsString(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue().getAsString().getBytes(StandardCharsets.UTF_8))));
            assertEquals(16,rules.getAsJsonArray("boostCandidates").size());
            int[] perGroup=new int[4];rules.getAsJsonArray("boostCandidates").forEach(row->perGroup[row.getAsJsonObject().get("waypoint").getAsInt()-1]++);
            assertArrayEquals(new int[]{4,4,4,4},perGroup);
            String tick=rules.getAsJsonObject("sourceFunctions").get("take_flight_tick").getAsString();
            assertTrue(tick.contains("run scoreboard players remove @s boost 21"));
            assertTrue(tick.contains("run scoreboard players remove @s boost 81"));
            assertTrue(tick.contains("if block ^ ^ ^1 air run tp @s ^ ^ ^1"));
            assertTrue(tick.contains("rocket_recharge=10"));
            assertTrue(tick.contains("flight_duration:0b"));
            assertTrue(tick.contains("if block ~ -48 ~ minecraft:diamond_block"));
        }
    }
}
