package com.example.pvp.mixin;

import com.example.pvp.gui.PvpGuiManager;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 禁止扔出 UI 工具（主菜单指南针、排队红石、观战物品）。
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    @Inject(method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;", at = @At("HEAD"), cancellable = true)
    private void pvp$preventDropUiItems(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
        if (PvpGuiManager.isMenuItem(stack) || PvpGuiManager.isQueueItem(stack) || PvpGuiManager.isSpectatorUiItem(stack)) {
            cir.setReturnValue(null);
        }
    }
}
