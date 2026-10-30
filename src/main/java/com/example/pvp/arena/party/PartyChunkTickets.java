package com.example.pvp.arena.party;

import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 完整长赛道在玩家抵达前也必须保留；ArenaWorld不保存，普通读写票过期会丢失远端场景。
 * 每个租约使用独立标识，只保持实际场景区块或当前珍珠邻域，绝不改变服务器forceload集合。
 */
public final class PartyChunkTickets implements AutoCloseable {
    private static final ChunkTicketType<UUID> TYPE = ChunkTicketType.create("pvp_party_scene", Comparator.<UUID>naturalOrder());
    private static final int RADIUS = 2;
    private final ServerWorld world;
    private final UUID key = UUID.randomUUID();
    private final Set<ChunkPos> chunks = new HashSet<>();
    private boolean closed;

    public PartyChunkTickets(ServerWorld world) { this.world = world; }

    public void retain(BlockPos minimum, BlockPos maximum) {
        if (closed) throw new IllegalStateException("Party chunk lease is closed");
        for (int x = Math.floorDiv(minimum.getX(), 16); x <= Math.floorDiv(maximum.getX(), 16); x++) {
            for (int z = Math.floorDiv(minimum.getZ(), 16); z <= Math.floorDiv(maximum.getZ(), 16); z++) add(new ChunkPos(x, z));
        }
    }

    /** Add the new projectile neighborhood before releasing the old one. */
    public void update(Set<ChunkPos> desired) {
        if (closed) throw new IllegalStateException("Party chunk lease is closed");
        for (ChunkPos pos : desired) add(pos);
        var iterator = chunks.iterator();
        while (iterator.hasNext()) {
            ChunkPos pos = iterator.next();
            if (!desired.contains(pos)) {
                world.getChunkManager().removeTicket(TYPE, pos, RADIUS, key);
                iterator.remove();
            }
        }
    }

    private void add(ChunkPos pos) {
        if (chunks.add(pos)) world.getChunkManager().addTicket(TYPE, pos, RADIUS, key);
    }

    public boolean ready() {
        for (ChunkPos pos : chunks) {
            if (!world.isChunkLoaded(pos.toLong()) || !world.shouldTickEntity(new BlockPos(pos.getStartX(), 0, pos.getStartZ()))) return false;
        }
        return true;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (ChunkPos pos : chunks) world.getChunkManager().removeTicket(TYPE, pos, RADIUS, key);
        chunks.clear();
    }
}
