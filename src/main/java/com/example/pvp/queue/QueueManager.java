package com.example.pvp.queue;

import com.example.pvp.kit.Kit;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 匹配队列：按（模式, 套件）分组，凑齐人数自动开赛。
 */
public final class QueueManager {
    private final MinecraftServer server;
    private final List<QueueEntry> entries = new ArrayList<>();

    public QueueManager(MinecraftServer server) {
        this.server = server;
    }

    public boolean join(ServerPlayerEntity player, MatchType type, Kit kit) {
        if (this.contains(player.getUuid())) {
            return false;
        }
        this.entries.add(new QueueEntry(player, type, kit, this.server.getTicks()));
        return true;
    }

    public boolean leave(ServerPlayerEntity player) {
        return this.leave(player.getUuid());
    }

    public boolean leave(UUID uuid) {
        return this.entries.removeIf(e -> e.getPlayer().getUuid().equals(uuid));
    }

    public boolean contains(UUID uuid) {
        for (QueueEntry entry : this.entries) {
            if (entry.getPlayer().getUuid().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    public QueueEntry getEntry(ServerPlayerEntity player) {
        for (QueueEntry entry : this.entries) {
            if (entry.getPlayer().getUuid().equals(player.getUuid())) {
                return entry;
            }
        }
        return null;
    }

    public List<QueueEntry> getEntries() {
        return List.copyOf(this.entries);
    }

    public int queuedCount(MatchType type, Kit kit) {
        int count = 0;
        for (QueueEntry entry : this.entries) {
            if (entry.getType() == type && entry.getKit().getId().equals(kit.getId())) {
                count++;
            }
        }
        return count;
    }

    /** 每个服务器 tick 调用：尝试凑齐人数开赛。 */
    public void tick(MatchManager matchManager) {
        Map<String, List<QueueEntry>> groups = new LinkedHashMap<>();
        for (QueueEntry entry : this.entries) {
            groups.computeIfAbsent(entry.getType().getId() + "|" + entry.getKit().getId(), k -> new ArrayList<>()).add(entry);
        }

        for (List<QueueEntry> group : groups.values()) {
            if (group.isEmpty()) {
                continue;
            }
            MatchType type = group.get(0).getType();
            int required = type.requiredPlayers();
            if (group.size() < required) {
                continue;
            }

            List<ServerPlayerEntity> players = new ArrayList<>();
            List<QueueEntry> toRemove = new ArrayList<>();
            for (QueueEntry entry : group) {
                if (players.size() >= required) {
                    break;
                }
                ServerPlayerEntity online = matchManager.getOnlinePlayer(entry.getPlayer().getUuid());
                toRemove.add(entry); // 离线者也一并移出队列
                if (online != null) {
                    players.add(online);
                }
            }

            if (players.size() < required) {
                this.entries.removeAll(toRemove);
                continue;
            }

            // 2v2 随机分队
            if (type == MatchType.DUEL_2V2) {
                Collections.shuffle(players);
            }

            Kit kit = group.get(0).getKit();
            if (matchManager.startMatch(players, type, kit)) {
                this.entries.removeAll(toRemove);
            }
        }
    }
}
