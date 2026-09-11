package com.example.pvp.arena.colorblind;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * 色盲派对调色板：16 种染料色 ↔ 混凝土方块 ↔ 中文颜色名 ↔ 标题 RGB。
 *
 * <p>标题里写的是<b>颜色名</b>，但整段文字被渲染成<b>另一种颜色</b>；玩家要找的是
 * "文字被渲染成什么颜色"，而不是"文字写的是什么颜色"（Stroop 效应）。
 * 所以每个颜色既要能当地板方块（{@link #block()}），又要能当文字渲染色（{@link #rgb()}）。
 *
 * <p>不用 {@code Formatting} 的 §色码：16 个 §码和 16 个染料色对不上（品红与粉红都只能给 §d，
 * 淡蓝/青也没有独立码），会导致"文字渲染色 → 地板方块"无法唯一对应。这里改用
 * {@code TextColor.fromRgb} 精确上色。
 */
public final class ColorblindPalette {

    /**
     * @param block      地板方块（混凝土）
     * @param name       中文颜色名（标题的文字内容）
     * @param rgb        标题文字渲染色
     * @param answerable 能否作为"正确答案"。纯黑文字在标题上看不见，只能当地板干扰色与文字内容。
     */
    public record Entry(Block block, String name, int rgb, boolean answerable) {
    }

    /** 顺序固定；{@link ColorblindFloor} 用下标存色号。 */
    private static final Entry[] ENTRIES = {
            new Entry(Blocks.WHITE_CONCRETE, "白色", 0xF9FFFE, true),
            new Entry(Blocks.ORANGE_CONCRETE, "橙色", 0xF9801D, true),
            new Entry(Blocks.MAGENTA_CONCRETE, "品红色", 0xC74EBD, true),
            new Entry(Blocks.LIGHT_BLUE_CONCRETE, "淡蓝色", 0x3AB3DA, true),
            new Entry(Blocks.YELLOW_CONCRETE, "黄色", 0xFED83D, true),
            new Entry(Blocks.LIME_CONCRETE, "黄绿色", 0x80C71F, true),
            new Entry(Blocks.PINK_CONCRETE, "粉红色", 0xF38BAA, true),
            new Entry(Blocks.GRAY_CONCRETE, "灰色", 0x474F52, true),
            new Entry(Blocks.LIGHT_GRAY_CONCRETE, "淡灰色", 0x9D9D97, true),
            new Entry(Blocks.CYAN_CONCRETE, "青色", 0x169C9C, true),
            new Entry(Blocks.PURPLE_CONCRETE, "紫色", 0x8932B8, true),
            new Entry(Blocks.BLUE_CONCRETE, "蓝色", 0x3C44AA, true),
            new Entry(Blocks.BROWN_CONCRETE, "棕色", 0x835432, true),
            new Entry(Blocks.GREEN_CONCRETE, "绿色", 0x5E7C16, true),
            new Entry(Blocks.RED_CONCRETE, "红色", 0xB02E26, true),
            new Entry(Blocks.BLACK_CONCRETE, "黑色", 0x1D1D21, false),
    };

    /** 可作为正确答案的下标（去掉纯黑）。 */
    private static final int[] ANSWERABLE;

    static {
        List<Integer> ok = new ArrayList<>();
        for (int i = 0; i < ENTRIES.length; i++) {
            if (ENTRIES[i].answerable()) {
                ok.add(i);
            }
        }
        ANSWERABLE = new int[ok.size()];
        for (int i = 0; i < ok.size(); i++) {
            ANSWERABLE[i] = ok.get(i);
        }
    }

    private ColorblindPalette() {
    }

    public static int size() {
        return ENTRIES.length;
    }

    public static Entry get(int index) {
        return ENTRIES[index];
    }

    public static int[] answerableIndices() {
        return ANSWERABLE.clone();
    }

    /** 由方块反查色号；不是调色板方块返回 -1。 */
    public static int indexOf(Block block) {
        for (int i = 0; i < ENTRIES.length; i++) {
            if (ENTRIES[i].block() == block) {
                return i;
            }
        }
        return -1;
    }
}
