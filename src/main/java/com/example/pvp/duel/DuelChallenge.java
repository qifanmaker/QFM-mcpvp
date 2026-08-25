package com.example.pvp.duel;

import com.example.pvp.kit.Kit;
import com.example.pvp.match.MatchType;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/**
 * 一场待接受的决斗挑战。
 */
public final class DuelChallenge {
    private final ServerPlayerEntity challenger;
    private final ServerPlayerEntity target;
    private final MatchType type;
    private final Kit kit;
    private final long expiryTick;

    public DuelChallenge(ServerPlayerEntity challenger, ServerPlayerEntity target, MatchType type, Kit kit, long expiryTick) {
        this.challenger = challenger;
        this.target = target;
        this.type = type;
        this.kit = kit;
        this.expiryTick = expiryTick;
    }

    public ServerPlayerEntity getChallenger() {
        return this.challenger;
    }

    public ServerPlayerEntity getTarget() {
        return this.target;
    }

    public MatchType getType() {
        return this.type;
    }

    public Kit getKit() {
        return this.kit;
    }

    public boolean expired(long now) {
        return now >= this.expiryTick;
    }

    public boolean involves(UUID uuid) {
        return this.challenger.getUuid().equals(uuid) || this.target.getUuid().equals(uuid);
    }
}
