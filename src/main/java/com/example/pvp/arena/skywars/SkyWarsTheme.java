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
    /** 地狱：地狱岩，岛面随机刷灵魂沙与岩浆。 */
    NETHER("地狱"),
    /** 冰原：全部由雪块/浮冰构成，云杉树。 */
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

    /** 岛面表层方块。 */
    public Block topBlock() {
        return switch (this) {
            case OVERWORLD -> Blocks.GRASS_BLOCK;
            case NETHER -> Blocks.NETHERRACK;
            case ICE -> Blocks.SNOW_BLOCK;
            case END -> Blocks.END_STONE;
        };
    }

    /** 表层下方第 1 层。 */
    public Block subBlock() {
        return switch (this) {
            case OVERWORLD -> Blocks.DIRT;
            case NETHER -> Blocks.NETHERRACK;
            case ICE -> Blocks.PACKED_ICE;
            case END -> Blocks.END_STONE;
        };
    }

    /** 更深的 2 层。 */
    public Block deepBlock() {
        return switch (this) {
            case OVERWORLD -> Blocks.STONE;
            case NETHER -> Blocks.NETHERRACK;
            case ICE -> Blocks.PACKED_ICE;
            case END -> Blocks.END_STONE;
        };
    }

    /** 中间主岛是否为空心环（末地是）。 */
    public boolean ringMiddle() {
        return this == END;
    }

    /** 中岛空心环的内半径比例（0~1，乘以中岛半径；仅末地 >0）。 */
    public double ringInnerRatio() {
        return this == END ? 0.4 : 0.0;
    }

    /** 非末地主题 = 中岛中心；末地 = 环上的一个安全点（避免掉进空心中央再次坠虚空）。 */
    public BlockPos rescuePoint(SkyWarsLayout.Island middle) {
        BlockPos c = middle.center();
        if (this == END) {
            int offset = Math.max(2, (int) Math.round(middle.radius() * 0.6));
            return new BlockPos(c.getX() + offset, c.getY(), c.getZ());
        }
        return c;
    }
}
