package com.example.pvp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 战绩持久化 config/pvp/stats.json：UUID → 胜/负/场次。
 */
public final class StatsStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static StatsStore INSTANCE = new StatsStore();

    private final Map<String, PlayerStats> stats = new HashMap<>();

    private StatsStore() {
    }

    public void load() {
        this.stats.clear();
        Path path = getStatsPath();
        if (Files.exists(path)) {
            try {
                Map<String, PlayerStats> parsed = GSON.fromJson(Files.readString(path), new com.google.gson.reflect.TypeToken<Map<String, PlayerStats>>() {}.getType());
                if (parsed != null) {
                    this.stats.putAll(parsed);
                }
            } catch (Exception e) {
                LOGGER.warn("[PvP] 战绩读取失败: {}", e.toString());
            }
        }
    }

    public void save() {
        Path path = getStatsPath();
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(this.stats));
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("[PvP] 无法保存战绩 {}", path, e);
        }
    }

    public PlayerStats getStats(UUID uuid) {
        return this.stats.computeIfAbsent(uuid.toString(), k -> new PlayerStats());
    }

    public Map<String, PlayerStats> getStatsMap() {
        return Map.copyOf(this.stats);
    }

    /** 记录一场比赛结果，won=true 表示胜利。 */
    public void recordResult(UUID uuid, boolean won) {
        PlayerStats s = this.getStats(uuid);
        s.matches++;
        if (won) {
            s.wins++;
        } else {
            s.losses++;
        }
    }

    private static Path getStatsPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvp/stats.json");
    }
}
