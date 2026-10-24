package com.example.pvp.match;

import com.example.pvp.text.Messages;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/** 救援道具共用的落脚检查，避免中心被挡仍扣道具，或图腾传回已经塌掉的岛。 */
public final class SkyWarsRescue {
    private SkyWarsRescue() { }

    public static boolean safeLanding(ServerWorld world, BlockPos feet) {
        if (!world.isInBuildLimit(feet.down()) || !world.isInBuildLimit(feet.up())) return false;
        var floor = world.getBlockState(feet.down());
        if (!floor.isSideSolidFullSquare(world, feet.down(), Direction.UP)
                || floor.isOf(Blocks.MAGMA_BLOCK) || floor.isOf(Blocks.CAMPFIRE)
                || floor.isOf(Blocks.SOUL_CAMPFIRE) || floor.isOf(Blocks.CACTUS)) return false;
        for (int dy = 0; dy <= 1; dy++) {
            var state = world.getBlockState(feet.up(dy));
            if (!state.getFluidState().isEmpty() || state.isOf(Blocks.FIRE)
                    || state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.POWDER_SNOW)
                    || state.isOf(Blocks.SWEET_BERRY_BUSH)) return false;
        }
        return world.isSpaceEmpty(new Box(feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.2,
                feet.getX() + 0.8, feet.getY() + 1.8, feet.getZ() + 0.8));
    }

    public static boolean deployPlatform(ServerPlayerEntity player, ItemStack hand) {
        ServerWorld world = player.getServerWorld();
        if (player.getItemCooldownManager().isCoolingDown(Items.BLAZE_ROD)) return false;
        Box body = player.getBoundingBox();
        Box support = new Box(body.minX + 0.001, body.minY - 0.15, body.minZ + 0.001,
                body.maxX - 0.001, body.minY + 0.001, body.maxZ - 0.001);
        if (world.getBlockCollisions(player, support).iterator().hasNext()) {
            player.sendMessage(Messages.warn("脚下有实体方块，无法展开自救平台"), true);
            return false;
        }
        // 保留用户指定的脚下五格；先确认正中心，不能只成功放下边缘就扣除道具。
        BlockPos platform = player.getBlockPos().down(5);
        if (!world.isInBuildLimit(platform) || !world.getBlockState(platform).isReplaceable()
                || !world.getBlockState(platform.up()).getCollisionShape(world, platform.up()).isEmpty()
                || !world.setBlockState(platform, Blocks.SLIME_BLOCK.getDefaultState(), 3)) {
            player.sendMessage(Messages.warn("下方没有足够空间展开自救平台"), true);
            return false;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 4 || (dx == 0 && dz == 0)) continue;
                BlockPos pos = platform.add(dx, 0, dz);
                if (world.isInBuildLimit(pos) && world.getBlockState(pos).isReplaceable()) {
                    world.setBlockState(pos, Blocks.SLIME_BLOCK.getDefaultState(), 3);
                }
            }
        }
        hand.decrement(1);
        // 同一次右键的方块/空气交互，以及长按右键，不能瞬间吃掉两个救援机会。
        player.getItemCooldownManager().set(Items.BLAZE_ROD, 10);
        player.sendMessage(Messages.gold("自救平台已展开！"), true);
        return true;
    }
}
