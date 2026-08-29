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
 * 心跳水立方（多关卡自由落体跳水）地图生成：N 座小塔并排，
 * 每关塔底平台 + 中央水池 → 满铺玻璃地板（挖洞）→ 塔顶出发台。
 */
public final class HeartbeatMapGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    private HeartbeatMapGenerator() {
    }

    /** 生成整张心跳水立方地图（全部关卡）。 */
    public static void generate(ArenaWorld world, HeartbeatLayout layout) {
        int halfSize = layout.halfSize;
        int poolY = layout.poolY;

        for (int level = 0; level < layout.levelCount; level++) {
            BlockPos c = layout.center(level);
            int cx = c.getX();
            int cz = c.getZ();

            // 1) 塔底水池：整塔方形 2 格深水，池底有实心底（保证穿过最后一层玻璃洞后必落水）
            for (int dx = -halfSize; dx <= halfSize; dx++) {
                for (int dz = -halfSize; dz <= halfSize; dz++) {
                    int x = cx + dx;
                    int z = cz + dz;
                    world.setBlockState(new BlockPos(x, poolY, z), layout.poolWater().getDefaultState(), 3);
                    world.setBlockState(new BlockPos(x, poolY - 1, z), layout.poolWater().getDefaultState(), 3);
                    world.setBlockState(new BlockPos(x, poolY - 2, z), layout.platformBlock.getDefaultState(), 3);
                }
            }

            // 2) 塔顶出发台：环形（中央留 7×7 洞口，玩家跳进洞口下落）
            int topY = layout.topY(level);
            BlockState topState = layout.platformBlock.getDefaultState();
            for (int dx = -halfSize; dx <= halfSize; dx++) {
                for (int dz = -halfSize; dz <= halfSize; dz++) {
                    if (Math.abs(dx) <= HeartbeatLayout.START_HOLE_HALF
                            && Math.abs(dz) <= HeartbeatLayout.START_HOLE_HALF) {
                        continue; // 中央洞口（不铺出发台）
                    }
                    world.setBlockState(new BlockPos(cx + dx, topY, cz + dz), topState, 3);
                }
            }
        }

        // 3) 全部玻璃地板满铺 + 洞位挖空（先铺后挖，保证洞一定是空气）
        BlockState glass = layout.floorBlock.getDefaultState();
        for (BlockPos pos : layout.floorBlocks()) {
            world.setBlockState(pos, glass, 3);
        }
        BlockState air = Blocks.AIR.getDefaultState();
        for (BlockPos pos : layout.holeBlocks()) {
            world.setBlockState(pos, air, 3);
        }

        // 4) 四周外墙：封住塔身（防止从出发台边缘跳出绕过地板直落水），彩虹羊毛装饰，内嵌发光石照明
        for (int level = 0; level < layout.levelCount; level++) {
            BlockPos c = layout.center(level);
            int cx = c.getX();
            int cz = c.getZ();
            int outer = halfSize + 1; // 外墙在地板外圈 1 格，内壁与地板齐平
            int wallBottom = layout.poolY - 2;
            int wallTop = layout.topY(level) + 3; // 高出出发台 3 格，防止跳出外墙
            BlockState wallState = layout.wallBlock(level).getDefaultState();
            BlockState glowState = layout.glowBlock().getDefaultState();

            for (int y = wallBottom; y <= wallTop; y++) {
                for (int x = cx - outer; x <= cx + outer; x++) {
                    world.setBlockState(new BlockPos(x, y, cz - outer), wallState, 3);
                    world.setBlockState(new BlockPos(x, y, cz + outer), wallState, 3);
                }
                for (int z = cz - outer + 1; z <= cz + outer - 1; z++) {
                    world.setBlockState(new BlockPos(cx - outer, y, z), wallState, 3);
                    world.setBlockState(new BlockPos(cx + outer, y, z), wallState, 3);
                }
            }
            // 光源：沿四周外墙每 10 格高嵌入发光石（塔内全封闭，无光会很暗）
            for (int y = wallBottom + 4; y <= wallTop; y += 10) {
                for (int x = cx - outer + 3; x <= cx + outer - 3; x += 7) {
                    world.setBlockState(new BlockPos(x, y, cz - outer), glowState, 3);
                    world.setBlockState(new BlockPos(x, y, cz + outer), glowState, 3);
                }
                for (int z = cz - outer + 3; z <= cz + outer - 3; z += 7) {
                    world.setBlockState(new BlockPos(cx - outer, y, z), glowState, 3);
                    world.setBlockState(new BlockPos(cx + outer, y, z), glowState, 3);
                }
            }
        }

        LOGGER.info("[PvP] 心跳水立方地图已生成: {} 关，塔宽 {}，层距 {}，起始层数 {}，已封外墙+光源",
                layout.levelCount, halfSize * 2 + 1, layout.floorGap, layout.baseFloors);
    }

    /** 清空一场心跳水立方的全部塔（含玻璃地板/出发台/水池平台）。 */
    public static void clear(ArenaWorld world, int regionIndex, int maxRadius) {
        if (maxRadius <= 0) {
            maxRadius = PvPConfig.INSTANCE.heartbeatSize / 2 + 8;
        }
        BlockPos center = center(regionIndex);
        int levelCount = Math.max(2, PvPConfig.INSTANCE.heartbeatLevels);
        int baseFloors = Math.max(2, PvPConfig.INSTANCE.heartbeatBaseFloors);
        int floorGap = Math.max(6, PvPConfig.INSTANCE.heartbeatFloorGap);
        int maxY = HeartbeatLayout.BASE_Y + 4 + (baseFloors + levelCount - 1) * floorGap + 8;
        int minY = HeartbeatLayout.BASE_Y - 6;
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

    /** 心跳水立方地图中心（第 0 关塔中心，与 Match 构造用的一致）。 */
    public static BlockPos center(int regionIndex) {
        BlockPos origin = new BlockPos(regionIndex * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        return new BlockPos(origin.getX() + PvPConfig.INSTANCE.heartbeatSize / 2,
                ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + PvPConfig.INSTANCE.heartbeatSize / 2);
    }
}
