package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Exact source geometry is streamed so this check never allocates13M cells. */
final class RocketLaunchRoundTest {
    @Test void sourceDistanceFlooringBackwardOrderAndHeightRewardRemainExact(){
        assertEquals(0,RocketLaunchRound.distance(120));
        assertEquals(1,RocketLaunchRound.distance(116.999));
        assertEquals(2,RocketLaunchRound.distance(115.999));
        assertEquals(2160,RocketLaunchRound.distance(-2043));
        assertEquals(2161,RocketLaunchRound.distance(-2043.01));
        assertFalse(RocketLaunchRound.backward(115,125));
        assertTrue(RocketLaunchRound.backward(114,118));
        assertFalse(RocketLaunchRound.backward(113,118));
        assertFalse(RocketLaunchRound.backward(116,-100));
        assertEquals(2159,RocketLaunchRound.terminalDistance(2159,250));
        assertEquals(2173,RocketLaunchRound.terminalDistance(2166,203));
        assertEquals(2154,RocketLaunchRound.terminalDistance(2160,190));
        assertEquals(4003,RocketLaunchRound.rawScore(1000,4,10,List.of(11,12,13)));
        assertEquals(4001,RocketLaunchRound.rawScore(1000,4,11,List.of(11,11,12)));
        assertEquals(4000,RocketLaunchRound.rawScore(1000,4,-1,List.of(11,12,13)));
    }

    @Test void exactRleRetainsEntire2160CourseAndAllBuriedSourceTerrain()throws Exception{
        try(var input=getClass().getResourceAsStream("/pvp/party/geometry/rocket_launch.json.gz")){
            assertNotNull(input);
            try(var reader=new JsonReader(new InputStreamReader(new GZIPInputStream(input),StandardCharsets.UTF_8))){
                int version=0,count=0;long expanded=0,declared=0;
                JsonArray palette=null;String axis=null;boolean legal=true;
                Map<String,String> witness=new HashMap<>();int[] heights={-64,137,195,236};
                reader.beginObject();
                while(reader.hasNext())switch(reader.nextName()){
                    case "formatVersion"->version=reader.nextInt();
                    case "runAxis"->axis=reader.nextString();
                    case "bounds"->{JsonObject bounds=JsonParser.parseReader(reader).getAsJsonObject();
                        assertEquals("[-83,-64,-2060]",bounds.get("min").toString());
                        assertEquals("[-41,255,127]",bounds.get("max").toString());}
                    case "palette"->palette=JsonParser.parseReader(reader).getAsJsonArray();
                    case "runs"->{
                        assertNotNull(palette);reader.beginArray();
                        while(reader.hasNext()){
                            reader.beginArray();int x=reader.nextInt(),y=reader.nextInt(),z=reader.nextInt(),length=reader.nextInt(),state=reader.nextInt();reader.endArray();
                            legal&=x>=0&&x<43&&y>=0&&length>0&&y+length<=320&&z>=0&&z<2188&&state>=0&&state<palette.size();
                            expanded+=length;count++;
                            int sx=x-83,sz=z-2060,sy=y-64;
                            for(int wy:heights)if(sx==-62&&wy>=sy&&wy<sy+length&&(sz==120||sz==116||sz==125||sz==-2043))
                                witness.put(sx+","+wy+","+sz,palette.get(state).getAsJsonObject().get("Name").getAsString());
                        }
                        reader.endArray();
                    }
                    case "nonAirBlocks"->declared=reader.nextLong();
                    default->reader.skipValue();
                }
                reader.endObject();assertEquals(2,version);assertEquals("y",axis);assertTrue(legal);
                assertEquals(12933322,expanded);assertEquals(expanded,declared);assertEquals(1618692,count);
                assertEquals("minecraft:bedrock",witness.get("-62,-64,120"));
                assertEquals("minecraft:red_sandstone",witness.get("-62,137,120"));
                assertEquals("minecraft:light",witness.get("-62,137,116"));
                assertEquals("minecraft:barrier",witness.get("-62,236,125"));
                assertEquals("minecraft:black_concrete",witness.get("-62,195,-2043"));
            }
        }
        try(var input=getClass().getResourceAsStream("/pvp/party/rocket_launch_rules.json")){
            assertNotNull(input);JsonObject rules=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(15,rules.getAsJsonObject("functions").size());
            assertEquals(36,rules.getAsJsonArray("blockEntities").size());assertEquals(2,rules.getAsJsonArray("entities").size());
            assertTrue(rules.getAsJsonObject("scene").get("completeBuriedTerrain").getAsBoolean());
            String tick=rules.getAsJsonObject("functions").get("rocket_launch_tick").getAsString();
            assertTrue(tick.contains("zpos=2160.."));assertTrue(tick.contains("unless block ~ ~-1 ~ minecraft:light"));
            assertTrue(tick.contains("if score finished variables >= players variables"));
            assertTrue(rules.getAsJsonObject("rules").get("noSourceTimeLimit").getAsBoolean());
        }
    }
}
