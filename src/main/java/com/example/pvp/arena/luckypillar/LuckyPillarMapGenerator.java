package com.example.pvp.arena.luckypillar;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.luckypillar.LuckyPillarLayout.Pillar;
import com.example.pvp.config.PvPConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;

/**
 * 幸运之柱地图生成：按 {@link LuckyPillarLayout} 铺每根 1 格宽的基岩棍子（从柱底通到柱顶）。
 * 同时提供整场清理（方块 + 掉落物）。
 */
public final class LuckyPillarMapGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    private LuckyPillarMapGenerator() {
    }

    /** 生成一整张幸运之柱地图：柱顶下 20 格的大平台圆盘 + 1 格宽基岩棍。 */
    public static void generate(ArenaWorld world, LuckyPillarLayout layout) {
        // 平台圆盘（柱顶下方 luckyPillarPlatformGap 格）：整片铺上作为掉落的"安全楼层"
        int py = layout.platformY();
        int pr = layout.platformRadius();
        for (int dx = -pr; dx <= pr; dx++) {
            for (int dz = -pr; dz <= pr; dz++) {
                if (Math.sqrt(dx * dx + dz * dz) > pr) {
                    continue;
                }
                world.setBlockState(new BlockPos(layout.mapCenter().getX() + dx, py, layout.mapCenter().getZ() + dz),
                        Blocks.SMOOTH_STONE.getDefaultState(), 3);
            }
        }

        // 基岩棍：1 格宽，从平台竖到柱顶（顶端为站立面，基岩不可破坏）
        java.util.List<Pillar> pillars = layout.pillars();
        for (Pillar p : pillars) {
            for (int y = p.columnBaseY(); y <= p.topY(); y++) {
                world.setBlockState(new BlockPos(p.center().getX(), y, p.center().getZ()),
                        Blocks.BEDROCK.getDefaultState(), 3);
            }
        }
        LOGGER.info("[PvP] 幸运之柱地图已生成: {} 根基岩柱 + 平台（Y {}，半径 {}）",
                pillars.size(), py, pr);
    }

    /**
     * 清空一场幸运之柱的全部地形（方块 + 掉落物），供赛后清理复用。
     * 范围按「地图实际最大半径」居中清除，高度覆盖柱底（平台下方深处）到世界最高可搭建 Y。
     *
     * @param maxRadius 该场比赛生成时的最大半径（来自 LuckyPillarLayout）；<=0 时按当前配置兜底计算
     */
    public static void clear(ArenaWorld world, int regionIndex, int maxRadius) {
        if (maxRadius <= 0) {
            maxRadius = LuckyPillarLayout.computeMaxRadius();
        }
        BlockPos center = center(regionIndex); // 与生成时一致的地图中心

        // 先拆方块、再清掉落物（拆箱会重新掉落内容物成实体）
        // 平台在柱顶下 20 格（约 PLATFORM_Y 附近），下方无地形，清到平台以下一点即可
        int minY = ArenaTemplate.PLATFORM_Y - 10;
        int maxDy = world.getTopY() - 1 - ArenaTemplate.PLATFORM_Y;
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                for (int y = minY; y <= ArenaTemplate.PLATFORM_Y + maxDy; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                    }
                }
            }
        }

        // 再清掉落物（含玩家淘汰时掉落的物品）
        Box box = new Box(
                center.getX() - maxRadius, minY, center.getZ() - maxRadius,
                center.getX() + maxRadius + 1, ArenaTemplate.PLATFORM_Y + maxDy, center.getZ() + maxRadius + 1
        );
        for (ItemEntity entity : world.getEntitiesByClass(ItemEntity.class, box, e -> true)) {
            entity.discard();
        }
    }

    /** 幸运之柱地图中心：与平台中心一致，便于复用传送/清理等逻辑。 */
    public static BlockPos center(int regionIndex) {
        BlockPos origin = new BlockPos(regionIndex * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        return new BlockPos(origin.getX() + PvPConfig.INSTANCE.luckyPillarSize / 2,
                ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + PvPConfig.INSTANCE.luckyPillarSize / 2);
    }
}
