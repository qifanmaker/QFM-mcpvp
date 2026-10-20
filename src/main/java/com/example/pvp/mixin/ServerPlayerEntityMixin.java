package com.example.pvp.mixin;

import com.example.pvp.gui.PvpGuiManager;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
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
     * tab 列表 / 头顶名字的显示名：原版返回 {@code null}（客户端退回 GameProfile 名字），
     * 这里改成带 Elo 段位前缀的显示名。{@code PlayerListS2CPacket.Entry} 会用这个值，
     * 所以一处改动同时覆盖 tab 与头顶名牌；{@code RankTitles.styled} 是幂等的，
     * 不会和 {@code getDisplayName} 的前缀叠加成两层。
     */
    @Inject(method = "getPlayerListName", at = @At("RETURN"), cancellable = true)
    private void pvp$rankPrefixInTabList(CallbackInfoReturnable<Text> cir) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        cir.setReturnValue(com.example.pvp.text.RankTitles.styled(self.getDisplayName(), self.getUuid()));
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
