package com.example.pvp.arena.skywars;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.config.PvPConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;

import java.util.List;
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

    /** 生成一整张空岛地图（含战利品），并返回布局（出生点已在 Match 构造时算好）。主题由 seed 抽取。 */
    public static SkyWarsLayout generate(ArenaWorld world, int regionIndex, int seed, int playerCount,
                                         List<ServerPlayerEntity> players) {
        BlockPos mapCenter = center(regionIndex);
        SkyWarsLayout layout = SkyWarsLayout.compute(mapCenter, seed, playerCount);
        SkyWarsTheme theme = SkyWarsTheme.pick(seed);

        // 出生岛/中途岛按玩家索引归属：本局胜率最低的 1~2 名获得轻微的装备/神器提升
        int[] handicaps = players == null
                ? new int[Math.max(0, playerCount)]
                : SkyWarsLoot.handicapForMatch(players);
        List<SkyWarsLayout.Island> spawnIslands = layout.spawnIslands();
        for (int i = 0; i < spawnIslands.size(); i++) {
            buildIsland(world, spawnIslands.get(i), false, theme, handicaps[i], false);
        }
        List<SkyWarsLayout.Island> midIslands = layout.midIslands();
        for (int i = 0; i < midIslands.size(); i++) {
            buildIsland(world, midIslands.get(i), false, theme, handicaps[i], false);
        }
        // 中岛群：中央主岛（END 主题才空心环）+ 卫星岛（实心），各带障碍物
        List<SkyWarsLayout.Island> middleIslands = layout.middleIslands();
        for (int i = 0; i < middleIslands.size(); i++) {
            buildIsland(world, middleIslands.get(i), true, theme, 0, i == 0);
        }

        LOGGER.info("[PvP] 空岛战争地图已生成: {} 个出生岛 + {} 个中途岛 + 中间主岛({} 箱)，主题：{}",
                spawnIslands.size(), midIslands.size(), layout.middle().chests().size(),
                theme.getDisplayName());
        return layout;
    }

    /** 铺一座岛（按主题选材质 + 箱子 + 偶发装饰）；中间岛前两个箱子放在石砖柱上；中岛群带障碍物。 */
    private static void buildIsland(ArenaWorld world, SkyWarsLayout.Island island, boolean middle,
                                    SkyWarsTheme theme, int handicap, boolean ring) {
        Random random = new Random(island.center.hashCode());
        int r = island.radius;
        BlockPos c = island.center;
        Block top = theme.topBlock(), sub = theme.subBlock(), deep = theme.deepBlock();
        boolean applyRing = ring && theme.ringMiddle(); // 只有中央主岛才空心环
        int innerR = applyRing ? (int) (r * theme.ringInnerRatio()) : 0;
        // 地狱岛面随机危害：灵魂沙/岩浆（只放玩家岛，避免中岛太混乱）
        int soulSandLeft = theme == SkyWarsTheme.NETHER ? 3 + random.nextInt(3) : 0;
        int lavaLeft = theme == SkyWarsTheme.NETHER ? 1 + random.nextInt(2) : 0;

        // 圆台：按主题铺表层/次层/深层；末地中岛为空心环；地狱岛面随机刷灵魂沙/岩浆
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r) {
                    continue;
                }
                if (applyRing && dist < innerR) {
                    continue; // 空心环：中央不铺块
                }
                BlockPos topPos = new BlockPos(c.getX() + dx, c.getY(), c.getZ() + dz);
                Block topBlock = top;
                if (!middle && theme == SkyWarsTheme.NETHER) {
                    int roll = random.nextInt(100);
                    if (roll < 4 && soulSandLeft > 0) {
                        topBlock = Blocks.SOUL_SAND;
                        soulSandLeft--;
                    } else if (roll < 5 && lavaLeft > 0) {
                        topBlock = Blocks.LAVA;
                        lavaLeft--;
                    }
                }
                world.setBlockState(topPos, topBlock.getDefaultState(), 3);
                world.setBlockState(topPos.down(), sub.getDefaultState(), 3);
                for (int depth = 2; depth <= ISLAND_DEPTH; depth++) {
                    world.setBlockState(topPos.down(depth), deep.getDefaultState(), 3);
                }
            }
        }

        // 箱子（中间岛前两个放 3 格高石砖柱上；末地环上把落进空心的箱子挪到环内缘，避免浮空）
        List<BlockPos> chests = island.chests();
        for (int i = 0; i < chests.size(); i++) {
            BlockPos pos = islandChestPos(island, i, middle, ring, theme);
            if (middle && i < 2) {
                // 立柱：Y+1、Y+2 石砖，箱子在 Y+3
                world.setBlockState(pos.down(2), Blocks.STONE_BRICKS.getDefaultState(), 3);
                world.setBlockState(pos.down(), Blocks.STONE_BRICKS.getDefaultState(), 3);
            }
            world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof ChestBlockEntity chest) {
                SkyWarsLoot.populate(chest, middle, handicap);
            }
        }

        // 中岛群障碍物：石柱/蜘蛛网/水(岩浆)池/矮墙掩体，提供遮蔽与战术点
        if (middle) {
            addMiddleObstacles(world, random, island, theme);
        }

        // 主题装饰/树（放到离岛心 ≥3 格处，避免玩家出生卡进树干/树叶里）
        if (!middle && r >= 5 && random.nextInt(3) == 0) {
            int treeDist = 3 + random.nextInt(Math.max(1, r - 4));
            double ta = random.nextDouble() * 2.0 * Math.PI;
            BlockPos treeBase = new BlockPos(
                    c.getX() + (int) Math.round(Math.cos(ta) * treeDist),
                    c.getY(),
                    c.getZ() + (int) Math.round(Math.sin(ta) * treeDist));
            switch (theme) {
                case NETHER -> placeNetherFungi(world, random, treeBase);
                case ICE -> buildSmallTree(world, random, treeBase, Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES);
                case END -> buildChorus(world, random, treeBase);
                default -> buildSmallTree(world, random, treeBase, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
            }
        }
    }

    /**
     * 一座岛上一个箱子的实际落位（与 {@link #buildIsland} 完全一致）：
     * 末地中央主岛(空心环)会把落在环内的箱子沿角度外推至环内缘；中岛群前两个箱子上 3 格立柱。
     * 生成与"5 分钟物资刷新"共用，保证重刷时能找到生成时真正落位的箱子。
     */
    private static BlockPos islandChestPos(SkyWarsLayout.Island island, int chestIndex, boolean middle,
                                           boolean ring, SkyWarsTheme theme) {
        BlockPos chestPos = island.chests().get(chestIndex);
        int cx = chestPos.getX();
        int cz = chestPos.getZ();
        int cX = island.center.getX();
        int cZ = island.center.getZ();
        boolean applyRing = ring && theme.ringMiddle();
        int innerR = applyRing ? (int) (island.radius * theme.ringInnerRatio()) : 0;
        if (ring) {
            double d = Math.hypot(cx - cX, cz - cZ);
            if (d < innerR + 1) {
                double ang = Math.atan2(cz - cZ, cx - cX);
                cx = cX + (int) Math.round(Math.cos(ang) * (innerR + 1));
                cz = cZ + (int) Math.round(Math.sin(ang) * (innerR + 1));
            }
        }
        int y = (middle && chestIndex < 2) ? island.center.getY() + 3 : chestPos.getY();
        return new BlockPos(cx, y, cz);
    }

    /**
     * "5 分钟物资刷新"事件：清空并重新塞满全图所有箱子（含已被开过的）。
     * 需要与生成时相同的布局/主题/弱势补偿，逐岛按生成顺序定位箱子并重填战利品；
     * 被玩家/缩圈拆掉的箱子不复活（找不到方块实体即跳过）。
     *
     * @param players 本场原始玩家列表（重新计算弱势补偿，结果与生成时一致）
     */
    public static void refillChests(ArenaWorld world, SkyWarsLayout layout, SkyWarsTheme theme,
                                    List<ServerPlayerEntity> players) {
        if (world == null || layout == null) {
            return;
        }
        int[] handicaps = players == null ? new int[0] : SkyWarsLoot.handicapForMatch(players);

        List<SkyWarsLayout.Island> spawnIslands = layout.spawnIslands();
        for (int i = 0; i < spawnIslands.size(); i++) {
            refillIslandChests(world, spawnIslands.get(i), false, false, theme, handicapAt(handicaps, i));
        }
        List<SkyWarsLayout.Island> midIslands = layout.midIslands();
        for (int i = 0; i < midIslands.size(); i++) {
            refillIslandChests(world, midIslands.get(i), false, false, theme, handicapAt(handicaps, i));
        }
        List<SkyWarsLayout.Island> middleIslands = layout.middleIslands();
        for (int i = 0; i < middleIslands.size(); i++) {
            refillIslandChests(world, middleIslands.get(i), true, i == 0, theme, 0);
        }
    }

    private static int handicapAt(int[] handicaps, int i) {
        return i >= 0 && i < handicaps.length ? handicaps[i] : 0;
    }

    private static void refillIslandChests(ArenaWorld world, SkyWarsLayout.Island island, boolean middle,
                                           boolean ring, SkyWarsTheme theme, int handicap) {
        for (int i = 0; i < island.chests().size(); i++) {
            BlockPos pos = islandChestPos(island, i, middle, ring, theme);
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof ChestBlockEntity chest) {
                chest.clear(); // 清空旧物资，再重新塞满全新随机战利品
                SkyWarsLoot.populate(chest, middle, handicap);
            }
        }
    }

    /** 中岛群障碍物：石柱/蜘蛛网/水(岩浆)池/矮墙掩体——随机放在岛面上（避开箱子）。 */
    private static void addMiddleObstacles(ArenaWorld world, Random random, SkyWarsLayout.Island island, SkyWarsTheme theme) {
        int count = 1 + random.nextInt(2); // 每座中岛 1~2 个障碍
        for (int i = 0; i < count; i++) {
            int r = island.radius;
            if (r < 3) {
                continue;
            }
            int dist = 2 + random.nextInt(Math.max(1, r - 2));
            double angle = random.nextDouble() * 2.0 * Math.PI;
            int x = island.center.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = island.center.getZ() + (int) Math.round(Math.sin(angle) * dist);
            boolean onChest = false;
            for (BlockPos chest : island.chests()) {
                if (chest.getX() == x && chest.getZ() == z) {
                    onChest = true;
                    break;
                }
            }
            if (onChest) {
                continue;
            }
            int y = island.center.getY();
            switch (random.nextInt(4)) {
                case 0 -> {
                    // 石柱（掩体）：2~4 格石砖柱
                    int h = 2 + random.nextInt(3);
                    for (int dy = 1; dy <= h; dy++) {
                        world.setBlockState(new BlockPos(x, y + dy, z), Blocks.STONE_BRICKS.getDefaultState(), 3);
                    }
                }
                case 1 -> {
                    // 蜘蛛网（减速）：1~2 个
                    for (int k = 0; k < 2; k++) {
                        world.setBlockState(new BlockPos(x, y + 1 + random.nextInt(2), z), Blocks.COBWEB.getDefaultState(), 3);
                    }
                }
                case 2 -> {
                    // 水/岩浆浅池：挖掉表层，下面填液体（地狱→岩浆，其余→水）
                    boolean lava = theme == SkyWarsTheme.NETHER;
                    world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState(), 3);
                    world.setBlockState(new BlockPos(x, y - 1, z),
                            (lava ? Blocks.LAVA : Blocks.WATER).getDefaultState(), 3);
                }
                default -> {
                    // 矮墙（3 格宽、2 格高石砖），作掩体
                    for (int k = -1; k <= 1; k++) {
                        world.setBlockState(new BlockPos(x + k, y + 1, z), Blocks.STONE_BRICKS.getDefaultState(), 3);
                        world.setBlockState(new BlockPos(x + k, y + 2, z), Blocks.STONE_BRICKS.getDefaultState(), 3);
                    }
                }
            }
        }
    }

    /** 铺一棵 2~3 格树干 + 叶团的小树（主世界橡树 / 冰原云杉树）。 */
    private static void buildSmallTree(ArenaWorld world, Random random, BlockPos base, Block log, Block leaves) {
        int trunk = 2 + random.nextInt(2);
        for (int i = 1; i <= trunk; i++) {
            world.setBlockState(base.up(i), log.getDefaultState(), 3);
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
                        world.setBlockState(p, leaves.getDefaultState(), 3);
                    }
                }
            }
        }
        world.setBlockState(new BlockPos(base.getX(), topY + 1, base.getZ()), leaves.getDefaultState(), 3);
    }

    /** 地狱装饰：在地面附近放 1~2 个绯红/诡异菌。 */
    private static void placeNetherFungi(ArenaWorld world, Random random, BlockPos base) {
        for (int i = 0; i < 2; i++) {
            BlockPos p = base.add(random.nextInt(3) - 1, 0, random.nextInt(3) - 1);
            if (world.getBlockState(p).isAir()) {
                world.setBlockState(p, random.nextBoolean()
                        ? Blocks.CRIMSON_FUNGUS.getDefaultState() : Blocks.WARPED_FUNGUS.getDefaultState(), 3);
            }
        }
    }

    /** 末地装饰：一株小紫颂植物。 */
    private static void buildChorus(ArenaWorld world, Random random, BlockPos base) {
        int h = 2 + random.nextInt(2);
        for (int i = 1; i <= h; i++) {
            world.setBlockState(base.up(i), Blocks.CHORUS_PLANT.getDefaultState(), 3);
        }
        world.setBlockState(base.up(h + 1), Blocks.CHORUS_FLOWER.getDefaultState(), 3);
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
                for (int dy = -ISLAND_DEPTH - 3; dy <= 9; dy++) {
                    BlockPos p = new BlockPos(x, ArenaTemplate.PLATFORM_Y + dy, z);
                    if (!world.getBlockState(p).isAir()) {
                        world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
                    }
                }
            }
        }
    }

    /**
     * 清空一场空岛战争的全部地形（方块 + 掉落物），供赛后清理复用。
     * 范围按「地图实际最大半径」居中清除，不依赖 skywarsSize 边界——
     * 即使配置里 size 偏小、岛屿落在 size 框外，也能清干净，避免箱子/岛屿残留。
     *
     * @param maxRadius 该场比赛生成时的最大半径（来自 SkyWarsLayout）；<=0 时按当前配置兜底计算
     */
    public static void clearIslands(ArenaWorld world, int regionIndex, int maxRadius) {
        if (maxRadius <= 0) {
            maxRadius = SkyWarsLayout.computeMaxRadius();
        }
        BlockPos center = center(regionIndex); // 与生成时一致的地图中心

        // 先拆方块：箱子被拆掉时会把里面战利品掉落成实体，
        // 所以必须先拆块、再清掉落物，否则箱子内容会残留在地上
        // 大图大部分是虚空空气，跳过空气降低耗时；高度清到世界最高可搭建 Y（玩家可能向上搭很高的塔）
        int maxDy = world.getTopY() - 1 - ArenaTemplate.PLATFORM_Y;
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                for (int dy = -16; dy <= maxDy; dy++) {
                    BlockPos pos = new BlockPos(center.getX() + dx,
                            ArenaTemplate.PLATFORM_Y + dy, center.getZ() + dz);
                    if (!world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                    }
                }
            }
        }

        // 再清掉落物（含拆箱掉出来的战利品与玩家淘汰时的掉落）
        Box box = new Box(
                center.getX() - maxRadius, ArenaTemplate.PLATFORM_Y - 16, center.getZ() - maxRadius,
                center.getX() + maxRadius + 1, ArenaTemplate.PLATFORM_Y + maxDy, center.getZ() + maxRadius + 1
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
