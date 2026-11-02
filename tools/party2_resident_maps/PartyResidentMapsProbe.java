package com.example.pvp.validation;

import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.party.PartyChunkTickets;
import com.example.pvp.arena.party.PartyMapBlueprint;
import com.example.pvp.arena.party.PartyResidentCatalog;
import com.example.pvp.arena.party.PartyResidentMaps;
import com.google.gson.Gson;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot-only native pool integration. All source cells, including the full
 * Rocket/Flight bodies, are compared independently with last-placement-wins
 * overlap handling. Direct API/state fixtures are explicitly not player input
 * or a rendered-client acceptance test. Never included in the shipped mod.
 */
public final class PartyResidentMapsProbe implements ModInitializer {
    private record Geometry(PartyMapBlueprint blueprint, BlockPos translation, BlockBox box) { }
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final Map<String, Double> readyMillis = new LinkedHashMap<>();
    private final List<Map<String, Object>> projections = new ArrayList<>();
    private final List<Double> warmTickMillis = new ArrayList<>();
    private final Object ownerA = new Object(), ownerD = new Object(), ownerE = new Object(), ownerC = new Object();
    private ArenaWorld world;
    private PartyResidentMaps pool;
    private PartyResidentCatalog catalog;
    private List<PartyResidentMaps.Slot> initialSlots;
    private PartyResidentMaps.Lease a, d, e, cancel, reused;
    private PartyChunkTickets ticketsA, ticketsE;
    private BlockPos air, newChest, solid, baselineChest, sentinelE;
    private BlockState baselineSolid;
    private NbtCompound baselineChestData;
    private ArmorStandEntity temporary;
    private long started, tickStarted, resetStarted;
    private int ticks, verifyIndex, drain;
    private String phase = "STARTING";
    private boolean finished;

    @Override public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> this.tickStarted = System.nanoTime());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                this.started = System.nanoTime();
                this.world = ArenaWorldManager.get(server).createWorld();
                // Reflection permits compiling this observer before root's
                // shared world-getter integration has been built by Gradle.
                this.pool = (PartyResidentMaps) ArenaWorld.class.getMethod("residentMaps").invoke(this.world);
                this.catalog = PartyResidentCatalog.load();
                this.check("Production startup registered every resident profile", this.pool.slots().size() == this.catalog.allProfiles().size(), this.pool.slots().size());
                this.initialSlots = this.pool.slots();
                this.report.put("profileCount", this.catalog.allProfiles().size());
                this.phase = "WARM_ALL";
            } catch (Throwable failure) { this.finish(server, failure); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (this.finished) return;
            try { this.tick(server); }
            catch (Throwable failure) { this.finish(server, failure); }
        });
    }

    private void tick(MinecraftServer server) throws Exception {
        this.ticks++;
        if (this.ticks > 18_000) throw new IllegalStateException("Resident native regression exceeded18000ticks");
        // The production entrypoint already calls this; the manager's native
        // tick guard must prevent accidental double flushes from observers.
        this.pool.tick();
        if (this.phase.equals("WARM_ALL")) {
            this.warmTickMillis.add(millisSince(this.tickStarted));
            for (PartyResidentMaps.Slot slot : this.initialSlots) {
                if (slot.state() == PartyResidentMaps.State.BROKEN) throw new IllegalStateException("Prewarm failed: " + slot.profile().key());
                if (slot.state() == PartyResidentMaps.State.READY) this.readyMillis.putIfAbsent(slot.profile().key(), millisSince(this.started));
            }
            if (this.ticks % 200 == 0) System.out.println("PARTY_RESIDENT_PROGRESS " + this.readyMillis.size() + "/" + this.initialSlots.size());
            if (this.readyMillis.size() != this.initialSlots.size()) return;
            this.report.put("allProfilesWarmMillis", millisSince(this.started));
            this.report.put("profileReadyMillis", this.readyMillis);
            this.report.put("warmTickUntilObserverMillis", this.stats(this.warmTickMillis));
            this.check("All production baselines reached unowned READY", this.initialSlots.stream().allMatch(slot -> slot.state() == PartyResidentMaps.State.READY && slot.owner() == null), this.initialSlots.size());
            this.phase = "VERIFY_ALL";
        } else if (this.phase.equals("VERIFY_ALL")) {
            if (this.verifyIndex < this.initialSlots.size()) {
                PartyResidentMaps.Slot slot = this.initialSlots.get(this.verifyIndex++);
                this.verifyProfile(slot, "initial");
                BlockPos witness = this.firstCell(slot);
                this.check("Idle FULL does not entity-tick " + slot.profile().key(), !this.world.shouldTickEntity(witness), witness.toString());
                return;
            }
            this.beginLeases();
        } else if (this.phase.equals("WAIT_DUPLICATE")) {
            this.failIfBroken(this.e);
            if (!this.e.ready() || !this.ticketsA.ready() || !this.ticketsE.ready()) return;
            this.check("Simultaneous same-game leases are ready on separate slots", this.d.ready() && this.e.ready() && this.d.slotIndex() != this.e.slotIndex(), List.of(this.d.slotIndex(), this.e.slotIndex()));
            this.cancel = this.pool.acquire(this.catalog.profile("tedious_tasks"), this.ownerC);
            this.phase = "WAIT_CANCEL";
        } else if (this.phase.equals("WAIT_CANCEL")) {
            this.failIfBroken(this.cancel);
            PartyResidentMaps.State state = this.pool.slotAt(this.cancel.center()).state();
            if (state == PartyResidentMaps.State.QUEUED) return;
            this.check("Cancellation fixture observed an actually started WARMING reservation", state == PartyResidentMaps.State.WARMING, state.name());
            this.pool.release(this.cancel);
            this.check("Cancelling WARMING releases its owner without declaring the scene ready", this.cancel.resetReady()
                    && this.pool.ownerAt(this.cancel.center()) == null && this.pool.slotAt(this.cancel.center()).state() == PartyResidentMaps.State.WARMING,
                    this.pool.slotAt(this.cancel.center()).state().name());
            this.mutateFixtures();
        } else if (this.phase.equals("WAIT_RESET")) {
            this.failIfBroken(this.a);
            if (!this.a.resetReady()) return;
            this.report.put("sparseRocketResetMillis", millisSince(this.resetStarted));
            this.verifyReset();
        } else if (this.phase.equals("WAIT_FINAL_RESETS")) {
            this.failIfBroken(this.reused); this.failIfBroken(this.d); this.failIfBroken(this.e);
            if (!this.reused.resetReady() || !this.d.resetReady() || !this.e.resetReady()
                    || this.pool.slotAt(this.cancel.center()).state() != PartyResidentMaps.State.READY) return;
            this.ticketsA.close(); this.ticketsE.close();
            this.phase = "DRAIN_ACTIVE_TICKETS";
        } else if (this.phase.equals("DRAIN_ACTIVE_TICKETS")) {
            if (++this.drain < 40) return;
            this.check("Returned scenes retain FULL chunks after active tickets are removed", this.world.getChunkManager().getWorldChunk(this.air.getX() >> 4, this.air.getZ() >> 4) != null
                    && this.world.getChunkManager().getWorldChunk(this.sentinelE.getX() >> 4, this.sentinelE.getZ() >> 4) != null, "FULL");
            this.check("Returned scenes stop entity ticking while remaining resident", !this.world.shouldTickEntity(this.air) && !this.world.shouldTickEntity(this.sentinelE), "level33only");
            this.check("Cancelled warmup completes as an unowned reusable baseline", this.pool.slotAt(this.cancel.center()).owner() == null, this.pool.slotAt(this.cancel.center()).state().name());
            this.check("All original and additional resident slots finish READY", this.pool.slots().stream().allMatch(slot -> slot.state() == PartyResidentMaps.State.READY), this.pool.slots().size());
            this.finish(server, null);
        }
    }

    private void beginLeases() throws Exception {
        long before = System.nanoTime();
        this.a = this.pool.acquire(this.catalog.profile("rocket_launch"), this.ownerA);
        this.report.put("firstWarmRocketLeaseMillis", millisSince(before));
        this.d = this.pool.acquire(this.catalog.profile("tedious_tasks"), this.ownerD);
        this.e = this.pool.acquire(this.catalog.profile("tedious_tasks"), this.ownerE);
        this.check("A prewarmed large scene is available immediately", this.a.ready(), this.a.slotIndex());
        this.check("A second concurrent same-game lease reserves a different scene", this.d.slotIndex() != this.e.slotIndex(), List.of(this.d.slotIndex(), this.e.slotIndex()));
        this.air = this.a.center().up(199);
        this.newChest = this.air.east(2);
        this.sentinelE = this.e.center().up(199);
        this.check("Delta fixtures start in actual baseline air", this.world.getBlockState(this.air).isAir() && this.world.getBlockState(this.newChest).isAir(), this.air.toString());
        PartyResidentMaps.Slot rocket = this.pool.slotAt(this.a.center());
        this.solid = this.findStone(rocket);
        this.baselineSolid = this.world.getBlockState(this.solid);
        this.baselineChest = this.findChest(rocket);
        this.baselineChestData = this.world.getBlockEntity(this.baselineChest).createNbtWithIdentifyingData(this.world.getRegistryManager());
        this.ticketsA = new PartyChunkTickets(this.world);
        for (BlockPos pos : List.of(this.air, this.solid, this.baselineChest)) this.ticketsA.retain(pos, pos);
        this.ticketsE = new PartyChunkTickets(this.world); this.ticketsE.retain(this.sentinelE, this.sentinelE);
        this.phase = "WAIT_DUPLICATE";
    }

    private void mutateFixtures() {
        this.world.setBlockStateDirect(this.air, Blocks.STONE.getDefaultState());
        this.world.setBlockStateSnapshotDirect(this.air, Blocks.GOLD_BLOCK.getDefaultState());
        this.world.setBlockStateDirect(this.solid, Blocks.AIR.getDefaultState());
        this.world.setBlockStateSnapshotDirect(this.newChest, Blocks.CHEST.getDefaultState());
        ((ChestBlockEntity)this.world.getBlockEntity(this.newChest)).setStack(0, new ItemStack(Items.DIAMOND, 9));
        ((ChestBlockEntity)this.world.getBlockEntity(this.baselineChest)).setStack(0, new ItemStack(Items.DIAMOND, 7));
        this.world.scheduleBlockTick(this.air, Blocks.STONE, 1000);
        this.world.scheduleFluidTick(this.air, Fluids.WATER, 1000);
        this.temporary = new ArmorStandEntity(this.world, this.air.getX() + .5, this.air.getY() + 1, this.air.getZ() + .5);
        this.temporary.setNoGravity(true); this.temporary.addCommandTag("party_resident_fixture");
        this.check("Fixture creates a real native temporary entity", this.world.spawnEntityDirect(this.temporary), this.temporary.getUuid().toString());
        this.world.setBlockStateDirect(this.sentinelE, Blocks.DIAMOND_BLOCK.getDefaultState());
        this.check("Every leased write including snapshots journals its first old state exactly once", this.pool.slotAt(this.a.center()).changedBlocks() == 3, this.pool.slotAt(this.a.center()).changedBlocks());
        this.check("Both old native tick queues contain the explicit scheduled fixtures", this.world.getBlockTickScheduler().isQueued(this.air, Blocks.STONE)
                && this.world.getFluidTickScheduler().isQueued(this.air, Fluids.WATER), "block+fluid");
        this.check("A concurrent slot has its own independent owner and delta", this.pool.ownerAt(this.sentinelE) == this.ownerE
                && this.pool.slotAt(this.sentinelE).changedBlocks() == 1, this.e.slotIndex());
        this.pool.release(this.a);
        this.resetStarted = System.nanoTime();
        this.check("Released scenes remain unavailable until sparse restoration completes", !this.a.resetReady() && this.pool.isIdleAt(this.air), this.pool.slotAt(this.air).state().name());
        this.phase = "WAIT_RESET";
    }

    private void verifyReset() throws Exception {
        this.check("Former baseline air and a newly-created BE return to air", this.world.getBlockState(this.air).isAir()
                && this.world.getBlockState(this.newChest).isAir() && this.world.getBlockEntity(this.newChest) == null, "AIR/noBE");
        this.check("A removed original block returns to its exact saved state", this.world.getBlockState(this.solid) == this.baselineSolid, this.world.getBlockState(this.solid).toString());
        BlockEntity chest = this.world.getBlockEntity(this.baselineChest);
        this.check("NBT-only inventory changes reset to the entire baseline BE data", chest instanceof ChestBlockEntity
                && this.baselineChestData.equals(chest.createNbtWithIdentifyingData(this.world.getRegistryManager())), this.baselineChest.toString());
        this.check("Old scheduled block and fluid ticks are removed", !this.world.getBlockTickScheduler().isQueued(this.air, Blocks.STONE)
                && !this.world.getFluidTickScheduler().isQueued(this.air, Fluids.WATER), "cleared");
        this.check("Native temporary entities are discarded", this.temporary.isRemoved(), String.valueOf(this.temporary.getRemovalReason()));
        this.check("Reset does not scatter old or newly placed container contents", this.world.getEntitiesByClass(ItemEntity.class, this.a.bounds().box(this.a.center(), this.world), item -> item.getStack().isOf(Items.DIAMOND)).isEmpty(), "no diamonds");
        this.check("Reset releases ownership and the sparse journal", this.pool.ownerAt(this.air) == null && this.pool.slotAt(this.air).changedBlocks() == 0, this.pool.slotAt(this.air).state().name());
        this.check("Reset does not affect a concurrent scene's owner, terrain or lease", this.e.ready() && this.d.ready()
                && this.pool.ownerAt(this.sentinelE) == this.ownerE && this.world.getBlockState(this.sentinelE).isOf(Blocks.DIAMOND_BLOCK), this.e.slotIndex());
        this.verifyProfile(this.pool.slotAt(this.a.center()), "afterSparseReset");
        long before = System.nanoTime();
        this.reused = this.pool.acquire(this.catalog.profile("rocket_launch"), new Object());
        this.report.put("repeatRocketLeaseMillis", millisSince(before));
        this.check("The next large-map lease reuses the ready scene immediately", this.reused.ready() && this.reused.slotIndex() == this.a.slotIndex(), List.of(this.a.slotIndex(), this.reused.slotIndex()));
        PartyResidentCatalog.Placement placement = this.reused.profile().placements().getFirst();
        BlockPos translation = this.reused.center().add(placement.translationRelativeToCenter());
        this.check("Only the exact prewarmed source placement skips restaging", this.reused.coversGeometry(placement.geometry(), translation)
                && !this.reused.coversGeometry(placement.geometry(), translation.east()) && !this.reused.coversGeometry("take_flight", translation), placement.geometry());
        this.pool.release(this.reused); this.pool.release(this.d); this.pool.release(this.e);
        this.phase = "WAIT_FINAL_RESETS";
    }

    private void verifyProfile(PartyResidentMaps.Slot slot, String reason) throws Exception {
        long before = System.nanoTime();
        List<Geometry> geometries = this.geometries(slot);
        Long2ObjectOpenHashMap<BlockState> overrides = new Long2ObjectOpenHashMap<>();
        BlockPos.Mutable position = new BlockPos.Mutable();
        // Only material from intersecting later placements is indexed. The13M
        // one-placement map remains compressed throughout independent checking.
        for (int index = 1; index < geometries.size(); index++) {
            Geometry later = geometries.get(index);
            List<BlockBox> intersections = new ArrayList<>();
            for (int earlier = 0; earlier < index; earlier++) if (later.box().intersects(geometries.get(earlier).box())) intersections.add(geometries.get(earlier).box());
            if (intersections.isEmpty()) continue;
            later.blueprint().forEachRun(later.translation(), (start, state, axis, length) -> {
                for (int offset = 0; offset < length; offset++) {
                    this.offset(position, start, axis, offset);
                    if (intersections.stream().anyMatch(box -> box.contains(position))) overrides.put(position.asLong(), state);
                }
            });
        }
        LongOpenHashSet chunks = new LongOpenHashSet();
        for (Geometry geometry : geometries) {
            BlockBox box = geometry.box();
            for (int x = box.getMinX() >> 4; x <= box.getMaxX() >> 4; x++) for (int z = box.getMinZ() >> 4; z <= box.getMaxZ() >> 4; z++) chunks.add(ChunkPos.toLong(x, z));
        }
        boolean full = true;
        var chunksIterator = chunks.iterator();
        while (chunksIterator.hasNext()) {
            long packed = chunksIterator.nextLong();
            if (this.world.getChunkManager().getWorldChunk(ChunkPos.getPackedX(packed), ChunkPos.getPackedZ(packed)) == null) { full = false; break; }
        }
        this.check(reason + " nonblocking FULL chunks " + slot.profile().key(), full, chunks.size());
        long[] visited = {0};
        for (Geometry geometry : geometries) geometry.blueprint().forEachRun(geometry.translation(), (start, expected, axis, length) -> {
            for (int offset = 0; offset < length; offset++) {
                this.offset(position, start, axis, offset);
                BlockState replacement = overrides.get(position.asLong());
                BlockState finalExpected = replacement == null ? expected : replacement;
                BlockState actual = this.world.getBlockState(position);
                if (actual != finalExpected) throw new IllegalStateException("Resident projection differs " + slot.profile().key() + " at " + position + ": " + actual + " expected " + finalExpected);
                visited[0]++;
            }
        });
        long expectedCount = geometries.stream().mapToLong(geometry -> geometry.blueprint().blockCount()).sum();
        this.check(reason + " exact full-cell final projection " + slot.profile().key(), visited[0] == expectedCount, visited[0]);
        this.projections.add(Map.of("profile", slot.profile().key(), "reason", reason, "encodedCells", visited[0],
                "overlapCells", overrides.size(), "verificationMillis", millisSince(before)));
    }

    private List<Geometry> geometries(PartyResidentMaps.Slot slot) throws Exception {
        List<Geometry> result = new ArrayList<>();
        for (PartyResidentCatalog.Placement placement : slot.profile().placements()) {
            PartyMapBlueprint blueprint = PartyMapBlueprint.load(placement.geometry());
            BlockPos translation = slot.center().add(placement.translationRelativeToCenter());
            BlockPos min = blueprint.minimum().add(translation), max = blueprint.maximum().add(translation);
            result.add(new Geometry(blueprint, translation, new BlockBox(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ())));
        }
        return result;
    }
    private void offset(BlockPos.Mutable target, BlockPos start, Direction.Axis axis, int offset) {
        target.set(start.getX() + (axis == Direction.Axis.X ? offset : 0), start.getY() + (axis == Direction.Axis.Y ? offset : 0), start.getZ() + (axis == Direction.Axis.Z ? offset : 0));
    }
    private BlockPos firstCell(PartyResidentMaps.Slot slot) throws Exception {
        BlockPos[] first = {null};
        Geometry geometry = this.geometries(slot).getFirst();
        geometry.blueprint().forEachRun(geometry.translation(), (pos, state, axis, length) -> { if (first[0] == null) first[0] = pos; });
        if (first[0] == null) throw new IllegalStateException("Empty resident geometry");
        return first[0];
    }
    private BlockPos findStone(PartyResidentMaps.Slot slot) throws Exception {
        BlockPos[] first = {null};
        for (Geometry geometry : this.geometries(slot)) geometry.blueprint().forEachRun(geometry.translation(), (pos, state, axis, length) -> {
            if (first[0] == null && state.isOf(Blocks.STONE)) first[0] = pos;
        });
        if (first[0] == null) throw new IllegalStateException("Fixture needs an original stone block");
        return first[0];
    }
    private BlockPos findChest(PartyResidentMaps.Slot slot) throws Exception {
        for (Geometry geometry : this.geometries(slot)) {
            BlockBox box = geometry.box();
            for (int x = box.getMinX() >> 4; x <= box.getMaxX() >> 4; x++) for (int z = box.getMinZ() >> 4; z <= box.getMaxZ() >> 4; z++) {
                var chunk = this.world.getChunkManager().getWorldChunk(x, z);
                if (chunk != null) for (BlockEntity entity : chunk.getBlockEntities().values()) if (entity instanceof ChestBlockEntity && slot.bounds().contains(slot.center(), entity.getPos())) return entity.getPos().toImmutable();
            }
        }
        throw new IllegalStateException("Fixture needs an original chest BE");
    }
    private void failIfBroken(PartyResidentMaps.Lease lease) { if (lease.failed()) throw new IllegalStateException(lease.failure()); }
    private Map<String, Object> stats(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return Map.of("count", values.size(), "mean", values.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                "p95", sorted.get(Math.min(sorted.size() - 1, (int)Math.ceil(sorted.size() * .95) - 1)), "max", sorted.getLast());
    }
    private void check(String name, boolean pass, Object actual) {
        this.checks.add(Map.of("name", name, "pass", pass, "actual", actual));
        if (!pass) throw new IllegalStateException(name + ": " + actual);
    }
    private static double millisSince(long before) { return (System.nanoTime() - before) / 1_000_000.0; }
    private void finish(MinecraftServer server, Throwable failure) {
        if (this.finished) return;
        this.finished = true;
        if (this.ticketsA != null) this.ticketsA.close();
        if (this.ticketsE != null) this.ticketsE.close();
        this.report.put("ok", failure == null); this.report.put("checks", this.checks); this.report.put("projections", this.projections);
        this.report.put("phase", this.phase); this.report.put("nativeTicks", this.ticks); this.report.put("totalMillisIncludingVerification", millisSince(this.started));
        this.report.put("fixture", "Direct native API leases and actual block/BE/entity/tick mutations; no player packets or rendered-client acceptance claimed. Full-cell verification time is excluded from warm/reset timings.");
        if (failure != null) { this.report.put("error", failure.toString()); failure.printStackTrace(); }
        try { Files.writeString(Path.of("party-resident-maps-report.json"), new Gson().toJson(this.report) + "\n"); }
        catch (Exception error) { throw new IllegalStateException(error); }
        server.stop(false);
    }
}
