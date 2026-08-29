package com.example.pvp.arena.heartbeat;

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
 * 心跳水立方（多关卡自由落体跳水）地图布局：
 * N 座小塔沿 +z 方向并排，第 0 关最易、最后一关最难。
 * 每关 = 塔顶出发台 → 若干层满铺玻璃地板（每层固定个 1×1 洞）→ 塔底整塔方形水池。
 * 玩家从塔顶跳下，逐层穿过洞里，落进水池即过关，传送进下一关。
 *
 * 纯几何、确定性（洞位由 seed + level + floor 决定，固定不脉冲）。
 */
public final class HeartbeatLayout {

    public final BlockPos mapCenter;     // 第 0 关塔中心
    public final int levelCount;         // 关卡总数
    public final int halfSize;           // 塔半宽（方形边长 = 2*halfSize+1）
    public final int levelStride;        // 相邻塔中心 z 间距
    public final int poolY;              // 塔底池面 Y（整塔方形水池，水面与塔底齐平）
    public final int floorGap;           // 玻璃层间距（足够下落时横向移动）
    public final int baseFloors;         // 第 1 关玻璃层数（每关 +1）
    public final int maxRadius;          // 清理半径（自第 0 关中心覆盖全部塔）
    public final Block floorBlock;       // 玻璃地板（透明，可看透找洞）
    public final Block platformBlock;    // 出发台/塔底平台（白色混凝土）
    public final List<BlockPos> spawns;  // 第 0 关出发台环（setupPlayers 用）

    /** 全部玻璃地板方块位置（绝对坐标）。 */
    private final Set<BlockPos> floorBlocks = new HashSet<>();
    /** 全部洞位（绝对坐标，玻璃地板中挖成空气）。 */
    private final Set<BlockPos> holeBlocks = new HashSet<>();

    private HeartbeatLayout(BlockPos mapCenter, int levelCount, int halfSize, int levelStride,
                            int poolY, int floorGap, int baseFloors,
                            int maxRadius, List<BlockPos> spawns, Block floorBlock, Block platformBlock) {
        this.mapCenter = mapCenter;
        this.levelCount = levelCount;
        this.halfSize = halfSize;
        this.levelStride = levelStride;
        this.poolY = poolY;
        this.floorGap = floorGap;
        this.baseFloors = baseFloors;
        this.maxRadius = maxRadius;
        this.spawns = List.copyOf(spawns);
        this.floorBlock = floorBlock;
        this.platformBlock = platformBlock;
    }

    public static HeartbeatLayout compute(BlockPos mapCenter, PvPConfig cfg, int seed) {
        int halfSize = Math.max(4, cfg.heartbeatSize / 2);
        int levelCount = Math.max(2, cfg.heartbeatLevels);
        int floorGap = Math.max(6, cfg.heartbeatFloorGap);
        int baseFloors = Math.max(2, cfg.heartbeatBaseFloors);
        int poolY = ArenaTemplate.PLATFORM_Y;
        int levelStride = halfSize * 2 + 9;

        // 清理半径：从第 0 关中心覆盖到最后一关塔边 + 边距
        int maxRadius = halfSize + (levelCount - 1) * levelStride + halfSize + 8;

        HeartbeatLayout layout = new HeartbeatLayout(mapCenter, levelCount, halfSize, levelStride,
                poolY, floorGap, baseFloors, maxRadius,
                level0Spawns(mapCenter, halfSize, baseFloors, floorGap, poolY),
                Blocks.GLASS, Blocks.WHITE_CONCRETE);

        // 每关每层洞位：确定性，避免与上一层洞位太近（防笔直下落）
        for (int level = 0; level < levelCount; level++) {
            Set<BlockPos> prevFloorHoles = null;
            int cx = layout.center(level).getX();
            int cz = layout.center(level).getZ();
            for (int f = 0; f < layout.floors(level); f++) {
                int fy = layout.floorY(level, f);
                Set<BlockPos> holes = pickHoles(seed, level, f, layout.holes(level), prevFloorHoles, halfSize);
                // 玻璃地板满铺 + 洞位挖空
                for (int dx = -halfSize; dx <= halfSize; dx++) {
                    for (int dz = -halfSize; dz <= halfSize; dz++) {
                        layout.floorBlocks.add(new BlockPos(cx + dx, fy, cz + dz));
                    }
                }
                for (BlockPos rel : holes) {
                    layout.holeBlocks.add(new BlockPos(cx + rel.getX(), fy, cz + rel.getZ()));
                }
                prevFloorHoles = holes;
            }
        }
        return layout;
    }

    /** 第 0 关出发台环（最多 8 人，围一圈）。 */
    private static List<BlockPos> level0Spawns(BlockPos mapCenter, int halfSize, int baseFloors,
                                               int floorGap, int poolY) {
        int topY = poolY + 4 + baseFloors * floorGap;
        int spawnR = Math.max(1, halfSize - 2);
        List<BlockPos> spawns = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double angle = i * 2.0 * Math.PI / 8 + 0.4;
            int x = mapCenter.getX() + (int) Math.round(Math.cos(angle) * spawnR);
            int z = mapCenter.getZ() + (int) Math.round(Math.sin(angle) * spawnR);
            spawns.add(new BlockPos(x, topY + 1, z));
        }
        return spawns;
    }

    /** 抽取一层地板上的洞位（相对坐标，xz 分量，y=0 占位）。 */
    private static Set<BlockPos> pickHoles(int seed, int level, int floor, int count,
                                           Set<BlockPos> prevFloorHoles, int halfSize) {
        Set<BlockPos> holes = new HashSet<>();
        Random rng = new Random(seed * 31L + level * 131L + floor * 17L + 7L);
        // 洞位至少距塔边 1 格，避免穿洞后立刻掉出塔外
        int range = halfSize - 1;
        int attempts = 0;
        while (holes.size() < count && attempts < count * 40) {
            int hx = rng.nextInt(range * 2 + 1) - range;
            int hz = rng.nextInt(range * 2 + 1) - range;
            BlockPos p = new BlockPos(hx, 0, hz);
            if (holes.contains(p)) {
                attempts++;
                continue;
            }
            // 与上一层洞位保持曼哈顿距离 >= 2（避免两层洞位叠一起笔直下落）
            if (prevFloorHoles != null) {
                boolean tooClose = false;
                for (BlockPos q : prevFloorHoles) {
                    if (Math.abs(p.getX() - q.getX()) + Math.abs(p.getZ() - q.getZ()) < 2) {
                        tooClose = true;
                        break;
                    }
                }
                if (tooClose) {
                    attempts++;
                    continue;
                }
            }
            holes.add(p);
        }
        return holes;
    }

    // ---------- 查询接口 ----------

    /** 第 level 关塔中心（绝对坐标）。 */
    public BlockPos center(int level) {
        return new BlockPos(this.mapCenter.getX(), this.mapCenter.getY(),
                this.mapCenter.getZ() + level * this.levelStride);
    }

    /** 第 level 关玻璃地板层数（第 1 关 baseFloors，每关 +1）。 */
    public int floors(int level) {
        return this.baseFloors + level;
    }

    /** 第 level 关第 f 层（0 起）玻璃地板表面 Y。 */
    public int floorY(int level, int f) {
        return this.poolY + 4 + f * this.floorGap;
    }

    /** 第 level 关出发台表面 Y（最后一层玻璃之上 floorGap）。 */
    public int topY(int level) {
        return this.poolY + 4 + this.floors(level) * this.floorGap;
    }

    /** 第 level 关每层洞数（由易到难：5 → 1）。 */
    public int holes(int level) {
        return Math.max(1, 5 - level);
    }

    /** 是否在该关塔底水池内（整塔方形水池，与塔身同宽）。 */
    public boolean isInPool(int level, double x, double z) {
        BlockPos c = this.center(level);
        return x >= c.getX() - this.halfSize && x <= c.getX() + this.halfSize + 1
                && z >= c.getZ() - this.halfSize && z <= c.getZ() + this.halfSize + 1;
    }

    /** 第 level 关出发台上第 index 个出生点（环，8 个循环取）。 */
    public BlockPos levelTopSpawn(int level, int index) {
        int k = index % 8;
        int spawnR = Math.max(1, this.halfSize - 2);
        double angle = k * 2.0 * Math.PI / 8 + 0.4;
        BlockPos c = this.center(level);
        int x = c.getX() + (int) Math.round(Math.cos(angle) * spawnR);
        int z = c.getZ() + (int) Math.round(Math.sin(angle) * spawnR);
        return new BlockPos(x, this.topY(level) + 1, z);
    }

    /** 清理半径。 */
    public int maxRadius() {
        return this.maxRadius;
    }

    /** 水池水方块。 */
    public Block poolWater() {
        return Blocks.WATER;
    }

    /** 全部玻璃地板方块（生成用）。 */
    public Set<BlockPos> floorBlocks() {
        return this.floorBlocks;
    }

    /** 全部洞位（生成用，这些位置留空气）。 */
    public Set<BlockPos> holeBlocks() {
        return this.holeBlocks;
    }
}
