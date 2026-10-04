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
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.TreeConfiguredFeatures;
import org.slf4j.Logger;

import java.util.ArrayList;
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
    /**
     * 出生点（岛心）周围保留的干净半径的平方。
     * 玩家出生在岛心正上方，地狱主题的随机岩浆若刷在岛心会把玩家直接泡进岩浆里。
     */
    private static final int SPAWN_CLEAR_RADIUS_SQ = 4;
    /** 中岛上的树与箱子/救回点之间至少留的距离（切比雪夫，树冠半径 2 + 1 格余量）。 */
    private static final int TREE_CLEARANCE = 3;
    /** 树与树之间的最小距离：挨太近树冠会糊成一大片，既挡视线也看不出是几棵树。 */
    private static final int TREE_SPACING = 7;
    /**
     * 主世界中岛可选的原版树种（都是高个子）：
     * {@code FANCY_OAK} 大型橡树（带侧枝，7~11 格）、{@code PINE} 高大云杉（10 格上下）。
     */
    private static final List<RegistryKey<ConfiguredFeature<?, ?>>> MIDDLE_TREE_POOL = List.of(
            TreeConfiguredFeatures.FANCY_OAK,
            TreeConfiguredFeatures.FANCY_OAK, // 大橡树多一份，主世界的中岛还是橡树最搭
            TreeConfiguredFeatures.PINE
    );

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

        LOGGER.info("[PvP] 空岛战争地图已生成: {} 个出生岛 + {} 个中途岛 + 中间主岛({} 箱)，主题：{}，中岛核心：{}",
                spawnIslands.size(), midIslands.size(), layout.middle().chests().size(),
                theme.getDisplayName(), coreDesc(theme));
        return layout;
    }

    /** 铺一座岛（按主题选材质 + 箱子 + 偶发装饰）；中间岛前两个箱子放在石砖柱上；中岛群带障碍物。 */
    private static void buildIsland(ArenaWorld world, SkyWarsLayout.Island island, boolean middle,
                                    SkyWarsTheme theme, int handicap, boolean ring) {
        Random random = new Random(island.center.hashCode());
        int r = island.radius;
        BlockPos c = island.center;
        Block top = theme.topBlock(), sub = theme.subBlock(), deep = theme.deepBlock();
        // 只有中央主岛才有「核心」：末地=空心虚空，冰原=水池，地狱=岩浆池
        boolean applyCore = ring && theme.hasMiddleCore();
        int innerR = applyCore ? (int) (r * theme.middleCoreRatio()) : 0;
        Block coreFluid = applyCore ? theme.middleCoreFluid() : null; // null = 空心，不铺块
        // 地狱岛面随机危害：灵魂沙/岩浆（只放玩家岛，避免中岛太混乱）
        int soulSandLeft = theme == SkyWarsTheme.NETHER ? 3 + random.nextInt(3) : 0;
        int lavaLeft = theme == SkyWarsTheme.NETHER ? 1 + random.nextInt(2) : 0;

        // 圆台：按主题铺表层/次层/深层；中岛核心见上；地狱岛面随机刷灵魂沙/岩浆
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r) {
                    continue;
                }
                if (applyCore && dist < innerR && coreFluid == null) {
                    continue; // 末地：正中挖穿，下方是虚空
                }
                BlockPos topPos = new BlockPos(c.getX() + dx, c.getY(), c.getZ() + dz);
                // 冰原/地狱：正中只把表层换成水/岩浆，下面两层照铺，池底是实心地面
                Block topBlock = (applyCore && dist < innerR) ? coreFluid : top;
                // 岛心留一圈干净地面：出生点在岛心正上方，刷岩浆会把玩家出生就泡进岩浆
                if (!middle && theme == SkyWarsTheme.NETHER
                        && dx * dx + dz * dz > SPAWN_CLEAR_RADIUS_SQ) {
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

        // 箱子的实际落位先算出来：树要避开箱子，下面的箱子循环也用这同一份坐标
        List<BlockPos> placedChests = new ArrayList<>(island.chests().size());
        for (int i = 0; i < island.chests().size(); i++) {
            placedChests.add(islandChestPos(island, i, middle, ring, theme));
        }

        // 主题装饰/树（放到离岛心 ≥3 格处，避免玩家出生卡进树干/树叶里）。
        // 必须排在放箱子之前：树干/紫颂是直接 setBlock 的，后放会把箱子顶掉，箱子凭空少一个。
        List<BlockPos> trees = new ArrayList<>();
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
        } else if (middle && ring && theme == SkyWarsTheme.OVERWORLD) {
            // 主世界的中岛群主岛种一片高树（原版树木生成器），既是掩体也让中岛不再是一片光地
            trees.addAll(plantVanillaTrees(world, random, island, theme, placedChests));
        }

        // 箱子（中间岛前两个放 3 格高石砖柱上；末地环上把落进空心的箱子挪到环内缘，避免浮空）
        for (int i = 0; i < placedChests.size(); i++) {
            BlockPos pos = placedChests.get(i);
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

        // 中岛群障碍物：石柱/蜘蛛网/水(岩浆)池/矮墙掩体，提供遮蔽与战术点（避开箱子与树）
        if (middle) {
            placedChests.addAll(trees);
            addMiddleObstacles(world, random, island, theme, ring, placedChests);
        }
    }

    /**
     * 一座岛上一个箱子的实际落位（与 {@link #buildIsland} 完全一致）：
     * 末地中央主岛(空心环)会把落在环内的箱子沿角度外推至环内缘；中岛群前两个箱子上 3 格立柱。
     * 生成与"物资刷新"共用，保证重刷时能找到生成时真正落位的箱子。
     */
    private static BlockPos islandChestPos(SkyWarsLayout.Island island, int chestIndex, boolean middle,
                                           boolean ring, SkyWarsTheme theme) {
        BlockPos chestPos = island.chests().get(chestIndex);
        int cx = chestPos.getX();
        int cz = chestPos.getZ();
        int cX = island.center.getX();
        int cZ = island.center.getZ();
        boolean applyCore = ring && theme.hasMiddleCore();
        int innerR = applyCore ? (int) (island.radius * theme.middleCoreRatio()) : 0;
        if (ring) {
            // 落在核心（虚空/水/岩浆）里的箱子一律推到核心外缘，避免浮空或泡在液体里。
            // 再按箱子序号错开角度：两个原本落在核心里的箱子可能在取整后撞到同一格，
            // 那样生成时又会被覆盖掉一个。这里按序号散开，周长足够不会重叠。
            double d = Math.hypot(cx - cX, cz - cZ);
            if (d < innerR + 1) {
                double ang = Math.atan2(cz - cZ, cx - cX) + chestIndex * 0.7;
                cx = cX + (int) Math.round(Math.cos(ang) * (innerR + 1));
                cz = cZ + (int) Math.round(Math.sin(ang) * (innerR + 1));
            }
        }
        int y = (middle && chestIndex < 2) ? island.center.getY() + 3 : chestPos.getY();
        return new BlockPos(cx, y, cz);
    }

    /**
     * "物资刷新"事件：清空并重新塞满全图所有箱子（含已被开过的）。
     * 需要与生成时相同的布局/主题/弱势补偿，逐岛按生成顺序定位箱子并重填战利品；
     * 被玩家/缩圈拆掉的箱子不复活（找不到方块实体即跳过）。
     *
     * @param players 本场原始玩家列表（重新计算弱势补偿，结果与生成时一致）
     */
    /**
     * 定时补货。
     *
     * @return {@code {找到的箱子数, 补货前就已经有物资的箱子数}}。第二个数是个很有用的自检：
     *         正常情况下开局那次装填已经把箱子塞满了，所以第一次补货时它应该 &gt; 0；
     *         如果恒为 0，说明"开局装填"没生效（历史上空岛箱子变空就是这个原因）。
     */
    public static int[] refillChests(ArenaWorld world, SkyWarsLayout layout, SkyWarsTheme theme,
                                     List<ServerPlayerEntity> players) {
        if (world == null || layout == null) {
            return new int[]{0, 0};
        }
        int[] handicaps = players == null ? new int[0] : SkyWarsLoot.handicapForMatch(players);
        int[] stats = new int[2];

        List<SkyWarsLayout.Island> spawnIslands = layout.spawnIslands();
        for (int i = 0; i < spawnIslands.size(); i++) {
            refillIslandChests(world, spawnIslands.get(i), false, false, theme, handicapAt(handicaps, i), stats);
        }
        List<SkyWarsLayout.Island> midIslands = layout.midIslands();
        for (int i = 0; i < midIslands.size(); i++) {
            refillIslandChests(world, midIslands.get(i), false, false, theme, handicapAt(handicaps, i), stats);
        }
        List<SkyWarsLayout.Island> middleIslands = layout.middleIslands();
        for (int i = 0; i < middleIslands.size(); i++) {
            refillIslandChests(world, middleIslands.get(i), true, i == 0, theme, 0, stats);
        }
        return stats;
    }

    private static int handicapAt(int[] handicaps, int i) {
        return i >= 0 && i < handicaps.length ? handicaps[i] : 0;
    }

    private static void refillIslandChests(ArenaWorld world, SkyWarsLayout.Island island, boolean middle,
                                           boolean ring, SkyWarsTheme theme, int handicap, int[] stats) {
        for (int i = 0; i < island.chests().size(); i++) {
            BlockPos pos = islandChestPos(island, i, middle, ring, theme);
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof ChestBlockEntity chest) {
                stats[0]++;
                if (!chest.isEmpty()) {
                    stats[1]++;
                }
                chest.clear(); // 清空旧物资，再重新塞满全新随机战利品
                SkyWarsLoot.populate(chest, middle, handicap);
            }
        }
    }

    /**
     * 中岛群障碍物：石柱/蜘蛛网/水(岩浆)池/矮墙掩体——随机放在岛面上
     * （避开箱子/树、中岛核心与救回点）。
     *
     * @param reserved 不许被盖住的格子（箱子 + 树的实际落位）。矮墙要占 3 格宽，
     *                 所以这里按切比雪夫距离 ≤1 整片避开，否则墙会把紧挨着的箱子顶掉。
     */
    private static void addMiddleObstacles(ArenaWorld world, Random random, SkyWarsLayout.Island island,
                                           SkyWarsTheme theme, boolean central, List<BlockPos> reserved) {
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
            // 用实际落位的坐标比：末地/冰原/地狱的箱子会被外推到核心边缘，不再是 island.chests() 里的原坐标
            if (isNear(new BlockPos(x, island.center.getY(), z), reserved, 1)) {
                continue;
            }
            // 中央主岛：不打在核心（虚空/水/岩浆）里，也不堵住不死图腾的救回点
            if (central && theme.hasMiddleCore()) {
                int coreR = (int) (r * theme.middleCoreRatio());
                if (Math.hypot(x - island.center.getX(), z - island.center.getZ()) < coreR + 1) {
                    continue;
                }
                BlockPos rescue = theme.rescuePoint(island);
                if (Math.abs(x - rescue.getX()) <= 1 && Math.abs(z - rescue.getZ()) <= 1) {
                    continue;
                }
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

    /**
     * 用原版树木生成器在主岛（中岛群的中央岛）上种几棵树。
     *
     * <p>走原版 {@link ConfiguredFeature}（和树苗长成树是同一条路径），树干高度、树冠形状、
     * 树枝/树根土块全由原版逻辑随机生成，不用手写方块。
     *
     * @param reserved 必须避开的格子（箱子实际落位）
     * @return 实际种下的树苗位置（树干下方那一格的地面），供障碍物生成时避让
     */
    private static List<BlockPos> plantVanillaTrees(ArenaWorld world, Random random, SkyWarsLayout.Island island,
                                                    SkyWarsTheme theme, List<BlockPos> reserved) {
        Registry<ConfiguredFeature<?, ?>> registry =
                world.getRegistryManager().get(RegistryKeys.CONFIGURED_FEATURE);
        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        // 树形用独立随机源：不干扰本方法外那份按岛心哈希固定的布局随机
        net.minecraft.util.math.random.Random treeRandom =
                net.minecraft.util.math.random.Random.create(random.nextLong());

        int r = island.radius;
        int want = Math.max(2, Math.min(10, r * r / 110)); // 数量随岛面积走
        BlockPos rescue = theme.rescuePoint(island); // 主世界=岛心，图腾会把玩家救回这里
        List<BlockPos> bases = new ArrayList<>();
        for (int attempt = 0; attempt < want * 16 && bases.size() < want; attempt++) {
            int dist = 2 + random.nextInt(Math.max(1, r - 2));
            double angle = random.nextDouble() * 2.0 * Math.PI;
            BlockPos base = new BlockPos(
                    island.center.getX() + (int) Math.round(Math.cos(angle) * dist),
                    island.center.getY() + 1,
                    island.center.getZ() + (int) Math.round(Math.sin(angle) * dist));
            if (isNear(base, reserved, TREE_CLEARANCE) || isNear(base, bases, TREE_SPACING)) {
                continue;
            }
            // 救回点在主岛正中：树干/树冠别盖上去，否则图腾把玩家救回来会卡在树里
            if (Math.abs(base.getX() - rescue.getX()) <= TREE_CLEARANCE
                    && Math.abs(base.getZ() - rescue.getZ()) <= TREE_CLEARANCE) {
                continue;
            }
            if (!world.getBlockState(base).isAir() || !world.getBlockState(base.down()).isOf(theme.topBlock())) {
                continue; // 只能种在空着的岛面上
            }
            RegistryEntry<ConfiguredFeature<?, ?>> entry = registry
                    .getEntry(MIDDLE_TREE_POOL.get(random.nextInt(MIDDLE_TREE_POOL.size())))
                    .orElse(null);
            if (entry == null) {
                continue;
            }
            if (!entry.value().generate(world, generator, treeRandom, base)) {
                continue; // 空间不够/随机长失败，换个位置再试
            }
            bases.add(base);
        }
        LOGGER.info("[PvP] 空岛战争中岛种树: {}/{} 棵（{} 主题，原版树木生成器）",
                bases.size(), want, theme.getDisplayName());
        return bases;
    }

    /** 目标点到 {@code others} 里的任意格子是否在切比雪夫距离 {@code radius} 之内（只看水平方向）。 */
    private static boolean isNear(BlockPos pos, List<BlockPos> others, int radius) {
        for (BlockPos other : others) {
            if (Math.abs(other.getX() - pos.getX()) <= radius && Math.abs(other.getZ() - pos.getZ()) <= radius) {
                return true;
            }
        }
        return false;
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

    /** 中岛核心的人类可读描述（日志用）。 */
    private static String coreDesc(SkyWarsTheme theme) {
        if (!theme.hasMiddleCore()) {
            return "无";
        }
        Block fluid = theme.middleCoreFluid();
        return fluid == null ? "空心(虚空)" : fluid.getName().getString();
    }

    /** 空岛地图中心：与平台中心一致，便于复用传送/faceCenter 等逻辑。 */
    public static BlockPos center(int regionIndex) {
        BlockPos origin = new BlockPos(regionIndex * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        return new BlockPos(origin.getX() + PvPConfig.INSTANCE.skywarsSize / 2,
                ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + PvPConfig.INSTANCE.skywarsSize / 2);
    }
}
