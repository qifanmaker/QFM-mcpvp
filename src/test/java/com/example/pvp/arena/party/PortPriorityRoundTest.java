package com.example.pvp.arena.party;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Native protocol covers GUI/physics; these tests verify source random pools, geometry and checkpoint ordering. */
final class PortPriorityRoundTest {
    private static JsonObject resource(String name,boolean compressed)throws Exception {
        try(var input=PortPriorityRoundTest.class.getResourceAsStream("/pvp/party/"+name)) {
            assertNotNull(input,name);
            try(var reader=new InputStreamReader(compressed?new GZIPInputStream(input):input,StandardCharsets.UTF_8)){return JsonParser.parseReader(reader).getAsJsonObject();}
        }
    }
    // Regression reason: native decoder audit found tree substitutes lost axis/leaf
    // properties. Stream cells and retain just eight original source witnesses.
    private static final int[][] TREE_CELLS={{-524,-58,-348},{-524,-58,-347},{-524,-58,-345},{-523,-56,-346},{-511,-57,-346},{-524,-52,-349},{-523,-62,-349},{-521,-52,-349}};
    private static int cellKey(int x,int y,int z){return (x<<18)|(y<<9)|z;}
    private record GeometryWitness(String minimum,String maximum,int blocks,Map<Integer,JsonObject> trees) { }
    private static GeometryWitness geometry()throws Exception {
        try(var input=PortPriorityRoundTest.class.getResourceAsStream("/pvp/party/geometry/port_priority.json.gz")) {
            assertNotNull(input);String min=null,max=null;int blocks=0;
            var palette=new ArrayList<JsonObject>();var witnessIds=new HashMap<Integer,Integer>();var required=new HashSet<Integer>();
            for(int[] p:TREE_CELLS)required.add(cellKey(p[0]+524,p[1]+64,p[2]+512));
            try(var reader=new JsonReader(new InputStreamReader(new GZIPInputStream(input),StandardCharsets.UTF_8))) {
                reader.beginObject();while(reader.hasNext())switch(reader.nextName()) {
                    case "bounds"->{var bounds=JsonParser.parseReader(reader).getAsJsonObject();min=bounds.get("min").toString();max=bounds.get("max").toString();}
                    case "palette"->{reader.beginArray();while(reader.hasNext())palette.add(JsonParser.parseReader(reader).getAsJsonObject());reader.endArray();}
                    case "blocks"->{reader.beginArray();while(reader.hasNext()){
                        reader.beginArray();int key=cellKey(reader.nextInt(),reader.nextInt(),reader.nextInt());int state=reader.nextInt();reader.endArray();
                        if(required.contains(key))witnessIds.put(key,state);blocks++;
                    }reader.endArray();}
                    default->reader.skipValue();
                }reader.endObject();
            }
            var trees=new HashMap<Integer,JsonObject>();witnessIds.forEach((key,index)->trees.put(key,palette.get(index)));
            return new GeometryWitness(min,max,blocks,trees);
        }
    }
    @Test void locationContentsAndTargetPoolsRetainTheExactCardinalities() {
        Set<Integer> seen=new HashSet<>();
        for(int players=2;players<=10;players++)for(int seed=0;seed<100;seed++) {
            var layout=PortPriorityRound.generate(new Random(seed),players);
            assertEquals(7,new HashSet<>(layout.sites()).size());assertEquals(7,new HashSet<>(layout.contents()).size());
            assertEquals(3,new HashSet<>(layout.targets()).size());assertEquals(players,layout.orders().size());
            seen.addAll(layout.sites());
            for(var order:layout.orders())assertEquals(new HashSet<>(layout.targets()),new HashSet<>(order));
        }
        assertEquals(12,seen.size());
    }
    @Test void permutationsAreDistinctUpToFourAndUsedAtMostTwiceForLargerGroups() {
        for(int players=2;players<=10;players++)for(int seed=0;seed<100;seed++) {
            var orders=PortPriorityRound.generate(new Random(seed),players).orders();
            var count=new HashMap<List<Integer>,Integer>();orders.forEach(order->count.merge(order,1,Integer::sum));
            if(players<=4)assertEquals(players,count.size());
            else assertTrue(count.values().stream().allMatch(n->n<=2));
        }
    }
    @Test void checkpointRecencyRewardsEarlierProgressAndPreservesSameTickTies() {
        assertEquals(3,PortPriorityRound.rawScore(4,0,-1,List.of(-1,-1,-1,-1),false));
        assertEquals(5,PortPriorityRound.rawScore(4,1,10,List.of(10,20,-1,-1),true));
        assertEquals(4,PortPriorityRound.rawScore(4,1,20,List.of(10,20,-1,-1),true));
        assertEquals(2,PortPriorityRound.rawScore(4,0,-1,List.of(10,20,-1,-1),true));
        assertEquals(4,PortPriorityRound.rawScore(4,1,20,List.of(20,20,-1,-1),true));
        assertEquals(14,PortPriorityRound.rawScore(4,3,50,List.of(50,70,90,-1),true));
    }
    @Test void completeHarborRetainsOriginalOceanDepthAndExplicitCompatibilityOnly()throws Exception {
        var map=geometry();var rules=resource("port_priority_rules.json",false);
        assertEquals("[-524,-64,-512]",map.minimum());
        assertEquals("[-297,68,-238]",map.maximum());
        assertEquals(1741484,map.blocks());
        assertEquals(1092,rules.getAsJsonObject("geometry").get("compatibleTreePropertiesPreserved").getAsInt());
        assertEquals(8,map.trees().size());
        for(int i=0;i<TREE_CELLS.length;i++){
            int[] p=TREE_CELLS[i];var state=map.trees().get(cellKey(p[0]+524,p[1]+64,p[2]+512));var props=state.getAsJsonObject("Properties");
            assertEquals("minecraft:birch_"+(i<5?"leaves":i<7?"log":"wood"),state.get("Name").getAsString());
            if(i<5){assertEquals(Integer.toString(i+1),props.get("distance").getAsString());assertEquals("false",props.get("persistent").getAsString());assertEquals("false",props.get("waterlogged").getAsString());}
            else assertEquals(i==5?"x":"y",props.get("axis").getAsString());
        }assertEquals(12,rules.getAsJsonArray("sites").size());
        assertEquals(361,rules.getAsJsonArray("banners").size());assertEquals(28,rules.getAsJsonArray("signs").size());
        assertEquals(6,rules.getAsJsonArray("pistons").size());assertEquals(1,rules.getAsJsonArray("skulls").size());
        assertEquals(33,rules.getAsJsonObject("geometry").getAsJsonObject("replacements").get("minecraft:iron_chain -> minecraft:chain").getAsInt());
        assertEquals(1267,rules.getAsJsonObject("geometry").getAsJsonObject("replacements").get("minecraft:pale_moss_block -> minecraft:moss_block").getAsInt());
        assertTrue(rules.getAsJsonObject("geometry").getAsJsonObject("omitted").isEmpty());
    }
    @Test void sourceUnlockedGoodsAndLiteralNavigationRemainAvailable()throws Exception {
        var rules=resource("port_priority_rules.json",false);int unlockedGoods=0,lockedTemplates=0,cobwebPots=0;
        for(var value:rules.getAsJsonArray("containers")) {
            var container=value.getAsJsonObject();var pos=container.getAsJsonArray("position");
            if(pos.get(1).getAsInt()==30&&pos.get(2).getAsInt()==-239) {
                Set<String> questItems=Set.of("minecraft:copper_ingot","minecraft:netherite_ingot","minecraft:prismarine_shard","minecraft:quartz","minecraft:gold_ingot","minecraft:emerald","minecraft:diamond");
                if(container.getAsJsonArray("items").asList().stream().anyMatch(item->questItems.contains(item.getAsJsonObject().get("id").getAsString())))unlockedGoods++;
            }
            if(pos.get(1).getAsInt()==13&&pos.get(2).getAsInt()==-308&&container.has("lock"))lockedTemplates++;
            if(container.get("type").getAsString().equals("minecraft:decorated_pot")&&!container.getAsJsonArray("items").isEmpty())cobwebPots++;
        }
        assertEquals(4,unlockedGoods);assertEquals(7,lockedTemplates);assertEquals(6,cobwebPots);
        String signs=rules.getAsJsonArray("signs").toString();assertTrue(signs.contains("To the Engine"));assertTrue(signs.contains("FIRST AID"));assertTrue(signs.contains("←"));
        assertEquals(4800,rules.getAsJsonObject("rules").get("timeLimitTicks").getAsInt());
        String chest=rules.getAsJsonObject("functions").get("port_priority_set_independent_chest").getAsString();assertTrue(chest.contains("Slot:13b"));assertTrue(chest.contains("Copper Ingot Key"));
    }
}
