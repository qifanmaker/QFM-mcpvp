package com.example.pvp.mygo.practice;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * A world-only humanoid keeps the exact MyGO hitboxes and gun rules without
 * occupying a login slot. There is no socket: packets must never queue forever.
 * Actual player physics and fall handling run once in the world's entity tick.
 */
public final class MyGoBotPlayer extends ServerPlayerEntity {
    private final ServerWorld initialWorld;
    private Vec3d receivedVelocity;
    private boolean spawned, discarded;

    public MyGoBotPlayer(MinecraftServer server, ServerWorld world, UUID uuid, String name) {
        super(server, world, new GameProfile(uuid, name), SyncedClientOptions.createDefault());
        this.initialWorld = world;
        new Network(server, this);
        this.addCommandTag("pvp_mygo_practice_bot");
        this.changeGameMode(GameMode.ADVENTURE);
        this.setInvulnerable(false);
        this.setNoGravity(false);
    }

    public void spawn(BlockPos pos, float yaw) {
        if (spawned || discarded) throw new IllegalStateException("MyGO bot cannot be spawned twice");
        this.refreshPositionAndAngles(pos.getX() + .5, pos.getY(), pos.getZ() + .5, yaw, 0);
        this.setHeadYaw(yaw); this.setBodyYaw(yaw);
        this.getServer().getPlayerManager().sendToAll(profilePacket());
        this.initialWorld.onPlayerConnected(this);
        this.spawned = true;
    }

    /** Send profiles before a joining human starts tracking the existing bots. */
    public void sendProfile(ServerPlayerEntity viewer) { viewer.networkHandler.sendPacket(profilePacket()); }

    private PlayerListS2CPacket profilePacket() {
        return new PlayerListS2CPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER,
                PlayerListS2CPacket.Action.UPDATE_GAME_MODE, PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME), List.of(this));
    }

    public static boolean isBot(ServerPlayerEntity actor) { return actor instanceof MyGoBotPlayer; }

    public void input(float forward, float sideways, boolean jump) {
        this.forwardSpeed = forward; this.sidewaysSpeed = sideways; this.jumping = jump;
    }

    @Override public void tick() {
        if (receivedVelocity != null) { setVelocity(receivedVelocity); receivedVelocity = null; }
        super.tick();
        Vec3d before = this.getPos();
        this.playerTick();
        Vec3d movement = this.getPos().subtract(before);
        this.handleFall(movement.x, movement.y, movement.z, this.isOnGround());
        // Real move packets also advance the player's chunk watch/tickets.
        // Without this, large-map bots stop at the original view-distance edge
        // even when the path and terrain are clear (small PvP arenas hide it).
        this.getServerWorld().getChunkManager().updatePosition(this);
    }

    @Override public Text getPlayerListName() { return Text.literal("§7" + getGameProfile().getName() + " §8[人机]"); }

    public void discardBot() {
        if (discarded) return;
        discarded = true;
        this.input(0, 0, false); this.clearActiveItem(); this.getInventory().clear();
        this.getTextStream().onDisconnect();
        if (spawned) {
            this.initialWorld.removePlayer(this, Entity.RemovalReason.DISCARDED);
            this.getServer().getPlayerManager().sendToAll(new PlayerRemoveS2CPacket(List.of(this.getUuid())));
        }
    }

    private static final class Network extends ServerPlayNetworkHandler {
        private final MyGoBotPlayer bot;
        private Network(MinecraftServer server, MyGoBotPlayer bot) {
            super(server, new ClientConnection(NetworkSide.SERVERBOUND), bot,
                    ConnectedClientData.createDefault(bot.getGameProfile(), false));
            this.bot = bot;
        }
        @Override public void sendPacket(Packet<?> packet) { receive(packet); }
        @Override public void send(Packet<?> packet, PacketCallbacks callbacks) { receive(packet); }
        private void receive(Packet<?> packet) {
            // Vanilla restores the server velocity after sending a player impulse;
            // consume that packet just as a real client would before the next tick.
            if (bot != null && packet instanceof EntityVelocityUpdateS2CPacket velocity && velocity.getEntityId() == bot.getId())
                bot.receivedVelocity = new Vec3d(velocity.getVelocityX(), velocity.getVelocityY(), velocity.getVelocityZ());
        }
        @Override public boolean isConnectionOpen() { return true; }
    }
}
