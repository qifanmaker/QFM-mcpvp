package com.example.pvp.kit;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

/**
 * 战桥装备发放：每次重生/进球后补全。Hypixel 风格——
 * 铁剑、弓 + 1 支箭（箭矢由对局每 4 秒补 1）、效率 II 钻石镐、
 * 128 个队伍色陶瓦、8 个金苹果、全套队伍色皮革甲；生存模式（可放/拆方块）。
 */
public final class BridgeGear {
    private static Registry<Enchantment> enchantmentRegistry;

    private BridgeGear() {
    }

    /** 服务器启动后调用：附魔注册表此时可用（与 KitManager 相同）。 */
    public static void onServerStarted(MinecraftServer server) {
        enchantmentRegistry = server.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
    }

    /** 给玩家发一套战桥装备，按队伍颜色染甲/发陶瓦。 */
    public static void apply(ServerPlayerEntity player, Formatting teamColor) {
        var inventory = player.getInventory();
        inventory.clear();

        inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
        inventory.setStack(1, new ItemStack(Items.BOW));
        inventory.setStack(2, new ItemStack(Items.ARROW, 1));

        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        if (enchantmentRegistry != null) {
            RegistryEntry<Enchantment> efficiency = enchantmentRegistry.getEntry(Enchantments.EFFICIENCY).orElse(null);
            if (efficiency != null) {
                pick.addEnchantment(efficiency, 2);
            }
        }
        inventory.setStack(3, pick);

        inventory.setStack(4, new ItemStack(terracotta(teamColor), 128));
        inventory.setStack(5, new ItemStack(Items.GOLDEN_APPLE, 8));

        int rgb = rgb(teamColor);
        inventory.armor.set(3, dyed(Items.LEATHER_HELMET, rgb));
        inventory.armor.set(2, dyed(Items.LEATHER_CHESTPLATE, rgb));
        inventory.armor.set(1, dyed(Items.LEATHER_LEGGINGS, rgb));
        inventory.armor.set(0, dyed(Items.LEATHER_BOOTS, rgb));
        inventory.selectedSlot = 0;

        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(20f);
        player.setAbsorptionAmount(0);
        player.setFireTicks(0);
        player.fallDistance = 0;
        player.clearStatusEffects();
        player.changeGameMode(GameMode.SURVIVAL);
        player.setInvulnerable(false);
        player.currentScreenHandler.sendContentUpdates();
    }

    private static ItemStack dyed(Item item, int rgb) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(rgb, false));
        return stack;
    }

    private static int rgb(Formatting color) {
        return switch (color) {
            case RED -> 0xB02E26;
            case BLUE -> 0x3C44AA;
            case GREEN -> 0x4C763C;
            default -> 0xFED83D;
        };
    }

    private static Item terracotta(Formatting color) {
        return switch (color) {
            case RED -> Items.RED_TERRACOTTA;
            case BLUE -> Items.BLUE_TERRACOTTA;
            case GREEN -> Items.GREEN_TERRACOTTA;
            default -> Items.YELLOW_TERRACOTTA;
        };
    }
}
