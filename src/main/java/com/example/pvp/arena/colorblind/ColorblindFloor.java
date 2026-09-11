package com.example.pvp.arena.colorblind;

import com.example.pvp.arena.ArenaWorld;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.Random;

/**
 * 色盲派对的彩色地板：一块 {@code size × size} 的单层平台，每格记一个
 * {@link ColorblindPalette} 色号（-1 = 该格本回合已消失）。
 *
 * <p>回合流程：{@link #fill} 重新摇色 → {@link #placeAll} 铺回 → 倒计时结束
 * {@link #vanishExcept} 抹掉非目标色 → 站错的人掉虚空。
 * Hyper 事件/加成会通过 {@link #repaint}、{@link #rotateStrips} 局部改色。
 */
public final class ColorblindFloor {

    private final BlockPos origin;
    private final int size;
    /** 下标 dx * size + dz；-1 表示空气。 */
    private final int[] colors;

    public ColorblindFloor(BlockPos origin, int size) {
        this.origin = origin;
        this.size = size;
        this.colors = new int[size * size];
        java.util.Arrays.fill(this.colors, -1);
    }

    public int size() {
        return this.size;
    }

    public BlockPos origin() {
        return this.origin;
    }

    /** 地板中心（y 为地板层）。 */
    public BlockPos center() {
        return this.origin.add(this.size / 2, 0, this.size / 2);
    }

    public boolean inBounds(int dx, int dz) {
        return dx >= 0 && dx < this.size && dz >= 0 && dz < this.size;
    }

    public int colorAt(int dx, int dz) {
        return this.inBounds(dx, dz) ? this.colors[dx * this.size + dz] : -1;
    }

    private void setColor(int dx, int dz, int color) {
        if (this.inBounds(dx, dz)) {
            this.colors[dx * this.size + dz] = color;
        }
    }

    /** 把 (worldX, worldZ) 换算成格子下标；不在范围内返回 false。 */
    public boolean toLocal(int worldX, int worldZ, int[] out) {
        int dx = worldX - this.origin.getX();
        int dz = worldZ - this.origin.getZ();
        if (!this.inBounds(dx, dz)) {
            return false;
        }
        out[0] = dx;
        out[1] = dz;
        return true;
    }

    /** 该格当前的方块是不是指定色号（用于判定玩家脚下站得对不对）。 */
    public boolean isColorAt(int dx, int dz, int color) {
        return this.colorAt(dx, dz) == color;
    }

    /** 直接指定某格的颜色（写进世界由 {@link #placeAll} 统一做）。 */
    public void set(int dx, int dz, int color) {
        this.setColor(dx, dz, color);
    }

    /** 按调色板随机重摇整块地板（{@code activeColors} 为本回合上场的色号）。 */
    public void fill(Random random, int[] activeColors) {
        for (int dx = 0; dx < this.size; dx++) {
            for (int dz = 0; dz < this.size; dz++) {
                this.setColor(dx, dz, activeColors[random.nextInt(activeColors.length)]);
            }
        }
    }

    /** 把整块地板按当前色号写进世界。 */
    public void placeAll(ArenaWorld world) {
        for (int dx = 0; dx < this.size; dx++) {
            for (int dz = 0; dz < this.size; dz++) {
                int color = this.colorAt(dx, dz);
                if (color >= 0) {
                    world.setBlockState(this.origin.add(dx, 0, dz), state(color), 3);
                }
            }
        }
    }

    /** 非 {@code keepColor} 的格子全部抹成空气（倒计时结束的那一刻）。返回抹掉的格数。 */
    public int vanishExcept(ArenaWorld world, int keepColor) {
        int removed = 0;
        for (int dx = 0; dx < this.size; dx++) {
            for (int dz = 0; dz < this.size; dz++) {
                if (this.colorAt(dx, dz) == keepColor) {
                    continue;
                }
                this.setColor(dx, dz, -1);
                world.setBlockState(this.origin.add(dx, 0, dz), Blocks.AIR.getDefaultState(), 3);
                removed++;
            }
        }
        return removed;
    }

    /** 把以 (cx,cz) 为中心、半径 radius 的圆形区域重涂成 {@code color}（含已消失的格子）。 */
    public int repaint(ArenaWorld world, int cx, int cz, int radius, int color) {
        int changed = 0;
        int r2 = radius * radius;
        for (int dx = cx - radius; dx <= cx + radius; dx++) {
            for (int dz = cz - radius; dz <= cz + radius; dz++) {
                if (!this.inBounds(dx, dz)) {
                    continue;
                }
                int ddx = dx - cx;
                int ddz = dz - cz;
                if (ddx * ddx + ddz * ddz > r2) {
                    continue;
                }
                if (this.colorAt(dx, dz) == color) {
                    continue;
                }
                this.setColor(dx, dz, color);
                world.setBlockState(this.origin.add(dx, 0, dz), state(color), 3);
                changed++;
            }
        }
        return changed;
    }

    /** 当前 {@code color} 在地板上还剩多少格（用于确认目标色真的存在）。 */
    public int countOf(int color) {
        int n = 0;
        for (int c : this.colors) {
            if (c == color) {
                n++;
            }
        }
        return n;
    }

    /**
     * "滚动色块"：按 {@code stripWidth} 格宽把地板切成条带，相邻条带沿 Z 轴反向滚动一格。
     * 只写变化的格子。返回改动的格数。
     */
    public int rotateStrips(ArenaWorld world, int stripWidth) {
        if (stripWidth < 1) {
            stripWidth = 1;
        }
        int changed = 0;
        for (int dx = 0; dx < this.size; dx++) {
            boolean forward = ((dx / stripWidth) & 1) == 0;
            int[] column = new int[this.size];
            for (int dz = 0; dz < this.size; dz++) {
                column[dz] = this.colorAt(dx, dz);
            }
            for (int dz = 0; dz < this.size; dz++) {
                int src = forward ? (dz - 1 + this.size) % this.size : (dz + 1) % this.size;
                int next = column[src];
                if (next < 0 || next == column[dz]) {
                    continue; // 已消失的格子不复活，颜色没变也不用写
                }
                this.setColor(dx, dz, next);
                world.setBlockState(this.origin.add(dx, 0, dz), state(next), 3);
                changed++;
            }
        }
        return changed;
    }

    public static net.minecraft.block.BlockState state(int colorIndex) {
        return ColorblindPalette.get(colorIndex).block().getDefaultState();
    }
}
