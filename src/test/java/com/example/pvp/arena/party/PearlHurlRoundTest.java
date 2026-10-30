package com.example.pvp.arena.party;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Reason: preserve exact route pools, source shortcuts and counters; native protocols cover actual pearls/gateways. */
final class PearlHurlRoundTest {
    private static JsonObject rules()throws Exception {
        try(var input=PearlHurlRoundTest.class.getResourceAsStream("/pvp/party/pearl_hurl_rules.json")) {
            assertNotNull(input);return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void largeLobbiesUseOriginalAlternatingLineAndIndependentInitialMarkers(){
        double[] five={66,67,65,68,64},six={65.5,66.5,64.5,67.5,63.5,68.5};
        for(int seat=0;seat<5;seat++){var p=PearlHurlRound.launchSpawn(5,seat);assertEquals(five[seat],p.x());assertEquals(-79,p.z());}
        for(int seat=0;seat<6;seat++)assertEquals(six[seat],PearlHurlRound.launchSpawn(6,seat).x());
        for(int seat=0;seat<10;seat++){var p=PearlHurlRound.initialSafe(seat);assertEquals(64.5+Math.min(seat,3),p.x());assertEquals(-79.5,p.z());}
        for(int count=5;count<=10;count++)for(int seat=0;seat<count;seat++){var p=PearlHurlRound.launchSpawn(count,seat);assertTrue(p.x()>=61&&p.x()<71);assertEquals(39,p.y());}
        for(int seat=0;seat<4;seat++)assertEquals(PearlHurlRound.initialSafe(seat),PearlHurlRound.launchSpawn(4,seat));
    }
    @Test void allThreeSourceRoutesRetainEveryCheckpointAndTheirOriginalRadii()throws Exception {
        var clauses=PearlHurlRound.clauses(rules().getAsJsonArray("checkpoints"));assertEquals(56,clauses.size());
        for(int path=1;path<=3;path++) {
            int last=new int[]{23,21,19}[path-1];var values=new HashSet<Integer>();int checkpoint=0;
            for(int goal=1;goal<=last;goal++) {
                int route=path,target=goal;var clause=clauses.stream().filter(c->(c.pathMask()&(1<<route))!=0&&c.value()==target).findFirst().orElseThrow();values.add(clause.value());
                assertTrue(clause.radius()>=3&&clause.radius()<=7);checkpoint=PearlHurlRound.nextCheckpoint(path,checkpoint,clause.x(),clause.y(),clause.z(),clauses);assertEquals(goal,checkpoint);
            }
            assertEquals(last,values.size());assertEquals(last,checkpoint);
        }
    }
    @Test void exactSourcePreviousRangesAllowInitialSecondCheckpointButRejectLargerSkips()throws Exception {
        var c=PearlHurlRound.clauses(rules().getAsJsonArray("checkpoints"));
        for(int path=1;path<=3;path++)assertEquals(2,PearlHurlRound.nextCheckpoint(path,0,32,38,-123,c));
        assertEquals(0,PearlHurlRound.nextCheckpoint(1,0,23,50,-111,c));
        assertEquals(0,PearlHurlRound.nextCheckpoint(2,0,7,36,-131,c));
        assertEquals(0,PearlHurlRound.nextCheckpoint(3,0,7,36,-131,c));
        assertEquals(0,PearlHurlRound.nextCheckpoint(1,0,1000,36,-120,c));
        assertEquals(1,PearlHurlRound.nextCheckpoint(1,0,61,36,-120,c));
        assertEquals(0,PearlHurlRound.nextCheckpoint(1,0,61.001,36,-120,c));
    }
    @Test void supplyRetainsElevenTickFirstPearlAndTwentyFiveTickReload() {
        int counter=14;for(int i=1;i<11;i++){counter=PearlHurlRound.nextReload(counter,false);assertNotEquals(25,counter);}
        counter=PearlHurlRound.nextReload(counter,false);assertEquals(25,counter);counter=PearlHurlRound.nextReload(counter,false);assertEquals(26,counter);
        assertEquals(26,PearlHurlRound.nextReload(counter,false));counter=PearlHurlRound.nextReload(counter,true);assertEquals(1,counter);
        for(int i=1;i<24;i++){counter=PearlHurlRound.nextReload(counter,false);assertNotEquals(25,counter);}counter=PearlHurlRound.nextReload(counter,false);assertEquals(25,counter);
    }
    @Test void rawProgressDominatesEarlierCheckpointAndPreservesSameTickTies() {
        assertEquals(3,PearlHurlRound.rawScore(4,0,-1,List.of(-1,-1,-1,-1),false));
        assertEquals(5,PearlHurlRound.rawScore(4,1,10,List.of(10,20,-1,-1),true));
        assertEquals(4,PearlHurlRound.rawScore(4,1,20,List.of(10,20,-1,-1),true));
        assertEquals(4,PearlHurlRound.rawScore(4,1,20,List.of(20,20,-1,-1),true));
        assertEquals(98,PearlHurlRound.rawScore(4,24,40,List.of(40,50,60,-1),true));
    }
    @Test void fullOriginalIslandStreamsWithoutDroppingPillarsLightsOrBubbleColumns()throws Exception {
        int blocks=0,palette=0;String min=null,max=null;
        try(var input=PearlHurlRoundTest.class.getResourceAsStream("/pvp/party/geometry/pearl_hurl.json.gz")) {
            assertNotNull(input);try(var reader=new JsonReader(new InputStreamReader(new GZIPInputStream(input),StandardCharsets.UTF_8))) {
                reader.beginObject();while(reader.hasNext())switch(reader.nextName()) {
                    case "bounds"->{var b=JsonParser.parseReader(reader).getAsJsonObject();min=b.get("min").toString();max=b.get("max").toString();}
                    case "palette"->{reader.beginArray();while(reader.hasNext()){reader.skipValue();palette++;}reader.endArray();}
                    case "blocks"->{reader.beginArray();while(reader.hasNext()){reader.skipValue();blocks++;}reader.endArray();}
                    default->reader.skipValue();
                }reader.endObject();
            }
        }
        assertEquals("[-64,0,-140]",min);assertEquals("[140,106,95]",max);assertEquals(938098,blocks);assertEquals(66,palette);
        var r=rules();assertTrue(r.getAsJsonObject("geometry").getAsJsonObject("omitted").isEmpty());assertTrue(r.getAsJsonObject("geometry").getAsJsonObject("replacements").isEmpty());
        assertEquals(43,r.getAsJsonArray("signs").size());assertEquals(22,r.getAsJsonArray("gateways").size());
        assertTrue(r.getAsJsonArray("unresolvedGateways").isEmpty());assertEquals(18,r.getAsJsonArray("remoteGeometry").size());
        assertEquals(751805,r.getAsJsonArray("remoteGeometry").asList().stream().mapToInt(v->v.getAsJsonObject().get("blocks").getAsInt()).sum());
        for(var region:r.getAsJsonArray("remoteGeometry"))assertTrue(region.getAsJsonObject().getAsJsonArray("missingSourceChunks").isEmpty());
        assertEquals("1.21.10",r.getAsJsonObject("gatewayResolution").get("version").getAsString());assertEquals(69420,r.getAsJsonObject("gatewayResolution").get("sourceSeed").getAsInt());
        assertEquals(10,r.getAsJsonObject("gatewayResolution").getAsJsonArray("nativeResolvedExits").size());
        assertTrue(r.getAsJsonArray("signs").asList().stream().anyMatch(v->v.getAsJsonObject().getAsJsonObject("front").get("glowing").getAsBoolean()));
    }
    @Test void literalGenerationRetainsBothFullAirOverwritingTunnelsAndOriginalTiming()throws Exception {
        var r=rules();assertEquals(30,r.getAsJsonArray("generation").size());int tunnels=0;
        for(var value:r.getAsJsonArray("generation")) {
            var op=value.getAsJsonObject();if(!op.get("type").getAsString().equals("clone"))continue;
            var a=op.getAsJsonArray("min");var b=op.getAsJsonArray("max");int volume=1;for(int i=0;i<3;i++)volume*=Math.abs(a.get(i).getAsInt()-b.get(i).getAsInt())+1;
            if(volume>1){assertEquals(1280,volume);tunnels++;}
        }
        assertEquals(2,tunnels);assertEquals(3600,r.getAsJsonObject("rules").get("timeLimitTicks").getAsInt());assertEquals(25,r.getAsJsonObject("rules").get("pearlReloadTicks").getAsInt());
        assertTrue(r.getAsJsonObject("functions").get("pearl_hurl_start").getAsString().contains("timeLeft variables 3600"));
    }
}
