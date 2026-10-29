package com.example.pvp.mixin;

import com.example.pvp.match.PortedGameEvents;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 默认冻结裁剪装饰水槽；声明真实进水的回合仅允许本场边界内的原生流动。 */
@Mixin(FlowableFluid.class)
public abstract class FlowableFluidMixin {
    @Inject(method = "onScheduledTick", at = @At("HEAD"), cancellable = true)
    private void pvp$freezePartyDecoration(World world, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (PortedGameEvents.freezesPartyFluid(world, pos)) ci.cancel();
    }

    @Inject(method = "flow", at = @At("HEAD"), cancellable = true)
    private void pvp$boundPartyFlow(WorldAccess world, BlockPos pos, BlockState state, Direction direction,
                                     FluidState fluid, CallbackInfo ci) {
        if (!PortedGameEvents.allowsFluidFlow(world, pos, direction)) ci.cancel();
    }
}
