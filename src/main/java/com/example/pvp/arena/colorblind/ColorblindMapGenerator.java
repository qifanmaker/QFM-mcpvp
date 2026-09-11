package com.example.pvp.arena.colorblind;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.config.PvPConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

/**
 * 色盲派对地图：一整块单层彩色地板悬在虚空上（没有围墙、没有多余结构）。
 *
 * <p>地板内容（配色）由 {@link ColorblindFloor} 每回合重摇，这里只负责场地定位与清场。
 */
public final class ColorblindMapGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 地板层以上的清场高度：加成/事件可能在地板上方放方块（雪、玻璃罩、飞毯等）。 */
    private static final int CLEAR_ABOVE = 48;
    private static final int CLEAR_BELOW = 16;
    /** 每回合清理的"地板层以上装饰"层数（雪/铁砧/玻璃罩/飞毯都在这个范围内）。 */
    private static final int DECOR_LAYERS = 5;

    private ColorblindMapGenerator() {
    }

    /** 地板所在层（= 竞技场平台层）的西南角。 */
    public static BlockPos origin(int regionIndex) {
        return new BlockPos(regionIndex * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
    }

    /** 地板中心，与 {@link ArenaTemplate#getCenter} 一致。 */
    public static BlockPos center(int regionIndex) {
        int size = PvPConfig.INSTANCE.colorblindSize;
        return origin(regionIndex).add(size / 2, 1, size / 2);
    }

    /**
     * 在一场色盲派对场地里建出本场的地板对象（尚未铺方块）。
     * 铺方块由 {@link ColorblindFloor#fill} + {@link ColorblindFloor#placeAll} 完成。
     */
    public static ColorblindFloor createFloor(int regionIndex) {
        int size = Math.max(8, PvPConfig.INSTANCE.colorblindSize);
        return new ColorblindFloor(origin(regionIndex), size);
    }

    /**
     * 只清掉地板层上方 {@code DECOR_LAYERS} 格内的"装饰残留"（暴雪的雪、铁砧雨落下的铁砧、
     * 随机玻璃罩、飞毯等），地板本身不动。每回合重建地板前调用。
     */
    public static void clearAboveFloor(ArenaWorld world, BlockPos floorOrigin, int floorSize) {
        clearAboveFloor(world, floorOrigin, floorSize, DECOR_LAYERS);
    }

    /** 同上，但指定清理层数（回合结束只清 2 层，够拆掉信标/铁砧/玻璃罩，又不动玩家脚下的魔毯）。 */
    public static void clearAboveFloor(ArenaWorld world, BlockPos floorOrigin, int floorSize, int layers) {
        for (int dx = 0; dx < floorSize; dx++) {
            for (int dz = 0; dz < floorSize; dz++) {
                for (int dy = 1; dy <= layers; dy++) {
                    BlockPos pos = new BlockPos(floorOrigin.getX() + dx,
                            ArenaTemplate.PLATFORM_Y + dy, floorOrigin.getZ() + dz);
                    if (!world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                    }
                }
            }
        }
    }

    /** 清空一场色盲派对：地板层上下方整片区域（方块 + 掉落物由 ArenaWorldManager 统一处理）。 */
    public static void clear(ArenaWorld world, int regionIndex, int maxRadius) {
        int size = Math.max(8, PvPConfig.INSTANCE.colorblindSize);
        BlockPos corner = origin(regionIndex);
        int minY = ArenaTemplate.PLATFORM_Y - CLEAR_BELOW;
        int maxY = ArenaTemplate.PLATFORM_Y + CLEAR_ABOVE;
        int cleared = 0;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(corner.getX() + dx, y, corner.getZ() + dz);
                    if (!world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                        cleared++;
                    }
                }
            }
        }
        LOGGER.info("[PvP] 色盲派对场地已清理: 区域 {}（{} 格方块）", regionIndex, cleared);
    }
}
