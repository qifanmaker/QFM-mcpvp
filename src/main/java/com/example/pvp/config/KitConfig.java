package com.example.pvp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自定义套件配置 config/pvp/kits.json（可热重载）。
 * <pre>
 * {
 *   "kits": [
 *     {
 *       "name": "铁套测试",
 *       "items": [
 *         {"id": "minecraft:iron_sword"},
 *         {"id": "minecraft:bow"},
 *         {"id": "minecraft:arrow", "count": 32},
 *         {"id": "minecraft:fishing_rod", "components": {"enchantments": {"levels": {"minecraft:unbreaking": 3}}}}
 *       ],
 *       "armor": [
 *         {"id": "minecraft:iron_helmet"},
 *         {"id": "minecraft:iron_chestplate"},
 *         {"id": "minecraft:iron_leggings"},
 *         {"id": "minecraft:iron_boots"}
 *       ],
 *       "effects": [
 *         {"effect": "minecraft:speed", "duration": 6000, "amplifier": 0}
 *       ],
 *       "food": 20,
 *       "saturation": 20.0,
 *       "gamemode": "adventure"
 *     }
 *   ]
 * }
 * </pre>
 */
public final class KitConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static KitConfig INSTANCE = new KitConfig();

    @SerializedName("kits")
    public List<CustomKitSpec> kits = new ArrayList<>();

    private KitConfig() {
    }

    public static void load() {
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try {
                KitConfig parsed = GSON.fromJson(Files.readString(path), KitConfig.class);
                INSTANCE = parsed != null ? parsed : new KitConfig();
                if (INSTANCE.kits == null) {
                    INSTANCE.kits = new ArrayList<>();
                }
            } catch (Exception e) {
                LOGGER.warn("[PvP] 套件配置解析失败，使用空套件列表: {}", e.toString());
                INSTANCE = new KitConfig();
            }
        } else {
            LOGGER.info("[PvP] 未找到套件配置文件，生成默认文件 {}", path);
            save();
        }
    }

    public static void save() {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            LOGGER.warn("[PvP] 无法保存套件配置 {}", path, e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvp/kits.json");
    }

    public static class CustomKitSpec {
        public String name = "";
        public List<ItemSpec> items = new ArrayList<>();
        /** 放入主背包（9-35 格）的物品，适合不堆叠物品如桶。 */
        public List<ItemSpec> backpack = new ArrayList<>();
        public List<ItemSpec> armor = new ArrayList<>();
        public List<EffectSpec> effects = new ArrayList<>();
        public Integer food;
        public Float saturation;
        public String gamemode;
    }

    public static class ItemSpec {
        public String id;
        public int count = 1;
        public Components components;

        /** 物品组件（目前支持附魔）：{"enchantments": {"levels": {"minecraft:unbreaking": 3}}} */
        public static class Components {
            public Enchantments enchantments;

            public static class Enchantments {
                public Map<String, Integer> levels;
            }
        }
    }

    public static class EffectSpec {
        public String effect;
        public int duration = 6000;
        public int amplifier = 0;
    }
}
