package com.example.pvp.arena.party;

import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.MapBuildQueue;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Retains complete Party scenes for this ArenaWorld's lifetime. Rebuilding and
 * erasing millions of unchanged cells between rounds is unnecessary: an
 * exclusive lease records the first old state of actual changes, then restores
 * only those changes and baseline BE data. Idle scenes keep level33 FULL tickets
 * without enabling their block/entity simulation. All access is server-thread
 * only; active sessions retain their separate level31 tickets until reset ends.
 */
public final class PartyResidentMaps implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ChunkTicketType<UUID> TICKET = ChunkTicketType.create(
            "pvp_party_resident", Comparator.<UUID>naturalOrder());
    private static final int FULL_RADIUS = 0;
    private static final int COLUMNS = 8;
    private static final int COLUMN_SPACING = 4096;
    private static final int ROW_SPACING = 8192;
    private static final int FIRST_Z = 65536;

    public enum State { QUEUED, WARMING, READY, LEASED, RESETTING, BROKEN }

    private record SavedBlockEntity(BlockState state, NbtCompound nbt) { }

    private final ArenaWorld world;
    private final List<Slot> slots = new ArrayList<>();
    private final Map<String, PartyResidentCatalog.Profile> profiles = new LinkedHashMap<>();
    private final Long2ObjectMap<Slot> byChunk = new Long2ObjectOpenHashMap<>();
    private int lastTick = Integer.MIN_VALUE;
    private boolean closed;

    public PartyResidentMaps(ArenaWorld world) { this.world = Objects.requireNonNull(world, "world"); }

    /** Adds one permanent baseline per profile; world generation is deferred to tick(). */
    public void prewarm(Collection<PartyResidentCatalog.Profile> requested) {
        this.ensureOpen();
        for (PartyResidentCatalog.Profile profile : requested) {
            this.registerProfile(profile);
            if (this.slots.stream().noneMatch(slot -> slot.profile.key().equals(profile.key()))) this.createSlot(profile);
        }
    }

    /** A busy profile receives another independent scene, never a shared arena. */
    public Lease acquire(PartyResidentCatalog.Profile profile, Object owner) {
        this.ensureOpen();
        Objects.requireNonNull(owner, "owner");
        this.registerProfile(profile);
        for (Slot slot : this.slots) {
            if (slot.profile.key().equals(profile.key()) && slot.lease != null
                    && slot.lease.owner == owner && !slot.lease.released) return slot.lease;
        }
        Slot available = null;
        for (Slot slot : this.slots) {
            if (!slot.profile.key().equals(profile.key()) || slot.lease != null || slot.state == State.BROKEN) continue;
            if (available == null || slot.state == State.READY) available = slot;
            if (slot.state == State.READY) break;
        }
        if (available == null) available = this.createSlot(profile);
        available.lease = new Lease(available, owner);
        if (available.state == State.READY) available.state = State.LEASED;
        return available.lease;
    }

    /**
     * Cancelling an unready reservation resumes background prewarming. A used
     * scene stays owned and unavailable until its sparse reset has completed.
     */
    public void release(Lease lease) {
        this.ensureLease(lease);
        if (lease.released) return;
        lease.released = true;
        Slot slot = lease.slot;
        if (slot.state == State.QUEUED || slot.state == State.WARMING) {
            slot.lease = null;
            lease.resetDone = true;
        } else if (slot.state == State.LEASED) {
            slot.state = State.RESETTING;
            slot.queue = null;
        } else if (slot.state == State.BROKEN) {
            lease.resetDone = true;
            slot.lease = null;
        }
    }

    /**
     * Exactly one pool flush per native tick. Resets and requested scenes can
     * preempt a background warmup without losing its cursor or permanent tickets.
     */
    public void tick() {
        if (this.closed) return;
        int now = this.world.getServer().getTicks();
        if (this.lastTick == now) return;
        this.lastTick = now;
        Slot selected = this.selectJob();
        if (selected == null) return;
        try {
            if (selected.state == State.QUEUED) this.beginWarmup(selected);
            if (!this.chunksReady(selected)) return;
            if (selected.state == State.RESETTING && selected.queue == null) this.beginReset(selected);
            if (selected.queue == null || !selected.queue.flushAdaptive()) return;
            selected.queue = null;
            if (selected.state == State.WARMING) {
                this.captureBlockEntities(selected);
                this.clearScheduledTicks(selected);
                selected.state = selected.lease == null ? State.READY : State.LEASED;
                LOGGER.info("[PvP] Party 常驻地图已就绪：{} / slot{} / {}格 / {}区块",
                        selected.profile.key(), selected.index, selected.baselineBlocks, selected.chunks.size());
            } else if (selected.state == State.RESETTING) {
                this.clearScheduledTicks(selected);
                this.discardEntities(selected);
                selected.journal.clear();
                if (selected.lease != null) selected.lease.resetDone = true;
                selected.lease = null;
                selected.state = State.READY;
            }
        } catch (Exception failure) {
            selected.state = State.BROKEN;
            selected.failure = failure.toString();
            selected.queue = null;
            if (selected.lease != null && selected.lease.released) {
                selected.lease.resetDone = true;
                selected.lease = null;
            }
            // A broken scene remains protected and cannot be handed to another
            // match. The caller can report failure and release its reservation.
            LOGGER.error("[PvP] Party 常驻地图准备/恢复失败：{} / slot{}", selected.profile.key(), selected.index, failure);
        }
    }

    public boolean contains(BlockPos pos) { return this.slotAt(pos) != null; }
    public boolean intersectsChunk(int chunkX, int chunkZ) {
        return !this.closed && this.byChunk.containsKey(ChunkPos.toLong(chunkX, chunkZ));
    }
    public boolean isIdleAt(BlockPos pos) {
        Slot slot = this.slotAt(pos);
        return slot != null && slot.state != State.LEASED;
    }
    public Object ownerAt(BlockPos pos) {
        Slot slot = this.slotAt(pos);
        return slot == null || slot.lease == null ? null : slot.lease.owner;
    }

    /** O(1) chunk lookup followed by precise bounds, including formerly-air cells. */
    public Slot slotAt(BlockPos pos) {
        if (this.closed || pos.getY() < this.world.getBottomY() || pos.getY() >= this.world.getTopY()) return null;
        Slot candidate = this.byChunk.get(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
        return candidate != null && candidate.profile.bounds().contains(candidate.center, pos) ? candidate : null;
    }

    /**
     * Called from native WorldChunk write HEAD before block callbacks can make
     * nested writes. Snapshot imports made by a leased module are real deltas
     * too; only pool WARMING/RESETTING writes are excluded from the journal.
     */
    public void beforeBlockChange(BlockPos pos, BlockState replacement, BlockState previous) {
        if (replacement == previous) return;
        Slot slot = this.slotAt(pos);
        if (slot == null || slot.state != State.LEASED) return;
        slot.journal.putIfAbsent(pos.asLong(), Objects.requireNonNull(previous, "previous"));
        // Runtime geometry can reach a chunk outside static scene footprints.
        // Retain it before active tickets disappear, so reset cannot lose it.
        this.retainChunk(slot, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public List<Slot> slots() { return List.copyOf(this.slots); }

    private Slot selectJob() {
        for (Slot slot : this.slots) if (slot.state == State.RESETTING) return slot;
        for (Slot slot : this.slots) if (slot.lease != null && (slot.state == State.QUEUED || slot.state == State.WARMING)) return slot;
        for (Slot slot : this.slots) if (slot.state == State.QUEUED || slot.state == State.WARMING) return slot;
        return null;
    }

    private void registerProfile(PartyResidentCatalog.Profile profile) {
        Objects.requireNonNull(profile, "profile");
        PartyResidentCatalog.Profile existing = this.profiles.putIfAbsent(profile.key(), profile);
        if (existing != null && !existing.equals(profile)) throw new IllegalArgumentException("Resident profile changed: " + profile.key());
    }

    private Slot createSlot(PartyResidentCatalog.Profile profile) {
        int index = this.slots.size();
        BlockPos center = new BlockPos(88 + index % COLUMNS * COLUMN_SPACING, 101,
                FIRST_Z + 88 + index / COLUMNS * ROW_SPACING);
        Slot slot = new Slot(index, profile, center);
        BlockPos minimum = profile.bounds().minimum(center, this.world);
        BlockPos maximum = profile.bounds().maximum(center, this.world);
        // Validate the entire index before publishing any part of a new slot.
        // A rejected future profile must not leave orphan protected chunks.
        for (int x = minimum.getX() >> 4; x <= maximum.getX() >> 4; x++) {
            for (int z = minimum.getZ() >> 4; z <= maximum.getZ() >> 4; z++) {
                long key = ChunkPos.toLong(x, z);
                if (this.byChunk.containsKey(key)) throw new IllegalStateException("Overlapping Party resident slots");
            }
        }
        for (int x = minimum.getX() >> 4; x <= maximum.getX() >> 4; x++) {
            for (int z = minimum.getZ() >> 4; z <= maximum.getZ() >> 4; z++) this.byChunk.put(ChunkPos.toLong(x, z), slot);
        }
        this.slots.add(slot);
        return slot;
    }

    private void beginWarmup(Slot slot) throws IOException {
        MapBuildQueue queue = this.newQueue(slot);
        for (PartyResidentCatalog.Placement placement : slot.profile.placements()) {
            PartyMapBlueprint blueprint = PartyMapBlueprint.load(placement.geometry());
            BlockPos translation = slot.center.add(placement.translationRelativeToCenter());
            BlockPos minimum = blueprint.minimum().add(translation), maximum = blueprint.maximum().add(translation);
            if (!slot.profile.bounds().contains(slot.center, minimum) || !slot.profile.bounds().contains(slot.center, maximum)
                    || minimum.getY() < this.world.getBottomY() || maximum.getY() >= this.world.getTopY())
                throw new IOException("Resident geometry outside profile bounds: " + slot.profile.key() + "/" + placement.geometry());
            for (int x = minimum.getX() >> 4; x <= maximum.getX() >> 4; x++) {
                for (int z = minimum.getZ() >> 4; z <= maximum.getZ() >> 4; z++) this.retainChunk(slot, x, z);
            }
            blueprint.forEachRun(translation, queue::stageSnapshotRun);
            slot.baselineBlocks = Math.addExact(slot.baselineBlocks, blueprint.blockCount());
        }
        queue.close();
        slot.queue = queue;
        slot.state = State.WARMING;
    }

    private MapBuildQueue newQueue(Slot slot) {
        return new MapBuildQueue(this.world, slot.profile.bounds().minimum(slot.center, this.world),
                slot.profile.bounds().maximum(slot.center, this.world));
    }

    private void retainChunk(Slot slot, int x, int z) {
        if (slot.chunks.add(ChunkPos.toLong(x, z))) this.world.getChunkManager().addTicket(TICKET, new ChunkPos(x, z), FULL_RADIUS, slot.ticketKey);
    }

    private boolean chunksReady(Slot slot) {
        var iterator = slot.chunks.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            if (this.world.getChunkManager().getWorldChunk(ChunkPos.getPackedX(packed), ChunkPos.getPackedZ(packed)) == null) return false;
        }
        return true;
    }

    private void captureBlockEntities(Slot slot) {
        slot.baselineBlockEntities.clear();
        this.visitBlockEntities(slot, blockEntity -> slot.baselineBlockEntities.put(blockEntity.getPos().asLong(),
                new SavedBlockEntity(blockEntity.getCachedState(), blockEntity.createNbtWithIdentifyingData(this.world.getRegistryManager()))));
    }

    private void visitBlockEntities(Slot slot, java.util.function.Consumer<BlockEntity> visitor) {
        var iterator = slot.chunks.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            WorldChunk chunk = this.world.getChunkManager().getWorldChunk(ChunkPos.getPackedX(packed), ChunkPos.getPackedZ(packed));
            if (chunk == null) throw new IllegalStateException("Resident FULL chunk disappeared");
            for (BlockEntity blockEntity : List.copyOf(chunk.getBlockEntities().values())) {
                if (!blockEntity.isRemoved() && slot.profile.bounds().contains(slot.center, blockEntity.getPos())) visitor.accept(blockEntity);
            }
        }
    }

    private void beginReset(Slot slot) {
        this.clearScheduledTicks(slot);
        this.discardEntities(slot);
        // Data-only changes do not call setBlockState. Recreate every baseline
        // BE from saved data instead of retaining a previous round's inventory.
        this.visitBlockEntities(slot, blockEntity -> this.world.removeBlockEntity(blockEntity.getPos()));
        MapBuildQueue queue = this.newQueue(slot);
        for (Long2ObjectMap.Entry<BlockState> entry : slot.journal.long2ObjectEntrySet()) {
            queue.stageSnapshotRun(BlockPos.fromLong(entry.getLongKey()), entry.getValue(), Direction.Axis.X, 1);
        }
        for (Long2ObjectMap.Entry<SavedBlockEntity> entry : slot.baselineBlockEntities.long2ObjectEntrySet()) {
            SavedBlockEntity baseline = entry.getValue();
            BlockPos pos = BlockPos.fromLong(entry.getLongKey());
            BlockEntity restored = BlockEntity.createFromNbt(pos, baseline.state(), baseline.nbt().copy(), this.world.getRegistryManager());
            if (restored == null) throw new IllegalStateException("Cannot restore resident block entity at " + pos);
            queue.deferBlockEntity(restored);
        }
        queue.close();
        slot.queue = queue;
    }

    private void clearScheduledTicks(Slot slot) {
        BlockPos minimum = slot.profile.bounds().minimum(slot.center, this.world), maximum = slot.profile.bounds().maximum(slot.center, this.world);
        BlockBox box = new BlockBox(minimum.getX(), minimum.getY(), minimum.getZ(), maximum.getX(), maximum.getY(), maximum.getZ());
        this.world.getBlockTickScheduler().clearNextTicks(box);
        this.world.getFluidTickScheduler().clearNextTicks(box);
    }

    private void discardEntities(Slot slot) {
        for (Entity entity : this.world.getOtherEntities(null, slot.profile.bounds().box(slot.center, this.world), entity -> !(entity instanceof PlayerEntity))) entity.discard();
    }

    private void ensureOpen() { if (this.closed) throw new IllegalStateException("Party resident maps are closed"); }
    private void ensureLease(Lease lease) {
        this.ensureOpen();
        if (lease == null || lease.manager() != this) throw new IllegalArgumentException("Foreign Party resident lease");
    }

    @Override public void close() {
        if (this.closed) return;
        this.closed = true;
        for (Slot slot : this.slots) {
            var iterator = slot.chunks.iterator();
            while (iterator.hasNext()) {
                long packed = iterator.nextLong();
                this.world.getChunkManager().removeTicket(TICKET, new ChunkPos(packed), FULL_RADIUS, slot.ticketKey);
            }
            slot.chunks.clear(); slot.journal.clear(); slot.baselineBlockEntities.clear(); slot.queue = null;
            if (slot.lease != null) { slot.lease.resetDone = true; slot.lease.released = true; slot.lease = null; }
        }
        this.byChunk.clear(); this.slots.clear(); this.profiles.clear();
    }

    public static final class Slot {
        private final int index;
        private final PartyResidentCatalog.Profile profile;
        private final BlockPos center;
        private final UUID ticketKey = UUID.randomUUID();
        private final LongOpenHashSet chunks = new LongOpenHashSet();
        private final Long2ObjectMap<BlockState> journal = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectMap<SavedBlockEntity> baselineBlockEntities = new Long2ObjectOpenHashMap<>();
        private State state = State.QUEUED;
        private Lease lease;
        private MapBuildQueue queue;
        private long baselineBlocks;
        private String failure;
        private Slot(int index, PartyResidentCatalog.Profile profile, BlockPos center) {
            this.index = index; this.profile = profile; this.center = center.toImmutable();
        }
        public int index() { return this.index; }
        public PartyResidentCatalog.Profile profile() { return this.profile; }
        public BlockPos center() { return this.center; }
        public PartyArenaBounds bounds() { return this.profile.bounds(); }
        public State state() { return this.state; }
        public Object owner() { return this.lease == null ? null : this.lease.owner; }
        public long baselineBlocks() { return this.baselineBlocks; }
        public int changedBlocks() { return this.journal.size(); }
        public int retainedChunks() { return this.chunks.size(); }
        public int baselineBlockEntityCount() { return this.baselineBlockEntities.size(); }
    }

    public final class Lease {
        private final Slot slot;
        private final Object owner;
        private boolean released, resetDone;
        private Lease(Slot slot, Object owner) { this.slot = slot; this.owner = owner; }
        private PartyResidentMaps manager() { return PartyResidentMaps.this; }
        public BlockPos center() { return this.slot.center; }
        public PartyArenaBounds bounds() { return this.slot.profile.bounds(); }
        public PartyResidentCatalog.Profile profile() { return this.slot.profile; }
        public int slotIndex() { return this.slot.index; }
        public Object owner() { return this.owner; }
        public boolean ready() { return !this.released && this.slot.lease == this && this.slot.state == State.LEASED; }
        public boolean resetReady() { return this.resetDone; }
        public boolean failed() { return this.slot.state == State.BROKEN; }
        public String failure() { return this.slot.failure; }
        public double progress() { return this.ready() ? 1 : this.slot.queue == null ? 0 : this.slot.queue.progress(); }
        public boolean contains(BlockPos pos) { return this.slot.profile.bounds().contains(this.slot.center, pos); }
        /** Skip only exact static baseline placements, never a different template or translation. */
        public boolean coversGeometry(String name, BlockPos absoluteTranslation) {
            if (!this.ready()) return false;
            for (PartyResidentCatalog.Placement placement : this.slot.profile.placements()) {
                if (placement.geometry().equals(name) && this.slot.center.add(placement.translationRelativeToCenter()).equals(absoluteTranslation)) return true;
            }
            return false;
        }
    }
}
