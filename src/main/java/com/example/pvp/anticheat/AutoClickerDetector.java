package com.example.pvp.anticheat;

import com.example.pvp.PvPMod;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchState;
import com.example.pvp.text.Messages;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 对局内稳定高频操作检测。
 *
 * <p>这里只处理攻击和成功的方块交互，不判断 Scaffold 等高级作弊。处罚是递进的：
 * 第一次警告，第二次退出当前对局，第三次交给服务端封禁列表。</p>
 */
public final class AutoClickerDetector {
    private static final long WINDOW_NANOS = 5_000_000_000L;
    private static final long MIN_STABLE_NANOS = 3_000_000_000L;
    private static final long DECISION_COOLDOWN_NANOS = 10_000_000_000L;

    private final Map<UUID, PlayerData> players = new HashMap<>();

    public void recordAttack(ServerPlayerEntity player) {
        this.record(player, ActionType.ATTACK);
    }

    public void recordBlockPlace(ServerPlayerEntity player) {
        this.record(player, ActionType.BLOCK_PLACE);
    }

    public void clear(UUID uuid) {
        this.players.remove(uuid);
    }

    private void record(ServerPlayerEntity player, ActionType type) {
        if (!PvPConfig.INSTANCE.autoClickerDetection || player == null
                || PvPMod.MATCH == null || player.getWorld().getRegistryKey()
                != com.example.pvp.arena.ArenaWorldManager.ARENA_WORLD_KEY) {
            return;
        }
        Match match = PvPMod.MATCH.getMatchFor(player);
        if (match == null || match.getState() != MatchState.ACTIVE
                || match.isEliminated(player.getUuid())) {
            return;
        }

        long now = System.nanoTime();
        PlayerData data = this.players.computeIfAbsent(player.getUuid(), ignored -> new PlayerData());
        Deque<Long> samples = type == ActionType.ATTACK ? data.attacks : data.blockPlaces;
        samples.addLast(now);
        while (!samples.isEmpty() && now - samples.peekFirst() > WINDOW_NANOS) {
            samples.removeFirst();
        }
        if (now < data.nextDecisionNanos || !this.isStableHighRate(samples, type)) {
            return;
        }

        data.nextDecisionNanos = now + DECISION_COOLDOWN_NANOS;
        data.strikes++;
        this.applyPenalty(player, match, data.strikes, type, this.rate(samples));
    }

    private boolean isStableHighRate(Deque<Long> samples, ActionType type) {
        int threshold = type == ActionType.ATTACK
                ? PvPConfig.INSTANCE.autoClickerAttackCps
                : PvPConfig.INSTANCE.autoClickerBlockPlaceCps;
        if (threshold <= 0 || samples.size() < threshold * 3) {
            return false;
        }
        long duration = samples.peekLast() - samples.peekFirst();
        if (duration < MIN_STABLE_NANOS) {
            return false;
        }
        double cps = this.rate(samples);
        if (cps < threshold) {
            return false;
        }

        // 稳定性只看最近一段完整样本，允许网络抖动，但排除一次性爆发。
        ArrayDeque<Long> intervals = new ArrayDeque<>();
        Long previous = null;
        for (Long sample : samples) {
            if (previous != null) {
                intervals.addLast(sample - previous);
            }
            previous = sample;
        }
        if (intervals.size() < threshold * 2) {
            return false;
        }
        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(Double.MAX_VALUE);
        double variance = intervals.stream()
                .mapToDouble(interval -> {
                    double delta = interval - mean;
                    return delta * delta;
                }).average().orElse(Double.MAX_VALUE);
        return mean > 0.0 && Math.sqrt(variance) / mean <= 0.28;
    }

    private double rate(Deque<Long> samples) {
        if (samples.size() < 2) {
            return 0.0;
        }
        long duration = samples.peekLast() - samples.peekFirst();
        return duration <= 0 ? 0.0 : (samples.size() - 1) * 1_000_000_000.0 / duration;
    }

    private void applyPenalty(ServerPlayerEntity player, Match match, int strike,
                              ActionType type, double cps) {
        String kind = type == ActionType.ATTACK ? "攻击" : "搭路";
        String detail = player.getGameProfile().getName() + " 疑似稳定高频" + kind
                + "（约 " + String.format(java.util.Locale.ROOT, "%.1f", cps) + " CPS）";
        this.notifyAdmins(Text.literal("[PvP AntiCheat] " + detail + "，阶段 " + Math.min(strike, 3)));
        if (strike == 1) {
            player.sendMessage(Messages.warn("检测到异常稳定的" + kind + "频率，请停止使用连点器或宏。"), false);
        } else if (strike == 2) {
            player.sendMessage(Messages.error("因疑似使用连点器，已移出本局游戏。"), false);
            match.leaveMatch(player);
        } else if (strike >= 3) {
            this.ban(player, detail);
        }
    }

    private void notifyAdmins(Text message) {
        if (PvPMod.SERVER == null) {
            return;
        }
        for (ServerPlayerEntity online : PvPMod.SERVER.getPlayerManager().getPlayerList()) {
            if (online.hasPermissionLevel(2)) {
                online.sendMessage(message, false);
            }
        }
    }

    private void ban(ServerPlayerEntity player, String reason) {
        if (PvPMod.SERVER == null) {
            return;
        }
        String command = "ban " + player.getGameProfile().getName() + " " + reason;
        PvPMod.SERVER.getCommandManager().executeWithPrefix(PvPMod.SERVER.getCommandSource(), command);
    }

    private enum ActionType {
        ATTACK, BLOCK_PLACE
    }

    private static final class PlayerData {
        private final Deque<Long> attacks = new ArrayDeque<>();
        private final Deque<Long> blockPlaces = new ArrayDeque<>();
        private long nextDecisionNanos;
        private int strikes;
    }
}
