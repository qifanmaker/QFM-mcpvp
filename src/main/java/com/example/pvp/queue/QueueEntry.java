package com.example.pvp.queue;

import com.example.pvp.kit.Kit;
import com.example.pvp.match.MatchType;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 队列中的一名玩家。
 */
public final class QueueEntry {
    private final ServerPlayerEntity player;
    private final MatchType type;
    private final Kit kit;
    private final long queuedAtTick;

    public QueueEntry(ServerPlayerEntity player, MatchType type, Kit kit, long queuedAtTick) {
        this.player = player;
        this.type = type;
        this.kit = kit;
        this.queuedAtTick = queuedAtTick;
    }

    public ServerPlayerEntity getPlayer() {
        return this.player;
    }

    public MatchType getType() {
        return this.type;
    }

    public Kit getKit() {
        return this.kit;
    }

    public long getQueuedAtTick() {
        return this.queuedAtTick;
    }
}
