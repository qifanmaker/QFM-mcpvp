package com.example.pvp.arena.party;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Farming Frenzy 的60tick预生长与正式200随机采样不能改动共享世界规则，
 * 也不能把每株作物直接加龄。复用原生区段采样及方块/流体 randomTick，
 * 在模块完成采收结算和补种之后调用，以保留源函数先于世界生长的顺序。
 */
public final class PartyRandomTicks {
    private PartyRandomTicks() { }

    public static void tick(ServerWorld world, BlockPos minimum, BlockPos maximum, int speed) {
        if (speed < 0 || minimum.getX() > maximum.getX() || minimum.getY() > maximum.getY()
                || minimum.getZ() > maximum.getZ()) throw new IllegalArgumentException("Invalid Party random-tick range");
        if (speed == 0) return;
        for (int x = Math.floorDiv(minimum.getX(), 16); x <= Math.floorDiv(maximum.getX(), 16); x++) {
            for (int z = Math.floorDiv(minimum.getZ(), 16); z <= Math.floorDiv(maximum.getZ(), 16); z++) {
                WorldChunk chunk = world.getChunk(x, z);
                var sections = chunk.getSectionArray();
                for (int index = 0; index < sections.length; index++) {
                    var section = sections[index];
                    int bottom = chunk.sectionIndexToCoord(index) * 16;
                    if (bottom > maximum.getY() || bottom + 15 < minimum.getY() || !section.hasRandomTicks()) continue;
                    for (int draw = 0; draw < speed; draw++) {
                        BlockPos pos = world.getRandomPosInChunk(x * 16, bottom, z * 16, 15);
                        if (pos.getX() < minimum.getX() || pos.getX() > maximum.getX()
                                || pos.getY() < minimum.getY() || pos.getY() > maximum.getY()
                                || pos.getZ() < minimum.getZ() || pos.getZ() > maximum.getZ()) continue;
                        BlockState state = section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                        if (state.hasRandomTicks()) state.randomTick(world, pos, world.random);
                        var fluid = state.getFluidState();
                        if (fluid.hasRandomTicks()) fluid.onRandomTick(world, pos, world.random);
                    }
                }
            }
        }
    }
}
