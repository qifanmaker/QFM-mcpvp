package com.example.pvp.arena.skywars;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Random;
import org.slf4j.Logger;

/**
 * 空岛 Beta 地图生成器：复用经典地图的出生点、箱子和物资布局，叠加确定性的多层结构。
 * 这样 Beta 只改变地形，不改变空岛战争的战斗、缩圈、掉落和胜负规则。
 */
public final class SkyWarsBetaMapGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SkyWarsBetaMapGenerator() {
    }

    /**
     * 生成 Beta 立体地形。
     *
     * @param layout 调用方已经算好的布局（正式对局传 {@code Match.skywarsLayout}）：
     *               附加箱子会登记在这份对象上，物资刷新才能找到它们；为 null 时自己算一份（调试用）
     */
    public static SkyWarsLayout generate(ArenaWorld world, int regionIndex, int seed, int playerCount,
                                         List<ServerPlayerEntity> players, SkyWarsLayout layout) {
        layout = SkyWarsMapGenerator.generateClassic(world, regionIndex, seed, playerCount, players, layout);
        List<String> validationErrors = SkyWarsBetaLayoutValidator.validate(layout, playerCount);
        if (!validationErrors.isEmpty()) {
            LOGGER.warn("空岛 Beta 布局校验出现问题（seed={}）: {}", seed, String.join("；", validationErrors));
        }
        SkyWarsTheme theme = SkyWarsTheme.pick(seed);
        Random random = new Random(seed * 1103515245L + 12345L);
        Block structure = structureBlock(theme);
        Block accent = accentBlock(theme);
        int style = Math.floorMod(seed, 6);

        int[] handicaps = players == null || players.isEmpty()
                ? new int[0] : SkyWarsLoot.handicapForMatch(players);

        // 每个出生岛增加一个低矮侧台；出生点和原有箱子保持不动，避免开局被结构卡住。
        for (int i = 0; i < layout.spawnIslands().size(); i++) {
            SkyWarsLayout.Island island = layout.spawnIslands().get(i);
            double angle = Math.atan2(island.center().getZ() - layout.mapCenter().getZ(),
                    island.center().getX() - layout.mapCenter().getX());
            BlockPos side = offset(island.center(), angle + Math.PI / 2.0, island.radius() + 3, 2);
            buildPlatform(world, side, 2, structure, 0);
            buildBridge(world, island.center(), side, structure, 0);
            // 侧台上也放一个箱子（用的是玩家岛的普通战利品表，补偿归属也跟玩家岛一致）
            placeExtraChest(world, layout, side.up(), false, i, handicaps);
        }

        // 中途岛：随机生成上层观察台或下层绕行台，形成不同高度的进攻路线。
        for (int i = 0; i < layout.midIslands().size(); i++) {
            SkyWarsLayout.Island island = layout.midIslands().get(i);
            int delta = (i + style) % 3 == 0 ? -4 : 5 + random.nextInt(3);
            double angle = Math.atan2(island.center().getZ() - layout.mapCenter().getZ(),
                    island.center().getX() - layout.mapCenter().getX());
            BlockPos ledge = offset(island.center(), angle + (i % 2 == 0 ? 1 : -1) * Math.PI / 2.0,
                    island.radius() + 3, delta);
            int ledgeRadius = 2 + (style % 2);
            buildPlatform(world, ledge, ledgeRadius, accent, 1);
            buildBridge(world, island.center(), ledge, structure, delta > 0 ? 1 : 0);
            if ((i + style) % 2 == 0) {
                buildColumn(world, ledge, 3 + Math.abs(delta), structure);
            }
            // 观察台/绕行台也给一个箱子：高风险路线要有收益，玩家才会真的走
            // 偏开平台中心，避免和桥/柱子抢同一格
            placeExtraChest(world, layout, ledge.add(1, 1, 1), false, i, handicaps);
        }

        // 中央核心：保留原有中心岛和卫星岛，再叠加上下两层平台与中心高塔。
        // 村庄地图的中岛整座都是村庄，再叠平台/立柱会把房子埋掉，所以跳过。
        SkyWarsLayout.Island middle = layout.middle();
        if (middle != null && theme != SkyWarsTheme.VILLAGE) {
            buildCentralCore(world, layout, middle.center(), middle.radius(), style, structure, accent, theme,
                    handicaps);
        }

        LOGGER.info("生成空岛 Beta 地图: seed={}, style={}, theme={}, spawnIslands={}, midIslands={}, 附加箱子={}",
                seed, style, theme.name(), layout.spawnIslands().size(), layout.midIslands().size(),
                layout.extraChests().size());
        return layout;
    }

    /**
     * 在立体地形上放一个箱子并登记，供 {@code 物资刷新} 复用同一份坐标。
     *
     * <p>先写箱子再取方块实体：新平台的这一格按构造应该是空的，但桥/柱/拱梁在极端 style 下
     * 有可能正好穿过它，直接覆盖成箱子可以保证不会被结构吃掉。
     */
    private static void placeExtraChest(ArenaWorld world, SkyWarsLayout layout, BlockPos pos,
                                        boolean middle, int handicapIndex, int[] handicaps) {
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof net.minecraft.block.entity.ChestBlockEntity chest) {
            SkyWarsLoot.populate(chest, middle, handicapAt(handicaps, handicapIndex));
        }
        layout.addExtraChest(new SkyWarsLayout.ExtraChest(pos, middle, handicapIndex));
    }

    private static int handicapAt(int[] handicaps, int index) {
        return index >= 0 && index < handicaps.length ? handicaps[index] : 0;
    }

    private static void buildCentralCore(ArenaWorld world, SkyWarsLayout layout, BlockPos center, int radius,
                                         int style, Block structure, Block accent, SkyWarsTheme theme,
                                         int[] handicaps) {
        int lowerY = -5;
        int ringRadius = Math.max(5, radius / 3);
        buildRingPlatform(world, center, ringRadius, lowerY, accent);
        placeExtraChest(world, layout, center.add(ringRadius, lowerY + 1, 0), true, -1, handicaps);

        if (theme == SkyWarsTheme.OVERWORLD) {
            // 主世界主岛已经有第二层了（见 SkyWarsMapGenerator#buildSecondLayer）：
            // 原来的小上层平台/立柱/拱梁都会被实心圆台埋掉，不再生成；
            // 只在第二层顶面补一个中岛级箱子（中心 3 格是图腾救回点，所以偏开 4 格）。
            placeExtraChest(world, layout,
                    center.add(4, SkyWarsMapGenerator.SECOND_LAYER_HEIGHT + 1, 0), true, -1, handicaps);
            return;
        }

        int upperY = 6 + (style % 3);
        BlockPos upperPlatform = center.add(style % 2 == 0 ? 0 : 4, upperY, style % 2 == 0 ? 4 : 0);
        buildPlatform(world, upperPlatform, 3 + style % 2, structure, 1);
        placeExtraChest(world, layout, upperPlatform.add(1, 1, 1), true, -1, handicaps);

        int pillarCount = 4 + style % 3;
        for (int i = 0; i < pillarCount; i++) {
            double angle = i * Math.PI * 2.0 / pillarCount + style * 0.17;
            BlockPos base = offset(center, angle, Math.max(4, radius / 2), 0);
            buildColumn(world, base, 4 + (i + style) % 4, structure);
            if (style % 2 == 1) {
                buildBridge(world, base, center.add(0, upperY, 0), accent, 1);
            }
        }

        if (style >= 3) {
            // 高层风格保留一条不封死中心视线的拱形侧梁。
            BlockPos left = center.add(-radius / 2, upperY - 1, 0);
            BlockPos right = center.add(radius / 2, upperY - 1, 0);
            buildBridge(world, left, right, accent, 1);
        }
    }

    private static void buildPlatform(ArenaWorld world, BlockPos center, int radius, Block block, int depth) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                BlockPos top = center.add(dx, 0, dz);
                world.setBlockState(top, block.getDefaultState(), 3);
                for (int d = 1; d <= depth; d++) {
                    world.setBlockState(top.down(d), block.getDefaultState(), 3);
                }
            }
        }
    }

    private static void buildRingPlatform(ArenaWorld world, BlockPos center, int radius, int y, Block block) {
        int inner = Math.max(2, radius - 2);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distance = dx * dx + dz * dz;
                if (distance >= inner * inner && distance <= radius * radius) {
                    world.setBlockState(center.add(dx, y, dz), block.getDefaultState(), 3);
                }
            }
        }
    }

    private static void buildColumn(ArenaWorld world, BlockPos base, int height, Block block) {
        for (int y = 0; y < height; y++) {
            world.setBlockState(base.up(y), block.getDefaultState(), 3);
        }
    }

    private static void buildBridge(ArenaWorld world, BlockPos from, BlockPos to, Block block, int width) {
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        if (steps == 0) {
            return;
        }
        boolean widenAlongZ = Math.abs(to.getX() - from.getX()) >= Math.abs(to.getZ() - from.getZ());
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * t);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            for (int offset = -width; offset <= width; offset++) {
                int blockX = widenAlongZ ? x : x + offset;
                int blockZ = widenAlongZ ? z + offset : z;
                BlockPos pos = new BlockPos(blockX, y, blockZ);
                // 别把已经放好的箱子顶掉：岛心→侧台/观察台的桥是贴着岛面建的，
                // 可能正好穿过岛上的箱子，覆盖掉会让箱子凭空少一个（箱子本身可以踩，留空不影响通行）
                if (world.getBlockState(pos).isOf(Blocks.CHEST)) {
                    continue;
                }
                world.setBlockState(pos, block.getDefaultState(), 3);
            }
        }
    }

    private static BlockPos offset(BlockPos center, double angle, int distance, int yOffset) {
        return new BlockPos(
                center.getX() + (int) Math.round(Math.cos(angle) * distance),
                center.getY() + yOffset,
                center.getZ() + (int) Math.round(Math.sin(angle) * distance));
    }

    private static Block structureBlock(SkyWarsTheme theme) {
        return switch (theme) {
            case ICE -> Blocks.PACKED_ICE;
            case NETHER -> Blocks.POLISHED_BLACKSTONE;
            case END -> Blocks.PURPUR_BLOCK;
            case OVERWORLD, VILLAGE -> Blocks.STONE_BRICKS;
        };
    }

    private static Block accentBlock(SkyWarsTheme theme) {
        return switch (theme) {
            case ICE -> Blocks.BLUE_ICE;
            case NETHER -> Blocks.NETHER_BRICKS;
            case END -> Blocks.END_STONE_BRICKS;
            case OVERWORLD, VILLAGE -> Blocks.MOSSY_STONE_BRICKS;
        };
    }
}
