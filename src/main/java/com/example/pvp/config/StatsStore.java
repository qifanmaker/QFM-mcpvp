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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 战绩持久化 config/pvp/stats.json：UUID → 隐藏分 + 胜/负/场次。
 *
 * <p><b>隐藏分（rating）不展示给玩家</b>：它只作为系统计算工具（目前用于空岛弱势补偿），
 * 玩家能看到、能排序的仍然只有胜/负/场次与胜率。
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
        // 迁移：早期 stats.json 没有 rating 字段。Gson 会用字段初始值（1600）兜底，
        // 正常读档不需要改写；但历史数据/手工编辑里可能出现 0、NaN 这类脏值，
        // 否则玩家会带着 0 分进入隐藏分体系（补偿永远给同一个人），这里统一拉回初始分并落盘。
        int migrated = 0;
        for (PlayerStats s : this.stats.values()) {
            if (!(s.rating > 0.0) || Double.isNaN(s.rating) || Double.isInfinite(s.rating)) {
                s.rating = EloRating.INITIAL;
                migrated++;
            }
        }
        if (migrated > 0) {
            LOGGER.info("[PvP] 隐藏分迁移：{} 名玩家的 rating 置为初始分 {}", migrated, EloRating.INITIAL);
            this.save();
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

    /** 排行榜：按胜率降序，同胜率依次按胜场、总场次降序（隐藏分不参与展示与排序）。 */
    public java.util.List<Map.Entry<String, PlayerStats>> getLeaderboard() {
        return this.stats.entrySet().stream()
                .sorted(java.util.Comparator
                        .comparingDouble((Map.Entry<String, PlayerStats> e) -> e.getValue().winRate()).reversed()
                        .thenComparing(e -> e.getValue().wins, java.util.Comparator.reverseOrder())
                        .thenComparing(e -> e.getValue().matches, java.util.Comparator.reverseOrder()))
                .toList();
    }

    /**
     * 记录一整场比赛：先累加胜/负/场次，再按 Elo 结算隐藏分。
     *
     * @param groups  参赛分组：多队模式每队一组，FFA 等"单人"模式每人一组。
     *                同组玩家视为队友，彼此不结算。
     * @param winners 本场胜者（可能多人，例如竞速全员完赛/合作模式全员胜）
     * @return UUID → 本场隐藏分变化量（未结算/无变化时为 0）。不展示给玩家。
     */
    public Map<UUID, Double> recordMatch(List<List<UUID>> groups, Set<UUID> winners) {
        Map<UUID, Double> changes = new LinkedHashMap<>();
        List<List<UUID>> valid = new ArrayList<>();
        for (List<UUID> group : groups) {
            if (group == null || group.isEmpty()) {
                continue;
            }
            valid.add(group);
        }

        // 1) 原始战绩：所有参赛者都记场次与胜负（即使 rating 因"全员同胜/同败"不变）
        for (List<UUID> group : valid) {
            for (UUID uuid : group) {
                PlayerStats s = this.getStats(uuid);
                s.matches++;
                if (winners.contains(uuid)) {
                    s.wins++;
                } else {
                    s.losses++;
                }
                changes.put(uuid, 0.0);
            }
        }

        // 2) 隐藏分：用队内平均分代表整队，队伍的变化量全队各拿一份
        int n = valid.size();
        if (n < 2) {
            return changes;
        }
        double[] ratings = new double[n];
        boolean[] won = new boolean[n];
        int[] played = new int[n];
        for (int i = 0; i < n; i++) {
            List<UUID> group = valid.get(i);
            double ratingSum = 0.0;
            int matchSum = 0;
            boolean groupWon = false;
            for (UUID uuid : group) {
                PlayerStats s = this.getStats(uuid);
                ratingSum += s.rating;
                matchSum += s.matches;
                groupWon |= winners.contains(uuid);
            }
            ratings[i] = ratingSum / group.size();
            played[i] = matchSum / group.size();
            won[i] = groupWon;
        }
        double[] deltas = EloRating.deltas(ratings, won, played);
        for (int i = 0; i < n; i++) {
            if (deltas[i] == 0.0) {
                continue;
            }
            for (UUID uuid : valid.get(i)) {
                PlayerStats s = this.getStats(uuid);
                s.rating = EloRating.round1(s.rating + deltas[i]);
                changes.put(uuid, deltas[i]);
            }
        }
        return changes;
    }

    private static Path getStatsPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvp/stats.json");
    }
}
