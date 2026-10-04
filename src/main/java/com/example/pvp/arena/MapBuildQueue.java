package com.example.pvp.arena;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 铺图的「暂存 + 分帧落盘」队列。
 *
 * <p><b>解决的问题</b>：所有模式的竞技场地形原本都在开赛倒计时的**第一帧**一次性写进世界
 * （床战整张地图粘贴、空岛生成、竞速赛道栅格化…），一帧里几十万次 {@code setBlockState}
 * 加上区块加载，在服务端主线程上就是一次 ~1 秒的卡顿尖峰 —— 同服其他人的 TPS 会跟着掉。
 *
 * <p><b>做法</b>：生成器完全不用改。{@link ArenaWorld} 在 {@code staging} 期间把
 * {@code setBlockState / addBlockEntity / spawnEntity} 全部转发到本队列，只做数组追加
 * （纯内存操作，比真正写世界快一个数量级）；之后每个服务器 tick 由
 * {@link #flush(int)} 在固定时间预算内把方块真正写进世界。这样：
 * <ul>
 *   <li>单帧耗时从"整张地图"降到"预算内的几百~几千块"，不会有 TPS 尖峰；</li>
 *   <li>方块<b>严格按生成器调用顺序</b>落盘，与"直接写"的最终结果逐位一致；</li>
 *   <li>实体与方块实体统一推迟到方块全部落盘之后（它们要求所在区块已加载）；</li>
 *   <li>没有任何模式需要改写自己的生成器。</li>
 * </ul>
 *
 * <p><b>回读怎么办</b>：空岛/起床战争/竞速的生成器会一边写一边读
 * （例如"种树前先确认这格还是空的"）。所以本队列提供一个<b>懒建</b>的读取叠加层：
 * 第一次发生回读时才建立并回填，之后写入同步进叠加层。
 * 完全不回读的生成器（多数模式）一笔内存都不额外付。
 *
 * <p>另外，{@link ArenaWorld#getBlockState} 在暂存期间对本区域内的格子直接返回叠加层或空气 ——
 * 既保证语义正确（本区域开赛前一定被清空过），又避免了"为了读一格而加载区块"。
 */
public final class MapBuildQueue {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 每写这么多块才查一次时钟（避免 System.nanoTime 在大循环里占比过高）。 */
    private static final int TIME_CHECK_BATCH = 256;
    private static final int INITIAL_CAPACITY = 1 << 13;

    private final ArenaWorld world;
    private final int centerX;
    private final int centerZ;
    private final int halfExtent;

    private long[] positions = new long[INITIAL_CAPACITY];
    private BlockState[] states = new BlockState[INITIAL_CAPACITY];
    private int size;
    private int cursor;

    /** 懒建的读取叠加层（只有生成器真的回读时才存在）。 */
    private Long2ObjectOpenHashMap<BlockState> overlay;

    private final List<BlockEntity> blockEntities = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();
    private int blockEntityCursor;
    private int entityCursor;

    private boolean closed;
    private boolean storageReleased;
    private int placed;
    private long lastFlushNanos;

    /**
     * @param world      竞技场世界
     * @param center     本场区域中心（用于"区域内未暂存即空气"的读判定）
     * @param halfExtent 区域半宽（必须 &lt; REGION_SPACING/2，否则会误判到相邻竞技场的方块）
     */
    public MapBuildQueue(ArenaWorld world, BlockPos center, int halfExtent) {
        this.world = world;
        this.centerX = center.getX();
        this.centerZ = center.getZ();
        this.halfExtent = halfExtent;
    }

    // ==================== 生成阶段（staging） ====================

    /** 暂存一次方块写入。 */
    public void stage(BlockPos pos, BlockState state) {
        if (this.size == this.positions.length) {
            int next = this.size << 1;
            long[] np = new long[next];
            BlockState[] ns = new BlockState[next];
            System.arraycopy(this.positions, 0, np, 0, this.size);
            System.arraycopy(this.states, 0, ns, 0, this.size);
            this.positions = np;
            this.states = ns;
        }
        long packed = pos.asLong();
        this.positions[this.size] = packed;
        this.states[this.size] = state;
        this.size++;
        if (this.overlay != null) {
            this.overlay.put(packed, state);
        }
    }

    /**
     * 回读暂存内容。第一次调用会建立叠加层并回填已暂存的写入 ——
     * 这是"写后回读"语义正确的关键（如空岛"这格还是空的吗"、起床战争按方块状态重建方块实体）。
     *
     * @return 暂存里的最新状态；该位置从未被暂存过则返回 null（调用方应回退到空气/真实世界）
     */
    public BlockState peek(BlockPos pos) {
        if (this.overlay == null) {
            this.overlay = new Long2ObjectOpenHashMap<>(Math.max(16, this.size * 2));
            for (int i = 0; i < this.size; i++) {
                // 同一格被写多次时后面的覆盖前面的 —— 与"直接写世界"的最终结果一致
                this.overlay.put(this.positions[i], this.states[i]);
            }
        }
        return this.overlay.get(pos.asLong());
    }

    /** 该位置是否落在本场区域内（区域外不做"未暂存即空气"的推断）。 */
    public boolean inRegion(BlockPos pos) {
        return Math.abs(pos.getX() - this.centerX) <= this.halfExtent
                && Math.abs(pos.getZ() - this.centerZ) <= this.halfExtent;
    }

    /**
     * 暂存期间"取用"方块实体：生成器常见的写法是
     * {@code setBlockState(箱子) -> getBlockEntity(pos) -> 塞战利品}，
     * 但暂存时方块还没进世界，vanilla 那边根本没有 block entity。
     * 所以这里按暂存状态现造一个并登记，落盘时再真正写进世界。
     */
    public BlockEntity blockEntityAt(BlockPos pos) {
        for (BlockEntity blockEntity : this.blockEntities) {
            if (blockEntity.getPos().equals(pos)) {
                return blockEntity;
            }
        }
        return null;
    }

    public void deferBlockEntity(BlockEntity blockEntity) {
        // 同一坐标只保留最后登记的那一个：上面"现造"和生成器显式的 addBlockEntity
        // 可能都登记一次，去重避免同一格落盘两遍、也保证后填的内容不被旧实例覆盖。
        for (int i = this.blockEntities.size() - 1; i >= 0; i--) {
            if (this.blockEntities.get(i).getPos().equals(blockEntity.getPos())) {
                this.blockEntities.set(i, blockEntity);
                return;
            }
        }
        this.blockEntities.add(blockEntity);
    }

    public void deferEntity(Entity entity) {
        this.entities.add(entity);
    }

    /** 生成阶段结束：此后才可以 flush（保证"落盘顺序 == 生成顺序"）。 */
    public void close() {
        this.closed = true;
        // 生成阶段结束后不再需要回读叠加层
        this.overlay = null;
    }

    public int totalStaged() {
        return this.size;
    }

    /** 已落盘方块数（进度显示用）。 */
    public int placedCount() {
        return this.placed;
    }

    /** 铺图进度 0..1（方块 + 实体 + 方块实体）。 */
    public double progress() {
        int total = this.size + this.blockEntities.size() + this.entities.size();
        if (total == 0) {
            return 1.0;
        }
        int done = this.cursor + this.blockEntityCursor + this.entityCursor;
        return Math.min(1.0, done / (double) total);
    }

    // ==================== 分帧落盘 ====================

    /**
     * 用不超过 {@code budgetMillis} 毫秒的时间预算推进落盘。
     *
     * @return 是否已经全部落盘完成
     */
    public boolean flush(int budgetMillis) {
        if (!this.closed) {
            throw new IllegalStateException("MapBuildQueue 必须先 close() 再 flush()");
        }
        long started = System.nanoTime();
        if (this.cursor < this.size) {
            long deadline = started + budgetMillis * 1_000_000L;
            int batch = 0;
            while (this.cursor < this.size) {
                this.world.setBlockStateDirect(
                        BlockPos.fromLong(this.positions[this.cursor]), this.states[this.cursor]);
                this.cursor++;
                this.placed++;
                if (++batch >= TIME_CHECK_BATCH && System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (this.cursor < this.size) {
                this.lastFlushNanos = System.nanoTime() - started;
                return false;
            }
            releaseStorage();
        }
        // 方块实体必须先于实体（它们属于方块；而且方块实体要求区块已加载）
        if (this.blockEntityCursor < this.blockEntities.size()) {
            long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
            int batch = 0;
            while (this.blockEntityCursor < this.blockEntities.size()) {
                this.world.addBlockEntityDirect(this.blockEntities.get(this.blockEntityCursor++));
                if (++batch >= TIME_CHECK_BATCH && System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (this.blockEntityCursor < this.blockEntities.size()) {
                this.lastFlushNanos = System.nanoTime() - started;
                return false;
            }
            this.blockEntities.clear();
        }
        if (this.entityCursor < this.entities.size()) {
            long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
            while (this.entityCursor < this.entities.size()) {
                Entity entity = this.entities.get(this.entityCursor++);
                if (!this.world.spawnEntityDirect(entity)) {
                    LOGGER.warn("[PvP] 铺图时实体生成失败（{}，位置 {}）",
                            entity.getType(), entity.getBlockPos());
                }
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (this.entityCursor < this.entities.size()) {
                this.lastFlushNanos = System.nanoTime() - started;
                return false;
            }
            this.entities.clear();
        }
        this.lastFlushNanos = System.nanoTime() - started;
        return true;
    }

    /**
     * 兜底：无视时间预算一次性写完。
     * 只在"铺图拖得太久、再拖就要影响开赛"时由 Match 调用 —— 宁可卡一下也不能卡死。
     */
    public void forceFlushAll() {
        long started = System.nanoTime();
        this.flush(Integer.MAX_VALUE / 2);
        LOGGER.warn("[PvP] 铺图超时兜底：一次性写入 {} 个方块（{} ms）",
                this.placed, (System.nanoTime() - started) / 1_000_000L);
    }

    /** 上一次 flush 实际花掉的毫秒数（性能观测用）。 */
    public double lastFlushMillis() {
        return this.lastFlushNanos / 1.0e6;
    }

    private void releaseStorage() {
        if (this.storageReleased) {
            return;
        }
        this.storageReleased = true;
        this.positions = null;
        this.states = null;
    }
}
