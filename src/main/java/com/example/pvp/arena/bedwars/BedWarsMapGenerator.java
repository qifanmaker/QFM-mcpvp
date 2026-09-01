package com.example.pvp.arena.bedwars;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.config.PvPConfig;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

/**
 * Bed Wars 地图清理：整张地图区域清空（含玩家搭的方块、方块实体、掉落物、非玩家实体）。
 */
public final class BedWarsMapGenerator {
    private BedWarsMapGenerator() {
    }

    /** 清空一场床战的地图区域。 */
    public static void clear(ArenaWorld world, int regionIndex, int mapMaxRadius) {
        int half = Math.max(mapMaxRadius, PvPConfig.INSTANCE.bedWarsSize / 2) + 8;
        BlockPos origin = new BlockPos(regionIndex * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        BlockPos center = new BlockPos(origin.getX() + PvPConfig.INSTANCE.bedWarsSize / 2,
                ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + PvPConfig.INSTANCE.bedWarsSize / 2);
        // Y 范围：平台下方一点到世界最高可搭建 Y（和其他模式一致），避免全高度扫描拖慢清场
        int minY = ArenaTemplate.PLATFORM_Y - 16;
        int maxY = Math.min(world.getTopY() - 1, ArenaTemplate.PLATFORM_Y + 320);
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
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

    /** 床战地图中心（与 Match 构造用的区域中心一致）。 */
    public static BlockPos center(int regionIndex) {
        BlockPos origin = new BlockPos(regionIndex * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        return new BlockPos(origin.getX() + PvPConfig.INSTANCE.bedWarsSize / 2,
                ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + PvPConfig.INSTANCE.bedWarsSize / 2);
    }
}
