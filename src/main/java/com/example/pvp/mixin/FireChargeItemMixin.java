package com.example.pvp.mixin;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaWorldManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FireChargeItem;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 竞技场内火焰弹右键方块也要发射（否则原版 useOnBlock 会在方块上点火，盖过发射）。
 * 非竞技场保持原版点火。
 */
@Mixin(FireChargeItem.class)
public abstract class FireChargeItemMixin {
    @Inject(method = "useOnBlock", at = @At("HEAD"), cancellable = true)
    private void pvp$launchInArena(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        World world = context.getWorld();
        if (world.getRegistryKey() != ArenaWorldManager.ARENA_WORLD_KEY) {
            return;
        }
        PlayerEntity player = context.getPlayer();
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }
        PvPMod.launchFireCharge(serverPlayer, world);
        context.getStack().decrement(1);
        cir.setReturnValue(ActionResult.SUCCESS);
    }
}
