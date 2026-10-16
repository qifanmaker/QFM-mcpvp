package com.example.pvp.match;

import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/** 空岛战争开局职业：只提供固定物品，不提供技能或属性加成。 */
public enum SkyWarsProfession {
    WARRIOR("战士", Items.IRON_SWORD, List.of(stack(Items.IRON_SWORD), stack(Items.SHIELD), stack(Items.STONE, 16), stack(Items.COOKED_BEEF, 8))),
    KNIGHT("骑士", Items.IRON_CHESTPLATE, List.of(stack(Items.STONE_SWORD), stack(Items.IRON_HELMET), stack(Items.IRON_CHESTPLATE), stack(Items.STONE, 8))),
    ARCHER("弓箭手", Items.BOW, List.of(stack(Items.BOW), stack(Items.ARROW, 24), stack(Items.STONE_SWORD), stack(Items.STONE, 8))),
    ENGINEER("工程师", Items.IRON_PICKAXE, List.of(stack(Items.IRON_PICKAXE), stack(Items.STONE, 40), stack(Items.OAK_PLANKS, 8), stack(Items.WATER_BUCKET))),
    DEMOLITIONIST("爆破手", Items.TNT, List.of(stack(Items.TNT, 2), stack(Items.FLINT_AND_STEEL), stack(Items.FIRE_CHARGE, 2), stack(Items.STONE_SWORD), stack(Items.STONE, 8))),
    ALCHEMIST("炼金师", Items.POTION, List.of(potion(Potions.STRONG_SWIFTNESS), potion(Potions.FIRE_RESISTANCE), potion(Potions.STRENGTH), potion(Potions.STRONG_HEALING, 2), stack(Items.WOODEN_SWORD), stack(Items.STONE, 8))),
    ASSASSIN("刺客", Items.IRON_SWORD, List.of(stack(Items.IRON_SWORD), stack(Items.ENDER_PEARL, 2), stack(Items.LEATHER_HELMET), stack(Items.STONE, 8))),
    MINER("矿工", Items.DIAMOND_PICKAXE, List.of(stack(Items.DIAMOND_PICKAXE), stack(Items.IRON_PICKAXE), stack(Items.IRON_INGOT, 4), stack(Items.STONE, 16), stack(Items.STONE_SWORD))),
    FISHERMAN("渔夫", Items.FISHING_ROD, List.of(stack(Items.FISHING_ROD), stack(Items.IRON_SWORD), stack(Items.STONE, 16), stack(Items.COOKED_SALMON, 8)));

    private final String displayName;
    private final Item icon;
    private final List<ItemStack> items;

    SkyWarsProfession(String displayName, Item icon, List<ItemStack> items) {
        this.displayName = displayName;
        this.icon = icon;
        this.items = items;
    }

    public String displayName() {
        return this.displayName;
    }

    public Item icon() {
        return this.icon;
    }

    public void apply(ServerPlayerEntity player) {
        player.getInventory().clear();
        for (int i = 0; i < this.items.size() && i < 36; i++) {
            player.getInventory().setStack(i, this.items.get(i).copy());
        }
        player.sendMessage(Text.literal("§a职业：§e" + this.displayName), false);
        player.currentScreenHandler.sendContentUpdates();
    }

    private static ItemStack stack(Item item) {
        return new ItemStack(item);
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }

    private static ItemStack potion(net.minecraft.registry.entry.RegistryEntry<net.minecraft.potion.Potion> potion) {
        return potion(potion, 1);
    }

    private static ItemStack potion(net.minecraft.registry.entry.RegistryEntry<net.minecraft.potion.Potion> potion, int count) {
        ItemStack stack = PotionContentsComponent.createStack(Items.POTION, potion);
        stack.setCount(count);
        return stack;
    }
}
