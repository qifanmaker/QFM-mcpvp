package com.example.pvp.arena.race;

import com.example.pvp.arena.ArenaWorld;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 「亦可赛艇」赛道地表的统一覆写层：所有"把船脚下的冰面换成另一种方块"的效果都走这里。
 *
 * <p><b>为什么必须统一</b>：船的极速完全由脚下方块的滑度决定（{@code v = 0.04 / (1 − 滑度)}），
 * 所以加速（氮气 → 蓝冰）和减速（速冻胶 → 雪块）都是"换脚下方块"。如果两者各自维护一份
 * "原方块 + 还原表"，它们作用在同一格时会互相把对方的覆写当成"原方块"：
 * 氮气结束把蓝冰还原成浮冰，就把盖在上面的速冻胶一起抹掉了（反过来同理）。
 * 这里让每格只记录<b>一次</b>原方块，加上"各效果是否生效"的标记，最终方块由
 * {@link #refresh} 按优先级仲裁，全部失效才还原。
 *
 * <p><b>优先级：减速带 &gt; 加速带</b>。同一格上两种效果同时存在时以"更慢的那个"为准 ——
 * 否则两种效果会随着各自的窗口/计时来回翻方块，既刷方块更新又让玩家看到闪烁。
 *
 * <p><b>只接管赛道自己的表层方块</b>：栅格化时只有"当前位置确实是赛道冰面"才建立单元格，
 * 雪地缓冲带、护栏立柱（蓝冰）、门架、空气一律不碰 —— 这一点与原来的氮气实现一致。
 */
final class SurfaceOverlay {
    /** 方块更新标记（通知客户端 + 邻居）：客户端必须收到，否则滑度变化不会生效。 */
    private static final int SET_FLAGS = 3;

    /** 一格的覆写状态。{@code applied} 是我们最后一次写下去的状态，用来避免每 tick 重复写方块。 */
    private static final class Cell {
        /** 接管前那一格的方块，收尾时必须原样还回去。 */
        final BlockState original;
        /** 我们最后一次写下去的状态（初始 = 接管时的状态，表示"还没动过"）。 */
        BlockState applied;
        boolean boost;
        boolean trap;
        /** 带护盾的玩家正压在这一格上：暂时还原成原方块（优先级最高）。 */
        boolean guard;
        /** 缓冲区雪块被"结冰"援助冻成冰面（落后的玩家更容易触发）。 */
        boolean grip;

        Cell(BlockState original) {
            this.original = original;
            this.applied = original;
        }
    }

    private final Block surfaceBlock;
    private final Block runoffBlock;
    private final Block boostBlock;
    private final Block trapBlock;

    /** 格子 → 覆写状态（只有被接管过的格子才在里面）。 */
    private final Map<Long, Cell> cells = new LinkedHashMap<>();
    /** 格子 → 减速带失效的 tick。 */
    private final Map<Long, Long> trapUntil = new HashMap<>();
    /** "缓冲区结冰"冻成什么方块（默认就是赛道冰面）。 */
    private final Block gripBlock;

    SurfaceOverlay(Block surfaceBlock, Block runoffBlock, Block boostBlock, Block trapBlock,
                   Block gripBlock) {
        this.surfaceBlock = surfaceBlock;
        this.runoffBlock = runoffBlock;
        this.boostBlock = boostBlock;
        this.trapBlock = trapBlock;
        // 结冰只让雪块回到"和赛道一样滑"，所以默认就用赛道地表方块
        this.gripBlock = gripBlock;
    }

    /** 记录一批减速带格子，{@code untilTick} 之前一直生效（同一格取最晚的失效时间）。 */
    void addTrap(Set<Long> positions, long untilTick) {
        for (Long key : positions) {
            this.trapUntil.merge(key, untilTick, Math::max);
        }
    }

    int trapCellCount() {
        return this.trapUntil.size();
    }

    int cellCount() {
        return this.cells.size();
    }

    int boostCellCount() {
        int count = 0;
        for (Cell cell : this.cells.values()) {
            if (cell.boost) {
                count++;
            }
        }
        return count;
    }

    /** 这一格此刻是否有生效中的减速带（外面判定"带护盾压上去"用）。 */
    boolean isActiveTrap(long cell, long nowTick) {
        Long until = this.trapUntil.get(cell);
        return until != null && until > nowTick;
    }

    /** 当前被"缓冲区结冰"覆盖的格子数。 */
    int gripCellCount() {
        int count = 0;
        for (Cell cell : this.cells.values()) {
            if (cell.grip) {
                count++;
            }
        }
        return count;
    }

    /** 只因为"护盾窗口"被接管、当前状态仍是原方块的格子数（自检用）。 */
    int guardCellCount() {
        int count = 0;
        for (Cell cell : this.cells.values()) {
            if (cell.guard) {
                count++;
            }
        }
        return count;
    }

    boolean isEmpty() {
        return this.cells.isEmpty() && this.trapUntil.isEmpty();
    }

    /**
     * 应用本 tick 的目标状态。
     *
     * @param boostUnion 本 tick 所有加速窗口的并集（外面按玩家 UUID 维护窗口，这里只认并集）
     * @param gripUnion  本 tick 所有被"缓冲区结冰"援助覆盖的格子：雪块临时冻成冰面，
     *                   让冲进缓冲带的船不至于一下子掉到 2 格/秒（落后的人更容易触发）。
     * @param guardUnion 本 tick 所有"带护盾的船正压着、且要免疫减速带"的格子：
     *                   这些格就算有减速带也要还原成原方块，否则鱼鳞护盾的"免疫速冻胶"就是假的。
     *                   它只在玩家压着雪带的那几 tick 生效，不会在雪带上留下能被人利用的缺口。
     * @param nowTick    服务器 tick，用来判定减速带是否过期
     * @return 实际改动的方块数（用于日志/自检，判断"有没有真的生效"）
     */
    int refresh(ArenaWorld arena, Set<Long> boostUnion, Set<Long> gripUnion,
                Set<Long> guardUnion, long nowTick) {
        if (arena == null) {
            return 0;
        }
        if (!this.trapUntil.isEmpty()) {
            this.trapUntil.entrySet().removeIf(entry -> entry.getValue() <= nowTick);
        }
        Set<Long> needed = new HashSet<>(boostUnion);
        needed.addAll(gripUnion);
        needed.addAll(guardUnion);
        needed.addAll(this.trapUntil.keySet());

        int changed = 0;
        // 1) 需要覆写的格子：没接管过的先接管（只认赛道表层方块）
        for (Long key : needed) {
            Cell cell = this.cells.get(key);
            if (cell == null) {
                BlockState current = arena.getBlockState(BlockPos.fromLong(key));
                // 只接管"赛道地表"这两类：冰面（赛道本体）与雪块（两侧缓冲带）。
                // 护栏、门架、空气一律不碰 —— 所以结冰援助不会把护栏也变成冰。
                if (!current.isOf(this.surfaceBlock) && !current.isOf(this.runoffBlock)) {
                    continue;
                }
                cell = new Cell(current);
                this.cells.put(key, cell);
            }
            boolean boost = boostUnion.contains(key);
            boolean trap = this.trapUntil.containsKey(key);
            boolean guard = guardUnion.contains(key);
            boolean grip = gripUnion.contains(key);
            // 优先级：护盾 > 减速带 > 氮气加速 > 缓冲区结冰 > 原方块
            // （攻击类压过援助类；氮气比结冰快，重叠时取氮气）
            BlockState desired = guard ? cell.original
                    : trap ? this.trapBlock.getDefaultState()
                    : boost ? this.boostBlock.getDefaultState()
                    : grip ? this.gripBlock.getDefaultState()
                    : cell.original;
            if (cell.applied != desired) {
                arena.setBlockState(BlockPos.fromLong(key), desired, SET_FLAGS);
                cell.applied = desired;
                changed++;
            }
            cell.boost = boost;
            cell.trap = trap;
            cell.guard = guard;
            cell.grip = grip;
        }
        // 2) 已经不需要覆写的格子：还原 + 从表里删掉
        if (!this.cells.isEmpty()) {
            Iterator<Map.Entry<Long, Cell>> iterator = this.cells.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Cell> entry = iterator.next();
                if (needed.contains(entry.getKey())) {
                    continue;
                }
                Cell cell = entry.getValue();
                if (cell.applied != cell.original) {
                    arena.setBlockState(BlockPos.fromLong(entry.getKey()), cell.original, SET_FLAGS);
                    changed++;
                }
                iterator.remove();
            }
        }
        return changed;
    }

    /**
     * 立刻还原全部覆写并清空状态（比赛结束 / 清场前必须调用，且必须在
     * {@code RaceMapGenerator.clear} <b>之前</b>——否则精确清场把赛道变成空气后，
     * 这里又会把原方块写回去，留下一条多余的冰带）。
     */
    int restoreAll(ArenaWorld arena) {
        int restored = 0;
        if (arena != null) {
            for (Map.Entry<Long, Cell> entry : this.cells.entrySet()) {
                Cell cell = entry.getValue();
                if (cell.applied != cell.original) {
                    arena.setBlockState(BlockPos.fromLong(entry.getKey()), cell.original, SET_FLAGS);
                    restored++;
                }
            }
        }
        this.cells.clear();
        this.trapUntil.clear();
        return restored;
    }
}
