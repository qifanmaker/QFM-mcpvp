package com.example.pvp.queue;

import com.example.pvp.kit.Kit;
import com.example.pvp.match.MatchType;
import com.example.pvp.arena.skywars.SkyWarsMapVariant;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 队列中的一名玩家。
 */
public final class QueueEntry {
    private final ServerPlayerEntity player;
    private final MatchType type;
    private final Kit kit;
    private final long queuedAtTick;
    private final SkyWarsMapVariant skyWarsVariant;
    private MyGoTeamPreference myGoTeamPreference = MyGoTeamPreference.AUTO;

    public QueueEntry(ServerPlayerEntity player, MatchType type, Kit kit, long queuedAtTick) {
        this(player, type, kit, queuedAtTick, SkyWarsMapVariant.CLASSIC);
    }

    public QueueEntry(ServerPlayerEntity player, MatchType type, Kit kit, long queuedAtTick,
                      SkyWarsMapVariant skyWarsVariant) {
        this.player = player;
        this.type = type;
        this.kit = kit;
        this.queuedAtTick = queuedAtTick;
        this.skyWarsVariant = skyWarsVariant == null ? SkyWarsMapVariant.CLASSIC : skyWarsVariant;
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

    public SkyWarsMapVariant getSkyWarsVariant() {
        return this.skyWarsVariant;
    }

    public MyGoTeamPreference getMyGoTeamPreference() {
        return this.myGoTeamPreference;
    }

    public void setMyGoTeamPreference(MyGoTeamPreference preference) {
        this.myGoTeamPreference = preference == null ? MyGoTeamPreference.AUTO : preference;
    }
}
