package com.example.pvp.mygo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 真实存档的布局是游戏规则输入：逐点检查净空/支撑和地域，防止换图后卡墙或串场。 */
final class MyGoMapAssetsTest {
    private static JsonObject map;
    private static final Map<String, String> blocks = new HashMap<>();

    @BeforeAll static void read() throws Exception {
        try (InputStream input = MyGoMapAssetsTest.class.getResourceAsStream("/pvp/mygo/maps/dust2.json.gz")) {
            assertNotNull(input, "The actual Dust II geometry must ship in the server mod");
            try (var reader = new InputStreamReader(new GZIPInputStream(input), StandardCharsets.UTF_8)) {
                map = JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
        JsonArray palette = map.getAsJsonArray("palette");
        for (var entry : map.getAsJsonArray("blocks")) {
            JsonArray cell = entry.getAsJsonArray();
            assertNull(blocks.put(key(cell.get(0).getAsInt(), cell.get(1).getAsInt(), cell.get(2).getAsInt()),
                    palette.get(cell.get(3).getAsInt()).getAsJsonObject().get("Name").getAsString()), "Duplicate coordinate");
        }
    }

    @Test void actualDownloadedArchitectureReplacesTheSmallProceduralWarehouse() {
        assertEquals("lesraisins_dust2", map.get("id").getAsString());
        assertTrue(blocks.size() > 50_000, "Retain the downloaded map rather than a renamed procedural field");
        assertTrue(map.has("source"), "The extracted map must retain provenance");
        assertEquals("3debce04b6fb9183038b88a0a751927a7b431e23a3a52ee4d9fa570710085803",
                map.getAsJsonObject("source").get("sha256").getAsString());
    }

    @Test void allTenSpawnsHaveSeparateSolidFloorsAndClearHeadsInsideTheirOwnBuyZone() {
        Set<String> unique = new HashSet<>();
        for (String side : new String[]{"T", "CT"}) {
            JsonArray spawns = map.getAsJsonObject("spawns").getAsJsonArray(side);
            assertEquals(5, spawns.size());
            JsonObject zone = map.getAsJsonObject("buyZones").getAsJsonObject(side);
            for (var entry : spawns) {
                JsonObject spawn = entry.getAsJsonObject();
                int[] pos = position(spawn.getAsJsonArray("pos"));
                assertTrue(unique.add(key(pos[0], pos[1], pos[2])), "Overlapping team spawns");
                assertTrue(contains(zone, pos), side + " spawn is outside its buy zone");
                assertTrue(contains(map.getAsJsonObject("playBounds"), pos));
                assertStanding(pos);
                assertTrue(Float.isFinite(spawn.get("yaw").getAsFloat()));
            }
        }
        assertEquals(10, unique.size());
    }

    @Test void bothOriginalSitesAndMovementProbeHaveUsableFloorsOnTheirActualLevel() {
        JsonArray sites = map.getAsJsonArray("sites");
        assertEquals(2, sites.size());
        for (int i = 0; i < 2; i++) {
            JsonObject site = sites.get(i).getAsJsonObject();
            assertEquals(i == 0 ? "A" : "B", site.get("name").getAsString());
            int[] pos = position(site.getAsJsonArray("pos"));
            assertStanding(pos);
            assertTrue(contains(site, pos));
            assertTrue(contains(map.getAsJsonObject("playBounds"), pos));
            assertTrue(site.getAsJsonArray("max").get(1).getAsInt() - site.getAsJsonArray("min").get(1).getAsInt() <= 3,
                    "A roof above a site must not become a second plant floor");
        }
        assertStanding(position(map.getAsJsonObject("testAnchors").getAsJsonArray("range")));
        assertStanding(position(map.getAsJsonObject("testAnchors").getAsJsonArray("raised")));
    }

    @Test void mapAndDeathRoomRemainInsideTheShared384BlockRegion() {
        JsonObject bounds = map.getAsJsonObject("bounds");
        for (int axis : new int[]{0, 2}) {
            assertTrue(bounds.getAsJsonArray("min").get(axis).getAsInt() >= -160);
            assertTrue(bounds.getAsJsonArray("max").get(axis).getAsInt() <= 160);
        }
        int[] room = position(map.getAsJsonArray("spectator"));
        assertFalse(contains(map.getAsJsonObject("playBounds"), room), "Death room must be separate");
        assertTrue(Math.abs(room[0]) + 3 <= 160 && Math.abs(room[2]) + 3 <= 160);
    }

    @Test void sourceGameplayAndUnstableBlocksAreExcluded() {
        for (var entry : map.getAsJsonArray("palette")) {
            JsonObject state = entry.getAsJsonObject();
            String name = state.get("Name").getAsString();
            assertFalse(name.contains("command_block") || name.endsWith(":jigsaw") || name.endsWith(":spawner"));
            assertFalse(name.endsWith("_concrete_powder") || Set.of("minecraft:sand", "minecraft:red_sand",
                    "minecraft:gravel", "minecraft:water", "minecraft:lava").contains(name), name);
            if (state.has("Properties") && state.getAsJsonObject("Properties").has("waterlogged"))
                assertEquals("false", state.getAsJsonObject("Properties").get("waterlogged").getAsString());
        }
    }

    private static void assertStanding(int[] pos) {
        assertFalse(blocks.containsKey(key(pos[0], pos[1], pos[2])), "Feet obstructed at " + key(pos[0],pos[1],pos[2]));
        assertFalse(blocks.containsKey(key(pos[0], pos[1] + 1, pos[2])), "Head obstructed");
        String floor = blocks.get(key(pos[0], pos[1] - 1, pos[2]));
        assertNotNull(floor, "No floor");
        assertFalse(floor.contains("slab") || floor.contains("stairs") || floor.contains("wall") || floor.contains("trapdoor"), floor);
    }

    private static int[] position(JsonArray pos) { return new int[]{pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt()}; }
    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }
    private static boolean contains(JsonObject bounds, int[] pos) {
        for (int axis = 0; axis < 3; axis++)
            if (pos[axis] < bounds.getAsJsonArray("min").get(axis).getAsInt()
                    || pos[axis] > bounds.getAsJsonArray("max").get(axis).getAsInt()) return false;
        return true;
    }
}
