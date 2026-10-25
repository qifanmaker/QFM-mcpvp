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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    void hidesVanillaSelectedBlockOutlineForMyGoTargets() {
        String shader = new String(FILES.get("assets/minecraft/shaders/core/rendertype_lines.fsh"), StandardCharsets.UTF_8);
        assertTrue(shader.contains("discard"), "MyGO pack must hide the vanilla lines selection pass");
    }

    @Test
    void onlyReservedCustomModelNumbersSelectGuns() {
        JsonArray overrides = json("assets/minecraft/models/item/iron_pickaxe.json").getAsJsonArray("overrides");
        for (MyGoWeapon weapon : MyGoWeapon.values()) {
            assertEquals("pvp:item/mygo/" + weapon.name().toLowerCase(java.util.Locale.ROOT),
                    selectedModel(overrides, weapon.model));
        }
        assertNull(selectedModel(overrides, 0), "Ordinary pickaxes must keep their vanilla model");
        assertEquals("pvp:item/mygo/vanilla_iron_pickaxe", selectedModel(overrides, 71006));
        assertEquals("pvp:item/mygo/vanilla_iron_pickaxe", selectedModel(overrides, 71010));
        assertEquals("pvp:item/mygo/vanilla_iron_pickaxe", selectedModel(overrides, 99999));
        assertFalse(FILES.containsKey("assets/minecraft/models/item/spyglass.json"),
                "MyGO scopes must preserve the ordinary spyglass model");
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
        Set<String> expected = new HashSet<>();
        for (MyGoWeapon weapon : MyGoWeapon.values()) expected.add(weapon.name().toLowerCase(java.util.Locale.ROOT));
        expected.add("knife");
        for (String gun : expected) {
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
        for (MyGoUtilities.Type utility : MyGoUtilities.Type.values()) {
            String item = utility.fire() ? "fire_charge" : "slime_ball";
            JsonArray utilities = json("assets/minecraft/models/item/" + item + ".json").getAsJsonArray("overrides");
            assertEquals("pvp:item/mygo/" + utility.name().toLowerCase(java.util.Locale.ROOT), selectedModel(utilities, utility.model));
        }
        JsonArray utilities = json("assets/minecraft/models/item/slime_ball.json").getAsJsonArray("overrides");
        assertEquals("pvp:item/mygo/fog", selectedModel(utilities, 72004));
    }

    @Test
    void originalAssetsCarryTheirLicenseAndCatalogAlignedWithRuntimeWeapons() {
        assertTrue(new String(FILES.get("MYGO_ASSETS_LICENSE.txt"), StandardCharsets.UTF_8).contains("MIT License"));
        JsonObject source = json("MYGO_ASSETS_source.json");
        assertEquals("MIT", source.get("license").getAsString());
        if (source.getAsJsonObject("audio").getAsJsonArray("operator_supplied_files").isEmpty())
            assertFalse(source.get("valve_assets_included").getAsBoolean());
        else {
            assertTrue(source.get("valve_assets_included").isJsonNull(), "Imported content ownership must not be falsely claimed");
            assertTrue(FILES.containsKey("MYGO_IMPORTED_AUDIO_LICENSE.txt"));
        }
        Set<String> names = new HashSet<>();
        for (var entry : source.getAsJsonArray("weapons")) {
            JsonObject gun = entry.getAsJsonObject();
            MyGoWeapon weapon = MyGoWeapon.valueOf(gun.get("name").getAsString().toUpperCase(java.util.Locale.ROOT));
            assertEquals(weapon.model, gun.get("model").getAsInt());
            assertEquals("mygo." + weapon.name().toLowerCase(java.util.Locale.ROOT), gun.get("sound").getAsString());
            assertTrue(names.add(weapon.name()), "Duplicate weapon in the distributed manifest");
        }
        assertEquals(MyGoWeapon.values().length, names.size());
    }

    @Test
    void allEventsResolveToRealMonoOggSoundFiles() {
        JsonObject sounds = json("assets/pvp/sounds.json");
        Set<String> events = new HashSet<>(Set.of("reload", "empty", "plant", "defuse", "explode", "hit", "headshot", "throw", "flash", "smoke"));
        for (MyGoWeapon weapon : MyGoWeapon.values()) events.add(weapon.name().toLowerCase(java.util.Locale.ROOT));
        for (String name : events) {
            assertTrue(sounds.has("mygo." + name));
            String location = sounds.getAsJsonObject("mygo." + name).getAsJsonArray("sounds")
                    .get(0).getAsJsonObject().get("name").getAsString();
            assertEquals("pvp:mygo/" + name, location);
            byte[] audio = FILES.get("assets/pvp/sounds/mygo/" + name + ".ogg");
            assertNotNull(audio);
            assertTrue(audio.length > 1000, "Audio cannot be a placeholder");
            assertEquals("OggS", new String(audio, 0, 4, StandardCharsets.US_ASCII));
            assertMonoVorbis(audio);
        }
    }

    @Test
    void footstepsAndUtilityFoleyHaveMaterialVariantsAndPositionalMonoAudio() {
        JsonObject sounds = json("assets/pvp/sounds.json");
        for (String surface : new String[]{"stone", "metal", "wood", "dirt", "water"}) {
            for (String movement : new String[]{"step", "land"}) {
                JsonArray variants = sounds.getAsJsonObject("mygo." + movement + "." + surface).getAsJsonArray("sounds");
                assertEquals(movement.equals("step") ? 4 : 2, variants.size());
                for (var variant : variants) {
                    JsonObject sound = variant.getAsJsonObject();
                    assertEquals(movement.equals("step") ? 16 : 20, sound.get("attenuation_distance").getAsInt());
                    assertSoundReference(sound);
                }
            }
        }
        for (String event : new String[]{"grenade.pin", "grenade.bounce.hard", "grenade.bounce.metal",
                "grenade.bounce.soft", "smoke.end", "jump", "equip"}) {
            for (var sound : sounds.getAsJsonObject("mygo." + event).getAsJsonArray("sounds"))
                assertSoundReference(sound.getAsJsonObject());
        }
        JsonObject provenance = json("MYGO_ASSETS_source.json").getAsJsonObject("audio");
        assertEquals("MIT", provenance.get("generated_license").getAsString());
        if (!provenance.getAsJsonArray("operator_supplied_files").isEmpty())
            assertTrue(FILES.containsKey(provenance.get("operator_supplied_license").getAsString()), "Audio overrides require their included license/credits");
    }

    @Test
    void vanillaMovementSoundsAreMutedWithoutSilencingOtherInteractions() {
        JsonObject muted = json("assets/minecraft/sounds.json");
        for (String event : new String[]{"block.stone.step", "block.wood.step", "block.metal.step", "block.sand.step",
                "entity.player.swim", "entity.player.splash", "entity.player.splash.high_speed",
                "entity.player.small_fall", "entity.player.big_fall", "entity.generic.small_fall"}) {
            assertTrue(muted.has(event), event);
        }
        for (var event : muted.entrySet()) {
            String name = event.getKey();
            assertTrue(name.startsWith("block.") && name.endsWith(".step")
                    || name.matches("entity\\.(player|generic)\\.(swim|splash|small_fall|big_fall)")
                    || name.equals("entity.player.splash.high_speed"), "Unexpected muted event: " + name);
            assertTrue(event.getValue().getAsJsonObject().get("replace").getAsBoolean());
            assertEquals(0, event.getValue().getAsJsonObject().getAsJsonArray("sounds").size());
        }
        assertFalse(muted.has("block.stone.break"));
        assertFalse(muted.has("block.stone.hit"));
        assertFalse(muted.has("block.stone.place"));
        assertFalse(muted.has("entity.zombie.step"));
    }

    private static void assertSoundReference(JsonObject sound) {
        String location = sound.get("name").getAsString();
        assertTrue(location.startsWith("pvp:"));
        byte[] audio = FILES.get("assets/pvp/sounds/" + location.substring(4) + ".ogg");
        assertNotNull(audio, "Missing sound: " + location);
        assertTrue(audio.length > 1000);
        assertMonoVorbis(audio);
    }

    private static void assertMonoVorbis(byte[] audio) {
        // Vorbis identification packet follows the first Ogg page lacing table.
        assertEquals("OggS", new String(audio, 0, 4, StandardCharsets.US_ASCII));
        int identification = 27 + Byte.toUnsignedInt(audio[26]);
        assertEquals(1, audio[identification]);
        assertEquals("vorbis", new String(audio, identification + 1, 6, StandardCharsets.US_ASCII));
        assertEquals(1, audio[identification + 11], "Stereo samples break positional game audio");
        int rate = ByteBuffer.wrap(audio, identification + 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        assertTrue(rate == 22050 || rate == 44100 || rate == 48000, "Unsupported positional sample rate: " + rate);
    }
}
