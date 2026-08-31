package com.example.pvp.arena.bedwars;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.mojang.logging.LogUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Bed Wars 地图写入：把加载的方块表平移到竞技场虚空维度（地图中心对齐 region 中心，Y 对齐 PLATFORM_Y）。
 */
public final class BedWarsMapPaster {
    private static final Logger LOGGER = LogUtils.getLogger();

    private BedWarsMapPaster() {
    }

    /** 平移量：把地图方块坐标 → 竞技场坐标。 */
    public static BlockPos offsetFor(BedWarsMapLoader.MapData data, BlockPos regionCenter) {
        BlockPos mapCenter = data.centerXZ();
        // 水平：地图中心对齐 region 中心
        int dx = regionCenter.getX() - mapCenter.getX();
        int dz = regionCenter.getZ() - mapCenter.getZ();
        // 垂直：地图最低 Y 对齐 PLATFORM_Y（保持地图原样高度，不拉伸）
        int dy = ArenaTemplate.PLATFORM_Y - data.minY();
        return new BlockPos(dx, dy, dz);
    }

    /** 把地图方块 + 方块实体写入竞技场。 */
    public static void paste(ArenaWorld world, BedWarsMapLoader.MapData data, BlockPos regionCenter) {
        BlockPos offset = offsetFor(data, regionCenter);
        int count = 0;
        for (Map.Entry<BlockPos, BlockState> e : data.blocks.entrySet()) {
            BlockPos pos = e.getKey().add(offset);
            world.setBlockState(pos, e.getValue(), 3);
            count++;
        }
        // 方块实体（箱子等）：原 NBT 平移后重建
        int beCount = 0;
        for (NbtCompound nbt : data.blockEntities) {
            try {
                int bx = nbt.getInt("x");
                int by = nbt.getInt("y");
                int bz = nbt.getInt("z");
                BlockPos pos = new BlockPos(bx, by, bz).add(offset);
                BlockState state = world.getBlockState(pos);
                NbtCompound shifted = nbt.copy();
                shifted.putInt("x", pos.getX());
                shifted.putInt("y", pos.getY());
                shifted.putInt("z", pos.getZ());
                BlockEntity be = BlockEntity.createFromNbt(pos, state, shifted, world.getRegistryManager());
                if (be != null) {
                    world.addBlockEntity(be);
                    beCount++;
                }
            } catch (Exception ignored) {
            }
        }
        LOGGER.info("[PvP] BedWars 地图写入竞技场: {} 方块, {} 方块实体", count, beCount);
    }

    /** 地图中心在竞技场的实际位置（含平移）。 */
    public static BlockPos mapArenaCenter(BedWarsMapLoader.MapData data, BlockPos regionCenter) {
        return data.centerXZ().add(offsetFor(data, regionCenter));
    }
}
