package com.example.pvp.arena;

import com.example.pvp.mixin.MinecraftServerAccess;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 运行时创建的竞技场世界：虚空地形，永不保存，每次服务器启动全新生成。
 *
 * <p>它还承担一个额外职责：<b>铺图暂存</b>。开赛前生成器调用 {@code setBlockState} 时，
 * 如果当前处于 {@link #beginStaging(MapBuildQueue) staging} 状态，写入会被转发到
 * {@link MapBuildQueue}，由 {@code Match} 在后续若干 tick 里按时间预算分帧落盘
 * （详见 {@link MapBuildQueue} 的类注释）。这样所有模式的生成器都不用改，
 * 却都能把一次性 ~1 秒的卡顿摊成几十个小块。
 *
 * <p>暂存期间：
 * <ul>
 *   <li>{@code setBlockState} → 只记进队列，返回 true（生成器感知不到差别）；</li>
 *   <li>{@code getBlockState} → 本区域内先看队列的叠加层，未暂存即空气
 *       （本区域开赛前一定被清空过；同时避免"为读一格而加载区块"）；</li>
 *   <li>{@code addBlockEntity} / {@code spawnEntity} → 推迟到方块全部落盘之后
 *       （它们要求所在区块已加载）。</li>
 * </ul>
 */
public class ArenaWorld extends ServerWorld {

    /** 当前正在暂存铺图的队列；null = 正常直写世界（对局中/清场时都是 null）。 */
    private MapBuildQueue staging;
    private Box dropSuppression;
    /** Reused per-thread state avoids an allocation for each imported terrain cell. */
    private final ThreadLocal<SnapshotWriteScope> snapshotWrites = ThreadLocal.withInitial(SnapshotWriteScope::new);

    private static final class SnapshotWriteScope {
        int depth;
        long position;
        BlockState state;
    }

    /** Cleanup must not turn container contents or neighbour updates into new loot. */
    public void runWithoutDrops(Box bounds, Runnable cleanup) {
        Box previous = this.dropSuppression;
        this.dropSuppression = bounds;
        try {
            cleanup.run();
        } finally {
            this.dropSuppression = previous;
        }
    }

    public ArenaWorld(MinecraftServer server, RegistryKey<World> worldKey, ChunkGenerator generator) {
        super(
                server,
                Util.getMainWorkerExecutor(),
                ((MinecraftServerAccess) server).getSession(),
                new ArenaWorldProperties(server.getSaveProperties()),
                worldKey,
                new DimensionOptions(dimTypeEntry(server), generator),
                VoidWorldProgressListener.INSTANCE,
                false,
                BiomeAccess.hashSeed(0L),
                List.of(),
                false,
                null
        );
    }

    private static RegistryEntry<DimensionType> dimTypeEntry(MinecraftServer server) {
        return server.getRegistryManager()
                .get(RegistryKeys.DIMENSION_TYPE)
                .getEntry(DimensionTypes.OVERWORLD)
                .orElseThrow();
    }

    // ==================== 铺图暂存 ====================

    /** 进入暂存状态：接下来的 setBlockState/addBlockEntity/spawnEntity 都会被推迟。 */
    public void beginStaging(MapBuildQueue queue) {
        if (this.staging != null && this.staging != queue) {
            throw new IllegalStateException("竞技场世界已经在暂存另一场铺图（同一 tick 里只能有一场在生成）");
        }
        this.staging = queue;
    }

    /** 退出暂存状态（必须在 flush 之前调用，否则分帧落盘会被自己再次暂存）。 */
    public void endStaging() {
        this.staging = null;
    }

    public MapBuildQueue stagingQueue() {
        return this.staging;
    }

    // ==================== 世界读写拦截 ====================

    /**
     * 暂存期间读取方块实体：方块还没真正写进世界，所以 vanilla 只会返回 null，
     * 而生成器普遍依赖"放完箱子立刻拿它的 block entity 塞战利品"
     * （空岛箱子变空就是这么来的）。这里按暂存状态现造一个并登记进队列，
     * 落盘时随方块一起写进世界，内容（战利品/库存）不会丢。
     */
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        MapBuildQueue queue = this.staging;
        if (queue != null && queue.inRegion(pos)) {
            BlockState state = queue.peek(pos);
            BlockEntity staged = queue.blockEntityAt(pos);
            // 模板标记可能已经替换为普通方块，不能继续返回该坐标旧的拼图/结构方块实体。
            if (staged != null && state != null && staged.getType().supports(state)) {
                return staged;
            }
            if (state != null && state.getBlock() instanceof BlockEntityProvider provider) {
                BlockEntity created = provider.createBlockEntity(pos, state);
                if (created != null) {
                    queue.deferBlockEntity(created);
                    return created;
                }
            }
            return null;
        }
        return super.getBlockEntity(pos);
    }

    @Override
    public boolean setBlockState(BlockPos pos, BlockState state, int flags, int maxUpdateDepth) {
        MapBuildQueue queue = this.staging;
        // 只暂存"本场铺图区域"内的写入。别的区域（例如上一局延后 5 秒的延迟清场，
        // 正好在这一帧把旧场地拆掉）必须直接落到世界里：否则那些空气写入会被塞进本场的
        // 队列里，轻则被推迟到本场铺完之后、重则在本场队列已 flush 之后才进来而彻底丢失，
        // 表现就是"新开一局，上一局的地图还留在那儿"。
        if (queue != null && queue.inRegion(pos)) {
            queue.stage(pos, state);
            return true;
        }
        this.loadPatternNeighbors(pos, state);
        return super.setBlockState(pos, state, flags, maxUpdateDepth);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        MapBuildQueue queue = this.staging;
        if (queue != null && queue.inRegion(pos)) {
            BlockState staged = queue.peek(pos);
            if (staged != null) {
                return staged;
            }
            // 本区域在开赛前一定被上一场的清场清空过（或本来就是虚空），所以"没暂存就是空气"。
            // 直接返回空气还有个好处：不会为了读一格就把区块加载起来。
            return Blocks.AIR.getDefaultState();
        }
        return super.getBlockState(pos);
    }

    @Override
    public boolean spawnEntity(Entity entity) {
        if (this.dropSuppression != null && this.dropSuppression.contains(entity.getPos())
                && (entity instanceof ItemEntity || entity instanceof ExperienceOrbEntity)) {
            entity.discard();
            return false;
        }
        MapBuildQueue queue = this.staging;
        if (queue != null) {
            queue.deferEntity(entity);
            return true;
        }
        return super.spawnEntity(entity);
    }

    @Override
    public void addBlockEntity(BlockEntity blockEntity) {
        MapBuildQueue queue = this.staging;
        if (queue != null) {
            queue.deferBlockEntity(blockEntity);
            return;
        }
        super.addBlockEntity(blockEntity);
    }

    // ==================== 绕过暂存的直写通道（MapBuildQueue.flush 专用） ====================

    /** 直写世界，不经过暂存层。 */
    public boolean setBlockStateDirect(BlockPos pos, BlockState state) {
        this.loadPatternNeighbors(pos, state);
        return super.setBlockState(pos, state, 3, 512);
    }

    /**
     * Import an already-saved static scene without replaying placement/removal
     * effects. Replaying those callbacks makes suspended gravel fall, rewires
     * neighbours and can spawn golems while a large snapshot is only half built.
     *
     * Native World/WorldChunk writes still maintain section counts, all four
     * heightmaps, sky/block lighting, block entities and client listeners. Only
     * this exact write's onStateReplaced/onBlockAdded callbacks are suppressed
     * by PartySnapshotImportMixin; ordinary game writes keep their normal flags.
     */
    public boolean setBlockStateSnapshotDirect(BlockPos pos, BlockState state) {
        SnapshotWriteScope scope = this.snapshotWrites.get();
        int previousDepth = scope.depth;
        long previousPosition = scope.position;
        BlockState previousState = scope.state;
        scope.depth = previousDepth + 1;
        scope.position = pos.asLong();
        scope.state = state;
        try {
            return super.setBlockState(pos, state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE, 512);
        } finally {
            scope.depth = previousDepth;
            scope.position = previousPosition;
            scope.state = previousState;
        }
    }

    /** The same server thread may perform unrelated writes while notifying listeners. */
    public boolean isSnapshotImporting(BlockPos pos, BlockState state) {
        SnapshotWriteScope scope = this.snapshotWrites.get();
        return scope.depth > 0 && scope.position == pos.asLong() && scope.state == state;
    }

    /**
     * 原图雕刻南瓜会触发原生傀儡模式搜索；分帧铺图时相邻区块可能尚未加载，
     * vanilla '~' 判定直接读取其null状态会崩服。只在实际放置这两种方块的同一tick
     * 加载搜索所需的邻区块，保留原方块、生成判定及物理，不挂永久强制加载票。
     */
    private void loadPatternNeighbors(BlockPos pos, BlockState state) {
        if (!state.isOf(Blocks.CARVED_PUMPKIN) && !state.isOf(Blocks.JACK_O_LANTERN)) return;
        for (int x = Math.floorDiv(pos.getX() - 4, 16); x <= Math.floorDiv(pos.getX() + 4, 16); x++) {
            for (int z = Math.floorDiv(pos.getZ() - 4, 16); z <= Math.floorDiv(pos.getZ() + 4, 16); z++) this.getChunk(x, z);
        }
    }

    /** 直接生成实体，不经过暂存层。 */
    public boolean spawnEntityDirect(Entity entity) {
        return super.spawnEntity(entity);
    }

    /** 直接挂方块实体，不经过暂存层。 */
    public void addBlockEntityDirect(BlockEntity blockEntity) {
        super.addBlockEntity(blockEntity);
    }

    @Override
    public void save(@Nullable ProgressListener progressListener, boolean flush, boolean enabled) {
        // 竞技场世界从不写入磁盘
    }

    @Override
    public boolean isFlat() {
        return true;
    }
}
