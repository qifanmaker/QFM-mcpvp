package com.example.pvp.arena.tntrun;

import com.example.pvp.arena.ArenaTemplate;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TNT 跑酷地图布局：5 层方形平台叠放（每层一种颜色，踩过的方块会掉落）。
 * 纯几何、确定性；出生点/掉落物/方块消失判定共用同一份布局。
 */
public final class TntRunLayout {

    public final BlockPos mapCenter;
    public final int halfSize;       // 平台半宽（方形边长 = 2*halfSize+1）
    public final int maxRadius;      // 清理半径（半宽 + 边距）
    public final List<Integer> layerYs; // 每层表面 Y（自底向上）
    private final Set<BlockPos> platformBlocks = new HashSet<>();
    public final List<BlockPos> spawns;

    private TntRunLayout(BlockPos mapCenter, int halfSize, int maxRadius, List<Integer> layerYs,
                         List<BlockPos> spawns) {
        this.mapCenter = mapCenter;
        this.halfSize = halfSize;
        this.maxRadius = maxRadius;
        this.layerYs = List.copyOf(layerYs);
        this.spawns = List.copyOf(spawns);
        // 平台方块集合：每层方形范围内（用于消失判定/破坏保护）
        int cx = mapCenter.getX();
        int cz = mapCenter.getZ();
        for (int y : layerYs) {
            for (int dx = -halfSize; dx <= halfSize; dx++) {
                for (int dz = -halfSize; dz <= halfSize; dz++) {
                    this.platformBlocks.add(new BlockPos(cx + dx, y, cz + dz));
                }
            }
        }
    }

    public static TntRunLayout compute(BlockPos mapCenter, int halfSize, int layerCount, int layerGap) {
        List<Integer> ys = new ArrayList<>();
        for (int i = 0; i < layerCount; i++) {
            ys.add(ArenaTemplate.PLATFORM_Y + i * layerGap); // 底层在 PLATFORM_Y，向上叠
        }
        // 出生点：顶层均分一圈（站在最上层）
        int topY = ys.get(ys.size() - 1);
        List<BlockPos> spawns = new ArrayList<>();
        int spawnR = Math.max(1, halfSize * 2 / 3);
        for (int i = 0; i < 8; i++) {
            double angle = i * 2.0 * Math.PI / 8;
            int x = mapCenter.getX() + (int) Math.round(Math.cos(angle) * spawnR);
            int z = mapCenter.getZ() + (int) Math.round(Math.sin(angle) * spawnR);
            spawns.add(new BlockPos(x, topY + 1, z));
        }
        return new TntRunLayout(mapCenter, halfSize, halfSize + 8, ys, spawns);
    }

    /** 是否 TNT 跑酷平台方块（踩过会掉落）。 */
    public boolean isPlatformBlock(BlockPos pos) {
        return this.platformBlocks.contains(pos);
    }

    /** 是否在该场 TNT 跑酷的整个塔形范围内（含层间与玩家放置的方块，供爆炸掉落判定）。 */
    public boolean isWithinArea(BlockPos pos) {
        int dx = pos.getX() - this.mapCenter.getX();
        int dz = pos.getZ() - this.mapCenter.getZ();
        if (Math.abs(dx) > this.halfSize || Math.abs(dz) > this.halfSize) {
            return false;
        }
        int minY = this.layerYs.get(0);
        int maxY = this.layerYs.get(this.layerYs.size() - 1);
        return pos.getY() >= minY - 1 && pos.getY() <= maxY + 1;
    }

    public int topY() {
        return this.layerYs.get(this.layerYs.size() - 1);
    }
}
