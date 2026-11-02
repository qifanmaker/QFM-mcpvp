package com.example.pvp.arena.party;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Profile tests use source metadata, without initializing Minecraft item registries. */
class PartyResidentCatalogTest {
    private JsonObject resource() throws Exception {
        try (var input = getClass().getResourceAsStream("/pvp/party/resident_profiles.json")) {
            assertNotNull(input);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void everyRegisteredSourceGameHasItsResidentVariantAndEveryAuditedGeometryIsDecoded() throws Exception {
        JsonObject root = resource();
        assertEquals(101, root.get("referenceCenterY").getAsInt());
        assertEquals(113, root.getAsJsonArray("geometryNames").size());
        assertEquals(33, root.getAsJsonArray("profiles").size());
        var geometry = new HashSet<String>();
        for (var name : root.getAsJsonArray("geometryNames")) {
            assertTrue(geometry.add(name.getAsString()));
            assertNotNull(getClass().getResource("/pvp/party/geometry/" + name.getAsString() + ".json.gz"));
            assertFalse(name.getAsString().startsWith("odd_one_out"));
            assertFalse(name.getAsString().startsWith("sheep_scramble"));
            assertFalse(name.getAsString().startsWith("trident_tumblers"));
        }
        Map<Integer, Integer> counts = new TreeMap<>();
        for (var profile : root.getAsJsonArray("profiles")) counts.merge(profile.getAsJsonObject().get("sourceId").getAsInt(), 1, Integer::sum);
        assertEquals(28, counts.size());
        for (int id = 401; id <= 428; id++) assertEquals(id == 408 || id == 415 ? 2 : id == 412 ? 4 : 1, counts.get(id));
    }

    @Test void completeTranslatedBlueprintsFitTheirActualProtectedBoundsAndNativeWorldHeight() throws Exception {
        for (var encoded : resource().getAsJsonArray("profiles")) {
            JsonObject profile = encoded.getAsJsonObject();
            var bounds = profile.getAsJsonArray("bounds");
            for (var value : profile.getAsJsonArray("placements")) {
                JsonObject placement = value.getAsJsonObject();
                String name = placement.get("geometry").getAsString();
                var offset = placement.getAsJsonArray("translationRelativeToCenter");
                try (var input = new GZIPInputStream(getClass().getResourceAsStream("/pvp/party/geometry/" + name + ".json.gz"));
                     var reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    JsonObject source = null;
                    reader.beginObject();
                    while (reader.hasNext()) {
                        if (reader.nextName().equals("bounds")) { source = JsonParser.parseReader(reader).getAsJsonObject(); break; }
                        reader.skipValue();
                    }
                    assertNotNull(source, name);
                    for (String side : new String[]{"min", "max"}) {
                        var point = source.getAsJsonArray(side);
                        int x = point.get(0).getAsInt() + offset.get(0).getAsInt();
                        int y = point.get(1).getAsInt() + offset.get(1).getAsInt() + 101;
                        int z = point.get(2).getAsInt() + offset.get(2).getAsInt();
                        assertTrue(x >= bounds.get(0).getAsInt() && x <= bounds.get(1).getAsInt(), name + " X");
                        assertTrue(z >= bounds.get(2).getAsInt() && z <= bounds.get(3).getAsInt(), name + " Z");
                        assertTrue(y >= -64 && y <= 319, name + " Y=" + y);
                    }
                }
            }
        }
    }

    @Test void sourceCountBoundariesRetainOriginalVariantsWithoutNewRandomChoices() {
        for (int count = 2; count <= 10; count++) {
            assertEquals(count >= 5 ? "spleef_large" : "spleef_small", PartyResidentCatalog.variantKey(408, count, 0));
            assertEquals(count <= 4 ? "runner_small" : "runner_large", PartyResidentCatalog.variantKey(415, count, 0));
            if (count != 4) assertEquals(count <= 3 ? "tile_capture_small" : "tile_capture_crowd", PartyResidentCatalog.variantKey(412, count, 0));
        }
        assertEquals("tile_capture_small", PartyResidentCatalog.variantKey(412, 4, 0));
        assertEquals("tile_capture_medium", PartyResidentCatalog.variantKey(412, 4, 1));
        assertEquals("tile_capture_large", PartyResidentCatalog.variantKey(412, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> PartyResidentCatalog.variantKey(412, 4, 3));
        assertThrows(IllegalArgumentException.class, () -> PartyResidentCatalog.variantKey(412, 4, -1));
    }

    @Test void longCoursesAndFixedCloneTranslationsKeepTheirOriginalCoordinates() throws Exception {
        var profiles = new TreeMap<String, JsonObject>();
        for (var row : resource().getAsJsonArray("profiles")) profiles.put(row.getAsJsonObject().get("key").getAsString(), row.getAsJsonObject());
        var rocket = profiles.get("rocket_launch");
        assertEquals(-2250, rocket.getAsJsonArray("bounds").get(2).getAsInt());
        assertEquals(19, profiles.get("pearl_hurl").getAsJsonArray("placements").size());
        var dropper = profiles.get("dropper").getAsJsonArray("placements");
        var top = dropper.get(1).getAsJsonObject().getAsJsonArray("translationRelativeToCenter");
        assertEquals(136, top.get(0).getAsInt());
        assertEquals(209, top.get(1).getAsInt());
        assertEquals(40, top.get(2).getAsInt());
        var placement = new PartyResidentCatalog.Placement("dropper_pool", new BlockPos(184, -101, 41));
        assertEquals(0, placement.translation(new BlockPos(88, 101, 88)).getY());
        assertThrows(IllegalArgumentException.class, () -> placement.translation(new BlockPos(88, 100, 88)));
        assertEquals(5, profiles.get("weapon_devolution").getAsJsonArray("placements").size());
    }
}
