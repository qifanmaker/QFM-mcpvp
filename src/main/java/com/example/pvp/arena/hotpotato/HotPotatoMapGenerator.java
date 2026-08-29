package com.example.pvp.arena.hotpotato;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.config.PvPConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

/**
 * 烫手山芋地图生成：圆形石砖平台 + 边缘玻璃围墙 + 随机障碍物（玻璃柱/石砖矮墙）。
 */
public final class HotPotatoMapGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    private HotPotatoMapGenerator() {
    }

    /** 生成整张烫手山芋地图。 */
    public static void generate(ArenaWorld world, HotPotatoLayout layout) {
        int cx = layout.mapCenter.getX();
        int cz = layout.mapCenter.getZ();

        // 1) 平台：实心圆盘（半径 halfSize）
        fillDisk(world, cx, cz, layout.halfSize, layout.platformY, layout.platformBlock.getDefaultState());

        // 2) 边缘围墙：一圈玻璃，高 2
        for (int dx = -layout.halfSize; dx <= layout.halfSize; dx++) {
            for (int dz = -layout.halfSize; dz <= layout.halfSize; dz++) {
                if (dx * dx + dz * dz > layout.halfSize * layout.halfSize
                        || dx * dx + dz * dz < (layout.halfSize - 1) * (layout.halfSize - 1)) {
                    continue;
                }
                for (int h = 1; h <= 2; h++) {
                    world.setBlockState(new BlockPos(cx + dx, layout.platformY + h, cz + dz),
                            layout.wallBlock.getDefaultState(), 3);
                }
            }
        }

        // 3) 障碍物：玻璃柱 / 石砖矮墙
        for (BlockPos pos : layout.pillars()) {
            world.setBlockState(pos, layout.pillarBlock.getDefaultState(), 3);
        }
        for (BlockPos pos : layout.walls()) {
            world.setBlockState(pos, layout.platformBlock.getDefaultState(), 3);
        }

        LOGGER.info("[PvP] 烫手山芋地图已生成: 平台半宽 {}，玻璃柱 {} 根，矮墙方块 {} 块",
                layout.halfSize, layout.pillars().size(), layout.walls().size());
    }

    /** 清空一场烫手山芋的平台与障碍物（从平台下方到围墙上方）。 */
    public static void clear(ArenaWorld world, int regionIndex, int maxRadius) {
        if (maxRadius <= 0) {
            maxRadius = PvPConfig.INSTANCE.hotPotatoSize / 2 + 8;
        }
        BlockPos center = center(regionIndex);
        int minY = ArenaTemplate.PLATFORM_Y - 8;
        int maxY = ArenaTemplate.PLATFORM_Y + 12;
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                    }
                }
            }
        }
    }

    /** 烫手山芋地图中心：与 Match 构造用的布局中心一致。 */
    public static BlockPos center(int regionIndex) {
        BlockPos origin = new BlockPos(regionIndex * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        return new BlockPos(origin.getX() + PvPConfig.INSTANCE.hotPotatoSize / 2,
                ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + PvPConfig.INSTANCE.hotPotatoSize / 2);
    }

    private static void fillDisk(ArenaWorld world, int cx, int cz, int radius, int y, BlockState state) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                world.setBlockState(new BlockPos(cx + dx, y, cz + dz), state, 3);
            }
        }
    }
}
