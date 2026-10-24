package com.example.pvp.mixin;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaWorldManager;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原图裁剪会截断装饰水槽；冻结 Party 水/熔岩，避免流到本场清理边界之外。 */
@Mixin(FlowableFluid.class)
public abstract class FlowableFluidMixin {
    @Inject(method = "onScheduledTick", at = @At("HEAD"), cancellable = true)
    private void pvp$freezePartyDecoration(World world, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (world.getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY && PvPMod.MATCH != null
                && PvPMod.MATCH.isPartyScenePosition(pos)) {
            ci.cancel();
        }
    }
}
