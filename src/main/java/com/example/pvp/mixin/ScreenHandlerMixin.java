package com.example.pvp.mixin;

import com.example.pvp.gui.PvpGuiManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 UI 工具锁在背包里：禁止通过容器界面拿起、拖动、Shift、数字键交换等方式移动它们。
 * 与 ServerPlayerEntityMixin 配合：前者防止丢出，这里防止在背包/容器间移动。
 */
@Mixin(ScreenHandler.class)
public class ScreenHandlerMixin {
    @Inject(method = "internalOnSlotClick", at = @At("HEAD"), cancellable = true)
    private void pvp$preventMoveUiItems(int slotIndex, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayerEntity)) {
            return;
        }
        ScreenHandler self = (ScreenHandler) (Object) this;

        // 1) 点击的源槽位是 UI 工具：禁止拿起/Shift/拖动/交换
        if (slotIndex >= 0 && slotIndex < self.slots.size()) {
            ItemStack slotStack = self.getSlot(slotIndex).getStack();
            if (PvpGuiManager.isUiItem(slotStack)) {
                ci.cancel();
                return;
            }
        }

        // 2) 光标正持有 UI 工具：禁止放到任何槽位
        ItemStack cursor = self.getCursorStack();
        if (!cursor.isEmpty() && PvpGuiManager.isUiItem(cursor)) {
            ci.cancel();
            return;
        }

        // 3) 数字键交换（SWAP）的目标快捷栏槽是 UI 工具：禁止把该槽物品换走
        if (actionType == SlotActionType.SWAP && button >= 0 && button < 9) {
            ItemStack target = player.getInventory().getStack(button);
            if (!target.isEmpty() && PvpGuiManager.isUiItem(target)) {
                ci.cancel();
            }
        }
    }
}
