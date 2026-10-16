package com.example.pvp.arena.skywars;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Random;

/**
 * 空岛 Beta 地图生成器：复用经典地图的出生点、箱子和物资布局，叠加确定性的多层结构。
 * 这样 Beta 只改变地形，不改变空岛战争的战斗、缩圈、掉落和胜负规则。
 */
public final class SkyWarsBetaMapGenerator {
    private SkyWarsBetaMapGenerator() {
    }

    public static SkyWarsLayout generate(ArenaWorld world, int regionIndex, int seed, int playerCount,
                                         List<ServerPlayerEntity> players) {
        SkyWarsLayout layout = SkyWarsMapGenerator.generateClassic(world, regionIndex, seed, playerCount, players);
        SkyWarsTheme theme = SkyWarsTheme.pick(seed);
        Random random = new Random(seed * 1103515245L + 12345L);
        Block structure = structureBlock(theme);
        Block accent = accentBlock(theme);
        int style = Math.floorMod(seed, 6);

        // 每个出生岛增加一个低矮侧台；出生点和原有箱子保持不动，避免开局被结构卡住。
        for (int i = 0; i < layout.spawnIslands().size(); i++) {
            SkyWarsLayout.Island island = layout.spawnIslands().get(i);
            double angle = Math.atan2(island.center().getZ() - layout.mapCenter().getZ(),
                    island.center().getX() - layout.mapCenter().getX());
            BlockPos side = offset(island.center(), angle + Math.PI / 2.0, island.radius() + 3, 2);
            buildPlatform(world, side, 2, structure, 0);
            buildBridge(world, island.center(), side, structure, 0);
        }

        // 中途岛：随机生成上层观察台或下层绕行台，形成不同高度的进攻路线。
        for (int i = 0; i < layout.midIslands().size(); i++) {
            SkyWarsLayout.Island island = layout.midIslands().get(i);
            int delta = (i + style) % 3 == 0 ? -4 : 5 + random.nextInt(3);
            double angle = Math.atan2(island.center().getZ() - layout.mapCenter().getZ(),
                    island.center().getX() - layout.mapCenter().getX());
            BlockPos ledge = offset(island.center(), angle + (i % 2 == 0 ? 1 : -1) * Math.PI / 2.0,
                    island.radius() + 3, delta);
            buildPlatform(world, ledge, 2 + (style % 2), accent, 1);
            buildBridge(world, island.center(), ledge, structure, delta > 0 ? 1 : 0);
            if ((i + style) % 2 == 0) {
                buildColumn(world, ledge, 3 + Math.abs(delta), structure);
            }
        }

        // 中央核心：保留原有中心岛和卫星岛，再叠加上下两层平台与中心高塔。
        SkyWarsLayout.Island middle = layout.middle();
        if (middle != null) {
            buildCentralCore(world, middle.center(), middle.radius(), style, structure, accent);
        }

        return layout;
    }

    private static void buildCentralCore(ArenaWorld world, BlockPos center, int radius, int style,
                                         Block structure, Block accent) {
        int lowerY = -5;
        int upperY = 6 + (style % 3);
        int ringRadius = Math.max(5, radius / 3);
        buildRingPlatform(world, center, ringRadius, lowerY, accent);
        buildPlatform(world, center.add(style % 2 == 0 ? 0 : 4, upperY, style % 2 == 0 ? 4 : 0),
                3 + style % 2, structure, 1);

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
                world.setBlockState(new BlockPos(blockX, y, blockZ), block.getDefaultState(), 3);
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
            case OVERWORLD -> Blocks.STONE_BRICKS;
        };
    }

    private static Block accentBlock(SkyWarsTheme theme) {
        return switch (theme) {
            case ICE -> Blocks.BLUE_ICE;
            case NETHER -> Blocks.NETHER_BRICKS;
            case END -> Blocks.END_STONE_BRICKS;
            case OVERWORLD -> Blocks.MOSSY_STONE_BRICKS;
        };
    }
}
