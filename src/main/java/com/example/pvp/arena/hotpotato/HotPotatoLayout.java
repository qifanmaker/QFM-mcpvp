package com.example.pvp.arena.hotpotato;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.config.PvPConfig;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 烫手山芋地图布局：带障碍物的圆形平台。
 * 纯几何、确定性；出生点/障碍物判定共用同一份布局。
 *
 * 玩法：场上唯一一颗"烫手山芋"在玩家间左键传递，持有时间到会爆炸淘汰，
 * 障碍物提供绕行空间，最后存活者获胜。
 */
public final class HotPotatoLayout {

    public final BlockPos mapCenter;
    public final int halfSize;       // 平台半宽（边长 = 2*halfSize+1）
    public final int maxRadius;      // 清理半径
    public final int platformY;      // 平台表面 Y
    public final List<BlockPos> spawns;    // 出生点（围成一圈）
    /** 障碍物方块集合：玻璃柱。 */
    private final Set<BlockPos> pillars = new HashSet<>();
    /** 障碍物方块集合：石砖矮墙。 */
    private final Set<BlockPos> walls = new HashSet<>();
    public final Block platformBlock;
    public final Block wallBlock;
    public final Block pillarBlock;

    private HotPotatoLayout(BlockPos mapCenter, int halfSize, int maxRadius, int platformY,
                            List<BlockPos> spawns, Block platformBlock, Block wallBlock, Block pillarBlock) {
        this.mapCenter = mapCenter;
        this.halfSize = halfSize;
        this.maxRadius = maxRadius;
        this.platformY = platformY;
        this.spawns = List.copyOf(spawns);
        this.platformBlock = platformBlock;
        this.wallBlock = wallBlock;
        this.pillarBlock = pillarBlock;
    }

    public static HotPotatoLayout compute(BlockPos mapCenter, PvPConfig cfg, int seed) {
        int halfSize = Math.max(8, cfg.hotPotatoSize / 2);
        int platformY = ArenaTemplate.PLATFORM_Y;
        int cx = mapCenter.getX();
        int cz = mapCenter.getZ();

        // 出生点：半径 halfSize/2 围一圈（最多 8 人）
        List<BlockPos> spawns = new ArrayList<>();
        int spawnR = Math.max(3, halfSize / 2);
        for (int i = 0; i < 8; i++) {
            double angle = i * 2.0 * Math.PI / 8 + 0.39;
            int x = cx + (int) Math.round(Math.cos(angle) * spawnR);
            int z = cz + (int) Math.round(Math.sin(angle) * spawnR);
            spawns.add(new BlockPos(x, platformY + 1, z));
        }

        HotPotatoLayout layout = new HotPotatoLayout(mapCenter, halfSize, halfSize + 8, platformY,
                spawns, Blocks.STONE_BRICKS, Blocks.GLASS, Blocks.GLASS);
        layout.buildObstacles(seed, cx, cz);
        return layout;
    }

    /** 生成障碍物：环带内随机放置玻璃柱（1×1 高 3）与石砖矮墙（长 3~5 高 2），避开出生点。 */
    private void buildObstacles(int seed, int cx, int cz) {
        Random random = new Random(seed);
        int count = Math.max(6, this.halfSize / 2);
        int ringMin = Math.max(4, this.halfSize / 4);
        int ringMax = this.halfSize - 3;
        for (int i = 0; i < count; i++) {
            int x = cx + random.nextInt(ringMax * 2 + 1) - ringMax;
            int z = cz + random.nextInt(ringMax * 2 + 1) - ringMax;
            int dx = x - cx;
            int dz = z - cz;
            if (dx * dx + dz * dz < ringMin * ringMin || dx * dx + dz * dz > ringMax * ringMax) {
                continue; // 太靠近中心或太靠边，跳过
            }
            if (this.nearSpawn(x, z)) {
                continue; // 挡在出生点附近，跳过
            }
            boolean pillar = random.nextBoolean();
            if (pillar) {
                // 玻璃柱：1×1 高 3
                for (int h = 1; h <= 3; h++) {
                    this.pillars.add(new BlockPos(x, this.platformY + h, z));
                }
            } else {
                // 矮墙：沿 x 或 z 方向，长 3~5，高 2
                boolean alongX = random.nextBoolean();
                int len = 3 + random.nextInt(3);
                // 整面墙的所有方块都避开出生点，防止墙穿过出生位置卡人
                List<BlockPos> wall = new ArrayList<>();
                for (int j = 0; j < len; j++) {
                    int wx = alongX ? x + j : x;
                    int wz = alongX ? z : z + j;
                    if (this.nearSpawn(wx, wz)) {
                        wall.clear();
                        break;
                    }
                    for (int h = 1; h <= 2; h++) {
                        wall.add(new BlockPos(wx, this.platformY + h, wz));
                    }
                }
                this.walls.addAll(wall);
            }
        }
    }

    private boolean nearSpawn(int x, int z) {
        for (BlockPos spawn : this.spawns) {
            int dx = x - spawn.getX();
            int dz = z - spawn.getZ();
            if (dx * dx + dz * dz <= 4) {
                return true;
            }
        }
        return false;
    }

    /** 玻璃柱方块集合（用于生成与破坏保护）。 */
    public Set<BlockPos> pillars() {
        return this.pillars;
    }

    /** 石砖矮墙方块集合（用于生成与破坏保护）。 */
    public Set<BlockPos> walls() {
        return this.walls;
    }

    /** 全部障碍物方块集合（生成/破坏保护用）。 */
    public Set<BlockPos> obstacles() {
        Set<BlockPos> all = new HashSet<>(this.pillars);
        all.addAll(this.walls);
        return all;
    }
}
