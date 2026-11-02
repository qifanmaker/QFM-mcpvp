package com.example.pvp.mygo.practice;

import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreResetS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 练习不经过 Match 的侧边栏生命周期，但仍需显示同一会话的实时存活与回合信息。
 * 只向本房真人发送私有计分板包，避免共享服务器上的其他房间覆盖彼此的分数。
 */
public final class MyGoPracticeSidebar {
    private final ScoreboardObjective objective = new Scoreboard().addObjective("pvp_mygo_prac",
            ScoreboardCriterion.DUMMY, Text.literal("§6§lMyGO 人机练习"),
            ScoreboardCriterion.RenderType.INTEGER, false, null);
    private final Map<UUID, ServerPlayerEntity> viewers = new HashMap<>();
    private final Map<UUID, List<String>> previous = new HashMap<>();

    public void update(ServerPlayerEntity player, List<String> lines) {
        if (player == null || player instanceof MyGoBotPlayer || player.networkHandler == null) return;
        UUID id = player.getUuid();
        boolean first = viewers.put(id, player) == null;
        if (first) player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective, 0));
        List<String> old = previous.getOrDefault(id, List.of());
        if (!old.equals(lines)) {
            for (String line : old) if (!lines.contains(line))
                player.networkHandler.sendPacket(new ScoreboardScoreResetS2CPacket(line, objective.getName()));
            int score = lines.size();
            for (String line : lines) player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
                    line, objective.getName(), score--, Optional.empty(), Optional.empty()));
            previous.put(id, List.copyOf(lines));
        }
        if (first) player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective));
    }

    public void remove(ServerPlayerEntity player) {
        if (player == null || viewers.remove(player.getUuid()) == null) return;
        previous.remove(player.getUuid());
        if (player.networkHandler != null) {
            player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, null));
            player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective, 1));
        }
    }

    public void close() {
        for (ServerPlayerEntity player : List.copyOf(viewers.values())) remove(player);
    }
}
