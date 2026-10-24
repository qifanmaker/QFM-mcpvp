package com.example.pvp.arena.islandsiege;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Island Siege 的 1.21.1 原生场地。原版地图包含使用全服选择器的 3,533 个命令方块，
 * 不能在共享竞技场维度执行；这里按同一攻防目标重建多路线岛屿，不宣称复制原版建筑。
 * 所有写入通过 ArenaWorld 暂存，并精确登记，避免清场误删相邻对局。
 */
public final class IslandSiegeArena {
    private final BlockPos center;
    private final Random random;
    private final Set<BlockPos> blocks = new LinkedHashSet<>();
    private final Set<BlockPos> skullCage = new HashSet<>();
    private final List<BlockPos> supplies = new ArrayList<>();
    private final List<BlockPos> routeCenters = new ArrayList<>();
    private final BlockPos redSpawn;
    private final BlockPos blueSpawn;
    private final BlockPos flag;
    private final BlockPos guardian;
    private final BlockPos skull;
    private final int variant;

    public IslandSiegeArena(ArenaTemplate template, int regionIndex, int seed) {
        this.center = template.getCenter(regionIndex).down();
        this.random = new Random(seed * 341873128712L + 132897987541L);
        this.variant = this.random.nextInt(3);
        this.redSpawn = this.center.add(-62, 1, 0);
        this.blueSpawn = this.center.add(59, 5, 0);
        this.flag = this.center.add(27, 6, -24);
        this.guardian = this.center.add(26, 3, 24);
        this.skull = this.center.add(40, 9, 0);
        this.routeCenters.add(this.center.add(-19, 1 + this.variant, -26));
        this.routeCenters.add(this.center.add(-13, 5 - this.variant, 0));
        this.routeCenters.add(this.center.add(-18, 2 + this.variant, 26));
    }

    public BlockPos center() { return this.center; }
    public BlockPos redSpawn() { return this.redSpawn; }
    public BlockPos blueSpawn() { return this.blueSpawn; }
    public BlockPos flag() { return this.flag; }
    public BlockPos guardian() { return this.guardian; }
    public BlockPos skull() { return this.skull; }
    public List<BlockPos> supplies() { return List.copyOf(this.supplies); }
    public Set<BlockPos> skullCage() { return Set.copyOf(this.skullCage); }

    public boolean inBounds(BlockPos pos) {
        return Math.abs(pos.getX() - this.center.getX()) <= 75
                && Math.abs(pos.getZ() - this.center.getZ()) <= 47
                && pos.getY() >= this.center.getY() - 14
                && pos.getY() <= this.center.getY() + 32;
    }

    public boolean nearSpawn(BlockPos pos) {
        return horizontalDistanceSquared(pos, this.redSpawn) <= 36
                || horizontalDistanceSquared(pos, this.blueSpawn) <= 36;
    }

    public boolean protectedObjective(BlockPos pos) {
        return horizontalDistanceSquared(pos, this.flag) <= 25
                || horizontalDistanceSquared(pos, this.guardian) <= 25
                || horizontalDistanceSquared(pos, this.skull) <= 25
                || this.supplies.stream().anyMatch(p -> horizontalDistanceSquared(pos, p) <= 4);
    }

    public static double horizontalDistanceSquared(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    public void generate(ArenaWorld world) {
        Block top = this.variant == 0 ? Blocks.GRASS_BLOCK : this.variant == 1 ? Blocks.MOSS_BLOCK : Blocks.SANDSTONE;
        island(world, this.redSpawn.down(), 12, 12, Blocks.RED_TERRACOTTA);
        island(world, this.blueSpawn.down(), 12, 13, Blocks.BLUE_TERRACOTTA);
        island(world, this.flag.down(), 13, 12, top);
        island(world, this.guardian.down(), 14, 13, top);
        island(world, this.skull.down(), 12, 13, Blocks.DEEPSLATE_BRICKS);
        for (BlockPos route : this.routeCenters) {
            island(world, route.down(), 10 + this.random.nextInt(3), 9 + this.random.nextInt(3), top);
            bridge(world, this.redSpawn.add(6, 0, 0), route, Blocks.OAK_PLANKS, 3);
        }
        bridge(world, this.routeCenters.get(0), this.flag, Blocks.SPRUCE_PLANKS, 3);
        bridge(world, this.routeCenters.get(1), this.skull, Blocks.STONE_BRICKS, 4);
        bridge(world, this.routeCenters.get(2), this.guardian, Blocks.SPRUCE_PLANKS, 3);
        bridge(world, this.flag, this.skull, Blocks.STONE_BRICKS, 4);
        bridge(world, this.guardian, this.skull, Blocks.STONE_BRICKS, 4);
        bridge(world, this.skull, this.blueSpawn, Blocks.STONE_BRICKS, 4);
        bridge(world, this.flag, this.blueSpawn, Blocks.STONE_BRICKS, 3);
        bridge(world, this.guardian, this.blueSpawn, Blocks.STONE_BRICKS, 3);

        // 每条路线的资源和掩体是对称数量、随机朝向，保持随机地图也可步行抵达三个目标。
        for (BlockPos route : this.routeCenters) {
            BlockPos supply = route.add(0, 0, 4);
            crate(world, supply);
            for (int i = 0; i < 3; i++) {
                int dx = (i - 1) * 4;
                int dz = this.random.nextBoolean() ? -5 : 5;
                pillar(world, route.add(dx, 0, dz), Blocks.COBBLESTONE, 1 + this.random.nextInt(3));
            }
        }
        crate(world, this.redSpawn.add(-4, 0, -4));
        crate(world, this.blueSpawn.add(4, 0, -4));
        crate(world, this.flag.add(8, 0, 0));
        crate(world, this.guardian.add(8, 0, 0));
        pad(world, this.redSpawn.down(), Blocks.RED_CONCRETE, 4);
        pad(world, this.blueSpawn.down(), Blocks.BLUE_CONCRETE, 4);
        pad(world, this.flag.down(), Blocks.YELLOW_CONCRETE, 4);
        pillar(world, this.flag, Blocks.OAK_FENCE, 6);
        for (int dx = 1; dx <= 3; dx++) {
            for (int dy = 3; dy <= 5; dy++) {
                write(world, this.flag.add(dx, dy, 0), Blocks.BLUE_WOOL.getDefaultState());
            }
        }
        pad(world, this.guardian.down(), Blocks.IRON_BLOCK, 4);
        // 剑形标识位于守卫竞技台后侧，不挡通往目标的主路径。
        BlockPos sword = this.guardian.add(0, 0, 7);
        pillar(world, sword, Blocks.IRON_BLOCK, 6);
        for (int dx = -2; dx <= 2; dx++) write(world, sword.add(dx, 1, 0), Blocks.GOLD_BLOCK.getDefaultState());
        pad(world, this.skull.down(), Blocks.GOLD_BLOCK, 3);
        write(world, this.skull, Blocks.PIGLIN_HEAD.getDefaultState());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == 2) {
                        BlockPos p = this.skull.add(dx, dy, dz);
                        this.skullCage.add(p);
                        write(world, p, Blocks.IRON_BARS.getDefaultState());
                    }
                }
            }
        }
        // 出生点上方的色柱让长距离返回点清晰可辨。
        pillar(world, this.redSpawn.add(-7, 0, 0), Blocks.RED_CONCRETE, 8);
        pillar(world, this.blueSpawn.add(7, 0, 0), Blocks.BLUE_CONCRETE, 8);
    }

    private void island(ArenaWorld world, BlockPos top, int rx, int rz, Block surface) {
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dz = -rz; dz <= rz; dz++) {
                double radius = (double) dx * dx / (rx * rx) + (double) dz * dz / (rz * rz);
                if (radius > 1.0) continue;
                int depth = 2 + (int) ((1.0 - radius) * 8);
                write(world, top.add(dx, 0, dz), surface.getDefaultState());
                for (int y = 1; y <= depth; y++) {
                    write(world, top.add(dx, -y, dz), (y < 3 ? Blocks.DIRT : Blocks.STONE).getDefaultState());
                }
            }
        }
    }

    private void bridge(ArenaWorld world, BlockPos fromFeet, BlockPos toFeet, Block block, int width) {
        int dx = toFeet.getX() - fromFeet.getX();
        int dz = toFeet.getZ() - fromFeet.getZ();
        int distance = Math.max(Math.abs(dx), Math.abs(dz));
        for (int step = 0; step <= distance; step++) {
            double t = distance == 0 ? 0 : (double) step / distance;
            int x = fromFeet.getX() + (int) Math.round(dx * t);
            int z = fromFeet.getZ() + (int) Math.round(dz * t);
            int y = fromFeet.getY() - 1 + (int) Math.round((toFeet.getY() - fromFeet.getY()) * t);
            for (int offset = -width / 2; offset <= width / 2; offset++) {
                BlockPos p = Math.abs(dx) >= Math.abs(dz) ? new BlockPos(x, y, z + offset) : new BlockPos(x + offset, y, z);
                write(world, p, block.getDefaultState());
                // 清出岛屿交会处的头顶空间，避免上坡桥撞进较高岛的侧壁。
                write(world, p.up(), Blocks.AIR.getDefaultState());
                write(world, p.up(2), Blocks.AIR.getDefaultState());
                write(world, p.up(3), Blocks.AIR.getDefaultState());
            }
        }
    }

    private void pad(ArenaWorld world, BlockPos center, Block block, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                write(world, center.add(dx, 0, dz), block.getDefaultState());
            }
        }
    }

    private void pillar(ArenaWorld world, BlockPos pos, Block block, int height) {
        for (int dy = 0; dy < height; dy++) write(world, pos.up(dy), block.getDefaultState());
    }

    private void crate(ArenaWorld world, BlockPos pos) {
        this.supplies.add(pos);
        write(world, pos, Blocks.BARREL.getDefaultState());
    }

    public void openSkullCage(ArenaWorld world) {
        for (BlockPos p : this.skullCage) write(world, p, Blocks.AIR.getDefaultState());
    }

    public void showSkull(ArenaWorld world, boolean visible) {
        write(world, this.skull, (visible ? Blocks.PIGLIN_HEAD : Blocks.AIR).getDefaultState());
    }

    public void markFlagComplete(ArenaWorld world) {
        pad(world, this.flag.down(), Blocks.RED_CONCRETE, 4);
        for (int dx = 1; dx <= 3; dx++) {
            for (int dy = 3; dy <= 5; dy++) write(world, this.flag.add(dx, dy, 0), Blocks.RED_WOOL.getDefaultState());
        }
    }

    private void write(ArenaWorld world, BlockPos pos, BlockState state) {
        this.blocks.add(pos.toImmutable());
        world.setBlockState(pos, state, 3);
    }

    public void clear(ArenaWorld world) {
        for (BlockPos pos : this.blocks) world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
        this.blocks.clear();
    }
}
