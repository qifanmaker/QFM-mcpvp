package com.example.pvp.arena.heartbeat;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.config.PvPConfig;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 心跳水立方地图布局：高空出发台 → 多层"心跳"棋盘格地板 → 底部水坑平台。
 * 纯几何、确定性；心跳地板/水坑判定/出生点共用同一份布局。
 *
 * 玩法：玩家从顶部出发台往下跳，障碍地板周期性"心跳"（出现→消失），
 * 卡心跳窗口逐层下落，落进底部任一水坑即安全到达（按到达顺序排名），
 * 第一个到达水坑的玩家获胜。
 */
public final class HeartbeatLayout {

    public final BlockPos mapCenter;
    public final int halfSize;         // 塔半宽（边长 = 2*halfSize+1）
    public final int maxRadius;        // 清理半径
    public final int platformY;        // 底部平台表面 Y
    public final int topY;             // 顶部出发台表面 Y
    public final List<Integer> layerYs;    // 心跳地板层 Y（自底向上）
    public final List<BlockPos> spawns;    // 顶部出生点（围成一圈）
    public final List<BlockPos> pools;     // 水坑中心（xz，y 为平台表面）
    public final int poolRadius;           // 水坑半径
    /** 心跳地板方块位置（每层棋盘格：dx+dz 为偶数放方块，奇数留洞）。 */
    private final Set<BlockPos> layerBlocks = new HashSet<>();
    /** 心跳地板方块（材料）。 */
    public final Block layerBlock;
    public final Block poolWater;

    private HeartbeatLayout(BlockPos mapCenter, int halfSize, int maxRadius, int platformY, int topY,
                            List<Integer> layerYs, List<BlockPos> spawns, List<BlockPos> pools,
                            int poolRadius, Block layerBlock, Block poolWater) {
        this.mapCenter = mapCenter;
        this.halfSize = halfSize;
        this.maxRadius = maxRadius;
        this.platformY = platformY;
        this.topY = topY;
        this.layerYs = List.copyOf(layerYs);
        this.spawns = List.copyOf(spawns);
        this.pools = List.copyOf(pools);
        this.poolRadius = poolRadius;
        this.layerBlock = layerBlock;
        this.poolWater = poolWater;
        int cx = mapCenter.getX();
        int cz = mapCenter.getZ();
        for (int y : layerYs) {
            for (int dx = -halfSize; dx <= halfSize; dx++) {
                for (int dz = -halfSize; dz <= halfSize; dz++) {
                    if (((dx + dz) & 1) == 0) {
                        this.layerBlocks.add(new BlockPos(cx + dx, y, cz + dz));
                    }
                }
            }
        }
    }

    public static HeartbeatLayout compute(BlockPos mapCenter, PvPConfig cfg, int seed) {
        int halfSize = Math.max(8, cfg.heartbeatSize / 2);
        int platformY = ArenaTemplate.PLATFORM_Y;
        int layerGap = Math.max(4, cfg.heartbeatLayerGap);
        int layerCount = Math.max(2, cfg.heartbeatLayerCount);
        int poolCount = Math.max(2, cfg.heartbeatPoolCount);
        int poolRadius = Math.max(2, cfg.heartbeatPoolRadius);

        // 心跳地板层：平台上方 8 格起，每层间距 layerGap
        List<Integer> layerYs = new ArrayList<>();
        for (int i = 0; i < layerCount; i++) {
            layerYs.add(platformY + 8 + i * layerGap);
        }
        // 顶部出发台：最上层地板再往上 layerGap
        int topY = layerYs.get(layerCount - 1) + layerGap;

        int cx = mapCenter.getX();
        int cz = mapCenter.getZ();

        // 水坑：中心 1 个 + 一圈均匀分布
        List<BlockPos> pools = new ArrayList<>();
        pools.add(new BlockPos(cx, platformY, cz));
        double ringR = Math.max(poolRadius * 2 + 2, halfSize - 6);
        for (int i = 0; i < poolCount - 1; i++) {
            double angle = i * 2.0 * Math.PI / (poolCount - 1);
            int x = cx + (int) Math.round(Math.cos(angle) * ringR);
            int z = cz + (int) Math.round(Math.sin(angle) * ringR);
            pools.add(new BlockPos(x, platformY, z));
        }

        // 出生点：顶部出发台边缘围一圈（最多 8 人）
        List<BlockPos> spawns = new ArrayList<>();
        int spawnR = Math.max(2, halfSize - 3);
        for (int i = 0; i < 8; i++) {
            double angle = i * 2.0 * Math.PI / 8 + 0.4;
            int x = cx + (int) Math.round(Math.cos(angle) * spawnR);
            int z = cz + (int) Math.round(Math.sin(angle) * spawnR);
            spawns.add(new BlockPos(x, topY + 1, z));
        }

        return new HeartbeatLayout(mapCenter, halfSize, halfSize + 8, platformY, topY,
                layerYs, spawns, pools, poolRadius,
                Blocks.QUARTZ_BLOCK, Blocks.WATER);
    }

    /** 是否在水坑范围内（xz 判定）。 */
    public boolean isInPool(double x, double z) {
        for (BlockPos pool : this.pools) {
            double dx = x - (pool.getX() + 0.5);
            double dz = z - (pool.getZ() + 0.5);
            if (dx * dx + dz * dz <= this.poolRadius * this.poolRadius + 0.5) {
                return true;
            }
        }
        return false;
    }

    /** 心跳地板方块集合（切换消失/出现用）。 */
    public Set<BlockPos> layerBlocks() {
        return this.layerBlocks;
    }
}
