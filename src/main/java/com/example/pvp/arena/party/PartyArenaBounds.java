package com.example.pvp.arena.party;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/**
 * 原图火箭赛道长2160格、珍珠折跃出口距原点超过1000格，固定200半径会截断玩法。
 * 范围相对会话中心；保留旧项目默认值，宽场地由比赛分配器独占相邻区域。
 */
public record PartyArenaBounds(int minX, int maxX, int minZ, int maxZ) {
    public static final PartyArenaBounds DEFAULT = new PartyArenaBounds(-200, 200, -200, 200);
    public static final PartyArenaBounds LOBBY_PROTECTION = new PartyArenaBounds(-240, 240, -240, 240);

    public PartyArenaBounds {
        if (minX > maxX || minZ > maxZ || minX < -1552 || maxX > 1552
                || minZ < -4096 || maxZ > 4096) throw new IllegalArgumentException("Invalid Party arena bounds");
    }

    public boolean contains(BlockPos center, BlockPos position) {
        return contains(center, position.getX(), position.getZ(), 0);
    }

    public boolean contains(BlockPos center, double x, double z, int inset) {
        double relativeX = x - center.getX(), relativeZ = z - center.getZ();
        return relativeX >= minX + inset && relativeX <= maxX - inset
                && relativeZ >= minZ + inset && relativeZ <= maxZ - inset;
    }

    public PartyArenaBounds union(PartyArenaBounds other) {
        return new PartyArenaBounds(Math.min(minX, other.minX), Math.max(maxX, other.maxX),
                Math.min(minZ, other.minZ), Math.max(maxZ, other.maxZ));
    }

    public PartyArenaBounds expand(int amount) {
        return new PartyArenaBounds(minX - amount, maxX + amount, minZ - amount, maxZ + amount);
    }

    public BlockPos minimum(BlockPos center, World world) { return new BlockPos(center.getX() + minX, world.getBottomY(), center.getZ() + minZ); }
    public BlockPos maximum(BlockPos center, World world) { return new BlockPos(center.getX() + maxX, world.getTopY() - 1, center.getZ() + maxZ); }
    public Box box(BlockPos center, World world) {
        return new Box(center.getX() + minX, world.getBottomY(), center.getZ() + minZ,
                center.getX() + maxX + 1, world.getTopY(), center.getZ() + maxZ + 1);
    }
}
