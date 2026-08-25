package com.example.pvp.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/**
 * PvP 菜单用的容器 ScreenHandler。
 * 使用原版 GENERIC_9X3（三行箱子）作为客户端渲染，前 27 个槽位当作按钮，
 * 点击即触发 {@link PvpGuiManager#onMenuSlotClick}，玩家自己的物品一律禁止移动。
 */
public class PvpScreenHandler extends ScreenHandler {
    public static final int MENU_SIZE = 27;

    private final SimpleInventory menu = new SimpleInventory(MENU_SIZE);
    private final PvpGuiManager guiManager;
    private final UUID playerUuid;

    public PvpScreenHandler(int syncId, PlayerInventory playerInventory, PvpGuiManager guiManager, UUID playerUuid) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.guiManager = guiManager;
        this.playerUuid = playerUuid;

        // 菜单按钮槽位 0-26
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(this.menu, col + row * 9, 8 + col * 18, 18 + row * 18));
            }
        }
        // 玩家主背包槽位 27-53
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, 9 + col + row * 9, 8 + col * 18, 84 + row * 18));
            }
        }
        // 玩家快捷栏槽位 54-62
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
    }

    public SimpleInventory getMenu() {
        return this.menu;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex >= 0 && slotIndex < MENU_SIZE) {
            this.guiManager.onMenuSlotClick((ServerPlayerEntity) player, slotIndex);
        }
        // 其余槽位点击不产生任何移动
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        this.guiManager.onMenuClosed(this.playerUuid);
    }
}
