package com.example.pvp.arena.skywars;

import com.example.pvp.arena.ArenaWorld;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.util.List;
import java.util.Random;

/** 正式空岛立体地图：保留各主题，给同局各出生位相等的路线和物资机会。 */
public final class SkyWarsBetaMapGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int LOWER_LAYER_DROP = 9;

    private SkyWarsBetaMapGenerator() { }

    public static SkyWarsLayout generate(ArenaWorld world, int regionIndex, int seed, int playerCount,
                                         List<ServerPlayerEntity> players, SkyWarsLayout layout) {
        layout = SkyWarsMapGenerator.generateClassic(world, regionIndex, seed, playerCount, players, layout);
        List<String> validationErrors = SkyWarsBetaLayoutValidator.validate(layout, playerCount);
        if (!validationErrors.isEmpty()) {
            LOGGER.warn("空岛布局校验出现问题（seed={}）: {}", seed, String.join("；", validationErrors));
        }
        SkyWarsTheme theme = SkyWarsTheme.pick(seed);
        Random random = new Random(layout.styleSeed());
        Block structure = structureBlock(theme);
        Block accent = accentBlock(theme);
        int style = random.nextInt(6);

        for (int i = 0; i < layout.spawnIslands().size(); i++) {
            SkyWarsLayout.Island island = layout.spawnIslands().get(i);
            double angle = routeAngle(layout, island);
            BlockPos side = offset(island.center(), angle + Math.PI / 2.0, island.radius() + 3, 2);
            buildPlatform(world, side, 2, structure, 1);
            buildBridge(world, island.center(), side, structure, 0, true);
            placeExtraChest(world, layout, side.up(), SkyWarsLootPolicy.Tier.SPAWN, i);
        }

        // 一局内所有观察台采用同一高度/宽度；高度和支路风格可跨局随机。
        int transitDelta = transitHeightOffset(layout, style);
        int sideDirection = random.nextBoolean() ? 1 : -1;
        for (int i = 0; i < layout.midIslands().size(); i++) {
            SkyWarsLayout.Island island = layout.midIslands().get(i);
            double angle = routeAngle(layout, island);
            BlockPos ledge = offset(island.center(), angle + sideDirection * Math.PI / 2.0,
                    island.radius() + 4, transitDelta);
            buildPlatform(world, ledge, 3, accent, 1);
            buildBridge(world, island.center(), ledge, structure, 1, true);
            // 高台两侧都能进攻，不让单格连接变成唯一防守入口。
            BlockPos alternate = offset(island.center(), angle, island.radius() - 1, 0);
            buildBridge(world, alternate, ledge, structure, 0, true);
            placeExtraChest(world, layout, ledge.add(1, 1, 1), SkyWarsLootPolicy.Tier.TRANSIT, i);
        }

        SkyWarsLayout.Island middle = layout.middle();
        if (middle != null) {
            if (theme == SkyWarsTheme.VILLAGE) {
                addVillageArmories(world, layout, structure);
            } else if (layout.mapStyle().usesCustomStructure()) {
                SkyWarsStructureGenerator.generate(world, layout, theme);
            } else {
                buildCentralCore(world, layout, middle, style, structure, accent, theme);
            }
        }
        SkyWarsMapGenerator.populateChests(world, layout, theme, players, false);
        List<String> generatedErrors = SkyWarsBetaLayoutValidator.validateGenerated(world, layout, theme);
        if (!generatedErrors.isEmpty()) {
            LOGGER.warn("空岛地形校验出现问题（seed={}）: {}", seed, String.join("；", generatedErrors));
        }
        LOGGER.info("生成空岛地图: seed={}, structure={}, style={}, theme={}, compact={}, radius={}, 主路={}, 附加箱子={}",
                seed, layout.mapStyle().name(), style, theme.name(), layout.compact(), layout.maxRadius(),
                layout.spawnIslands().size(), layout.extraChests().size());
        return layout;
    }

    private static double routeAngle(SkyWarsLayout layout, SkyWarsLayout.Island island) {
        return Math.atan2(island.center().getZ() - layout.mapCenter().getZ(),
                island.center().getX() - layout.mapCenter().getX());
    }

    static int transitHeightOffset(SkyWarsLayout layout, int style) {
        int offset = style % 3 == 0 ? -4 : 5 + style % 3;
        if (offset > 0 && !layout.midIslands().isEmpty()) {
            // 较小自定义岛的斜向桥水平步数更少，限制高度，避免出现必须跳两格的台阶。
            int shortestBridge = (int) Math.floor((layout.midIslands().get(0).radius() + 4) / Math.sqrt(2.0));
            offset = Math.min(offset, shortestBridge);
        }
        return offset;
    }

    /** 放空箱、保留开启净空；最后统一按空岛物资表填充。 */
    private static void placeExtraChest(ArenaWorld world, SkyWarsLayout layout, BlockPos pos,
                                        SkyWarsLootPolicy.Tier tier, int playerIndex) {
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        world.setBlockState(pos.up(), Blocks.AIR.getDefaultState(), 3);
        layout.addExtraChest(new SkyWarsLayout.ExtraChest(pos, tier, playerIndex));
    }

    private static void addVillageArmories(ArenaWorld world, SkyWarsLayout layout, Block block) {
        BlockPos center = layout.middle().center();
        // 固定八个军械箱摆在中心广场边缘，与房屋和露天箱统一使用中岛物资。
        for (int i = 0; i < 8; i++) {
            BlockPos floor = offset(center, i * Math.PI / 4.0, 7, 0);
            buildPlatform(world, floor, 1, block, 0);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    clearHeadroom(world, floor.add(dx, 0, dz), 3);
                }
            }
            placeExtraChest(world, layout, floor.up(), SkyWarsLootPolicy.Tier.CENTER, -1);
        }
    }

    private static void buildCentralCore(ArenaWorld world, SkyWarsLayout layout, SkyWarsLayout.Island middle,
                                         int style, Block structure, Block accent, SkyWarsTheme theme) {
        BlockPos center = middle.center();
        int radius = middle.radius();
        // 原下层在岛面-5，箱子和净空被加厚后的岛体填满；-9保证厚4的岛底下有4格净空。
        BlockPos lower = center.down(LOWER_LAYER_DROP);
        buildPlatform(world, lower, radius - h(2), accent, 1);
        for (int dx = -(radius - h(2)); dx <= radius - h(2); dx++) {
            for (int dz = -(radius - h(2)); dz <= radius - h(2); dz++) {
                if (dx * dx + dz * dz <= (radius - h(2)) * (radius - h(2))) {
                    clearHeadroom(world, lower.add(dx, 0, dz), 4);
                }
            }
        }
        // 入口放在最终圈内，错开两条走廊；跨过岛心留足水平距离，缩圈后仍能上下。
        int approach = layout.suggestedFinalRadius() - h(2);
        buildBridge(world, center.add(h(4), 0, approach), lower.add(h(4), 0, -h(3)), structure, h(1), true);
        buildBridge(world, center.add(-h(4), 0, -approach), lower.add(-h(4), 0, h(3)), structure, h(1), true);
        placeExtraChest(world, layout, lower.add(h(3), 1, h(3)), SkyWarsLootPolicy.Tier.CENTER, -1);

        // 可拆的侧墙提供近战掩体，矿工可以直接开洞穿过；南北主通路始终开放。
        if (style % 2 == 1) {
            for (int sign : new int[]{-1, 1}) {
                for (int z = -h(2); z <= h(2); z++) {
                    for (int y = 1; y <= 2; y++) {
                        world.setBlockState(lower.add(sign * h(5), y, z), structure.getDefaultState(), 3);
                    }
                }
            }
        }

        int upperY = theme == SkyWarsTheme.OVERWORLD ? SkyWarsMapGenerator.SECOND_LAYER_HEIGHT : 6 + style % 3;
        int upperRadius = theme == SkyWarsTheme.OVERWORLD
                ? SkyWarsMapGenerator.secondLayerRadius(middle, true, true, theme) : h(6 + style % 2);
        BlockPos upper = center.up(upperY);
        if (theme != SkyWarsTheme.OVERWORLD) buildPlatform(world, upper, upperRadius, structure, 1);
        // 两条上行走廊分置岛心两侧，和下层通道保留高度间隔，入口不随最终圈消失。
        buildBridge(world, center.add(approach, 0, h(4)), upper.add(-h(4), 0, h(4)), structure, h(1), true);
        buildBridge(world, center.add(-approach, 0, -h(4)), upper.add(h(4), 0, -h(4)), structure, h(1), true);
        // 加宽后的坡道仍需避让上层箱底座。
        placeExtraChest(world, layout, upper.add(h(3), 1, 0), SkyWarsLootPolicy.Tier.CENTER, -1);
        if (style >= 3) {
            // 半高围挡提供远程掩护，同时保留两端进攻面和中央空地。
            for (int sign : new int[]{-1, 1}) {
                for (int dx = -h(2); dx <= h(2); dx++) {
                    world.setBlockState(upper.add(dx, 1, sign * (upperRadius - h(1))), accent.getDefaultState(), 3);
                }
            }
        }
    }

    private static int h(int blocks) {
        return SkyWarsLayout.scaleCentral(blocks);
    }

    private static void buildPlatform(ArenaWorld world, BlockPos center, int radius, Block block, int depth) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                BlockPos top = center.add(dx, 0, dz);
                world.setBlockState(top, block.getDefaultState(), 3);
                for (int d = 1; d <= depth; d++) world.setBlockState(top.down(d), block.getDefaultState(), 3);
            }
        }
    }

    private static void buildBridge(ArenaWorld world, BlockPos from, BlockPos to, Block block,
                                     int width, boolean clear) {
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        if (steps == 0) return;
        boolean widenAlongZ = Math.abs(to.getX() - from.getX()) >= Math.abs(to.getZ() - from.getZ());
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * t);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            for (int side = -width; side <= width; side++) {
                BlockPos floor = new BlockPos(widenAlongZ ? x : x + side, y, widenAlongZ ? z + side : z);
                if (world.getBlockState(floor).isOf(Blocks.CHEST)
                        || world.getBlockState(floor.down()).isOf(Blocks.CHEST)) continue;
                world.setBlockState(floor, block.getDefaultState(), 3);
                if (clear) clearHeadroom(world, floor, 3);
            }
        }
    }

    private static void clearHeadroom(ArenaWorld world, BlockPos floor, int height) {
        for (int y = 1; y <= height; y++) {
            BlockPos pos = floor.up(y);
            if (!world.getBlockState(pos).isOf(Blocks.CHEST)) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
            }
        }
    }

    private static BlockPos offset(BlockPos center, double angle, int distance, int yOffset) {
        return center.add((int) Math.round(Math.cos(angle) * distance), yOffset,
                (int) Math.round(Math.sin(angle) * distance));
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
