package com.example.pvp.arena;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 竞技场平台布局模板：不同模式对应不同尺寸与出生点分布。
 * 每个匹配独占一个区域（按 regionIndex 在 x 方向偏移 REGION_SPACING）。
 */
public class ArenaTemplate {
    public static final int PLATFORM_Y = 100;
    public static final int REGION_SPACING = 256;
    public static final int WALL_HEIGHT = 3;

    public enum Layout {
        DUEL_1V1,
        DUEL_2V2,
        FFA,
        SKYWARS,
        BRIDGE,
        LUCKY_PILLAR,
        TNT_RUN
    }

    private final Layout layout;
    private final int size;
    private final Block floorBlock;
    private final Block wallBlock;
    private final boolean hasWalls;

    public ArenaTemplate(Layout layout, int size, Block floorBlock, Block wallBlock, boolean hasWalls) {
        this.layout = layout;
        this.size = size;
        this.floorBlock = floorBlock;
        this.wallBlock = wallBlock;
        this.hasWalls = hasWalls;
    }

    public Layout getLayout() {
        return this.layout;
    }

    public int getSize() {
        return this.size;
    }

    public Block getFloorBlock() {
        return this.floorBlock;
    }

    public Block getWallBlock() {
        return this.wallBlock;
    }

    public boolean hasWalls() {
        return this.hasWalls;
    }

    public BlockPos getRegionOrigin(int regionIndex) {
        return new BlockPos(regionIndex * REGION_SPACING, PLATFORM_Y, 0);
    }

    public BlockPos getCenter(int regionIndex) {
        BlockPos origin = this.getRegionOrigin(regionIndex);
        return new BlockPos(origin.getX() + this.size / 2, PLATFORM_Y + 1, origin.getZ() + this.size / 2);
    }

    public List<BlockPos> computeSpawns(int regionIndex, int playerCount) {
        BlockPos center = this.getCenter(regionIndex);
        int cx = center.getX();
        int cz = center.getZ();
        int d = this.size / 4;

        List<BlockPos> spawns = new ArrayList<>();
        switch (this.layout) {
            case DUEL_1V1 -> {
                spawns.add(new BlockPos(cx - d, PLATFORM_Y + 1, cz));
                spawns.add(new BlockPos(cx + d, PLATFORM_Y + 1, cz));
            }
            case DUEL_2V2 -> {
                // 队伍A（前两人）在左半，队伍B（后两人）在右半
                spawns.add(new BlockPos(cx - d, PLATFORM_Y + 1, cz - d / 2));
                spawns.add(new BlockPos(cx - d, PLATFORM_Y + 1, cz + d / 2));
                spawns.add(new BlockPos(cx + d, PLATFORM_Y + 1, cz - d / 2));
                spawns.add(new BlockPos(cx + d, PLATFORM_Y + 1, cz + d / 2));
            }
            case FFA -> {
                for (int i = 0; i < playerCount; i++) {
                    double angle = i * 2.0 * Math.PI / playerCount;
                    int x = cx + (int) Math.round(Math.cos(angle) * d);
                    int z = cz + (int) Math.round(Math.sin(angle) * d);
                    spawns.add(new BlockPos(x, PLATFORM_Y + 1, z));
                }
            }
            case SKYWARS -> {
                // 空岛出生点由 SkyWarsLayout 计算（Match 构造时处理），这里返回空避免占位
            }
            case BRIDGE -> {
                // 战桥出生点由 BridgeLayout 计算（Match 构造时处理），这里返回空避免占位
            }
            case LUCKY_PILLAR -> {
                // 幸运之柱出生点由 LuckyPillarLayout 计算（Match 构造时处理），这里返回空避免占位
            }
            case TNT_RUN -> {
                // TNT 跑酷出生点由 TntRunLayout 计算（Match 构造时处理），这里返回空避免占位
            }
        }
        return spawns;
    }
}
