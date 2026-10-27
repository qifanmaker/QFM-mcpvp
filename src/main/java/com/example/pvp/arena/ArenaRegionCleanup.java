package com.example.pvp.arena;

import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Keeps a retiring arena loaded until both blocks and entities can be cleaned.
 * Loading terrain alone is insufficient: entity storage loads asynchronously,
 * and ordinary entity queries omit hidden chunks after players have left.
 */
public final class ArenaRegionCleanup implements AutoCloseable {
    private static final ChunkTicketType<Integer> TICKET =
            ChunkTicketType.create("pvp_cleanup", Comparator.<Integer>naturalOrder());
    private static final int TICKET_RADIUS = 2; // level 31: entities remain ticking/tracked

    private final ArenaWorld world;
    private final int regionIndex;
    private final List<ChunkPos> chunks = new ArrayList<>();
    private boolean closed;

    public ArenaRegionCleanup(ArenaWorld world, Box bounds, int regionIndex) {
        this.world = world;
        this.regionIndex = regionIndex;
        int minX = Math.floorDiv((int) Math.floor(bounds.minX), 16);
        int minZ = Math.floorDiv((int) Math.floor(bounds.minZ), 16);
        int maxX = Math.floorDiv((int) Math.ceil(bounds.maxX) - 1, 16);
        int maxZ = Math.floorDiv((int) Math.ceil(bounds.maxZ) - 1, 16);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                this.chunks.add(pos);
                world.getChunkManager().addTicket(TICKET, pos, TICKET_RADIUS, regionIndex);
            }
        }
    }

    /** Poll on later server ticks; never block the server waiting for entity I/O. */
    public boolean ready() {
        if (this.closed) return false;
        for (ChunkPos pos : this.chunks) {
            // ServerWorld.isChunkLoaded specifically checks the entity manager's
            // completed load, while shouldTickEntity excludes HIDDEN sections.
            if (!this.world.isChunkLoaded(pos.toLong())
                    || !this.world.shouldTickEntity(new BlockPos(pos.getStartX(), 0, pos.getStartZ()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        for (ChunkPos pos : this.chunks) {
            this.world.getChunkManager().removeTicket(TICKET, pos, TICKET_RADIUS, this.regionIndex);
        }
    }
}
