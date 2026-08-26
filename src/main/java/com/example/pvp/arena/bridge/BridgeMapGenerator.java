package com.example.pvp.arena.bridge;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;

/**
 * 战桥地图生成：按 {@link BridgeLayout} 铺基地/桥/枢纽，并负责赛后整场清空（方块 + 掉落物）。
 */
public final class BridgeMapGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    private BridgeMapGenerator() {
    }

    /** 铺一整张战桥地图（地板白混凝土 + 队伍色边圈 + 外侧石砖墙 + 桥 + 枢纽）。 */
    public static void generate(ArenaWorld world, BridgeLayout layout) {
        for (BridgeLayout.BridgeBase base : layout.bases()) {
            for (BlockPos p : base.floor()) {
                world.setBlockState(p, Blocks.WHITE_CONCRETE.getDefaultState(), 3);
            }
            for (BlockPos p : base.border()) {
                world.setBlockState(p, borderBlock(base.teamIndex()).getDefaultState(), 3);
            }
            for (BlockPos p : base.wall()) {
                world.setBlockState(p, Blocks.STONE_BRICKS.getDefaultState(), 3);
            }
        }
        for (BlockPos p : layout.bridgeBlocks()) {
            world.setBlockState(p, Blocks.WHITE_CONCRETE.getDefaultState(), 3);
        }
        for (BlockPos p : layout.hubBlocks()) {
            world.setBlockState(p, Blocks.WHITE_CONCRETE.getDefaultState(), 3);
        }
        LOGGER.info("[PvP] 战桥地图已生成: {} 座基地{}（半径 {}，空隙 {}）",
                layout.bases().size(), layout.fourTeam() ? "（四方）" : "",
                layout.baseRadius(), layout.gap());
    }

    /** 队伍色边圈方块。 */
    private static Block borderBlock(int teamIndex) {
        return switch (teamIndex) {
            case 0 -> Blocks.RED_TERRACOTTA;
            case 1 -> Blocks.BLUE_TERRACOTTA;
            case 2 -> Blocks.GREEN_TERRACOTTA;
            default -> Blocks.YELLOW_TERRACOTTA;
        };
    }

    /** 清空一张战桥地图：先拆方块再清掉落物（拆容器会掉落内容，必须先拆块）。 */
    public static void clear(ArenaWorld world, BridgeLayout layout) {
        BlockPos center = layout.mapCenter();
        int maxRadius = layout.maxRadius();

        // 清到世界最高可搭建 Y（玩家可能向上搭很高的塔），下方也留出夹方块/搭桥的空间
        int maxDy = world.getTopY() - 1 - ArenaTemplate.PLATFORM_Y;
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                for (int dy = -16; dy <= maxDy; dy++) {
                    BlockPos pos = new BlockPos(center.getX() + dx, ArenaTemplate.PLATFORM_Y + dy, center.getZ() + dz);
                    if (!world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                    }
                }
            }
        }

        Box box = new Box(
                center.getX() - maxRadius, ArenaTemplate.PLATFORM_Y - 16, center.getZ() - maxRadius,
                center.getX() + maxRadius + 1, ArenaTemplate.PLATFORM_Y + maxDy, center.getZ() + maxRadius + 1
        );
        for (ItemEntity entity : world.getEntitiesByClass(ItemEntity.class, box, e -> true)) {
            entity.discard();
        }
    }
}
