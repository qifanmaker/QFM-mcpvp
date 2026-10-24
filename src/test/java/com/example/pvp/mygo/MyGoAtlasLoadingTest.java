package com.example.pvp.mygo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.texture.atlas.AtlasSource;
import net.minecraft.client.texture.atlas.DirectoryAtlasSource;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.ZipResourcePack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PNG 存在并不代表客户端能渲染：旧包把材质放在默认物品图集未收录的目录。
 * 使用 1.21.1 的真实资源管理器、DirectoryAtlasSource 和模型继承解析防止紫黑材质回归；
 * 这里只加载资源及解析模型，不启动 OpenGL，也不把协议机器人视为画面渲染验证。
 */
final class MyGoAtlasLoadingTest {
    @TempDir
    Path temporary;

    @Test
    void everyBundledFaceAndInheritedPoseResolvesToAnAtlasSprite() throws Exception {
        Path bundled = temporary.resolve("mygo-pack.zip");
        try (InputStream input = getClass().getResourceAsStream("/pvp/mygo/mygo-pack.zip")) {
            assertNotNull(input);
            Files.copy(input, bundled);
        }
        try (LifecycledResourceManagerImpl resources = resources(bundled)) {
            Set<Identifier> sprites = directorySprites(resources);
            ModelResolver resolver = new ModelResolver(resources);
            Set<Identifier> usedSprites = new HashSet<>();
            int faces = 0;
            int models = 0;
            for (Identifier path : resources.findResources("models/item/mygo", id ->
                    id.getNamespace().equals("pvp") && id.getPath().endsWith(".json")).keySet()) {
                Identifier id = modelId(path);
                JsonUnbakedModel model = resolver.load(id);
                model.setParents(resolver::load);
                try (var reader = resources.getResourceOrThrow(path).getReader()) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    if (json.has("textures")) {
                        for (String key : json.getAsJsonObject("textures").keySet()) {
                            assertSprite(resources, sprites, model.resolveSprite(key).getTextureId(), id, usedSprites);
                        }
                    }
                }
                for (var element : model.getElements()) {
                    for (var face : element.faces.values()) {
                        assertSprite(resources, sprites, model.resolveSprite(face.textureId()).getTextureId(), id, usedSprites);
                        faces++;
                    }
                }
                // Generated vanilla items create their particle from layer0 in ItemModelGenerator.
                String particle = model.getRootModel() == resolver.generated ? "layer0" : "particle";
                assertSprite(resources, sprites, model.resolveSprite(particle).getTextureId(), id, usedSprites);
                models++;
            }
            assertTrue(models >= MyGoWeapon.values().length * 3,
                    "Every runtime gun needs its base, fire and reload model checked");
            assertTrue(faces >= MyGoWeapon.values().length * 3 * 3 * 6,
                    "Every pose must inherit at least three complete gun cuboids");
            Set<Identifier> expectedSprites = new HashSet<>();
            for (Identifier path : resources.findResources("textures/item/mygo", id ->
                    id.getNamespace().equals("pvp") && id.getPath().endsWith(".png")).keySet()) {
                expectedSprites.add(textureId(path));
            }
            assertFalse(expectedSprites.isEmpty());
            assertEquals(expectedSprites, sprites.stream().filter(id -> id.getNamespace().equals("pvp"))
                    .collect(java.util.stream.Collectors.toSet()));
            assertEquals(expectedSprites, usedSprites.stream().filter(id -> id.getNamespace().equals("pvp"))
                    .collect(java.util.stream.Collectors.toSet()), "No unreferenced custom material should hide an atlas regression");
            System.out.printf("MyGO atlas: %d models, %d inherited faces, %d custom PNG sprites resolved%n",
                    models, faces, usedSprites.stream().filter(id -> id.getNamespace().equals("pvp")).count());
        }
    }

    @Test
    void previousHostedPackReproducesMissingAtlasSpritesWhenAvailable() throws Exception {
        // Local diagnostic only: the old downloaded ZIP is deliberately not a committed fixture.
        Path previous = Path.of("build/mygo-texture-fix/hosted-5df4ab7h.zip");
        assumeTrue(Files.isRegularFile(previous), "Optional original hosted pack is not present");
        try (LifecycledResourceManagerImpl resources = resources(previous)) {
            Set<Identifier> sprites = directorySprites(resources);
            assertEquals(0, sprites.stream().filter(id -> id.getNamespace().equals("pvp")).count());
            Set<Identifier> missing = new TreeSet<>();
            for (var entry : resources.findResources("models/item/mygo", id ->
                    id.getNamespace().equals("pvp") && id.getPath().endsWith(".json")).entrySet()) {
                try (var reader = entry.getValue().getReader()) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    if (!json.has("textures")) continue;
                    for (var texture : json.getAsJsonObject("textures").entrySet()) {
                        String value = texture.getValue().getAsString();
                        if (value.startsWith("#")) continue;
                        Identifier id = Identifier.of(value);
                        if (id.getNamespace().equals("pvp") && !sprites.contains(id)) missing.add(id);
                    }
                }
            }
            long originalPngs = resources.findResources("textures/mygo", id ->
                    id.getNamespace().equals("pvp") && id.getPath().endsWith(".png")).size();
            assertTrue(originalPngs > 0, "The original pack must contain custom textures");
            assertEquals(originalPngs, missing.size(), "The original visible purple/black failure must be reproduced");
            for (Identifier id : missing) {
                // This is precisely the old test's blind spot: valid PNGs that the atlas never sees.
                try (InputStream input = resources.getResourceOrThrow(texturePath(id)).getInputStream()) {
                    assertNotNull(ImageIO.read(input), "The old PNG itself is present and readable: " + id);
                }
            }
            System.out.println("MyGO original hosted pack: 0 custom atlas sprites, valid PNGs missing: " + missing);
        }
    }

    private static LifecycledResourceManagerImpl resources(Path pack) throws Exception {
        Path vanilla = Path.of(DirectoryAtlasSource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isRegularFile(vanilla), "The exact 1.21.1 merged client JAR supplies vanilla resources");
        return new LifecycledResourceManagerImpl(ResourceType.CLIENT_RESOURCES, List.of(open(vanilla, "vanilla"), open(pack, "mygo")));
    }

    private static ResourcePack open(Path path, String name) {
        return new ZipResourcePack.ZipBackedFactory(path).open(
                new ResourcePackInfo(name, Text.literal(name), ResourcePackSource.NONE, Optional.empty()));
    }

    private static Set<Identifier> directorySprites(ResourceManager resources) throws Exception {
        Set<Identifier> sprites = new HashSet<>();
        AtlasSource.SpriteRegions regions = new AtlasSource.SpriteRegions() {
            @Override
            public void add(Identifier id, AtlasSource.SpriteRegion region) {
                sprites.add(id);
            }

            @Override
            public void removeIf(Predicate<Identifier> predicate) {
                sprites.removeIf(predicate);
            }
        };
        // Read the actual vanilla atlas definition rather than repeating its path assumptions.
        try (var reader = resources.getResourceOrThrow(Identifier.ofVanilla("atlases/blocks.json")).getReader()) {
            for (var element : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("sources")) {
                JsonObject source = element.getAsJsonObject();
                if (source.get("type").getAsString().equals("directory")) {
                    new DirectoryAtlasSource(source.get("source").getAsString(), source.get("prefix").getAsString())
                            .load(resources, regions);
                }
            }
        }
        return sprites;
    }

    private static void assertSprite(ResourceManager resources, Set<Identifier> atlas, Identifier texture,
                                     Identifier model, Set<Identifier> used) throws Exception {
        assertTrue(atlas.contains(texture), model + " uses a texture absent from the 1.21.1 item atlas: " + texture);
        if (used.add(texture)) {
            try (InputStream input = resources.getResourceOrThrow(texturePath(texture)).getInputStream()) {
                assertNotNull(ImageIO.read(input), "Unreadable PNG for " + model + ": " + texture);
            }
        }
    }

    private static Identifier texturePath(Identifier id) {
        return id.withPath("textures/" + id.getPath() + ".png");
    }

    private static Identifier textureId(Identifier resource) {
        String path = resource.getPath();
        return resource.withPath(path.substring("textures/".length(), path.length() - ".png".length()));
    }

    private static Identifier modelId(Identifier resource) {
        String path = resource.getPath();
        return resource.withPath(path.substring("models/".length(), path.length() - ".json".length()));
    }

    private static final class ModelResolver {
        private final ResourceManager resources;
        private final Map<Identifier, JsonUnbakedModel> models = new HashMap<>();
        // Vanilla's builtin/generated is a marker model; texture inheritance remains real vanilla code.
        private final JsonUnbakedModel generated = JsonUnbakedModel.deserialize("{}");

        private ModelResolver(ResourceManager resources) {
            this.resources = resources;
        }

        private JsonUnbakedModel load(Identifier id) {
            if (id.equals(Identifier.ofVanilla("builtin/generated"))) return generated;
            return models.computeIfAbsent(id, key -> {
                Identifier resource = key.withPath("models/" + key.getPath() + ".json");
                try (var reader = resources.getResourceOrThrow(resource).getReader()) {
                    JsonUnbakedModel model = JsonUnbakedModel.deserialize(reader);
                    model.id = key.toString();
                    return model;
                } catch (Exception exception) {
                    throw new IllegalStateException("Could not load actual model " + key, exception);
                }
            });
        }
    }
}
