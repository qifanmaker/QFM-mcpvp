package com.example.pvp.match;

import com.example.pvp.kit.HotbarSync;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.potion.Potions;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 空岛战争开局职业。通过初始装备的取舍区分职业，避免坦克装备完全覆盖骑士、工具职业缺少起手武器。
 * 发放和预览共用同一份物品，避免界面描述与实际附魔、药水和数量脱节。
 */
public enum SkyWarsProfession {
    WARRIOR("战士", "剑盾齐备，适合稳健近战", Items.IRON_SWORD, List.of(stack(Items.IRON_SWORD), stack(Items.SHIELD), stack(Items.STONE, 16), stack(Items.COOKED_BEEF, 8))),
    KNIGHT("骑士", "铁剑与铁甲齐备，适合开局交战", Items.IRON_CHESTPLATE, List.of(stack(Items.IRON_SWORD), stack(Items.IRON_HELMET), stack(Items.IRON_CHESTPLATE), stack(Items.STONE, 16))),
    ARCHER("弓箭手", "力量弓与充足箭矢，适合远程压制", Items.BOW, List.of(stack(Items.BOW), stack(Items.ARROW, 24), stack(Items.STONE_SWORD), stack(Items.STONE, 8))),
    ENGINEER("工程师", "充足方块与水桶，适合搭路和转移", Items.IRON_PICKAXE, List.of(stack(Items.STONE_SWORD), stack(Items.IRON_PICKAXE), stack(Items.STONE, 40), stack(Items.OAK_PLANKS, 8), stack(Items.WATER_BUCKET))),
    DEMOLITIONIST("爆破手", "炸药破坏掩体，烈焰弹击退敌人", Items.TNT, List.of(stack(Items.TNT, 2), stack(Items.FLINT_AND_STEEL), stack(Items.FIRE_CHARGE, 2), stack(Items.STONE_SWORD), stack(Items.STONE, 8))),
    ALCHEMIST("炼金师", "用增益与治疗药水把握交战时机", Items.POTION, List.of(potion(Potions.STRONG_SWIFTNESS), potion(Potions.FIRE_RESISTANCE), potion(Potions.STRENGTH), potion(Potions.STRONG_HEALING, 2), stack(Items.WOODEN_SWORD), stack(Items.STONE, 8))),
    ASSASSIN("刺客", "珍珠突袭或撤离，适合灵活近战", Items.IRON_SWORD, List.of(stack(Items.IRON_SWORD), stack(Items.ENDER_PEARL, 2), stack(Items.LEATHER_HELMET), stack(Items.STONE, 8))),
    MINER("矿工", "快速挖掘，利用木料和铁锭制作装备", Items.DIAMOND_PICKAXE, List.of(stack(Items.DIAMOND_PICKAXE), stack(Items.OAK_PLANKS, 16), stack(Items.CRAFTING_TABLE), stack(Items.IRON_INGOT, 4), stack(Items.STONE, 16), stack(Items.STONE_SWORD))),
    FISHERMAN("渔夫", "鱼竿打断移动，铁剑衔接近战", Items.FISHING_ROD, List.of(stack(Items.FISHING_ROD), stack(Items.IRON_SWORD), stack(Items.STONE, 16), stack(Items.COOKED_SALMON, 8))),
    TANK("坦克", "30 点生命，初始护甲较少", Items.CHAINMAIL_CHESTPLATE,
            List.of(stack(Items.STONE_SWORD), stack(Items.CHAINMAIL_CHESTPLATE),
                    stack(Items.STONE, 8), stack(Items.COOKED_BEEF, 8)), 1.5),
    SHOE_SENIOR("足力健老人鞋", "悬空右键展开平台，脚下预留 5 格", Items.BLAZE_ROD,
            List.of(SkyWarsItems.rescuePlatform(2), stack(Items.STONE_SWORD), stack(Items.STONE, 8)));

    private final String displayName;
    private final String description;
    private final Item icon;
    private final List<ItemStack> items;
    private final double maxHealthMultiplier;

    SkyWarsProfession(String displayName, String description, Item icon, List<ItemStack> items) {
        this(displayName, description, icon, items, 1.0);
    }

    SkyWarsProfession(String displayName, String description, Item icon, List<ItemStack> items, double maxHealthMultiplier) {
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.items = items;
        this.maxHealthMultiplier = maxHealthMultiplier;
    }

    public String displayName() {
        return this.displayName;
    }

    public Item icon() {
        return this.icon;
    }

    public String description() {
        return this.description;
    }

    public double maxHealthMultiplier() {
        return this.maxHealthMultiplier;
    }

    public List<ItemStack> createLoadout(ServerPlayerEntity player) {
        List<ItemStack> result = new ArrayList<>(this.items.size());
        var enchantments = player.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
        for (ItemStack template : this.items) {
            ItemStack item = template.copy();
            if (this == ARCHER && item.isOf(Items.BOW)) {
                enchantments.getEntry(Enchantments.POWER).ifPresent(entry -> item.addEnchantment(entry, 1));
            } else if (this == MINER && item.isOf(Items.DIAMOND_PICKAXE)) {
                enchantments.getEntry(Enchantments.EFFICIENCY).ifPresent(entry -> item.addEnchantment(entry, 2));
            }
            result.add(item);
        }
        return result;
    }

    public void apply(ServerPlayerEntity player) {
        var maxHealth = player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(20.0 * this.maxHealthMultiplier);
        }
        player.getInventory().clear();
        for (ItemStack item : this.createLoadout(player)) {
            if (item.getItem() instanceof ArmorItem armor) {
                player.equipStack(armor.getSlotType(), item);
            } else if (item.isOf(Items.SHIELD)) {
                player.equipStack(EquipmentSlot.OFFHAND, item);
            } else {
                this.placeInInventory(player, item);
            }
        }
        player.getInventory().selectedSlot = 0;
        player.setHealth(player.getMaxHealth());
        player.sendMessage(Text.literal("§a职业：§e" + this.displayName), false);
        player.currentScreenHandler.sendContentUpdates();
        HotbarSync.sendSelection(player);
    }

    private void placeInInventory(ServerPlayerEntity player, ItemStack item) {
        // 统一热键位置，减少五秒选职业后整理背包的负担：剑、远程、镐、方块、道具、食物。
        int[] preferred = preferredSlots(item);
        for (int slot : preferred) {
            if (player.getInventory().getStack(slot).isEmpty()) {
                player.getInventory().setStack(slot, item);
                return;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            if (player.getInventory().getStack(slot).isEmpty()) {
                player.getInventory().setStack(slot, item);
                return;
            }
        }
    }

    private static int[] preferredSlots(ItemStack item) {
        if (item.getItem() instanceof SwordItem) return new int[] {0};
        if (item.isOf(Items.BOW) || item.isOf(Items.FISHING_ROD)) return new int[] {1};
        if (item.getItem() instanceof ToolItem) return new int[] {2};
        if (item.isOf(Items.STONE)) return new int[] {3};
        if (item.isOf(Items.TNT) || item.isOf(Items.FIRE_CHARGE)) return new int[] {5, 6};
        if (item.isOf(Items.FLINT_AND_STEEL)) return new int[] {7};
        if (item.isOf(Items.CRAFTING_TABLE)) return new int[] {7};
        if (item.getItem() instanceof BlockItem) return new int[] {4};
        if (item.contains(DataComponentTypes.POTION_CONTENTS)) return new int[] {5, 6, 7, 8};
        if (item.contains(DataComponentTypes.FOOD)) return new int[] {8};
        if (item.isOf(Items.ARROW) || item.isOf(Items.IRON_INGOT)) return new int[0];
        return new int[] {5, 6, 7};
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
