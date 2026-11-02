package com.example.pvp.mixin;

import com.example.pvp.match.PortedGameEvents;
import com.example.pvp.arena.ArenaWorld;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 门、原生活塞和流体会修改模板原本为空气的格，不能仅登记玩家BlockItem放置。 */
@Mixin(WorldChunk.class)
public abstract class PartyTerrainMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void pvp$rememberResidentTerrain(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> result) {
        WorldChunk chunk = (WorldChunk) (Object) this;
        if (chunk.getWorld() instanceof ArenaWorld arena) {
            BlockState previous = chunk.getBlockState(pos);
            // Capture before replacement callbacks: they may recursively change
            // this same cell and remove its original block entity. Dynamic
            // snapshot writes also belong to the lease's restoration journal.
            if (previous != state) arena.residentMaps().beforeBlockChange(pos, state, previous);
        }
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void pvp$trackNativeTerrain(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> result) {
        if (result.getReturnValue() == null) return;
        var world = ((WorldChunk) (Object) this).getWorld();
        // Saved runs already own a cleanup footprint. Looking up every match
        // again for millions of imported/cleared cells only adds duplicate work.
        // Live player, fluid and piston changes still follow normal tracking.
        if (world instanceof ArenaWorld arena && arena.isSnapshotImporting(pos, state)) return;
        PortedGameEvents.terrainChanged(world, pos, state);
    }
}
