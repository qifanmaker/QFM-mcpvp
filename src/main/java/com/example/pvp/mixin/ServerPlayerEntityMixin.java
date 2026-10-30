package com.example.pvp.mixin;

import com.example.pvp.gui.PvpGuiManager;
import com.example.pvp.PvPMod;
import com.example.pvp.match.MinecraftPartySession;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 禁止扔出 UI 工具（主菜单指南针、排队红石、观战物品）。
 * 注意：dropSelectedItem 先从背包移除物品再调 dropItem，光取消 dropItem 会让物品凭空消失，
 * 所以取消的同时要把物品放回背包。
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    /**
     * 原图关闭 PvP 时，箭会跳过玩家后继续飞行。只取消伤害会让箭在玩家身上反弹，
     * 因此在原生判断入口提供本轮规则；肉掌及一箭奇迹仍使用原生战斗判断。
     */
    @Inject(method = "shouldDamagePlayer", at = @At("HEAD"), cancellable = true)
    private void pvp$partySourcePlayerCombat(PlayerEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (PvPMod.MATCH == null) return;
        var match = PvPMod.MATCH.getMatchFor((ServerPlayerEntity) (Object) this);
        if (match != null && match.portedSession() instanceof MinecraftPartySession session
                && !session.playerCombatEnabled()) cir.setReturnValue(false);
    }

    @Inject(method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;", at = @At("HEAD"), cancellable = true)
    private void pvp$preventDropUiItems(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
        if (PvpGuiManager.isMenuItem(stack) || PvpGuiManager.isQueueItem(stack) || PvpGuiManager.isSpectatorUiItem(stack)) {
            // 调用方已把物品从背包/光标移除，优先放回当前选中的快捷栏格（即原来的位置），
            // 若该格已被占用再使用 insertStack 兜底，防止 UI 工具丢失或乱跑。
            ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
            PlayerInventory inventory = self.getInventory();
            int selected = inventory.selectedSlot;
            if (inventory.getStack(selected).isEmpty()) {
                inventory.setStack(selected, stack);
            } else {
                inventory.insertStack(stack);
            }
            self.currentScreenHandler.sendContentUpdates();
            cir.setReturnValue(null);
        }
    }
}
