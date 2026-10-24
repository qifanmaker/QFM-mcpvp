package com.example.pvp.arena.skywars;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.config.PvPConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
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

    /** 岛面下方挖深的层数（表层下增加 3 层主体，整体比原地图更厚）。 */
    private static final int ISLAND_DEPTH = 4;
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
     * 主世界主题中央主岛的「第二层」：比第一层高出 10 格、半径是第一层的 70%（略小一圈）。
     * 只给主世界主题，其他主题的中岛保持单层。
     */
    public static final int SECOND_LAYER_HEIGHT = 10;
    private static final double SECOND_LAYER_RADIUS_RATIO = 0.7;
    /** 第二层中心给不死图腾救回点留的空地半径（箱子要让开）。 */
    private static final int SECOND_LAYER_RESCUE_CLEAR = 3;
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
        return generate(world, regionIndex, seed, playerCount, players, SkyWarsMapVariant.CLASSIC);
    }

    /** 生成空岛地图；正式主空岛统一使用立体 Beta 地形，战斗规则仍由同一个 SKYWARS 模式处理。 */
    public static SkyWarsLayout generate(ArenaWorld world, int regionIndex, int seed, int playerCount,
                                         List<ServerPlayerEntity> players, SkyWarsMapVariant variant) {
        return generate(world, regionIndex, seed, playerCount, players, variant, null);
    }

    /**
     * 生成空岛地图，允许调用方传入已经算好的布局。
     *
     * <p><b>为什么要传布局</b>：立体地形上额外放的箱子会登记在 {@link SkyWarsLayout} 上，
     * 而"物资刷新"用的是 {@code Match.skywarsLayout}。如果这里再自己 {@code compute} 一份，
     * Beta 生成器登记的箱子会写在另一份布局上，刷新时永远找不到 —— 表现为
     * "新地形上的箱子刷新后还是空的"。所以正式对局直接把 Match 那份布局传进来，全局只留一份。
     */
    public static SkyWarsLayout generate(ArenaWorld world, int regionIndex, int seed, int playerCount,
                                         List<ServerPlayerEntity> players, SkyWarsMapVariant variant,
                                         SkyWarsLayout precomputedLayout) {
        return SkyWarsBetaMapGenerator.generate(world, regionIndex, seed, playerCount, players, precomputedLayout);
    }

    static SkyWarsLayout generateClassic(ArenaWorld world, int regionIndex, int seed, int playerCount,
                                         List<ServerPlayerEntity> players, SkyWarsLayout precomputedLayout) {
        BlockPos mapCenter = center(regionIndex);
        SkyWarsLayout layout = precomputedLayout != null
                ? precomputedLayout
                : SkyWarsLayout.compute(mapCenter, seed, playerCount);
        SkyWarsTheme theme = SkyWarsTheme.pick(seed);

        List<SkyWarsLayout.Island> spawnIslands = layout.spawnIslands();
        for (int i = 0; i < spawnIslands.size(); i++) {
            buildIsland(world, spawnIslands.get(i), false, theme, false, layout.styleSeed() ^ 0x195bL);
        }
        List<SkyWarsLayout.Island> midIslands = layout.midIslands();
        for (int i = 0; i < midIslands.size(); i++) {
            buildIsland(world, midIslands.get(i), false, theme, false, layout.styleSeed() ^ 0x69f1L);
        }
        // 中岛群：中央主岛（END 主题才空心环）+ 卫星岛（实心），各带障碍物
        List<SkyWarsLayout.Island> middleIslands = layout.middleIslands();
        for (int i = 0; i < middleIslands.size(); i++) {
            buildIsland(world, middleIslands.get(i), true, theme, i == 0, layout.styleSeed() ^ (0x783aL + i));
        }

        // 村庄地图：中岛整座岛铺成村庄（原版房屋模板 + 道路 + 原版箱子）
        if (theme == SkyWarsTheme.VILLAGE) {
            SkyWarsVillageGenerator.build(world, layout, theme, seed);
        }

        LOGGER.info("[PvP] 空岛战争地图已生成: {} 个出生岛 + {} 个中途岛 + 中间主岛({} 箱)，主题：{}，中岛变体：{}，中岛核心：{}",
                spawnIslands.size(), midIslands.size(), layout.middle().chests().size(),
                theme.getDisplayName(), layout.middleVariant(), coreDesc(theme));
        return layout;
    }

    /** 铺一座岛（按主题选材质 + 箱子 + 偶发装饰）；中间岛前两个箱子放在石砖柱上；中岛群带障碍物。 */
    private static void buildIsland(ArenaWorld world, SkyWarsLayout.Island island, boolean middle,
                                    SkyWarsTheme theme, boolean ring, long decorationSeed) {
        Random random = new Random(decorationSeed);
        int r = island.radius;
        BlockPos c = island.center;
        Block top = theme.topBlock(), sub = theme.subBlock(), deep = theme.deepBlock();
        // 只有中央主岛才有「核心」：末地=空心虚空，冰原=水池，地狱=岩浆池
        boolean applyCore = ring && theme.hasMiddleCore();
        int innerR = applyCore ? (int) (r * theme.middleCoreRatio()) : 0;
        Block coreFluid = applyCore ? theme.middleCoreFluid() : null; // null = 空心，不铺块
        // 外岛保留灵魂沙地形，移除开局随机岩浆，避免同局某个出生位额外承受伤害风险。
        int soulSandLeft = theme == SkyWarsTheme.NETHER ? 3 + random.nextInt(3) : 0;

        // 圆台：按主题铺表层/次层/深层；中岛核心见上。
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
                // 岛心留一圈普通地面，保证出生时不会陷入减速方块。
                if (!middle && theme == SkyWarsTheme.NETHER
                        && dx * dx + dz * dz > SPAWN_CLEAR_RADIUS_SQ) {
                    int roll = random.nextInt(100);
                    if (roll < 4 && soulSandLeft > 0) {
                        topBlock = Blocks.SOUL_SAND;
                        soulSandLeft--;
                    }
                }
                world.setBlockState(topPos, topBlock.getDefaultState(), 3);
                world.setBlockState(topPos.down(), sub.getDefaultState(), 3);
                for (int depth = 2; depth <= ISLAND_DEPTH; depth++) {
                    world.setBlockState(topPos.down(depth), deep.getDefaultState(), 3);
                }
            }
        }

        if (!middle) {
            // 给矿工少量可挖资源；各出生位数量相同，不把矿藏变成额外随机装备礼包。
            world.setBlockState(c.add(2, -2, 0), Blocks.IRON_ORE.getDefaultState(), 3);
            world.setBlockState(c.add(-2, -2, 0), Blocks.IRON_ORE.getDefaultState(), 3);
            world.setBlockState(c.add(0, -2, 2), Blocks.COAL_ORE.getDefaultState(), 3);
            world.setBlockState(c.add(0, -2, -2), Blocks.COAL_ORE.getDefaultState(), 3);
        }

        // 主世界主题：中央主岛先铺好第二层，箱子落位才能知道自己在第一层还是第二层
        int tierRadius = secondLayerRadius(island, middle, ring, theme);
        if (tierRadius > 0) {
            buildSecondLayer(world, island, tierRadius, theme);
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
            world.setBlockState(pos.up(), Blocks.AIR.getDefaultState(), 3);
        }

        // 中岛群障碍物：石柱/蜘蛛网/水(岩浆)池/矮墙掩体，提供遮蔽与战术点（避开箱子与树）
        // 村庄地图的中岛由村庄生成器接管（房屋本身就是掩体），不再撒随机障碍
        if (middle && theme != SkyWarsTheme.VILLAGE) {
            placedChests.addAll(trees);
            addMiddleObstacles(world, random, island, theme, ring, placedChests);
        }
    }

    /**
     * 主世界主题中央主岛的第二层半径；返回 0 表示这座岛没有第二层。
     *
     * <p>只有"中央主岛"（{@code middle && ring}）且主题为主世界时才加：地狱/冰原/末地的中岛保持单层，
     * 避免缩小地图主题之间的差异（第二层是主世界这条大平岛的立体化补偿）。
     */
    static int secondLayerRadius(SkyWarsLayout.Island island, boolean middle, boolean ring, SkyWarsTheme theme) {
        if (!middle || !ring || theme != SkyWarsTheme.OVERWORLD) {
            return 0;
        }
        // 半径取第一层的 70%：看起来是"小一圈的第二层"，又不至于缩成一个塔尖
        return Math.max(6, (int) Math.round(island.radius * SECOND_LAYER_RADIUS_RATIO));
    }

    /**
     * 第二层只铺两层厚的平台，保留下方可通行空间；连接坡道由立体生成器统一生成。
     */
    private static void buildSecondLayer(ArenaWorld world, SkyWarsLayout.Island island, int tierRadius,
                                         SkyWarsTheme theme) {
        BlockPos c = island.center;
        Block top = theme.topBlock();
        Block sub = theme.subBlock();
        for (int dx = -tierRadius; dx <= tierRadius; dx++) {
            for (int dz = -tierRadius; dz <= tierRadius; dz++) {
                if (dx * dx + dz * dz > tierRadius * tierRadius) {
                    continue;
                }
                for (int dy = SECOND_LAYER_HEIGHT - 1; dy <= SECOND_LAYER_HEIGHT; dy++) {
                    Block block = dy == SECOND_LAYER_HEIGHT ? top : sub;
                    world.setBlockState(c.add(dx, dy, dz), block.getDefaultState(), 3);
                }
            }
        }
    }

    /**
     * 不死图腾的救回点。
     *
     * <p>主世界选上层预留空地；其他主题避开核心液体或虚空。
     */
    public static BlockPos rescuePoint(SkyWarsLayout layout, SkyWarsTheme theme) {
        SkyWarsLayout.Island middle = layout == null ? null : layout.middle();
        if (middle == null) {
            return layout == null ? BlockPos.ORIGIN : layout.mapCenter();
        }
        if (theme == SkyWarsTheme.VILLAGE) {
            // 村庄中岛正中是喷泉，救回点落在旁边的广场空地（村庄生成器保证广场是净空的路面）
            return new BlockPos(middle.center().getX() + 7, middle.center().getY() + 1, middle.center().getZ());
        }
        if (secondLayerRadius(middle, true, true, theme) > 0) {
            return new BlockPos(middle.center().getX(),
                    middle.center().getY() + SECOND_LAYER_HEIGHT + 1,
                    middle.center().getZ());
        }
        return theme.rescuePoint(middle);
    }

    /**
     * 一座岛上一个箱子的实际落位（与 {@link #buildIsland} 完全一致）：
     * 末地中央主岛(空心环)会把落在环内的箱子沿角度外推至环内缘；中岛群前两个箱子上 3 格立柱。
     * 生成与"物资刷新"共用，保证重刷时能找到生成时真正落位的箱子。
     */
    static BlockPos islandChestPos(SkyWarsLayout.Island island, int chestIndex, boolean middle,
                                           boolean ring, SkyWarsTheme theme) {
        BlockPos chestPos = island.chests().get(chestIndex);
        int cx = chestPos.getX();
        int cz = chestPos.getZ();
        int cX = island.center.getX();
        int cZ = island.center.getZ();
        boolean applyCore = ring && theme.hasMiddleCore();
        int innerR = applyCore ? (int) (island.radius * theme.middleCoreRatio()) : 0;
        int tierRadius = secondLayerRadius(island, middle, ring, theme);
        if (ring) {
            // 中央奖励按序号均分安全环，避免随机外推后重叠或落在液体/上下层的边缘。
            BlockPos first = island.chests().get(0);
            double angle = Math.atan2(first.getZ() - cZ, first.getX() - cX)
                    + chestIndex * Math.PI * 2.0 / island.chests().size();
            int distance = tierRadius > 0 ? Math.max(4, tierRadius - 2)
                    : Math.max(innerR + 2, (int) Math.round(island.radius * 0.65));
            cx = cX + (int) Math.round(Math.cos(angle) * distance);
            cz = cZ + (int) Math.round(Math.sin(angle) * distance);
        }
        boolean onSecondLayer = tierRadius > 0 && Math.hypot(cx - cX, cz - cZ) <= tierRadius;
        if (onSecondLayer) {
            // 第二层中心是图腾救回点：落在里面的箱子沿原角度推到空地外缘
            double d = Math.hypot(cx - cX, cz - cZ);
            if (d < SECOND_LAYER_RESCUE_CLEAR) {
                double ang = Math.atan2(cz - cZ, cx - cX);
                cx = cX + (int) Math.round(Math.cos(ang) * SECOND_LAYER_RESCUE_CLEAR);
                cz = cZ + (int) Math.round(Math.sin(ang) * SECOND_LAYER_RESCUE_CLEAR);
            }
        }
        int y;
        if (onSecondLayer) {
            y = island.center.getY() + SECOND_LAYER_HEIGHT + 1; // 站在第二层地面上
        } else {
            y = (middle && chestIndex < 2) ? island.center.getY() + 3 : chestPos.getY();
        }
        return new BlockPos(cx, y, cz);
    }

    /** 定时补货，返回找到的箱数和补货前非空箱数；被拆除的箱子不会重建。 */
    public static int[] refillChests(ArenaWorld world, SkyWarsLayout layout, SkyWarsTheme theme,
                                     List<ServerPlayerEntity> players) {
        return populateChests(world, layout, theme, players, true);
    }

    /**
     * 地形全部完成后再按整岛填箱，附加侧台不会额外复制一套保底装备。
     * 初次和补货共用布局持有的神器配额，刷新不能重新抽取整局稀有物品。
     */
    static int[] populateChests(ArenaWorld world, SkyWarsLayout layout, SkyWarsTheme theme,
                               List<ServerPlayerEntity> players, boolean refill) {
        if (world == null || layout == null) return new int[]{0, 0};
        int[] handicaps = players == null ? new int[0] : SkyWarsLoot.handicapForMatch(players);
        int[] stats = new int[2];
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        populateOuterIslands(world, layout, theme, layout.spawnIslands(),
                SkyWarsLootPolicy.Tier.SPAWN, handicaps, refill, stats, visited);
        populateOuterIslands(world, layout, theme, layout.midIslands(),
                SkyWarsLootPolicy.Tier.TRANSIT, handicaps, refill, stats, visited);

        List<ChestBlockEntity> centerChests = new ArrayList<>();
        for (int i = 0; i < layout.middleIslands().size(); i++) {
            collectIslandChests(world, layout.middleIslands().get(i), true, i == 0,
                    theme, centerChests, stats, visited);
        }
        for (SkyWarsLayout.ExtraChest extra : layout.extraChests()) {
            if (extra.tier() == SkyWarsLootPolicy.Tier.CENTER) {
                collectChest(world, extra.pos(), centerChests, stats, visited);
            }
        }
        SkyWarsLoot.populateIsland(centerChests, SkyWarsLootPolicy.Tier.CENTER, 0, refill, layout.lootPolicy());
        return stats;
    }

    private static void populateOuterIslands(ArenaWorld world, SkyWarsLayout layout, SkyWarsTheme theme,
                                             List<SkyWarsLayout.Island> islands, SkyWarsLootPolicy.Tier tier,
                                             int[] handicaps, boolean refill, int[] stats,
                                             java.util.Set<BlockPos> visited) {
        for (int i = 0; i < islands.size(); i++) {
            List<ChestBlockEntity> chests = new ArrayList<>();
            collectIslandChests(world, islands.get(i), false, false, theme, chests, stats, visited);
            for (SkyWarsLayout.ExtraChest extra : layout.extraChests()) {
                if (extra.tier() == tier && extra.handicapIndex() == i) {
                    collectChest(world, extra.pos(), chests, stats, visited);
                }
            }
            SkyWarsLoot.populateIsland(chests, tier, i < handicaps.length ? handicaps[i] : 0,
                    refill, layout.lootPolicy());
        }
    }

    private static void collectIslandChests(ArenaWorld world, SkyWarsLayout.Island island, boolean middle,
                                            boolean central, SkyWarsTheme theme, List<ChestBlockEntity> chests,
                                            int[] stats, java.util.Set<BlockPos> visited) {
        for (int i = 0; i < island.chests().size(); i++) {
            collectChest(world, islandChestPos(island, i, middle, central, theme), chests, stats, visited);
        }
    }

    private static void collectChest(ArenaWorld world, BlockPos pos, List<ChestBlockEntity> chests,
                                     int[] stats, java.util.Set<BlockPos> visited) {
        if (!visited.add(pos) || !(world.getBlockEntity(pos) instanceof ChestBlockEntity chest)) return;
        stats[0]++;
        // 先解除模板的延迟战利品，避免读取/清空箱子时重新生成原版村庄物资。
        chest.setLootTable(null);
        chest.setLootTableSeed(0L);
        if (!chest.isEmpty()) stats[1]++;
        chest.clear();
        chests.add(chest);
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
            // 上下层之间保留主要通路，第二层投影范围不放额外障碍。
            int tierRadius = secondLayerRadius(island, true, central, theme);
            if (tierRadius > 0 && Math.hypot(x - island.center.getX(), z - island.center.getZ()) <= tierRadius + 1) {
                continue;
            }
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

    /** 缩圈按整列清除，包含下层、深处自救平台与向上搭建的塔。 */
    public static void removeRing(ArenaWorld world, BlockPos center, int maxRadius, int keepRadius) {
        clearColumns(world, center, maxRadius, keepRadius);
    }

    /**
     * 只遍历非空section，覆盖全世界高度而不对大片虚空逐格扫描。
     * 必须取得完整区块，不能跳过已卸载区块，否则下次加载可能重新出现圈外地形。
     */
    private static void clearColumns(ArenaWorld world, BlockPos center, int maxRadius, int keepRadius) {
        int minX = center.getX() - maxRadius;
        int maxX = center.getX() + maxRadius;
        int minZ = center.getZ() - maxRadius;
        int maxZ = center.getZ() + maxRadius;
        long keepSquared = (long) keepRadius * keepRadius;
        for (int chunkX = Math.floorDiv(minX, 16); chunkX <= Math.floorDiv(maxX, 16); chunkX++) {
            for (int chunkZ = Math.floorDiv(minZ, 16); chunkZ <= Math.floorDiv(maxZ, 16); chunkZ++) {
                WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                ChunkSection[] sections = chunk.getSectionArray();
                for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                    ChunkSection section = sections[sectionIndex];
                    if (section == null || section.isEmpty()) continue;
                    int bottomY = world.sectionIndexToCoord(sectionIndex) << 4;
                    for (int x = Math.max(minX, chunkX << 4); x <= Math.min(maxX, (chunkX << 4) + 15); x++) {
                        for (int z = Math.max(minZ, chunkZ << 4); z <= Math.min(maxZ, (chunkZ << 4) + 15); z++) {
                            long dx = x - center.getX();
                            long dz = z - center.getZ();
                            if (keepRadius >= 0 && dx * dx + dz * dz <= keepSquared) continue;
                            for (int y = 0; y < 16; y++) {
                                if (!section.getBlockState(x & 15, y, z & 15).isAir()) {
                                    world.setBlockState(new BlockPos(x, bottomY + y, z), Blocks.AIR.getDefaultState(), 3);
                                }
                            }
                        }
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

        // 先拆箱再清掉落物，避免箱子 contents 在清实体后重新落到地上。
        clearColumns(world, center, maxRadius, -1);
        Box box = new Box(
                center.getX() - maxRadius, world.getBottomY(), center.getZ() - maxRadius,
                center.getX() + maxRadius + 1, world.getTopY(), center.getZ() + maxRadius + 1
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
