package com.example.pvp.arena.heartbeat;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.config.PvPConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

/**
 * 心跳水立方地图生成：底部水坑平台 + 多层心跳棋盘格地板 + 顶部出发台。
 */
public final class HeartbeatMapGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    private HeartbeatMapGenerator() {
    }

    /** 生成整张心跳水立方地图。 */
    public static void generate(ArenaWorld world, HeartbeatLayout layout) {
        int cx = layout.mapCenter.getX();
        int cz = layout.mapCenter.getZ();

        // 1) 底部平台：实心圆盘（半径 halfSize）
        fillDisk(world, cx, cz, layout.halfSize, layout.platformY, layout.layerBlock.getDefaultState());

        // 2) 水坑：挖 2 格深洞填水（水面与平台表面齐平）
        for (BlockPos pool : layout.pools) {
            for (int dx = -layout.poolRadius; dx <= layout.poolRadius; dx++) {
                for (int dz = -layout.poolRadius; dz <= layout.poolRadius; dz++) {
                    if (dx * dx + dz * dz > layout.poolRadius * layout.poolRadius) {
                        continue;
                    }
                    int x = pool.getX() + dx;
                    int z = pool.getZ() + dz;
                    world.setBlockState(new BlockPos(x, layout.platformY - 1, z),
                            layout.poolWater.getDefaultState(), 3);
                    world.setBlockState(new BlockPos(x, layout.platformY - 2, z),
                            layout.poolWater.getDefaultState(), 3);
                }
            }
        }

        // 3) 心跳地板层：棋盘格（布局中已算好方块位置）
        for (BlockPos pos : layout.layerBlocks()) {
            world.setBlockState(pos, layout.layerBlock.getDefaultState(), 3);
        }

        // 4) 顶部出发台：实心圆盘（半径 halfSize）
        fillDisk(world, cx, cz, layout.halfSize, layout.topY, layout.layerBlock.getDefaultState());

        LOGGER.info("[PvP] 心跳水立方地图已生成: 塔半宽 {}，障碍层 {} 层（层距 {}），水坑 {} 个",
                layout.halfSize, layout.layerYs.size(), layout.topY - layout.layerYs.get(0) - 8, layout.pools.size());
    }

    /** 铺/清除所有心跳地板层（open=true 时清除为空气，open=false 时重新铺棋盘格）。 */
    public static void setLayers(ArenaWorld world, HeartbeatLayout layout, boolean open) {
        for (BlockPos pos : layout.layerBlocks()) {
            if (open) {
                if (!world.getBlockState(pos).isAir()) {
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                }
            } else if (world.getBlockState(pos).isAir()) {
                world.setBlockState(pos, layout.layerBlock.getDefaultState(), 3);
            }
        }
    }

    /** 清空一场心跳水立方的塔（从底部平台下方到顶部出发台上方）。 */
    public static void clear(ArenaWorld world, int regionIndex, int maxRadius) {
        if (maxRadius <= 0) {
            maxRadius = PvPConfig.INSTANCE.heartbeatSize / 2 + 8;
        }
        BlockPos center = center(regionIndex);
        int minY = ArenaTemplate.PLATFORM_Y - 12;
        int maxY = ArenaTemplate.PLATFORM_Y + (PvPConfig.INSTANCE.heartbeatLayerCount + 1)
                * Math.max(4, PvPConfig.INSTANCE.heartbeatLayerGap) + 8 + 16;
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

    /** 心跳水立方地图中心：与 Match 构造用的布局中心一致。 */
    public static BlockPos center(int regionIndex) {
        BlockPos origin = new BlockPos(regionIndex * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        return new BlockPos(origin.getX() + PvPConfig.INSTANCE.heartbeatSize / 2,
                ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + PvPConfig.INSTANCE.heartbeatSize / 2);
    }

    private static void fillDisk(ArenaWorld world, int cx, int cz, int radius, int y,
                                 BlockState state) {
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
