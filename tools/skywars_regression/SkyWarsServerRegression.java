package com.example.pvp.validation;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.MapBuildQueue;
import com.example.pvp.arena.skywars.SkyWarsLayout;
import com.example.pvp.arena.skywars.SkyWarsMapGenerator;
import com.example.pvp.arena.skywars.SkyWarsMapVariant;
import com.example.pvp.arena.skywars.SkyWarsTheme;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.mygo.MyGoWeaponItemCodec;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test-only entrypoint added to the isolated runtime snapshot by the Python runner. */
public final class SkyWarsServerRegression implements ModInitializer {
    private static final boolean BASELINE = Boolean.getBoolean("skywars.validation.baseline");
    private static final int REGION = 1;
    private static final int RADIUS = 8;
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private ArenaWorld world;
    private ArenaTemplate template;
    private BlockPos center;
    private Box bounds;
    private Lease region;
    private Lease neighbor;
    private UUID oldItem;
    private UUID neighborItem;
    private UUID nextRoundItem;
    private int stage;
    private int stageStart;
    private int dropLoads;
    private boolean observingClear;
    private boolean finished;
    private SkyWarsLayout fullLayout;
    private MapBuildQueue fullBuild;
    private boolean fullGunMode;
    private final List<Map<String, Object>> fullMapTimings = new ArrayList<>();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            this.world = ArenaWorldManager.get(server).getWorld();
            this.template = new ArenaTemplate(ArenaTemplate.Layout.SKYWARS,
                    PvPConfig.INSTANCE.skywarsSize, Blocks.STONE, Blocks.STONE, false);
            this.center = SkyWarsMapGenerator.center(REGION);
            this.bounds = box(this.center, RADIUS + 4);
            this.stageStart = server.getTicks();
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, loadedWorld) -> {
            if (this.observingClear && loadedWorld == this.world && entity instanceof ItemEntity
                    && this.bounds.contains(entity.getPos())) this.dropLoads++;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (this.world == null || this.finished) return;
            try {
                if (server.getTicks() - this.stageStart > 700) {
                    throw new AssertionError("Timed out in regression stage " + this.stage);
                }
                tick(server);
            } catch (Throwable error) {
                error.printStackTrace();
                finish(server, error);
            }
        });
    }

    private void tick(MinecraftServer server) throws Exception {
        switch (this.stage) {
            case 0 -> {
                verifyLoot();
                this.region = new Lease(this.world, this.bounds, REGION);
                this.neighbor = new Lease(this.world, box(SkyWarsMapGenerator.center(REGION + 1), 4), REGION + 1);
                advance(server);
            }
            case 1 -> {
                if (!this.region.ready() || !this.neighbor.ready()) return;
                fixtureContainer(this.center.add(2, 0, 2), Blocks.CHEST.getDefaultState());
                fixtureContainer(this.center.add(-2, 0, 2), Blocks.BARREL.getDefaultState());
                fixtureContainer(this.center.add(0, 0, -2), Blocks.SHULKER_BOX.getDefaultState());
                this.world.setBlockState(this.center.add(3, 0, 0), Blocks.GRASS_BLOCK.getDefaultState(), 3);
                this.world.setBlockState(this.center.add(3, 1, 0), Blocks.DANDELION.getDefaultState(), 3);
                this.oldItem = spawnItem(this.center.up(4), Items.DIAMOND);
                this.neighborItem = spawnItem(SkyWarsMapGenerator.center(REGION + 1).up(4), Items.NETHER_STAR);
                check("fixture contains loose death loot", this.world.getEntity(this.oldItem) != null);
                this.region.close();
                advance(server);
            }
            case 2 -> {
                if (this.world.isChunkLoaded(new ChunkPos(this.center).toLong())) return;
                check("old region entity chunk actually unloaded", this.world.getEntity(this.oldItem) == null);
                if (BASELINE) {
                    // The old path loads block data and immediately queries only tracked entities.
                    SkyWarsMapGenerator.clearIslands(this.world, REGION, RADIUS);
                    ArenaWorldManager.get(server).clearRegionEntities(REGION, this.template, RADIUS);
                    this.region = new Lease(this.world, this.bounds, REGION);
                    advance(server);
                } else {
                    this.region = new Lease(this.world, this.bounds, REGION);
                    advance(server);
                }
            }
            case 3 -> {
                if (!this.region.ready()) return;
                if (BASELINE) {
                    check("baseline reproduces old items after cleanup/reload", itemCount() > 0);
                    check("baseline neighbor remains intact", this.world.getEntity(this.neighborItem) != null);
                    finish(server, null);
                    return;
                }
                check("cleanup waits for saved death loot to reload", this.world.getEntity(this.oldItem) != null);
                check("container inventory survived unload", !((Inventory) this.world.getBlockEntity(this.center.add(2, 0, 2))).isEmpty());
                this.observingClear = true;
                // This is the exact shared cleanup called for both SkyWars MatchTypes.
                ArenaWorldManager.get(server).clearArena(REGION, this.template, RADIUS);
                check("chest removed", this.world.getBlockState(this.center.add(2, 0, 2)).isAir());
                check("barrel removed", this.world.getBlockState(this.center.add(-2, 0, 2)).isAir());
                check("shulker removed", this.world.getBlockState(this.center.add(0, 0, -2)).isAir());
                check("loaded old drops removed", itemCount() == 0);
                check("neighbor survived cleanup", this.world.getEntity(this.neighborItem) != null);
                advance(server);
            }
            case 4 -> {
                if (server.getTicks() - this.stageStart < 8) return;
                check("container/neighbor updates produced no drops", this.dropLoads == 0 && itemCount() == 0);
                this.region.close();
                advance(server);
            }
            case 5 -> {
                if (this.world.isChunkLoaded(new ChunkPos(this.center).toLong())) return;
                this.region = new Lease(this.world, this.bounds, REGION);
                advance(server);
            }
            case 6 -> {
                if (!this.region.ready()) return;
                check("cleaned region reload has no old items", itemCount() == 0);
                check("neighbor survives region reuse", this.world.getEntity(this.neighborItem) != null);
                this.observingClear = false;
                this.nextRoundItem = spawnItem(this.center.up(4), Items.EMERALD);
                fixtureContainer(this.center.add(2, 0, 2), Blocks.CHEST.getDefaultState());
                advance(server);
            }
            case 7 -> {
                if (server.getTicks() - this.stageStart < 8) return;
                check("next round can spawn drops normally", this.world.getEntity(this.nextRoundItem) != null);
                // Normal gameplay chest destruction must still drop its inventory.
                this.world.setBlockState(this.center.add(2, 0, 2), Blocks.AIR.getDefaultState(), 3);
                check("ordinary chest breaking still drops loot", itemCount() > 1);
                ArenaWorldManager.get(server).clearArena(REGION, this.template, RADIUS);
                check("second cleanup also removes all drops", itemCount() == 0);
                this.region.close();
                beginFullMap(server);
                advance(server);
            }
            case 8 -> {
                if (!this.region.ready()) return;
                int waitTicks = server.getTicks() - this.stageStart;
                this.fullMapTimings.add(new LinkedHashMap<>(Map.of("gunMode", this.fullGunMode,
                        "radius", this.fullLayout.maxRadius(), "ticketWaitTicks", waitTicks)));
                advance(server);
            }
            case 9 -> {
                if (!this.fullBuild.flush(5)) return;
                check((this.fullGunMode ? "gun" : "normal") + " full map physically contains stocked chests",
                        !((Inventory) this.world.getBlockEntity(this.fullLayout.extraChests().getFirst().pos())).isEmpty());
                this.observingClear = true;
                this.dropLoads = 0;
                long start = System.nanoTime();
                ArenaWorldManager.get(server).clearArena(REGION, this.template, this.fullLayout.maxRadius());
                this.fullMapTimings.getLast().put("clearMillis", (System.nanoTime() - start) / 1_000_000L);
                advance(server);
            }
            case 10 -> {
                if (server.getTicks() - this.stageStart < 8) return;
                check((this.fullGunMode ? "gun" : "normal") + " full map cleanup leaves no drops",
                        this.dropLoads == 0 && itemCount() == 0);
                check((this.fullGunMode ? "gun" : "normal") + " full map cleanup preserves neighbor",
                        this.world.getEntity(this.neighborItem) != null);
                this.region.close();
                if (this.fullGunMode) finish(server, null);
                else {
                    this.fullGunMode = true;
                    beginFullMap(server);
                    this.stage = 8;
                    this.stageStart = server.getTicks();
                }
            }
            default -> throw new IllegalStateException("Unexpected stage " + this.stage);
        }
    }

    private void beginFullMap(MinecraftServer server) throws Exception {
        this.observingClear = false;
        int seed = 137;
        this.fullLayout = SkyWarsLayout.compute(this.center, seed, 4);
        this.bounds = ArenaWorldManager.get(server).regionEntityBounds(REGION, this.template, this.fullLayout.maxRadius());
        this.region = new Lease(this.world, this.bounds, REGION);
        this.fullBuild = new MapBuildQueue(this.world, this.center, this.fullLayout.maxRadius() + 16);
        this.world.beginStaging(this.fullBuild);
        try {
            if (this.fullGunMode) SkyWarsMapGenerator.generateGunSkywars(this.world, REGION, seed, 4, List.of(), this.fullLayout);
            else SkyWarsMapGenerator.generate(this.world, REGION, seed, 4, List.of(), SkyWarsMapVariant.CLASSIC, this.fullLayout);
        } finally {
            this.world.endStaging();
            this.fullBuild.close();
        }
    }

    private void verifyLoot() throws Exception {
        Fingerprint[] normal = {generate(false), generate(false)};
        Fingerprint[] guns = {generate(true), generate(true)};
        check("normal same map terrain unchanged", normal[0].terrain.equals(normal[1].terrain));
        check("gun same map terrain unchanged", guns[0].terrain.equals(guns[1].terrain));
        check("normal and gun modes reuse terrain", normal[0].terrain.equals(guns[0].terrain));
        check("normal maps contain real stocked chests", normal[0].chests > 0 && normal[1].chests == normal[0].chests);
        check("gun maps contain real tagged guns", guns[0].guns > 0 && guns[1].guns > 0);
        check(BASELINE ? "baseline normal loot repeats" : "normal loot changes between matches",
                normal[0].loot.equals(normal[1].loot) == BASELINE);
        check(BASELINE ? "baseline gun loot repeats" : "gun loot changes between matches",
                guns[0].loot.equals(guns[1].loot) == BASELINE);
        check("normal refill rerolls inventory", !normal[0].loot.equals(normal[0].refill));
        check("gun refill rerolls inventory", !guns[0].loot.equals(guns[0].refill));
    }

    private Fingerprint generate(boolean gunMode) throws Exception {
        int seed = 137;
        SkyWarsLayout layout = SkyWarsLayout.compute(this.center, seed, 4);
        MapBuildQueue queue = new MapBuildQueue(this.world, this.center, layout.maxRadius() + 16);
        this.world.beginStaging(queue);
        try {
            if (gunMode) SkyWarsMapGenerator.generateGunSkywars(this.world, REGION, seed, 4, List.of(), layout);
            else SkyWarsMapGenerator.generate(this.world, REGION, seed, 4, List.of(), SkyWarsMapVariant.CLASSIC, layout);
            String terrain = terrainHash(queue);
            List<ChestBlockEntity> chests = queuedChests(queue);
            String loot = inventoryHash(chests);
            int guns = 0;
            for (ChestBlockEntity chest : chests) for (int slot = 0; slot < chest.size(); slot++) {
                if (MyGoWeaponItemCodec.isGun(chest.getStack(slot))) guns++;
            }
            if (gunMode) SkyWarsMapGenerator.refillGunSkywarsChests(this.world, layout, SkyWarsTheme.pick(seed), List.of());
            else SkyWarsMapGenerator.refillChests(this.world, layout, SkyWarsTheme.pick(seed), List.of());
            return new Fingerprint(terrain, loot, inventoryHash(chests), chests.size(), guns);
        } finally {
            this.world.endStaging();
            queue.close();
        }
    }

    // Read-only reflection is limited to the isolated test fixture; production has no diagnostic API.
    @SuppressWarnings("unchecked")
    private static List<ChestBlockEntity> queuedChests(MapBuildQueue queue) throws Exception {
        Field field = MapBuildQueue.class.getDeclaredField("blockEntities");
        field.setAccessible(true);
        return ((List<BlockEntity>) field.get(queue)).stream().filter(ChestBlockEntity.class::isInstance)
                .map(ChestBlockEntity.class::cast).toList();
    }

    private static String terrainHash(MapBuildQueue queue) throws Exception {
        Field p = MapBuildQueue.class.getDeclaredField("positions");
        Field s = MapBuildQueue.class.getDeclaredField("states");
        p.setAccessible(true); s.setAccessible(true);
        long[] positions = (long[]) p.get(queue);
        BlockState[] states = (BlockState[]) s.get(queue);
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < queue.totalStaged(); i++) {
            hash.update((positions[i] + ":" + states[i] + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static String inventoryHash(List<ChestBlockEntity> chests) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (ChestBlockEntity chest : chests) for (int slot = 0; slot < chest.size(); slot++) {
            ItemStack stack = chest.getStack(slot);
            hash.update((chest.getPos() + ":" + slot + ":" + stack + ":" + stack.getComponents() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private void fixtureContainer(BlockPos pos, BlockState state) {
        this.world.setBlockState(pos.down(), Blocks.STONE.getDefaultState(), 3);
        this.world.setBlockState(pos, state, 3);
        Inventory inventory = (Inventory) this.world.getBlockEntity(pos);
        inventory.setStack(0, new ItemStack(Items.DIAMOND, 16));
        inventory.setStack(1, new ItemStack(Items.ENDER_PEARL, 8));
        inventory.markDirty();
    }

    private UUID spawnItem(BlockPos pos, net.minecraft.item.Item type) {
        ItemEntity item = new ItemEntity(this.world, pos.getX() + .5, pos.getY(), pos.getZ() + .5,
                new ItemStack(type));
        item.setNoGravity(true);
        this.world.spawnEntity(item);
        return item.getUuid();
    }

    private int itemCount() {
        return this.world.getEntitiesByClass(ItemEntity.class, this.bounds, entity -> true).size();
    }

    private Box box(BlockPos at, int radius) {
        return new Box(at.getX() - radius, this.world.getBottomY(), at.getZ() - radius,
                at.getX() + radius + 1, this.world.getTopY(), at.getZ() + radius + 1);
    }

    private void advance(MinecraftServer server) { this.stage++; this.stageStart = server.getTicks(); }

    private void check(String name, boolean condition) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name); row.put("passed", condition);
        this.checks.add(row);
        System.out.println("[SkyWars regression] " + (condition ? "PASS " : "FAIL ") + name);
        if (!condition) throw new AssertionError(name);
    }

    private void finish(MinecraftServer server, Throwable error) {
        this.finished = true;
        try {
            if (this.region != null) this.region.close();
            if (this.neighbor != null) this.neighbor.close();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("minecraft", "1.21.1"); report.put("baseline", BASELINE);
            report.put("passed", error == null); report.put("checks", this.checks);
            report.put("fullMapTimings", this.fullMapTimings);
            report.put("error", error == null ? null : error.toString());
            Files.writeString(Path.of("skywars-results.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        } catch (Exception reportingError) { reportingError.printStackTrace(); }
        server.stop(false);
    }

    private record Fingerprint(String terrain, String loot, String refill, int chests, int guns) { }

    private static final class Lease implements AutoCloseable {
        private final ArenaWorld world;
        private final List<ChunkPos> chunks = new ArrayList<>();
        private Object cleanup;
        private boolean closed;

        Lease(ArenaWorld world, Box bounds, int id) throws Exception {
            this.world = world;
            if (!BASELINE) {
                Class<?> type = Class.forName("com.example.pvp.arena.ArenaRegionCleanup");
                this.cleanup = type.getConstructor(ArenaWorld.class, Box.class, int.class).newInstance(world, bounds, id);
            } else {
                for (int x = Math.floorDiv((int) bounds.minX, 16); x <= Math.floorDiv((int) bounds.maxX - 1, 16); x++) {
                    for (int z = Math.floorDiv((int) bounds.minZ, 16); z <= Math.floorDiv((int) bounds.maxZ - 1, 16); z++) {
                        this.chunks.add(new ChunkPos(x, z));
                        world.setChunkForced(x, z, true);
                    }
                }
            }
        }

        boolean ready() throws Exception {
            if (this.cleanup != null) return (boolean) this.cleanup.getClass().getMethod("ready").invoke(this.cleanup);
            return this.chunks.stream().allMatch(pos -> this.world.isChunkLoaded(pos.toLong())
                    && this.world.shouldTickEntity(pos.getStartPos()));
        }

        @Override
        public void close() throws Exception {
            if (this.closed) return;
            this.closed = true;
            if (this.cleanup != null) ((AutoCloseable) this.cleanup).close();
            else for (ChunkPos pos : this.chunks) this.world.setChunkForced(pos.x, pos.z, false);
        }
    }
}
