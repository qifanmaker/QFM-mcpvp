package com.example.pvp.match;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 村庄保卫战 24 套 Kit（对齐 VD kits/*.yml 名称与技能）。
 * <p>此处只登记"属性"与"初始/补给装备"；主动技能按 {kitId + 手持物品} 在 Match 技能引擎里触发。
 */
public final class VillageDefenseKits {

    private VillageDefenseKits() {
    }

    /** 护甲等级：L=皮革 I=铁 G=金 D=钻石 N=无。 */
    public record KitSpec(String id, String display, Item main, Item food, int foodCount,
                          Item special, String specialName, char armor, int extraHp, boolean arrows,
                          boolean bow, boolean splashPotion, boolean leatherColor) {
    }

    private static final List<KitSpec> SPECS = List.of(
            new KitSpec("archer", "Archer", Items.WOODEN_SWORD, Items.COOKED_BEEF, 10, null, null, 'L', 0, true, true, false, true),
            new KitSpec("blocker", "Blocker", Items.STONE_SWORD, Items.COOKED_BEEF, 10, Items.OAK_FENCE, "Zombie Barrier", 'L', 0, false, false, false, true),
            new KitSpec("cleaner", "Cleaner", Items.WOODEN_SWORD, Items.COOKED_BEEF, 10, Items.BLAZE_ROD, "Cleaner", 'L', 0, false, false, false, true),
            new KitSpec("dog_friend", "Dog Friend", Items.STONE_SWORD, Items.COOKED_PORKCHOP, 8, Items.BONE, "Dog", 'L', 0, false, false, false, true),
            new KitSpec("golem_friend", "Golem Friend", Items.STONE_SWORD, Items.COOKED_PORKCHOP, 8, Items.IRON_INGOT, "Golem", 'L', 0, false, false, false, true),
            new KitSpec("hardcore_master", "Hardcore Master", Items.DIAMOND_SWORD, Items.COOKED_BEEF, 8, null, null, 'N', 0, false, false, false, false),
            new KitSpec("hardcore", "Hardcore", Items.WOODEN_SWORD, Items.COOKIE, 12, null, null, 'L', 0, false, false, true, true),
            new KitSpec("healer", "Healer", Items.WOODEN_SWORD, Items.COOKED_PORKCHOP, 8, Items.POPPY, "Healer", 'L', 0, false, false, true, true),
            new KitSpec("heavy_tank", "Heavy Tank", Items.STICK, Items.COOKED_PORKCHOP, 8, null, null, 'I', 20, false, false, false, false),
            new KitSpec("knight", "Knight", Items.WOODEN_SWORD, Items.COOKED_PORKCHOP, 8, null, null, 'L', 0, false, false, false, true),
            new KitSpec("light_tank", "Light Tank", Items.WOODEN_SWORD, Items.COOKED_PORKCHOP, 8, null, null, 'I', 0, false, false, false, false),
            new KitSpec("looter", "Looter", Items.STONE_SWORD, Items.COOKED_PORKCHOP, 8, Items.ROTTEN_FLESH, "Looter", 'L', 0, false, false, false, true),
            new KitSpec("medic", "Medic", Items.STONE_SWORD, Items.COOKED_PORKCHOP, 8, Items.GHAST_TEAR, "Medic", 'L', 0, false, false, true, true),
            new KitSpec("medium_tank", "Medium Tank", Items.WOODEN_SWORD, Items.COOKED_PORKCHOP, 8, null, null, 'I', 12, false, false, false, false),
            new KitSpec("puncher", "Puncher", Items.DIAMOND_SHOVEL, Items.COOKED_PORKCHOP, 8, null, null, 'L', 0, true, true, false, true),
            new KitSpec("runner", "Runner", Items.STICK, Items.COOKED_PORKCHOP, 8, null, null, 'L', 0, false, false, false, true),
            new KitSpec("shotbow_master", "Shotbow Master", Items.BOW, Items.COOKED_BEEF, 10, null, null, 'L', 0, true, false, false, true),
            new KitSpec("teleporter", "Teleporter", Items.STONE_SWORD, Items.COOKED_BEEF, 10, Items.GHAST_TEAR, "Teleporter", 'G', 0, false, false, false, false),
            new KitSpec("terminator", "Terminator", Items.STONE_SWORD, Items.COOKED_PORKCHOP, 8, Items.BONE, "Terminator", 'L', 0, false, false, true, true),
            new KitSpec("tornado", "Tornado", Items.STONE_SWORD, Items.COOKED_BEEF, 10, Items.COBWEB, "Tornado", 'G', 0, false, false, false, false),
            new KitSpec("wild_naked", "Wild Naked", Items.IRON_SWORD, Items.COOKED_PORKCHOP, 8, null, null, 'N', 0, false, false, true, false),
            new KitSpec("wizard", "Wizard", Items.STICK, Items.COOKED_BEEF, 10, Items.BLAZE_ROD, "Magic wand", 'L', 0, false, false, false, true),
            new KitSpec("worker", "Worker", Items.WOODEN_SWORD, Items.COOKED_BEEF, 10, Items.OAK_DOOR, "Door Regenerator", 'L', 0, true, true, false, true),
            new KitSpec("zombie_teleporter", "Zombie Teleporter", Items.WOODEN_SWORD, Items.COOKED_PORKCHOP, 8, Items.BOOK, "Teleport Zombie", 'N', 0, false, false, false, false)
    );

    public static KitSpec byId(String id) {
        for (KitSpec spec : SPECS) {
            if (spec.id().equalsIgnoreCase(id)) {
                return spec;
            }
        }
        return null;
    }

    public static List<KitSpec> all() {
        return SPECS;
    }

    public static List<String> ids() {
        List<String> list = new ArrayList<>();
        for (KitSpec spec : SPECS) {
            list.add(spec.id());
        }
        return list;
    }

    private static net.minecraft.registry.Registry<net.minecraft.enchantment.Enchantment> enchantments;

    /** 服务器启动后注入附魔注册表。 */
    public static void onServerStarted(net.minecraft.server.MinecraftServer server) {
        enchantments = server.getRegistryManager().get(net.minecraft.registry.RegistryKeys.ENCHANTMENT);
    }

    /** 给玩家按 kit 配装（武器/食物/特殊道具/护甲/弓箭/喷溅药水）。 */
    public static void apply(ServerPlayerEntity player, KitSpec spec) {
        player.getInventory().clear();
        player.getInventory().armor.clear();
        int slot = 0;

        ItemStack main = new ItemStack(spec.main(), 1);
        if (main.getItem() == Items.DIAMOND_SWORD) {
            applyEnch(main, Enchantments.SHARPNESS, 6);
        } else if (main.getItem() == Items.DIAMOND_SHOVEL) {
            applyEnch(main, Enchantments.KNOCKBACK, 5);
        } else if (main.getItem() == Items.IRON_SWORD) {
            applyEnch(main, Enchantments.SHARPNESS, 6);
            applyEnch(main, Enchantments.SMITE, 2);
        }
        player.getInventory().setStack(slot++, main);

        player.getInventory().setStack(slot++, new ItemStack(spec.food(), spec.foodCount()));

        if (spec.bow() || spec.id().equals("puncher")) {
            ItemStack bow = new ItemStack(Items.BOW, 1);
            applyEnch(bow, Enchantments.UNBREAKING, 10);
            player.getInventory().setStack(slot++, bow);
        }
        if (spec.arrows() || spec.id().equals("shotbow_master")) {
            player.getInventory().setStack(slot++, new ItemStack(Items.ARROW, 64));
        }
        if (spec.splashPotion()) {
            // 治疗补给用金苹果近似（真实喷溅治疗药水在技能引擎里给）
            player.getInventory().setStack(slot++, new ItemStack(Items.GOLDEN_APPLE, 3));
        }
        if (spec.special() != null) {
            ItemStack sp = new ItemStack(spec.special(), spec.special().getMaxCount() == 1 ? 1 : 4);
            if (spec.specialName() != null) {
                sp.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e" + spec.specialName()));
            }
            player.getInventory().setStack(slot++, sp);
        }
        if ("wizard".equals(spec.id())) {
            ItemStack dye = new ItemStack(Items.INK_SAC, 4);
            dye.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§eDark essence"));
            player.getInventory().setStack(slot++, dye);
        }

        // 护甲
        Item helmet = Items.AIR, chest = Items.AIR, legs = Items.AIR, boots = Items.AIR;
        switch (spec.armor()) {
            case 'I' -> {
                helmet = Items.IRON_HELMET;
                chest = Items.IRON_CHESTPLATE;
                legs = Items.IRON_LEGGINGS;
                boots = Items.IRON_BOOTS;
            }
            case 'G' -> {
                helmet = Items.GOLDEN_HELMET;
                chest = Items.GOLDEN_CHESTPLATE;
                legs = Items.GOLDEN_LEGGINGS;
                boots = Items.GOLDEN_BOOTS;
            }
            case 'D' -> {
                helmet = Items.DIAMOND_HELMET;
                chest = Items.DIAMOND_CHESTPLATE;
                legs = Items.DIAMOND_LEGGINGS;
                boots = Items.DIAMOND_BOOTS;
            }
            case 'N' -> {
            }
            default -> {
                helmet = Items.LEATHER_HELMET;
                chest = Items.LEATHER_CHESTPLATE;
                legs = Items.LEATHER_LEGGINGS;
                boots = Items.LEATHER_BOOTS;
            }
        }
        if (helmet != Items.AIR) {
            player.getInventory().armor.set(3, new ItemStack(helmet));
            player.getInventory().armor.set(2, new ItemStack(chest));
            player.getInventory().armor.set(1, new ItemStack(legs));
            player.getInventory().armor.set(0, new ItemStack(boots));
        }
        player.currentScreenHandler.sendContentUpdates();
    }

    private static void applyEnch(ItemStack stack, net.minecraft.registry.RegistryKey<net.minecraft.enchantment.Enchantment> key, int level) {
        if (enchantments == null) {
            return;
        }
        enchantments.getEntry(key).ifPresent(e -> stack.addEnchantment(e, level));
    }

    /** 给物品添加附魔（附魔台/商店书用）。 */
    public static void enchantItem(ItemStack stack, net.minecraft.registry.RegistryKey<net.minecraft.enchantment.Enchantment> key, int level) {
        applyEnch(stack, key, level);
    }
}
