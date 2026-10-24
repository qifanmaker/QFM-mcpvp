package com.example.pvp.mygo;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 为不依赖烟雾弹的首版枪战提供固定多路线、实体掩体与清晰包点。
 * 所有方块精确登记，清场只删除本局生成内容；阵亡室封闭以避免自由侦察。
 */
public final class MyGoArena {
    private final BlockPos center;
    private final Set<BlockPos> blocks = new LinkedHashSet<>();
    private final boolean singleSite;

    public MyGoArena(ArenaTemplate template, int regionIndex, int seed, boolean singleSite) {
        this.center = template.getCenter(regionIndex).down();
        this.singleSite = singleSite;
    }

    public BlockPos center() { return this.center; }
    public BlockPos spawn(boolean attackers, int index) { return this.center.add(attackers ? -34 : 34, 1, index * 2 - 4); }
    public BlockPos spectator() { return this.center.add(-47, 2, 0); }
    public List<BlockPos> sites() { return this.singleSite ? List.of(this.center.add(20, 1, 0))
            : List.of(this.center.add(20, 1, -15), this.center.add(20, 1, 15)); }
    public boolean inBounds(BlockPos pos) { return Math.abs(pos.getX() - this.center.getX()) < 40
            && Math.abs(pos.getZ() - this.center.getZ()) < 24
            && pos.getY() >= this.center.getY() + 1 && pos.getY() <= this.center.getY() + 5; }
    public boolean inBuyZone(BlockPos pos, boolean attackers) { return inBounds(pos)
            && (attackers ? pos.getX() <= this.center.getX() - 27 : pos.getX() >= this.center.getX() + 27); }
    public BlockPos siteAt(BlockPos pos) { return sites().stream().filter(s -> Math.abs(pos.getX() - s.getX()) <= 4
            && Math.abs(pos.getZ() - s.getZ()) <= 4 && Math.abs(pos.getY() - s.getY()) <= 2).findFirst().orElse(null); }
    public BlockPos safeDrop(BlockPos pos) { return inBounds(pos) ? new BlockPos(pos.getX(), this.center.getY() + 1, pos.getZ())
            : this.center.add(-25, 1, 0); }

    public void generate(ArenaWorld world) {
        fill(world, -40, 0, -24, 40, 0, 24, Blocks.SMOOTH_STONE);
        fill(world, -40, 1, -24, -40, 6, 24, Blocks.DEEPSLATE_BRICKS);
        fill(world, 40, 1, -24, 40, 6, 24, Blocks.DEEPSLATE_BRICKS);
        fill(world, -40, 1, -24, 40, 6, -24, Blocks.DEEPSLATE_BRICKS);
        fill(world, -40, 1, 24, 40, 6, 24, Blocks.DEEPSLATE_BRICKS);
        // 两条内墙切断出生直线和跨全图长枪线，开口允许中路及两侧绕行。
        for (int z : new int[]{-8, 8}) {
            fill(world, -23, 1, z, -12, 4, z, Blocks.STONE_BRICKS);
            fill(world, -4, 1, z, 8, 4, z, Blocks.STONE_BRICKS);
            fill(world, 15, 1, z, 28, 4, z, Blocks.STONE_BRICKS);
        }
        for (int x : new int[]{-27, -15, 0, 12, 28}) {
            for (int z : new int[]{-18, 0, 18}) {
                // 2 格高完整掩体；交错放置保留四格以上通行空间。
                fill(world, x, 1, z, x + 2, 2, z + 2, Blocks.OAK_PLANKS);
            }
        }
        for (int i = 0; i < sites().size(); i++) {
            BlockPos site = sites().get(i);
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++)
                write(world, site.add(dx, -1, dz), i == 0 ? Blocks.ORANGE_CONCRETE : Blocks.YELLOW_CONCRETE);
        }
        fill(world, -38, 0, -6, -29, 0, 6, Blocks.RED_CONCRETE);
        fill(world, 29, 0, -6, 38, 0, 6, Blocks.BLUE_CONCRETE);
        // 单独封闭阵亡室，不提供能看见敌方的窗口或自由飞行能力。
        fill(world, -50, 0, -5, -44, 6, 5, Blocks.DEEPSLATE_BRICKS);
        fill(world, -49, 1, -4, -45, 5, 4, Blocks.AIR);
        write(world, this.spectator().up(3), Blocks.SEA_LANTERN);
        for (BlockPos site : sites()) write(world, site.add(0, 5, 0), Blocks.SEA_LANTERN);
    }

    private void fill(ArenaWorld world, int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++)
            write(world, this.center.add(x, y, z), block);
    }
    private void write(ArenaWorld world, BlockPos pos, Block block) { this.blocks.add(pos.toImmutable()); world.setBlockState(pos, block.getDefaultState(), 3); }
    public void clear(ArenaWorld world) { for (BlockPos pos : this.blocks) world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3); this.blocks.clear(); }
}
