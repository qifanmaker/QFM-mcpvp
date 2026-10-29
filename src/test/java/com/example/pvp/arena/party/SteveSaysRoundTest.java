package com.example.pvp.arena.party;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Source-rule boundaries complement the real native-input protocol, including deliberately surprising source behavior. */
final class SteveSaysRoundTest {
    private static JsonObject resource(String name,boolean gzip)throws Exception {
        try(var input=SteveSaysRoundTest.class.getResourceAsStream("/pvp/party/"+name)) {
            assertNotNull(input,name);
            try(var reader=new InputStreamReader(gzip?new GZIPInputStream(input):input,StandardCharsets.UTF_8)) {return JsonParser.parseReader(reader).getAsJsonObject();}
        }
    }
    private static void outcome(int task,int type,SteveSaysRound.Input in,boolean first,int points,boolean eliminated) {
        var result=SteveSaysRound.adjudicate(task,type,in,first);assertEquals(points,result.points());assertEquals(eliminated,result.eliminate());
    }
    @Test void completeOriginalClassroomRetainsNativeDesksSlotsAndEveryBanner()throws Exception {
        var map=resource("geometry/steve_says.json.gz",true);var rules=resource("steve_says_rules.json",false);
        assertEquals("[-679,32,-35]",map.getAsJsonObject("bounds").get("min").toString());
        assertEquals("[-640,43,-10]",map.getAsJsonObject("bounds").get("max").toString());
        assertEquals(4547,map.getAsJsonArray("blocks").size());assertEquals(19,rules.getAsJsonObject("functions").size());
        assertEquals(79,rules.getAsJsonArray("banners").size());assertEquals(12,rules.getAsJsonArray("dynamicSlots").size());
        assertEquals(16,rules.getAsJsonArray("desks").size());assertEquals(22,rules.getAsJsonObject("rules").get("actions").getAsInt());
        int patterned=0;for(var b:rules.getAsJsonArray("banners"))if(!b.getAsJsonObject().getAsJsonArray("patterns").isEmpty())patterned++;
        assertEquals(9,patterned);assertTrue(rules.getAsJsonObject("geometry").getAsJsonObject("replacements").isEmpty());
        assertTrue(rules.getAsJsonObject("functions").get("ssnexttask").getAsString().contains("SStask=12"));
        assertTrue(rules.getAsJsonObject("functions").get("ssnexttask2").getAsString().contains("scoreboard players set @a SSrandomizer 2"));
    }
    @Test void nativeCountBoundariesKeepExactJumpSignAndTuneRequirements() {
        var in=new SteveSaysRound.Input();in.jumps=1;outcome(4,1,in,true,4,false);
        in.jumps=2;outcome(4,1,in,true,0,false);outcome(4,2,in,false,0,true);
        in=new SteveSaysRound.Input();in.clicks=1;outcome(6,1,in,true,4,false);
        in.clicks=2;outcome(6,1,in,true,0,true);outcome(6,3,in,false,0,true);
        in=new SteveSaysRound.Input();in.tunes=3;outcome(7,1,in,false,3,false);
        in.tunes=4;outcome(7,1,in,true,0,true);
        in.tunes=3;in.plays=1;in.bad=true;outcome(7,1,in,true,0,true);
    }
    @Test void noSteveInstructionsAndAction12TrapsRewardRefraining() {
        for(int task=1;task<=10;task++)outcome(task,2,new SteveSaysRound.Input(),false,3,false);
        var trap=new SteveSaysRound.Input();outcome(3,3,trap,false,3,false);trap.bad=true;outcome(3,3,trap,false,0,true);
        trap=new SteveSaysRound.Input();outcome(8,3,trap,false,3,false);trap.drops=1;outcome(8,3,trap,false,0,true);
        trap=new SteveSaysRound.Input();outcome(10,3,trap,false,3,false);trap.drops=1;outcome(10,3,trap,false,0,true);
    }
    @Test void stoneGoalCanEarnPointsBeforeItsLaterWrongDropEliminates() {
        var in=new SteveSaysRound.Input();in.complete=true;in.otherStone=false;
        outcome(8,1,in,true,4,true);in.otherStone=true;outcome(8,1,in,false,3,false);
        in.complete=false;in.drops=0;outcome(8,1,in,false,0,false);
    }
    @Test void stayStillActuallyInvertsWithoutSteveAndKeepsTinyMovementGap() {
        var in=new SteveSaysRound.Input();outcome(11,1,in,true,3,false);outcome(11,2,in,false,0,true);
        in.moved=true;in.displacementSquared=.01;outcome(11,1,in,true,0,true);outcome(11,2,in,true,4,false);
        in.moved=false;in.displacementSquared=.00005;outcome(11,2,in,false,0,false);
    }
    @Test void stickyPhysicalDeskAndCeilingGoalsRemainWrongAfterReturning() {
        var in=new SteveSaysRound.Input();in.onDesk=true;outcome(9,1,in,true,4,false);outcome(9,2,in,false,0,true);
        in=new SteveSaysRound.Input();in.looked=true;outcome(5,1,in,false,3,false);outcome(5,2,in,false,0,true);
        in=new SteveSaysRound.Input();in.complete=true;outcome(10,1,in,true,4,false);in.wrongDrops=1;outcome(10,1,in,true,0,true);
    }
}
