package com.example.pvp.match;

import com.example.pvp.config.StatsStore;
import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 每局输出一条结构化平衡日志，区分主动选职和默认职业，供后续按水平分析胜率。 */
public final class SkyWarsMetrics {
    private static final Gson GSON = new Gson();
    private final Map<UUID, Map<String, Object>> players = new LinkedHashMap<>();
    private int firstCombatTick = -1;
    private boolean finished;

    public void begin(List<ServerPlayerEntity> participants, Map<UUID, SkyWarsProfession> choices) {
        for (ServerPlayerEntity player : participants) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("profession", choices.getOrDefault(player.getUuid(), SkyWarsProfession.WARRIOR).name());
            data.put("selected", choices.containsKey(player.getUuid()));
            data.put("rating", StatsStore.INSTANCE.getStats(player.getUuid()).getRating());
            data.put("platformUses", 0);
            data.put("totemUses", 0);
            this.players.put(player.getUuid(), data);
        }
    }

    public void combat(int elapsedTicks) {
        if (this.firstCombatTick < 0) this.firstCombatTick = elapsedTicks;
    }

    public void eliminated(UUID uuid, String cause, int ticks) {
        var data = this.players.get(uuid);
        if (data == null) return;
        data.put("elimination", cause);
        data.put("survivalSeconds", ticks / 20.0);
    }

    public void used(UUID uuid, String counter) {
        var data = this.players.get(uuid);
        if (data != null) data.put(counter, (int) data.getOrDefault(counter, 0) + 1);
    }

    public void finish(int id, int seed, String theme, int elapsedTicks, boolean timedOut, Set<UUID> winners) {
        if (this.finished || this.players.isEmpty()) return;
        this.finished = true;
        this.players.forEach((uuid, data) -> data.put("won", winners.contains(uuid)));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", 1);
        report.put("match", id);
        report.put("seed", seed);
        report.put("theme", theme);
        report.put("durationSeconds", elapsedTicks / 20.0);
        report.put("firstCombatSeconds", this.firstCombatTick < 0 ? null : this.firstCombatTick / 20.0);
        report.put("timedOut", timedOut);
        report.put("players", this.players);
        LogUtils.getLogger().info("[SkyWarsBalance] {}", GSON.toJson(report));
    }
}
