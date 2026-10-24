package com.example.pvp.mygo;

import com.example.pvp.PvPMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 枪战和平局单独记账，避免影响剑战隐藏分，也避免把 6:6 计为全员失败。 */
public final class MyGoStats {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("pvp/mygo/stats.json");
    private static Map<String, Entry> stats;

    private MyGoStats() { }

    public static final class Entry {
        public int matches;
        public int wins;
        public int losses;
        public int draws;
    }

    private static Map<String, Entry> entries() {
        if (stats == null) {
            stats = new HashMap<>();
            if (Files.isRegularFile(PATH)) {
                try {
                    Map<String, Entry> loaded = GSON.fromJson(Files.readString(PATH),
                            new TypeToken<Map<String, Entry>>() { }.getType());
                    if (loaded != null) loaded.forEach((key, value) -> { if (value != null) stats.put(key, value); });
                } catch (Exception e) {
                    PvPMod.LOGGER.warn("[MyGO] 战绩读取失败", e);
                }
            }
        }
        return stats;
    }

    public static void record(List<ServerPlayerEntity> players, Set<UUID> winners) {
        // 双人局不计正式记录，避免互刷战绩；开局入口不影响这一规则。
        if (players.size() < 4) return;
        Map<String, Entry> records = entries();
        for (ServerPlayerEntity player : players) {
            Entry entry = records.computeIfAbsent(player.getUuidAsString(), ignored -> new Entry());
            entry.matches++;
            if (winners.isEmpty()) entry.draws++;
            else if (winners.contains(player.getUuid())) entry.wins++;
            else entry.losses++;
        }
        try {
            Files.createDirectories(PATH.getParent());
            Path temporary = PATH.resolveSibling("stats.json.tmp");
            Files.writeString(temporary, GSON.toJson(records));
            try {
                Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            PvPMod.LOGGER.error("[MyGO] 战绩保存失败", e);
        }
    }

    public static String describe(UUID uuid) {
        Entry entry = entries().getOrDefault(uuid.toString(), new Entry());
        return "§6MyGO 战绩 §f" + entry.matches + " 场 · " + entry.wins + " 胜 / " + entry.losses
                + " 负 / " + entry.draws + " 平（两人练习不计入）";
    }
}
