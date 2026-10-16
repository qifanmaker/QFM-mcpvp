package com.example.pvp.mixin;

import com.example.pvp.PvPMod;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 记录服务端实际接受的方块交互，用于搭路频率检测。 */
@Mixin(ServerPlayerInteractionManager.class)
public abstract class ServerPlayerInteractionManagerMixin {
    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void pvp$recordBlockInteraction(ServerPlayerEntity player, World world, ItemStack stack, Hand hand,
                                             BlockHitResult hitResult,
                                             CallbackInfoReturnable<ActionResult> cir) {
        if (!cir.getReturnValue().isAccepted()) {
            return;
        }
        if (player != null) {
            PvPMod.onBlockInteraction(player, true, stack);
        }
    }
}
