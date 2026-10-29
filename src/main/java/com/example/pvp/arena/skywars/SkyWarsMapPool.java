package com.example.pvp.arena.skywars;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 外部建筑地图与程序地图共用一个可配置池；地图本体随模组附带，开局不依赖下载。 */
public final class SkyWarsMapPool {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> BUNDLED = List.of("forest", "unresting_desert");
    private static final Map<String, SkyWarsImportedMap> LOADED = new LinkedHashMap<>();
    private static boolean loaded;

    private SkyWarsMapPool() { }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvp/skywars/maps.json");
    }

    /** null 表示选择程序地图；明确指定原有主题的对局由调用者直接走程序地图。 */
    public static SkyWarsImportedMap pick(int seed, int playerCount) {
        loadMaps();
        Config config = readConfig();
        if (!config.enabled) return null;
        List<SkyWarsImportedMap> available = LOADED.values().stream()
                .filter(map -> map.spawns().size() >= playerCount).toList();
        if (config.forcedMap != null && !config.forcedMap.isBlank()) {
            for (SkyWarsImportedMap map : available) {
                if (map.id().equals(config.forcedMap)) return map;
            }
            LOGGER.warn("[PvP] 指定空岛地图不可用或出生点不足：{}，本局使用程序地图", config.forcedMap);
            return null;
        }
        List<SkyWarsImportedMap> candidates = new ArrayList<>();
        int total = weight(config.generatedWeight);
        for (SkyWarsImportedMap map : available) {
            int weight = config.maps == null ? 0 : weight(config.maps.getOrDefault(map.id(), 0));
            for (int i = 0; i < weight; i++) candidates.add(map);
            total += weight;
        }
        if (total == 0) return null;
        int choice = new Random(0x57a4f10bL ^ ((long) seed * 0x9e3779b9L)).nextInt(total);
        return choice < candidates.size() ? candidates.get(choice) : null;
    }

    private static int weight(Integer value) {
        return value == null ? 0 : Math.max(0, Math.min(100, value));
    }

    private static void loadMaps() {
        if (loaded) return;
        loaded = true;
        for (String id : BUNDLED) {
            try {
                SkyWarsImportedMap map = SkyWarsImportedMap.load(id);
                LOADED.put(id, map);
                LOGGER.info("[PvP] 已加载空岛地图：{} / {}，{} 出生点，{} 物资箱；作者：{}，来源：{}",
                        map.id(), map.name(), map.spawns().size(), map.chests().size(), map.author(), map.source());
            } catch (Exception exception) {
                LOGGER.error("[PvP] 空岛地图 {} 未通过加载校验，已从地图池跳过", id, exception);
            }
        }
    }

    private static Config readConfig() {
        Path path = configPath();
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                try {
                    Files.writeString(path, GSON.toJson(new Config()), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW);
                } catch (java.nio.file.FileAlreadyExistsException ignored) { }
            }
            if (Files.size(path) > 64 * 1024) throw new IOException("地图池配置超过 64 KiB");
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Config config = GSON.fromJson(reader, Config.class);
                if (config == null) throw new IOException("地图池配置为空");
                return config;
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("[PvP] 无法读取空岛地图池配置，本局使用程序地图：{}", exception.toString());
            Config disabled = new Config();
            disabled.enabled = false;
            return disabled;
        }
    }

    private static final class Config {
        boolean enabled = true;
        int generatedWeight = 2;
        String forcedMap = "";
        Map<String, Integer> maps = new LinkedHashMap<>(Map.of("forest", 1, "unresting_desert", 1));
    }
}
