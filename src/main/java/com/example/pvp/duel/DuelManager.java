package com.example.pvp.duel;

import com.example.pvp.config.PvPConfig;
import com.example.pvp.kit.Kit;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 决斗挑战管理：挑战 / 接受 / 拒绝 / 过期。
 */
public final class DuelManager {
    private final MinecraftServer server;
    private final List<DuelChallenge> challenges = new ArrayList<>();

    public DuelManager(MinecraftServer server) {
        this.server = server;
    }

    public DuelChallenge challenge(ServerPlayerEntity challenger, ServerPlayerEntity target, MatchType type, Kit kit) {
        this.challenges.removeIf(c ->
                c.getChallenger().getUuid().equals(challenger.getUuid()) && c.getTarget().getUuid().equals(target.getUuid()));
        long expiryTick = this.server.getTicks() + (long) PvPConfig.INSTANCE.duelExpirySeconds * 20L;
        DuelChallenge challenge = new DuelChallenge(challenger, target, type, kit, expiryTick);
        this.challenges.add(challenge);
        return challenge;
    }

    public DuelChallenge findPendingFor(UUID targetUuid) {
        for (DuelChallenge challenge : this.challenges) {
            if (challenge.getTarget().getUuid().equals(targetUuid)) {
                return challenge;
            }
        }
        return null;
    }

    public DuelChallenge findChallengeBetween(UUID from, UUID to) {
        for (DuelChallenge challenge : this.challenges) {
            if (challenge.getChallenger().getUuid().equals(from) && challenge.getTarget().getUuid().equals(to)) {
                return challenge;
            }
        }
        return null;
    }

    public boolean accept(ServerPlayerEntity accepter, ServerPlayerEntity challenger) {
        DuelChallenge challenge = this.findChallengeBetween(challenger.getUuid(), accepter.getUuid());
        if (challenge == null) {
            return false;
        }
        this.challenges.remove(challenge);
        MatchManager matchManager = MatchManager.get();
        return matchManager != null && matchManager.startMatch(List.of(challenger, accepter), challenge.getType(), challenge.getKit());
    }

    public void deny(UUID from, UUID to) {
        this.challenges.removeIf(c -> c.getChallenger().getUuid().equals(from) && c.getTarget().getUuid().equals(to));
    }

    public void removeChallengesInvolving(UUID uuid) {
        this.challenges.removeIf(c -> c.involves(uuid));
    }

    public void tick() {
        this.challenges.removeIf(c -> c.expired(this.server.getTicks()));
    }

    public List<DuelChallenge> getChallenges() {
        return List.copyOf(this.challenges);
    }
}
