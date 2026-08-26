package com.example.pvp.arena;

import com.example.pvp.arena.bridge.BridgeLayout;
import com.example.pvp.arena.bridge.BridgeMapGenerator;
import com.example.pvp.arena.skywars.SkyWarsMapGenerator;
import com.example.pvp.match.MatchType;
import com.example.pvp.mixin.MinecraftServerAccess;
import com.example.pvp.util.PvpDimensionOptions;
import com.example.pvp.util.RemoveFromRegistry;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.SimpleRegistry;
import net.minecraft.registry.entry.RegistryEntryInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 管理运行时创建的竞技场世界：创建/注入、平台搭建与清理、目录回收。
 * 参考 NucleoidMC/fantasy 的运行时维度实现。
 */
public final class ArenaWorldManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final RegistryKey<World> ARENA_WORLD_KEY = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("pvp", "arena"));

    private static ArenaWorldManager instance;

    private final MinecraftServer server;
    private ArenaWorld world;

    /** 允许停留在竞技场内但不对局的玩家（调试/观览），到期自动移除。 */
    private final Set<UUID> inspectionVisitors = new HashSet<>();
    private final Map<UUID, Long> visitorExpiryTick = new HashMap<>();

    private ArenaWorldManager(MinecraftServer server) {
        this.server = server;
    }

    public void addVisitor(ServerPlayerEntity player, int seconds) {
        this.inspectionVisitors.add(player.getUuid());
        this.visitorExpiryTick.put(player.getUuid(), this.server.getTicks() + seconds * 20L);
    }

    public void removeVisitor(UUID uuid) {
        this.inspectionVisitors.remove(uuid);
        this.visitorExpiryTick.remove(uuid);
    }

    public boolean isVisitor(UUID uuid) {
        return this.inspectionVisitors.contains(uuid);
    }

    /** 每个服务器 tick 调用：到期自动移除访客。 */
    public void tickVisitors() {
        long now = this.server.getTicks();
        this.visitorExpiryTick.entrySet().removeIf(e -> e.getValue() < now && this.inspectionVisitors.remove(e.getKey()));
    }

    public static ArenaWorldManager get(MinecraftServer server) {
        if (instance == null || instance.server != server) {
            instance = new ArenaWorldManager(server);
        }
        return instance;
    }

    public static ArenaWorldManager getOrNull() {
        return instance;
    }

    /** 在服务器启动阶段创建竞技场世界（必须在服务端线程调用）。 */
    public ArenaWorld createWorld() {
        if (this.world != null) {
            return this.world;
        }

        var serverAccess = (MinecraftServerAccess) this.server;

        // 清理可能残留的旧竞技场目录
        Path arenaDir = serverAccess.getSession().getWorldDirectory(ARENA_WORLD_KEY);
        this.deleteDirectory(arenaDir);

        VoidChunkGenerator generator = new VoidChunkGenerator(this.server);
        DimensionOptions options = new DimensionOptions(this.dimTypeEntry(), generator);
        ((PvpDimensionOptions) (Object) options).pvp$setSave(false);
        ((PvpDimensionOptions) (Object) options).pvp$setSaveProperties(false);

        // 将竞技场维度注册进 DIMENSION 注册表（临时解冻）
        SimpleRegistry<DimensionOptions> dimRegistry = this.getDimensionsRegistry();
        boolean frozen = ((RemoveFromRegistry<?>) dimRegistry).pvp$isFrozen();
        ((RemoveFromRegistry<?>) dimRegistry).pvp$setFrozen(false);
        RegistryKey<DimensionOptions> dimKey = RegistryKey.of(RegistryKeys.DIMENSION, ARENA_WORLD_KEY.getValue());
        if (!dimRegistry.contains(dimKey)) {
            dimRegistry.add(dimKey, options, RegistryEntryInfo.DEFAULT);
        }
        ((RemoveFromRegistry<?>) dimRegistry).pvp$setFrozen(frozen);

        ArenaWorld arenaWorld = new ArenaWorld(this.server, ARENA_WORLD_KEY, generator);

        // 注入世界的 worlds 注册表并触发加载事件
        ((MinecraftServerAccess) this.server).getWorlds().put(ARENA_WORLD_KEY, arenaWorld);
        ServerWorldEvents.LOAD.invoker().onWorldLoad(this.server, arenaWorld);

        // 立刻 tick 一次以确保立即可用
        arenaWorld.tick(() -> true);

        this.configureGameRules(arenaWorld);

        LOGGER.info("[PvP] 竞技场世界已创建: {}", ARENA_WORLD_KEY.getValue());
        this.world = arenaWorld;
        return arenaWorld;
    }

    public ArenaWorld getWorld() {
        return this.world;
    }

    public void onServerStopping() {
        if (this.world != null) {
            var serverAccess = (MinecraftServerAccess) this.server;
            this.deleteDirectory(serverAccess.getSession().getWorldDirectory(ARENA_WORLD_KEY));
            this.world = null;
        }
    }

    /**
     * 搭建某场比赛的地形。
     *
     * @param seed        空岛战争的地图种子（比赛 ID），其他模式忽略
     * @param playerCount 空岛战争/战桥的玩家人数（决定出生岛数量/四方布局），其他模式忽略
     * @param type        对局模式：战桥用它决定双队/四方布局
     */
    public void buildArena(int regionIndex, ArenaTemplate template, int seed, int playerCount, MatchType type) {
        ArenaWorld arena = this.requireWorld();

        // 空岛战争：随机生成出生岛 + 中间主岛 + 箱子战利品
        if (template.getLayout() == ArenaTemplate.Layout.SKYWARS) {
            SkyWarsMapGenerator.generate(arena, regionIndex, seed, playerCount);
            return;
        }

        // 战桥：双基地或四方十字 + 中央桥
        if (template.getLayout() == ArenaTemplate.Layout.BRIDGE) {
            boolean fourTeam = type == MatchType.BRIDGE_1V1V1V1;
            BridgeLayout layout = BridgeLayout.compute(template.getCenter(regionIndex), playerCount, fourTeam);
            BridgeMapGenerator.generate(arena, layout);
            return;
        }

        BlockPos origin = template.getRegionOrigin(regionIndex);
        int size = template.getSize();

        // 地板
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                arena.setBlockState(origin.add(dx, 0, dz), template.getFloorBlock().getDefaultState(), 3);
            }
        }

        // 四周墙壁（相扑无墙，靠被击出平台判负）
        if (template.hasWalls()) {
            for (int h = 1; h <= ArenaTemplate.WALL_HEIGHT; h++) {
                for (int dx = 0; dx < size; dx++) {
                    arena.setBlockState(origin.add(dx, h, 0), template.getWallBlock().getDefaultState(), 3);
                    arena.setBlockState(origin.add(dx, h, size - 1), template.getWallBlock().getDefaultState(), 3);
                }
                for (int dz = 0; dz < size; dz++) {
                    arena.setBlockState(origin.add(0, h, dz), template.getWallBlock().getDefaultState(), 3);
                    arena.setBlockState(origin.add(size - 1, h, dz), template.getWallBlock().getDefaultState(), 3);
                }
            }
        }
    }

    /** 清理某场比赛的平台地形与区域内掉落物/实体。 */
    public void clearArena(int regionIndex, ArenaTemplate template, int mapMaxRadius) {
        ArenaWorld arena = this.world;
        if (arena == null) {
            return;
        }

        // 空岛战争：按该场实际最大半径清空（含立柱与小树），避免箱子/岛屿残留
        if (template.getLayout() == ArenaTemplate.Layout.SKYWARS) {
            SkyWarsMapGenerator.clearIslands(arena, regionIndex, mapMaxRadius);
            return;
        }

        // 战桥：按地图中心 ± 最大半径清空
        if (template.getLayout() == ArenaTemplate.Layout.BRIDGE) {
            BridgeLayout layout = BridgeLayout.compute(template.getCenter(regionIndex), 2, false);
            BridgeMapGenerator.clear(arena, layout);
            return;
        }

        BlockPos origin = template.getRegionOrigin(regionIndex);
        int size = template.getSize();

        // 先清方块再清掉落物：拆掉箱子等容器时内容物会重新掉落成实体
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                for (int dy = -1; dy <= ArenaTemplate.WALL_HEIGHT + 1; dy++) {
                    arena.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState(), 3);
                }
            }
        }

        // 再清空区域内掉落物
        Box box = new Box(
                origin.getX(), origin.getY() - 2, origin.getZ(),
                origin.getX() + size, origin.getY() + ArenaTemplate.WALL_HEIGHT + 2, origin.getZ() + size
        );
        for (ItemEntity entity : arena.getEntitiesByClass(ItemEntity.class, box, e -> true)) {
            entity.discard();
        }
    }

    private ArenaWorld requireWorld() {
        if (this.world == null) {
            throw new IllegalStateException("竞技场世界尚未创建");
        }
        return this.world;
    }

    private void configureGameRules(ArenaWorld arenaWorld) {
        GameRules rules = arenaWorld.getGameRules();
        MinecraftServer server = this.server;
        rules.get(GameRules.DO_MOB_SPAWNING).set(false, server);
        rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
        rules.get(GameRules.DO_WEATHER_CYCLE).set(false, server);
        rules.get(GameRules.DO_FIRE_TICK).set(false, server);
        rules.get(GameRules.NATURAL_REGENERATION).set(true, server); // 吃东西回血
        rules.get(GameRules.KEEP_INVENTORY).set(true, server);
        rules.get(GameRules.ANNOUNCE_ADVANCEMENTS).set(false, server);
        rules.get(GameRules.SHOW_DEATH_MESSAGES).set(false, server);
    }

    private RegistryEntry<DimensionType> dimTypeEntry() {
        return this.server.getRegistryManager()
                .get(RegistryKeys.DIMENSION_TYPE)
                .getEntry(DimensionTypes.OVERWORLD)
                .orElseThrow();
    }

    private SimpleRegistry<DimensionOptions> getDimensionsRegistry() {
        DynamicRegistryManager registryManager = this.server.getCombinedDynamicRegistries().getCombinedRegistryManager();
        return (SimpleRegistry<DimensionOptions>) registryManager.get(RegistryKeys.DIMENSION);
    }

    private void deleteDirectory(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOGGER.warn("[PvP] 无法删除文件 {}", p, e);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[PvP] 无法删除竞技场目录 {}", path, e);
        }
    }
}
