package com.example.pvp.arena;

import com.example.pvp.mixin.MinecraftServerAccess;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
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
        return super.setBlockState(pos, state, 3, 512);
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
