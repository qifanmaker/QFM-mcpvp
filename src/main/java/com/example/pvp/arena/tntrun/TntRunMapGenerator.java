package com.example.pvp.arena.tntrun;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.config.PvPConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

/**
 * TNT 跑酷地图生成：按 {@link TntRunLayout} 铺多层彩色羊毛平台（每层一种颜色）。
 */
public final class TntRunMapGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每层一种颜色（自底向上轮换）。 */
    private static final Block[] LAYER_WOOL = {
            Blocks.WHITE_WOOL, Blocks.LIGHT_BLUE_WOOL, Blocks.LIME_WOOL,
            Blocks.YELLOW_WOOL, Blocks.RED_WOOL
    };

    private TntRunMapGenerator() {
    }

    /** 生成整张 TNT 跑酷地图（5 层平台）。 */
    public static void generate(ArenaWorld world, TntRunLayout layout) {
        int cx = layout.mapCenter.getX();
        int cz = layout.mapCenter.getZ();
        for (int i = 0; i < layout.layerYs.size(); i++) {
            int y = layout.layerYs.get(i);
            Block wool = LAYER_WOOL[i % LAYER_WOOL.length];
            for (int dx = -layout.halfSize; dx <= layout.halfSize; dx++) {
                for (int dz = -layout.halfSize; dz <= layout.halfSize; dz++) {
                    world.setBlockState(new BlockPos(cx + dx, y, cz + dz), wool.getDefaultState(), 3);
                }
            }
        }
        LOGGER.info("[PvP] TNT 跑酷地图已生成: {} 层平台（边长 {}，间距 {}）", layout.layerYs.size(),
                layout.halfSize * 2 + 1, layout.layerYs.size() > 1 ? layout.layerYs.get(1) - layout.layerYs.get(0) : 0);
    }

    /** 清空一场 TNT 跑酷的平台（方块从底层下方到顶层上方）。 */
    public static void clear(ArenaWorld world, int regionIndex, int maxRadius) {
        if (maxRadius <= 0) {
            maxRadius = PvPConfig.INSTANCE.tntRunSize / 2 + 8;
        }
        BlockPos center = center(regionIndex);
        int minY = ArenaTemplate.PLATFORM_Y - 8;
        int maxY = ArenaTemplate.PLATFORM_Y + PvPConfig.INSTANCE.tntRunLayerCount * PvPConfig.INSTANCE.tntRunLayerGap + 8;
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

    /** TNT 跑酷地图中心：与 Match 构造用的布局中心一致。 */
    public static BlockPos center(int regionIndex) {
        BlockPos origin = new BlockPos(regionIndex * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        return new BlockPos(origin.getX() + PvPConfig.INSTANCE.tntRunSize / 2,
                ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + PvPConfig.INSTANCE.tntRunSize / 2);
    }
}
