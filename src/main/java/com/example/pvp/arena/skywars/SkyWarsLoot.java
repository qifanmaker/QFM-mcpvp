package com.example.pvp.arena.skywars;

import com.mojang.logging.LogUtils;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.potion.Potion;
import net.minecraft.potion.Potions;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;

/**
 * 空岛战争箱子战利品：加权随机 + 低等级附魔概率高 + 极稀有物品。
 * 附魔注册表在服务器启动后注入（与 KitManager 相同）。
 */
public final class SkyWarsLoot {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static Registry<Enchantment> enchantmentRegistry;

    private SkyWarsLoot() {
    }

    /** 服务器启动后调用：此时附魔注册表可用。 */
    public static void onServerStarted(MinecraftServer server) {
        enchantmentRegistry = server.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
    }

    /** 一条加权战利品条目：weight 权重，factory(random, 附魔概率%) 生成物品。 */
    private record LootEntry(int weight, BiFunction<Random, Integer, ItemStack> factory) {
    }

    /** 出生岛箱子战利品：以铁装为主，偶见钻石装，必带桥接方块与基础物资。 */
    private static final List<LootEntry> SPAWN_TABLE = List.of(
            new LootEntry(14, (r, c) -> weapon(Items.IRON_SWORD, r, c)),
            new LootEntry(6, (r, c) -> weapon(Items.IRON_AXE, r, c)),
            new LootEntry(6, (r, c) -> bow(r, c)),
            new LootEntry(10, (r, c) -> arrow(8 + r.nextInt(9))),
            new LootEntry(8, (r, c) -> weapon(Items.DIAMOND_SWORD, r, c)),
            new LootEntry(14, (r, c) -> armor(r, c, false)),
            new LootEntry(6, (r, c) -> armor(r, c, true)),
            new LootEntry(8, (r, c) -> food(r)),
            new LootEntry(6, (r, c) -> stack(Items.GOLDEN_APPLE, 1 + r.nextInt(2))),
            new LootEntry(5, (r, c) -> stack(Items.ENDER_PEARL, 1 + r.nextInt(2))),
            new LootEntry(3, (r, c) -> stack(Items.WATER_BUCKET, 1)),
            new LootEntry(3, (r, c) -> stack(Items.LAVA_BUCKET, 1)),
            new LootEntry(6, (r, c) -> stack(Items.TNT, 1 + r.nextInt(2))),
            new LootEntry(6, (r, c) -> stack(Items.FIRE_CHARGE, 2 + r.nextInt(3))),
            new LootEntry(5, (r, c) -> stack(Items.COBWEB, 2 + r.nextInt(3))),
            new LootEntry(4, (r, c) -> stack(Items.WIND_CHARGE, 1 + r.nextInt(2))),
            new LootEntry(8, (r, c) -> randomPotion(r, false)),
            new LootEntry(14, (r, c) -> bridgeBlocks(r))
    );

    /** 中间主岛箱子战利品：以钻石装为主，金苹果/末影珍珠更常见。 */
    private static final List<LootEntry> MIDDLE_TABLE = List.of(
            new LootEntry(14, (r, c) -> weapon(Items.DIAMOND_SWORD, r, c)),
            new LootEntry(6, (r, c) -> weapon(Items.DIAMOND_AXE, r, c)),
            new LootEntry(8, (r, c) -> bow(r, c)),
            new LootEntry(12, (r, c) -> arrow(12 + r.nextInt(20))),
            new LootEntry(18, (r, c) -> armor(r, c, true)),
            new LootEntry(6, (r, c) -> armor(r, c, false)),
            new LootEntry(8, (r, c) -> stack(Items.GOLDEN_APPLE, 2 + r.nextInt(3))),
            new LootEntry(8, (r, c) -> stack(Items.ENDER_PEARL, 2 + r.nextInt(3))),
            new LootEntry(6, (r, c) -> food(r)),
            new LootEntry(2, (r, c) -> stack(Items.WATER_BUCKET, 1)),
            new LootEntry(2, (r, c) -> stack(Items.LAVA_BUCKET, 1)),
            new LootEntry(6, (r, c) -> bridgeBlocks(r)),
            new LootEntry(6, (r, c) -> stack(Items.TNT, 2 + r.nextInt(2))),
            new LootEntry(8, (r, c) -> weapon(Items.MACE, r, c)),
            new LootEntry(6, (r, c) -> stack(Items.WIND_CHARGE, 2 + r.nextInt(3))),
            new LootEntry(6, (r, c) -> stack(Items.FIRE_CHARGE, 3 + r.nextInt(3))),
            new LootEntry(5, (r, c) -> stack(Items.COBWEB, 3 + r.nextInt(4))),
            new LootEntry(10, (r, c) -> randomPotion(r, true))
    );

    /** 往一个箱子填充随机战利品。 */
    public static void populate(ChestBlockEntity chest, boolean middle) {
        Random random = new Random();
        List<ItemStack> drops = new ArrayList<>();
        int stackCount = middle ? 4 + random.nextInt(3) : 3 + random.nextInt(2); // 中间 4~6，出生 3~4

        // 出生箱保底一组搭桥方块（岛间距大，没有方块无法搭桥过岛）
        if (!middle) {
            drops.add(bridgeBlocks(random));
        }

        for (int i = 0; i < stackCount; i++) {
            ItemStack stack = middle ? roll(MIDDLE_TABLE, random, 60) : roll(SPAWN_TABLE, random, 35);
            if (!stack.isEmpty()) {
                drops.add(stack);
            }
        }

        // 中间岛：极稀有物品（各 ~1%）
        if (middle) {
            rollUltraRare(random, drops);
        }

        // 随机槽位放入（不覆盖已有物品）
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            slots.add(i);
        }
        Collections.shuffle(slots, random);
        for (int i = 0; i < drops.size() && i < slots.size(); i++) {
            chest.setStack(slots.get(i), drops.get(i));
        }
    }

    private static ItemStack roll(List<LootEntry> table, Random random, int enchantChance) {
        int total = 0;
        for (LootEntry entry : table) {
            total += entry.weight();
        }
        int roll = random.nextInt(total);
        for (LootEntry entry : table) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry.factory().apply(random, enchantChance);
            }
        }
        return ItemStack.EMPTY;
    }

    // ---------- 物品工厂 ----------

    private static ItemStack weapon(Item item, Random random, int enchantChance) {
        ItemStack stack = new ItemStack(item);
        maybeEnchant(stack, random, enchantChance);
        return stack;
    }

    private static ItemStack bow(Random random, int enchantChance) {
        ItemStack stack = new ItemStack(Items.BOW);
        maybeEnchant(stack, random, enchantChance);
        return stack;
    }

    private static ItemStack armor(Random random, int enchantChance, boolean diamond) {
        Item[] pieces = diamond
                ? new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}
                : new Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
        ItemStack stack = new ItemStack(pieces[random.nextInt(pieces.length)]);
        maybeEnchant(stack, random, enchantChance);
        return stack;
    }

    private static ItemStack food(Random random) {
        return random.nextBoolean()
                ? stack(Items.COOKED_BEEF, 4 + random.nextInt(5))
                : stack(Items.BREAD, 4 + random.nextInt(5));
    }

    /** 桥接方块：橡木木板 / 圆石 32~64。 */
    private static ItemStack bridgeBlocks(Random random) {
        return random.nextBoolean()
                ? stack(Items.OAK_PLANKS, 32 + random.nextInt(33))
                : stack(Items.COBBLESTONE, 32 + random.nextInt(33));
    }

    private static ItemStack arrow(int count) {
        return stack(Items.ARROW, count);
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }

    // ---------- 药水：速度 I/II、跳跃 I/II、力量 I、生命恢复 I/II、瞬间治疗 I/II ----------

    private static final List<RegistryEntry<Potion>> POTIONS = List.of(
            Potions.SWIFTNESS, Potions.STRONG_SWIFTNESS,          // 速度 I / II
            Potions.LEAPING, Potions.STRONG_LEAPING,              // 跳跃 I / II
            Potions.STRENGTH,                                     // 力量 I
            Potions.REGENERATION, Potions.STRONG_REGENERATION,    // 生命恢复 I / II
            Potions.HEALING, Potions.STRONG_HEALING               // 瞬间治疗 I / II
    );

    /** 随机一瓶药水（饮用或喷溅；中间主岛更偏向喷溅）。 */
    private static ItemStack randomPotion(Random random, boolean splashBiased) {
        boolean splash = splashBiased ? random.nextInt(3) < 2 : random.nextBoolean();
        Item item = splash ? Items.SPLASH_POTION : Items.POTION;
        RegistryEntry<Potion> potion = POTIONS.get(random.nextInt(POTIONS.size()));
        return potionStack(item, potion);
    }

    private static ItemStack potionStack(Item item, RegistryEntry<Potion> potion) {
        if (potion == null) {
            return ItemStack.EMPTY;
        }
        return PotionContentsComponent.createStack(item, potion);
    }

    // ---------- 附魔：低等级概率高 ----------

    /** 按概率给武器/护甲附魔；等级 I 55% / II 30% / III 15%。 */
    private static void maybeEnchant(ItemStack stack, Random random, int chancePercent) {
        if (random.nextInt(100) >= chancePercent) {
            return;
        }
        int level = rollLevel(random);
        if (stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem) {
            applyEnchant(stack, Enchantments.SHARPNESS, level);
            if (random.nextInt(100) < 25) {
                applyEnchant(stack, Enchantments.KNOCKBACK, 1 + random.nextInt(2));
            }
        } else if (stack.getItem() instanceof BowItem) {
            applyEnchant(stack, Enchantments.POWER, level);
        } else if (stack.getItem() instanceof ArmorItem) {
            applyEnchant(stack, Enchantments.PROTECTION, level);
            if (random.nextInt(100) < 20) {
                applyEnchant(stack, Enchantments.UNBREAKING, 1 + random.nextInt(2));
            }
        }
    }

    private static int rollLevel(Random random) {
        int roll = random.nextInt(100);
        if (roll < 55) {
            return 1;
        }
        return roll < 85 ? 2 : 3;
    }

    private static void applyEnchant(ItemStack stack, RegistryKey<Enchantment> key, int level) {
        if (enchantmentRegistry == null) {
            return;
        }
        RegistryEntry<Enchantment> entry = enchantmentRegistry.getEntry(key).orElse(null);
        if (entry != null) {
            stack.addEnchantment(entry, level);
        }
    }

    // ---------- 极稀有物品 ----------

    /** 中间岛每箱额外约 3% 出极稀有物品（鞘翅、妙人斧、附魔金苹果各 ~1%）。 */
    private static void rollUltraRare(Random random, List<ItemStack> drops) {
        int roll = random.nextInt(100);
        if (roll < 1) {
            // 鞘翅 + 3 根烟花火箭：可以飞掠全图
            drops.add(new ItemStack(Items.ELYTRA));
            drops.add(stack(Items.FIREWORK_ROCKET, 3));
        } else if (roll < 2) {
            drops.add(makeMiaoRenAxe());
        } else if (roll < 3) {
            drops.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
        }
    }

    /** 妙人斧：锋利 666 金斧，耐久 1，一击必杀（梗）。 */
    private static ItemStack makeMiaoRenAxe() {
        ItemStack axe = new ItemStack(Items.GOLDEN_AXE);
        if (enchantmentRegistry != null) {
            RegistryEntry<Enchantment> sharpness = enchantmentRegistry.getEntry(Enchantments.SHARPNESS).orElse(null);
            if (sharpness != null) {
                axe.addEnchantment(sharpness, 666);
            }
        }
        axe.set(DataComponentTypes.MAX_DAMAGE, 1);
        axe.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§6§l妙人斧"));
        axe.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7锋利 666 · 耐久 1"),
                Text.literal("§7一击必杀（梗）"))));
        return axe;
    }
}
