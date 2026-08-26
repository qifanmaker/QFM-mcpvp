package com.example.pvp.arena.skywars;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.config.PvPConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;

import java.util.Random;

/**
 * 空岛战争地图生成：按 {@link SkyWarsLayout} 铺出生岛/中间主岛、放箱子并填充战利品。
 * 同时提供缩圈删块与整场清岛（清空方块 + 掉落物）。
 */
public final class SkyWarsMapGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 岛面下方挖深的层数（草方块 1 层 + 下方土石 2 层）。 */
    private static final int ISLAND_DEPTH = 2;

    private SkyWarsMapGenerator() {
    }

    /** 生成一整张空岛地图（含战利品），并返回布局（出生点已在 Match 构造时算好）。 */
    public static SkyWarsLayout generate(ArenaWorld world, int regionIndex, int seed, int playerCount) {
        BlockPos mapCenter = center(regionIndex);
        SkyWarsLayout layout = SkyWarsLayout.compute(mapCenter, seed, playerCount);

        for (SkyWarsLayout.Island island : layout.spawnIslands()) {
            buildIsland(world, island, false);
        }
        for (SkyWarsLayout.Island island : layout.midIslands()) {
            buildIsland(world, island, false);
        }
        buildIsland(world, layout.middle(), true);

        LOGGER.info("[PvP] 空岛战争地图已生成: {} 个出生岛 + {} 个中途岛 + 中间主岛({} 箱)",
                layout.spawnIslands().size(), layout.midIslands().size(), layout.middle().chests().size());
        return layout;
    }

    /** 铺一座岛（圆台 + 箱子 + 偶发小树）；中间岛前两个箱子放在石砖柱上。 */
    private static void buildIsland(ArenaWorld world, SkyWarsLayout.Island island, boolean middle) {
        Random random = new Random(island.center.hashCode());
        int r = island.radius;
        BlockPos c = island.center;

        // 圆台：顶层草方块，下方泥土/石头
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r) {
                    continue;
                }
                BlockPos top = new BlockPos(c.getX() + dx, c.getY(), c.getZ() + dz);
                world.setBlockState(top, Blocks.GRASS_BLOCK.getDefaultState(), 3);
                world.setBlockState(top.down(), Blocks.DIRT.getDefaultState(), 3);
                for (int depth = 2; depth <= ISLAND_DEPTH; depth++) {
                    world.setBlockState(top.down(depth), Blocks.STONE.getDefaultState(), 3);
                }
            }
        }

        // 箱子（中间岛前两个放 3 格高石砖柱上）
        java.util.List<BlockPos> chests = island.chests();
        for (int i = 0; i < chests.size(); i++) {
            BlockPos chestPos = chests.get(i);
            int y;
            if (middle && i < 2) {
                // 立柱：Y+1、Y+2 石砖，箱子在 Y+3
                world.setBlockState(new BlockPos(chestPos.getX(), c.getY() + 1, chestPos.getZ()), Blocks.STONE_BRICKS.getDefaultState(), 3);
                world.setBlockState(new BlockPos(chestPos.getX(), c.getY() + 2, chestPos.getZ()), Blocks.STONE_BRICKS.getDefaultState(), 3);
                y = c.getY() + 3;
            } else {
                y = chestPos.getY();
            }
            BlockPos pos = new BlockPos(chestPos.getX(), y, chestPos.getZ());
            world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof ChestBlockEntity chest) {
                SkyWarsLoot.populate(chest, middle);
            }
        }

        // 出生岛偶发一棵小橡树（放到离岛心 ≥3 格处，避免玩家出生卡进树干/树叶里）
        if (!middle && r >= 5 && random.nextInt(3) == 0) {
            int treeDist = 3 + random.nextInt(Math.max(1, r - 4));
            double ta = random.nextDouble() * 2.0 * Math.PI;
            BlockPos treeBase = new BlockPos(
                    c.getX() + (int) Math.round(Math.cos(ta) * treeDist),
                    c.getY(),
                    c.getZ() + (int) Math.round(Math.sin(ta) * treeDist));
            buildSmallTree(world, random, treeBase);
        }
    }

    /** 铺一棵 2~3 格树干 + 叶团的小橡树。 */
    private static void buildSmallTree(ArenaWorld world, Random random, BlockPos base) {
        int trunk = 2 + random.nextInt(2);
        for (int i = 1; i <= trunk; i++) {
            world.setBlockState(base.up(i), Blocks.OAK_LOG.getDefaultState(), 3);
        }
        int topY = base.getY() + trunk;
        int leafR = 2;
        for (int dx = -leafR; dx <= leafR; dx++) {
            for (int dz = -leafR; dz <= leafR; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (Math.abs(dx) == leafR && Math.abs(dz) == leafR && dy != 0) {
                        continue; // 只留圆角
                    }
                    BlockPos p = new BlockPos(base.getX() + dx, topY + dy, base.getZ() + dz);
                    if (world.getBlockState(p).isAir()) {
                        world.setBlockState(p, Blocks.OAK_LEAVES.getDefaultState(), 3);
                    }
                }
            }
        }
        world.setBlockState(new BlockPos(base.getX(), topY + 1, base.getZ()), Blocks.OAK_LEAVES.getDefaultState(), 3);
    }

    /** 缩圈：把水平距离大于 keepRadius 的所有方块（含岛、立柱、箱子）清为空气。 */
    public static void removeRing(ArenaWorld world, BlockPos center, int maxRadius, int keepRadius) {
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist <= keepRadius) {
                    continue;
                }
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                for (int dy = -ISLAND_DEPTH; dy <= 6; dy++) {
                    BlockPos p = new BlockPos(x, ArenaTemplate.PLATFORM_Y + dy, z);
                    if (!world.getBlockState(p).isAir()) {
                        world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
                    }
                }
            }
        }
    }

    /** 清空一场空岛战争的全部地形（方块 + 掉落物），供赛后清理复用。 */
    public static void clearIslands(ArenaWorld world, int regionIndex, int size) {
        BlockPos origin = new BlockPos(regionIndex * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);

        // 先拆方块：箱子被拆掉时会把里面战利品掉落成实体，
        // 所以必须先拆块、再清掉落物，否则箱子内容会残留在地上
        // 清场高度取平台下方 3 格到上方 6 格（覆盖岛体/立柱/小树顶部）
        // 大图大部分是虚空空气，跳过空气大幅降低清场耗时
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                for (int dy = -ISLAND_DEPTH - 1; dy <= 6; dy++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (!world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                    }
                }
            }
        }

        // 再清掉落物（含拆箱掉出来的战利品与玩家淘汰时的掉落）
        Box box = new Box(
                origin.getX(), origin.getY() - ISLAND_DEPTH - 1, origin.getZ(),
                origin.getX() + size, origin.getY() + 6, origin.getZ() + size
        );
        for (ItemEntity entity : world.getEntitiesByClass(ItemEntity.class, box, e -> true)) {
            entity.discard();
        }
    }

    /** 空岛地图中心：与平台中心一致，便于复用传送/faceCenter 等逻辑。 */
    public static BlockPos center(int regionIndex) {
        BlockPos origin = new BlockPos(regionIndex * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        return new BlockPos(origin.getX() + PvPConfig.INSTANCE.skywarsSize / 2,
                ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + PvPConfig.INSTANCE.skywarsSize / 2);
    }
}
