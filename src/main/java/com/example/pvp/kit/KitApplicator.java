package com.example.pvp.kit;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 将套件应用到玩家身上：清空背包、发放装备、重置生命/饥饿/效果。
 */
public final class KitApplicator {
    private KitApplicator() {
    }

    public static void apply(ServerPlayerEntity player, Kit kit) {
        var inventory = player.getInventory();
        inventory.clear();

        int slot = 0;
        for (ItemStack stack : kit.getInventory()) {
            if (slot >= 9) {
                break; // 只放入主手与前 8 格快捷栏
            }
            inventory.setStack(slot, stack.copy());
            slot++;
        }

        ItemStack[] armor = kit.getArmor();
        // armor 槽位：0=脚 1=腿 2=胸 3=头
        inventory.armor.set(0, armor[3] == null ? ItemStack.EMPTY : armor[3].copy());
        inventory.armor.set(1, armor[2] == null ? ItemStack.EMPTY : armor[2].copy());
        inventory.armor.set(2, armor[1] == null ? ItemStack.EMPTY : armor[1].copy());
        inventory.armor.set(3, armor[0] == null ? ItemStack.EMPTY : armor[0].copy());

        inventory.offHand.set(0, kit.getOffhand() == null ? ItemStack.EMPTY : kit.getOffhand().copy());
        inventory.selectedSlot = 0;

        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(kit.getFood());
        player.getHungerManager().setSaturationLevel(kit.getSaturation());
        player.setAbsorptionAmount(0);
        player.setFireTicks(0);
        player.fallDistance = 0;

        player.clearStatusEffects();
        for (StatusEffectInstance effect : kit.getEffects()) {
            player.addStatusEffect(new StatusEffectInstance(effect));
        }

        player.changeGameMode(kit.getGamemode());
        player.setInvulnerable(false);
        player.currentScreenHandler.sendContentUpdates();
    }
}
