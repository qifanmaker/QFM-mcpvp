package com.example.pvp.mixin;

import com.example.pvp.match.PortedGameEvents;
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
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void pvp$trackNativeTerrain(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> result) {
        if (result.getReturnValue() != null) PortedGameEvents.terrainChanged(((WorldChunk) (Object) this).getWorld(), pos, state);
    }
}
