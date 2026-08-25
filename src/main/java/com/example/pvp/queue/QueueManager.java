package com.example.pvp.queue;

import com.example.pvp.config.PvPConfig;
import com.example.pvp.gui.PvpGuiManager;
import com.example.pvp.kit.Kit;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchType;
import com.example.pvp.text.Messages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
    /** 自由乱斗开赛倒计时（tick 数）；null 表示未开始。 */
    private Integer ffaCountdownTicks;

    public QueueManager(MinecraftServer server) {
        this.server = server;
    }

    public boolean join(ServerPlayerEntity player, MatchType type, Kit kit) {
        if (this.contains(player.getUuid())) {
            return false;
        }
        this.entries.add(new QueueEntry(player, type, kit, this.server.getTicks()));
        PvpGuiManager.giveQueueItem(player); // 快捷栏第一格放「离开排队」红石
        return true;
    }

    public boolean leave(ServerPlayerEntity player) {
        boolean removed = this.leave(player.getUuid());
        if (removed) {
            PvpGuiManager.removeQueueItem(player);
        }
        return removed;
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

    /** 每个服务器 tick 调用：自由乱斗倒计时 + 非 FFA 即时凑齐开赛。 */
    public void tick(MatchManager matchManager) {
        // 自愈：清理已离线或已在比赛中的排队条目（防止残留状态导致无法再次开赛）
        this.entries.removeIf(e -> {
            ServerPlayerEntity online = matchManager.getOnlinePlayer(e.getPlayer().getUuid());
            return online == null || matchManager.isInMatch(e.getPlayer().getUuid());
        });

        this.tickFfa(matchManager);
        this.tickInstantMatches(matchManager);
    }

    /** 自由乱斗：≥3 人开始倒计时，≥6 人缩短为 min(剩余,10s)，倒计时结束开赛。 */
    private void tickFfa(MatchManager matchManager) {
        PvPConfig config = PvPConfig.INSTANCE;
        long ffaCount = this.countFfa();

        if (this.ffaCountdownTicks == null) {
            if (ffaCount >= config.ffaMinPlayers) {
                this.ffaCountdownTicks = config.ffaCountdownSeconds * 20;
                this.broadcastFfa(matchManager, Messages.info(
                        "§e" + ffaCount + "§r 人已就绪，§e" + config.ffaCountdownSeconds + "§r 秒后开始自由乱斗（达 "
                                + config.ffaEarlyStartPlayers + " 人将加速）"));
            }
            return;
        }

        if (ffaCount >= config.ffaEarlyStartPlayers || ffaCount >= config.ffaMaxPlayers) {
            this.ffaCountdownTicks = Math.min(this.ffaCountdownTicks, config.ffaEarlyStartSeconds * 20);
        }

        this.ffaCountdownTicks--;
        if (this.ffaCountdownTicks > 0 && this.ffaCountdownTicks % 20 == 0) {
            this.broadcastFfa(matchManager, Messages.info(
                    "自由乱斗将在 §e" + (this.ffaCountdownTicks / 20) + "§r 秒后开始（当前 " + ffaCount + " 人）"));
        }

        if (this.ffaCountdownTicks <= 0) {
            this.ffaCountdownTicks = null;
            this.startFfaMatch(matchManager);
        }
    }

    /** 倒计时结束，把当前所有 FFA 玩家拉进同一场比赛（每人各自套件）。 */
    private void startFfaMatch(MatchManager matchManager) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        Map<UUID, Kit> kits = new HashMap<>();
        List<QueueEntry> toRemove = new ArrayList<>();

        for (QueueEntry entry : List.copyOf(this.entries)) {
            if (entry.getType() != MatchType.FFA) {
                continue;
            }
            ServerPlayerEntity online = matchManager.getOnlinePlayer(entry.getPlayer().getUuid());
            toRemove.add(entry);
            if (online != null) {
                players.add(online);
                kits.put(entry.getPlayer().getUuid(), entry.getKit());
            }
        }

        if (players.size() < PvPConfig.INSTANCE.ffaMinPlayers) {
            // 人数不足，清掉这些残留条目
            this.entries.removeAll(toRemove);
            return;
        }
        if (matchManager.startMatch(players, MatchType.FFA, kits)) {
            this.entries.removeAll(toRemove);
        }
        // 开赛失败（场地已满）则保留排队，等待下一轮倒计时
    }

    /** 非 FFA（1v1/2v2/相扑）的即时凑齐开赛。 */
    private void tickInstantMatches(MatchManager matchManager) {
        Map<String, List<QueueEntry>> groups = new LinkedHashMap<>();
        for (QueueEntry entry : this.entries) {
            if (entry.getType() == MatchType.FFA) {
                continue;
            }
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

    private long countFfa() {
        return this.entries.stream().filter(e -> e.getType() == MatchType.FFA).count();
    }

    private void broadcastFfa(MatchManager matchManager, Text message) {
        for (QueueEntry entry : this.entries) {
            if (entry.getType() != MatchType.FFA) {
                continue;
            }
            ServerPlayerEntity online = matchManager.getOnlinePlayer(entry.getPlayer().getUuid());
            if (online != null) {
                online.sendMessage(message, false);
            }
        }
    }
}
