package com.example.pvp.mygo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for the named Mirage/Inferno resources and their isolated layouts. */
final class MyGoMapCatalogTest {
    @Test void allCatalogMapsShipValidatedLayoutMetadata() throws Exception {
        for (String id : new String[]{"mirage", "inferno"}) {
            JsonObject map = read(id);
            assertEquals(id, map.get("id").getAsString());
            assertTrue(map.get("blocks").getAsJsonArray().size() > 50_000, id);
            assertTrue(map.has("source"), id + " must retain provenance");
            assertEquals(5, map.getAsJsonObject("spawns").getAsJsonArray("T").size());
            assertEquals(5, map.getAsJsonObject("spawns").getAsJsonArray("CT").size());
            assertEquals(2, map.getAsJsonArray("sites").size());
            assertEquals("A", map.getAsJsonArray("sites").get(0).getAsJsonObject().get("name").getAsString());
            assertEquals("B", map.getAsJsonArray("sites").get(1).getAsJsonObject().get("name").getAsString());
            int[] min = position(map.getAsJsonObject("bounds").getAsJsonArray("min"));
            int[] max = position(map.getAsJsonObject("bounds").getAsJsonArray("max"));
            assertTrue(min[0] >= -160 && max[0] <= 160, id);
            assertTrue(min[2] >= -160 && max[2] <= 160, id);
            assertTrue(contains(map.getAsJsonObject("playBounds"), new int[]{max[0], max[1] + 3, max[2]}), id);
            assertStanding(map, position(map.getAsJsonArray("sites").get(0).getAsJsonObject().getAsJsonArray("pos")));
            assertStanding(map, position(map.getAsJsonArray("sites").get(1).getAsJsonObject().getAsJsonArray("pos")));
        }
    }

    @Test void unforcedSelectionCanChooseEveryCatalogMap() {
        Set<String> selected = new HashSet<>();
        for (int seed = 0; seed < 48; seed++) selected.add(MyGoMapBlueprint.randomKey(seed));
        assertEquals(Set.of("dust2", "mirage", "inferno"), selected);
    }

    private static JsonObject read(String id) throws Exception {
        try (InputStream input = MyGoMapCatalogTest.class.getResourceAsStream("/pvp/mygo/maps/" + id + ".json.gz")) {
            assertNotNull(input, id);
            try (var reader = new InputStreamReader(new GZIPInputStream(input), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static void assertStanding(JsonObject map, int[] pos) {
        JsonArray palette = map.getAsJsonArray("palette");
        Set<String> occupied = new HashSet<>();
        for (var element : map.getAsJsonArray("blocks")) {
            JsonArray cell = element.getAsJsonArray();
            occupied.add(key(cell.get(0).getAsInt(), cell.get(1).getAsInt(), cell.get(2).getAsInt()));
        }
        assertFalse(occupied.contains(key(pos[0], pos[1], pos[2])));
        assertFalse(occupied.contains(key(pos[0], pos[1] + 1, pos[2])));
        assertTrue(occupied.contains(key(pos[0], pos[1] - 1, pos[2])));
        assertNotNull(palette);
    }

    private static int[] position(JsonArray value) {
        return new int[]{value.get(0).getAsInt(), value.get(1).getAsInt(), value.get(2).getAsInt()};
    }

    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }

    private static boolean contains(JsonObject bounds, int[] pos) {
        for (int axis = 0; axis < 3; axis++) {
            if (pos[axis] < bounds.getAsJsonArray("min").get(axis).getAsInt()
                    || pos[axis] > bounds.getAsJsonArray("max").get(axis).getAsInt()) return false;
        }
        return true;
    }
}
