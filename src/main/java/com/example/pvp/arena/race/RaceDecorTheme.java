package com.example.pvp.arena.race;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

/**
 * 赛道装饰主题（纯外观，<b>不碰任何物理方块</b>）。
 *
 * <p><b>为什么需要它</b>：赛道骨架虽然每场随机，但护栏、门柱、看台、雪原一直用同一套方块，
 * 玩家看到的第一眼永远是"蓝冰门柱 + 雪原 + 云杉看台"，主观上就是"地图都长得一样"。
 * 主题只替换<b>赛道之外</b>的装饰方块（门柱/横梁/终点横幅/看台/内场雪原/山体/岩石），
 * 冰面（{@code boatRaceSurfaceBlock}）与缓冲带（{@code boatRaceRunoffBlock}，决定摩擦）
 * 仍然由配置固定，所以换主题不会改变任何一条船的物理表现 —— 只是同一副骨架换一张皮。
 *
 * <p>主题由 {@code track.seed()} 推导（{@link #pick(long)}），所以：
 * <ul>
 *   <li>同一 Seed 必得同一主题，{@code /pvp debug boatrace seed <n>} 能精确复现；</li>
 *   <li>{@link RaceMapGenerator#clear} 重放同一套布局时也拿到同一主题，清场不会漏方块。</li>
 * </ul>
 */
public enum RaceDecorTheme {
    /** 冰川：现状（蓝冰门柱 + 黑白格终点 + 雪原 + 云杉看台）。 */
    GLACIER("冰川",
            Blocks.BLUE_ICE, Blocks.BLUE_ICE, Blocks.BLACK_CONCRETE, Blocks.WHITE_CONCRETE,
            Blocks.SNOW_BLOCK, Blocks.PACKED_ICE, Blocks.STONE,
            Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_FENCE),
    /** 雪原：雪块门柱 + 浮冰横梁 + 浅蓝终点，内场是纯白雪原 + 浮冰湖。 */
    SNOWFIELD("雪原",
            Blocks.SNOW_BLOCK, Blocks.PACKED_ICE, Blocks.LIGHT_BLUE_CONCRETE, Blocks.WHITE_CONCRETE,
            Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.COBBLESTONE,
            Blocks.OAK_PLANKS, Blocks.OAK_FENCE),
    /** 岩窟：圆石门柱 + 石砖横梁，内场是裸岩盆地（冰湖变成小水洼）。 */
    ROCKY("岩窟",
            Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.BLACK_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE,
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE,
            Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_FENCE),
    /** 紫晶：紫晶块门柱 + 紫珀横梁，方解石雪原 + 平滑玄武岩地面。 */
    AMETHYST("紫晶",
            Blocks.AMETHYST_BLOCK, Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.END_STONE_BRICKS,
            Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.DEEPSLATE,
            Blocks.PURPUR_BLOCK, Blocks.IRON_BARS);

    /** 避免每次 {@link #pick} 都克隆一遍数组。 */
    private static final RaceDecorTheme[] VALUES = values();

    private final String displayName;
    private final Block pillar;
    private final Block beam;
    private final Block finishBeamA;
    private final Block finishBeamB;
    private final Block groundSnow;
    private final Block groundIce;
    private final Block rock;
    private final Block standPlanks;
    private final Block standFence;

    RaceDecorTheme(String displayName, Block pillar, Block beam, Block finishBeamA, Block finishBeamB,
                   Block groundSnow, Block groundIce, Block rock, Block standPlanks, Block standFence) {
        this.displayName = displayName;
        this.pillar = pillar;
        this.beam = beam;
        this.finishBeamA = finishBeamA;
        this.finishBeamB = finishBeamB;
        this.groundSnow = groundSnow;
        this.groundIce = groundIce;
        this.rock = rock;
        this.standPlanks = standPlanks;
        this.standFence = standFence;
    }

    /** 由赛道 Seed 确定性地挑一个主题（splitmix64 终混，分布均匀且与其它随机流互不干扰）。 */
    public static RaceDecorTheme pick(long seed) {
        long h = seed * 0x9E3779B97F4A7C15L + 0xD6E8FEB86659FD93L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return VALUES[Math.floorMod(h, VALUES.length)];
    }

    /** 中文名，用于日志。 */
    public String displayName() {
        return this.displayName;
    }

    /** 门柱方块。 */
    public Block pillar() {
        return this.pillar;
    }

    /** 门架横梁方块（非终点门）。 */
    public Block beam() {
        return this.beam;
    }

    /** 终点门横幅的两种方块（黑白格效果的替代配色）。 */
    public Block finishBeamA() {
        return this.finishBeamA;
    }

    public Block finishBeamB() {
        return this.finishBeamB;
    }

    /** 内场雪原 / 山体表层的"雪"方块。 */
    public Block groundSnow() {
        return this.groundSnow;
    }

    /** 内场冰湖 / 冰柱的"冰"方块。 */
    public Block groundIce() {
        return this.groundIce;
    }

    /** 岩石 / 山体的"石头"方块。 */
    public Block rock() {
        return this.rock;
    }

    /** 看台平台方块。 */
    public Block standPlanks() {
        return this.standPlanks;
    }

    /** 看台栏杆方块。 */
    public Block standFence() {
        return this.standFence;
    }
}
