package com.example.pvp.match;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Formatting;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 比赛中的一支队伍（FFA 时为全员一队）。
 */
public final class MatchTeam {
    private final String name;
    private final Formatting color;
    private final List<ServerPlayerEntity> players;
    private final Set<UUID> alive;

    public MatchTeam(String name, Formatting color, List<ServerPlayerEntity> players) {
        this.name = name;
        this.color = color;
        this.players = List.copyOf(players);
        this.alive = new HashSet<>();
        for (ServerPlayerEntity player : this.players) {
            this.alive.add(player.getUuid());
        }
    }

    public String getName() {
        return this.name;
    }

    public Formatting getColor() {
        return this.color;
    }

    public List<ServerPlayerEntity> getPlayers() {
        return this.players;
    }

    public boolean contains(UUID uuid) {
        for (ServerPlayerEntity player : this.players) {
            if (player.getUuid().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    public boolean isAlive(ServerPlayerEntity player) {
        return this.alive.contains(player.getUuid());
    }

    public void eliminate(ServerPlayerEntity player) {
        this.alive.remove(player.getUuid());
    }

    public boolean isDefeated() {
        return this.alive.isEmpty();
    }

    public List<ServerPlayerEntity> getAlivePlayers() {
        return this.players.stream().filter(p -> this.alive.contains(p.getUuid())).toList();
    }

    public int aliveCount() {
        return this.alive.size();
    }
}
