package com.example.pvp.mygo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 资源包是客户端进入模式的先决条件；检查版本、模型回退、透明瞄准中心及真实音频文件。 */
final class MyGoResourcePackAssetsTest {
    private static final Map<String, byte[]> FILES = new HashMap<>();

    @BeforeAll
    static void readPack() throws Exception {
        try (InputStream source = MyGoResourcePackAssetsTest.class.getResourceAsStream("/pvp/mygo/mygo-pack.zip")) {
            assertNotNull(source, "The actual distributed pack must be bundled in the mod");
            try (ZipInputStream zip = new ZipInputStream(source)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    assertNull(FILES.put(entry.getName(), zip.readAllBytes()), "Duplicate ZIP entry");
                }
            }
        }
    }

    private static JsonObject json(String name) {
        assertTrue(FILES.containsKey(name), "Missing resource: " + name);
        return JsonParser.parseString(new String(FILES.get(name), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void packTargetsExactClientVersionAndHasMatchingPublishedHash() throws Exception {
        assertEquals(34, json("pack.mcmeta").getAsJsonObject("pack").get("pack_format").getAsInt());
        try (InputStream pack = getClass().getResourceAsStream("/pvp/mygo/mygo-pack.zip");
             InputStream manifest = getClass().getResourceAsStream("/pvp/mygo/mygo-pack.sha1")) {
            assertNotNull(pack);
            assertNotNull(manifest);
            assertEquals(new String(manifest.readAllBytes(), StandardCharsets.UTF_8).trim(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(pack.readAllBytes())));
        }
    }

    @Test
    void onlyReservedCustomModelNumbersSelectGuns() {
        JsonArray overrides = json("assets/minecraft/models/item/iron_pickaxe.json").getAsJsonArray("overrides");
        String[] names = {"p9", "mp5", "ak", "m4", "aw"};
        for (int i = 0; i < names.length; i++) {
            assertEquals("pvp:item/mygo/" + names[i], selectedModel(overrides, 71001 + i));
        }
        assertNull(selectedModel(overrides, 0), "Ordinary pickaxes must keep their vanilla model");
        assertEquals("pvp:item/mygo/vanilla_iron_pickaxe", selectedModel(overrides, 71006));
        assertEquals("pvp:item/mygo/vanilla_iron_pickaxe", selectedModel(overrides, 99999));
        assertEquals("pvp:item/mygo/knife", selectedModel(json("assets/minecraft/models/item/iron_sword.json")
                .getAsJsonArray("overrides"), 71006));
        assertEquals("pvp:item/mygo/scope", selectedModel(json("assets/minecraft/models/item/carved_pumpkin.json")
                .getAsJsonArray("overrides"), 71010));
    }

    private static String selectedModel(JsonArray overrides, int value) {
        String model = null;
        for (var element : overrides) {
            JsonObject override = element.getAsJsonObject();
            if (value >= override.getAsJsonObject("predicate").get("custom_model_data").getAsInt()) {
                model = override.get("model").getAsString();
            }
        }
        return model;
    }

    @Test
    void everyGunHasDifferentThreeDimensionalGeometryAndResolvedMaterials() throws Exception {
        Set<String> geometry = new HashSet<>();
        for (String gun : new String[]{"p9", "mp5", "ak", "m4", "aw", "m16", "scar", "mini14", "mk14", "p90", "uzi", "vector", "tommy", "m24", "s686", "knife"}) {
            JsonObject model = json("assets/pvp/models/item/mygo/" + gun + ".json");
            JsonArray elements = model.getAsJsonArray("elements");
            assertTrue(elements.size() >= 3, gun + " needs actual barrel/receiver/grip geometry");
            assertTrue(geometry.add(elements.toString()), "Repeated placeholder geometry: " + gun);
            for (var part : elements) {
                JsonObject cuboid = part.getAsJsonObject();
                for (int axis = 0; axis < 3; axis++) {
                    double from = cuboid.getAsJsonArray("from").get(axis).getAsDouble();
                    double to = cuboid.getAsJsonArray("to").get(axis).getAsDouble();
                    assertTrue(from >= -16 && to <= 32 && from <= to, gun + " invalid element bounds");
                }
            }
            for (var material : model.getAsJsonObject("textures").entrySet()) {
                String[] location = material.getValue().getAsString().split(":", 2);
                byte[] texture = FILES.get("assets/" + location[0] + "/textures/" + location[1] + ".png");
                assertNotNull(texture, "Unresolved material: " + material.getValue());
                assertNotNull(ImageIO.read(new ByteArrayInputStream(texture)));
            }
        }
    }

    @Test
    void scopeHasAnUnobstructedTransparentCenterAndOpaqueRim() throws Exception {
        BufferedImage scope = ImageIO.read(new ByteArrayInputStream(
                FILES.get("assets/minecraft/textures/misc/pumpkinblur.png")));
        assertEquals(512, scope.getWidth());
        assertEquals(512, scope.getHeight());
        for (int x = 253; x <= 258; x++) {
            for (int y = 253; y <= 258; y++) {
                assertEquals(0, scope.getRGB(x, y) >>> 24, "Aiming point must remain transparent");
            }
        }
        assertTrue((scope.getRGB(0, 0) >>> 24) >= 220);
        assertEquals(255, scope.getRGB(256, 20) >>> 24);
    }

    @Test
    void everyGunPoseAndUtilityResolvesThroughVanillaCustomModelData() {
        JsonArray guns = json("assets/minecraft/models/item/iron_pickaxe.json").getAsJsonArray("overrides");
        for (MyGoWeapon weapon : MyGoWeapon.values()) {
            String name = weapon.name().toLowerCase(java.util.Locale.ROOT);
            assertEquals("pvp:item/mygo/" + name, selectedModel(guns, weapon.model));
            for (String pose : new String[]{"fire", "reload"}) {
                int offset = pose.equals("fire") ? 100 : 200;
                assertEquals("pvp:item/mygo/" + name + "_" + pose, selectedModel(guns, weapon.model + offset));
                assertEquals("pvp:item/mygo/" + name, json("assets/pvp/models/item/mygo/" + name + "_" + pose + ".json").get("parent").getAsString());
            }
        }
        JsonArray utilities = json("assets/minecraft/models/item/slime_ball.json").getAsJsonArray("overrides");
        for (MyGoUtilities.Type utility : MyGoUtilities.Type.values())
            assertEquals("pvp:item/mygo/" + utility.name().toLowerCase(java.util.Locale.ROOT), selectedModel(utilities, utility.model));
        assertEquals("pvp:item/mygo/fog", selectedModel(utilities, 72004));
    }

    @Test
    void adaptedAssetsCarryPinnedSourceAndLicense() {
        assertTrue(new String(FILES.get("WEAPON_PACK_LICENSE.txt"), StandardCharsets.UTF_8).contains("Pelmeshka_"));
        JsonObject source = json("WEAPON_PACK_source.json");
        assertEquals("MIT", source.get("license").getAsString());
        assertEquals("9ab80d963650c09a3c3b4e384db0133c9a7dd846", source.get("sha1").getAsString());
    }

    @Test
    void allEventsResolveToRealMonoOggSoundFiles() {
        JsonObject sounds = json("assets/pvp/sounds.json");
        for (String name : new String[]{"p9", "mp5", "ak", "m4", "aw", "m16", "scar", "mini14", "mk14", "p90", "uzi", "vector", "tommy", "m24", "s686", "reload", "empty", "plant", "defuse", "explode", "hit", "headshot", "throw", "flash", "smoke"}) {
            assertTrue(sounds.has("mygo." + name));
            String location = sounds.getAsJsonObject("mygo." + name).getAsJsonArray("sounds")
                    .get(0).getAsJsonObject().get("name").getAsString();
            assertEquals("pvp:mygo/" + name, location);
            byte[] audio = FILES.get("assets/pvp/sounds/mygo/" + name + ".ogg");
            assertNotNull(audio);
            assertTrue(audio.length > 1000, "Audio cannot be a placeholder");
            assertEquals("OggS", new String(audio, 0, 4, StandardCharsets.US_ASCII));
        }
    }
}
