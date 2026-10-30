package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorld;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Saved geometry is a snapshot, not millions of fresh player placements.
 * Keep WorldChunk's native height/light/BE bookkeeping and suppress only the
 * two block callbacks for ArenaWorld's exact scoped snapshot write.
 */
@Mixin(WorldChunk.class)
public abstract class PartySnapshotImportMixin {
    @WrapOperation(method = "setBlockState", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/block/BlockState;onStateReplaced(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Z)V"))
    private void pvp$preserveSnapshotOnReplacement(BlockState previous, World world, BlockPos pos,
                                                   BlockState replacement, boolean moved, Operation<Void> original) {
        if (world instanceof ArenaWorld arena && arena.isSnapshotImporting(pos, replacement)) {
            // The server normally removes the old BE inside onStateReplaced.
            // Retain that cleanup, without container drops/comparator effects.
            // WorldChunk's remaining code creates/validates the new BE itself.
            if (previous.hasBlockEntity() && !previous.isOf(replacement.getBlock())) {
                ((WorldChunk) (Object) this).removeBlockEntity(pos);
            }
            return;
        }
        original.call(previous, world, pos, replacement, moved);
    }

    @WrapOperation(method = "setBlockState", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/block/BlockState;onBlockAdded(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Z)V"))
    private void pvp$preserveSnapshotOnPlacement(BlockState state, World world, BlockPos pos,
                                                BlockState previous, boolean moved, Operation<Void> original) {
        if (world instanceof ArenaWorld arena && arena.isSnapshotImporting(pos, state)) return;
        original.call(state, world, pos, previous, moved);
    }
}
