package com.example.pvp.arena.skywars;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

/**
 * 空岛战争地图主题：由比赛种子确定性抽取，决定岛屿材质、中岛结构、装饰与地面危害。
 */
public enum SkyWarsTheme {
    /** 主世界：草方块/泥土/石头，小橡树。 */
    OVERWORLD("主世界"),
    /** 地狱：地狱岩，岛面随机刷灵魂沙与岩浆，中间主岛正中是岩浆池。 */
    NETHER("地狱"),
    /** 冰原：第一层冰、下面雪，云杉树，中间主岛正中是水池。 */
    ICE("冰原"),
    /** 末地：末地石构成，中间主岛为空心环（中间是虚空）。 */
    END("末地");

    private final String displayName;

    SkyWarsTheme(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    /** 由比赛种子确定性抽取主题（与生成器用同一 seed，保证一致）。 */
    public static SkyWarsTheme pick(int seed) {
        SkyWarsTheme[] values = values();
        return values[Math.floorMod(seed, values.length)];
    }

    /** 返回一个「pick 结果等于指定主题」的种子：只改主题对应的低位，地图布局仍随原种子变化。 */
    public static int alignSeed(int seed, SkyWarsTheme theme) {
        SkyWarsTheme[] values = values();
        return seed - Math.floorMod(seed, values.length) + theme.ordinal();
    }

    /** 按名称查找主题（中文名或英文枚举名），找不到返回 null。 */
    public static SkyWarsTheme byName(String name) {
        if (name == null) {
            return null;
        }
        for (SkyWarsTheme theme : values()) {
            if (theme.name().equalsIgnoreCase(name) || theme.displayName.equals(name)) {
                return theme;
            }
        }
        return null;
    }

    /** 岛面表层方块（冰原第一层为冰）。 */
    public Block topBlock() {
        return switch (this) {
            case OVERWORLD -> Blocks.GRASS_BLOCK;
            case NETHER -> Blocks.NETHERRACK;
            case ICE -> Blocks.PACKED_ICE;
            case END -> Blocks.END_STONE;
        };
    }

    /** 表层下方第 1 层（冰原为雪）。 */
    public Block subBlock() {
        return switch (this) {
            case OVERWORLD -> Blocks.DIRT;
            case NETHER -> Blocks.NETHERRACK;
            case ICE -> Blocks.SNOW_BLOCK;
            case END -> Blocks.END_STONE;
        };
    }

    /** 更深的 2 层（冰原为雪）。 */
    public Block deepBlock() {
        return switch (this) {
            case OVERWORLD -> Blocks.STONE;
            case NETHER -> Blocks.NETHERRACK;
            case ICE -> Blocks.SNOW_BLOCK;
            case END -> Blocks.END_STONE;
        };
    }

    /**
     * 中央主岛正中是否有一个「核心」（末地空心虚空 / 冰原水 / 地狱岩浆）。
     * 核心内部的方块铺法与普通岛面不同，且救回点要避开它。
     */
    public boolean hasMiddleCore() {
        return this == END || this == ICE || this == NETHER;
    }

    /** 中岛核心的半径比例（0~1，乘以中岛半径；无核心主题为 0）。 */
    public double middleCoreRatio() {
        return this.hasMiddleCore() ? 0.4 : 0.0;
    }

    /**
     * 中岛核心里的填充物，{@code null} 表示空心（末地：正中直接挖穿，下方是虚空）。
     * 非空心的核心只替换表层，下两层照常铺 {@link #subBlock()}/{@link #deepBlock()}，
     * 这样水池/岩浆池底下是实心地面，掉进去不会坠入虚空。
     */
    public Block middleCoreFluid() {
        return switch (this) {
            case ICE -> Blocks.WATER;
            case NETHER -> Blocks.LAVA;
            default -> null;
        };
    }

    /**
     * 不死图腾救回点（中岛上的落脚点）。
     *
     * <p>有核心的主题（末地虚空 / 冰原水 / 地狱岩浆）不能把玩家丢在正中央，
     * 所以取核心外一圈的实心地面上；实心中岛直接回中心。
     */
    public BlockPos rescuePoint(SkyWarsLayout.Island middle) {
        BlockPos c = middle.center();
        if (!this.hasMiddleCore()) {
            return c;
        }
        int innerR = (int) (middle.radius() * this.middleCoreRatio());
        // 至少离核心外 1 格，且不许越过岛缘（否则救回点会落在虚空/池子上方）
        int offset = Math.max(innerR + 2, (int) Math.round(middle.radius() * 0.6));
        offset = Math.min(offset, Math.max(1, middle.radius() - 1));
        return new BlockPos(c.getX() + offset, c.getY(), c.getZ());
    }
}
