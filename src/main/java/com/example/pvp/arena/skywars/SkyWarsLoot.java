package com.example.pvp.arena.skywars;

import com.example.pvp.config.EloRating;
import com.example.pvp.config.PlayerStats;
import com.example.pvp.config.StatsStore;
import com.example.pvp.match.SkyWarsItems;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.nbt.NbtCompound;
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
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;

/**
 * 按要求恢复平衡调整前的每箱随机物资表，开局和补货使用相同规则。
 * 恢复旧物品种类、权重、数量与附魔，同时继续使用实际箱位和专用道具标记。
 * 附魔注册表在服务器启动后注入（与 KitManager 相同）。
 */
public final class SkyWarsLoot {
    private static Registry<Enchantment> enchantmentRegistry;

    private SkyWarsLoot() {
    }

    /** 服务器启动后调用：此时附魔注册表可用。 */
    public static void onServerStarted(MinecraftServer server) {
        enchantmentRegistry = server.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
    }

    /** 一条加权战利品条目：weight 权重（可为小数），factory(random, 附魔概率%) 生成物品。 */
    private record LootEntry(double weight, BiFunction<Random, Integer, ItemStack> factory) {
    }

    /** 各套护甲部位。 */
    private static final Item[] IRON_PIECES = {
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS
    };
    private static final Item[] CHAINMAIL_PIECES = {
            Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS
    };
    private static final Item[] GOLD_PIECES = {
            Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS
    };
    private static final Item[] DIAMOND_PIECES = {
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS
    };

    /** 出生岛箱子战利品：以铁装为主，偶见钻石装，必带桥接方块与基础物资。 */
    private static final List<LootEntry> SPAWN_TABLE = List.of(
            new LootEntry(18, (r, c) -> weapon(Items.IRON_SWORD, r, c)),
            new LootEntry(10, (r, c) -> weapon(Items.IRON_AXE, r, c)),
            new LootEntry(6, (r, c) -> bow(r, c)),
            new LootEntry(6, (r, c) -> crossbow(r, c)),
            new LootEntry(10, (r, c) -> arrow(8 + r.nextInt(9))),
            new LootEntry(6, (r, c) -> weapon(Items.DIAMOND_SWORD, r, c)),
            new LootEntry(6, (r, c) -> diamondAxe(r)), // 钻石斧：耐久 1/5，锋利 I~III
            new LootEntry(5, (r, c) -> diamondTool(r, c)), // 普通钻石工具：镐/铲/锄
            new LootEntry(32, (r, c) -> armor(r, c, false)),
            new LootEntry(10, (r, c) -> armor(r, c, true)),
            new LootEntry(8, (r, c) -> goldenArmor(r)),
            new LootEntry(8, (r, c) -> food(r)),
            new LootEntry(12, (r, c) -> stack(Items.GOLDEN_APPLE, 1 + r.nextInt(2))),
            new LootEntry(5, (r, c) -> stack(Items.ENDER_PEARL, 1 + r.nextInt(2))),
            new LootEntry(3, (r, c) -> stack(Items.WATER_BUCKET, 1)),
            new LootEntry(3, (r, c) -> stack(Items.LAVA_BUCKET, 1)),
            new LootEntry(6, (r, c) -> stack(Items.TNT, 1 + r.nextInt(2))),
            new LootEntry(6, (r, c) -> stack(Items.FIRE_CHARGE, 2 + r.nextInt(3))),
            new LootEntry(7, (r, c) -> stack(Items.COBWEB, 2 + r.nextInt(3))),
            new LootEntry(4, (r, c) -> stack(Items.WIND_CHARGE, 1 + r.nextInt(2))),
            new LootEntry(8, (r, c) -> randomPotion(r, false)),
            // 铁砧(配经验瓶附魔)、经验瓶、钓鱼竿(命中击退)、追踪罗盘、雪球、专用击退球、铁质杂项
            new LootEntry(7, (r, c) -> stack(Items.EXPERIENCE_BOTTLE, 8 + r.nextInt(9))),
            new LootEntry(5, (r, c) -> stack(Items.FISHING_ROD, 1)),
            new LootEntry(5, (r, c) -> trackingCompass()),
            new LootEntry(6, (r, c) -> stack(Items.SNOWBALL, 16)),
            new LootEntry(0.2, (r, c) -> slimeBall()), // 专用击退球（刷新率极低，每次只出 1 个）
            new LootEntry(3, (r, c) -> stack(Items.ANVIL, 1)),
            new LootEntry(8, (r, c) -> goldenAxe(r)),
            new LootEntry(6, (r, c) -> junkIron(r)),
            new LootEntry(16, (r, c) -> bridgeBlocks(r)) // 搭桥方块：刷新率上调
    );

    /** 中间主岛箱子战利品：以钻石装为主，金苹果/末影珍珠更常见。 */
    private static final List<LootEntry> MIDDLE_TABLE = List.of(
            new LootEntry(14, (r, c) -> weapon(Items.DIAMOND_SWORD, r, c)),
            new LootEntry(6, (r, c) -> weapon(Items.DIAMOND_AXE, r, c)),
            new LootEntry(8, (r, c) -> diamondAxe(r)), // 钻石斧：耐久 1/5，锋利 I~III
            new LootEntry(6, (r, c) -> diamondTool(r, c)), // 普通钻石工具：镐/铲/锄
            new LootEntry(8, (r, c) -> bow(r, c)),
            new LootEntry(8, (r, c) -> crossbow(r, c)),
            new LootEntry(12, (r, c) -> arrow(12 + r.nextInt(20))),
            new LootEntry(30, (r, c) -> armor(r, c, true)),
            new LootEntry(10, (r, c) -> armor(r, c, false)),
            new LootEntry(8, (r, c) -> goldenArmor(r)),
            new LootEntry(15, (r, c) -> stack(Items.GOLDEN_APPLE, 2 + r.nextInt(3))),
            new LootEntry(8, (r, c) -> stack(Items.ENDER_PEARL, 2 + r.nextInt(3))),
            new LootEntry(6, (r, c) -> food(r)),
            new LootEntry(2, (r, c) -> stack(Items.WATER_BUCKET, 1)),
            new LootEntry(2, (r, c) -> stack(Items.LAVA_BUCKET, 1)),
            new LootEntry(10, (r, c) -> bridgeBlocks(r)), // 搭桥方块：刷新率上调
            new LootEntry(6, (r, c) -> stack(Items.TNT, 2 + r.nextInt(2))),
            new LootEntry(8, (r, c) -> weapon(Items.MACE, r, c)),
            new LootEntry(8, (r, c) -> spear()), // 长矛：与重锤同刷新率（同刷中间岛）
            new LootEntry(6, (r, c) -> stack(Items.WIND_CHARGE, 2 + r.nextInt(3))),
            new LootEntry(6, (r, c) -> stack(Items.FIRE_CHARGE, 3 + r.nextInt(3))),
            new LootEntry(7, (r, c) -> stack(Items.COBWEB, 3 + r.nextInt(4))),
            new LootEntry(10, (r, c) -> randomPotion(r, true)),
            new LootEntry(8, (r, c) -> stack(Items.EXPERIENCE_BOTTLE, 12 + r.nextInt(13))),
            new LootEntry(6, (r, c) -> trackingCompass()),
            new LootEntry(6, (r, c) -> stack(Items.SNOWBALL, 16)),
            new LootEntry(0.2, (r, c) -> slimeBall()), // 专用击退球（刷新率极低，不刷普通球）
            new LootEntry(4, (r, c) -> stack(Items.ANVIL, 1)),
            new LootEntry(6, (r, c) -> goldenAxe(r))
    );

    /** 同组箱子逐箱按旧表独立抽取；补货也恢复装备和稀有物品的正常抽取。 */
    public static void populateIsland(List<ChestBlockEntity> chests, SkyWarsLootPolicy.Tier tier,
                                       int handicap, boolean refill, SkyWarsLootPolicy policy) {
        List<ChestBlockEntity> available = new ArrayList<>(new LinkedHashSet<>(chests));
        available.removeIf(chest -> chest == null);
        Random random = policy.random();
        for (int i = 0; i < available.size(); i++) {
            List<ItemStack> drops = legacyDrops(random, tier, handicap);
            fillChest(available.get(i), drops, random);
        }
    }

    private static List<ItemStack> legacyDrops(Random random, SkyWarsLootPolicy.Tier tier, int handicap) {
        boolean middle = tier == SkyWarsLootPolicy.Tier.CENTER;
        List<ItemStack> drops = new ArrayList<>();
        int stackCount = SkyWarsLootPolicy.randomStackCount(random, tier);
        int enchantBonus = handicap * 10;
        if (!middle) {
            drops.add(bridgeBlocks(random));
            drops.add(bridgeBlocks(random));
            for (int i = 0; i < 3; i++) {
                if ((handicap > 0 && random.nextInt(100) < handicap * 16)
                        || random.nextInt(100) < 10) {
                    drops.add(random.nextBoolean()
                            ? weapon(Items.DIAMOND_SWORD, random, 30 + enchantBonus)
                            : armor(random, 30 + enchantBonus, true));
                } else {
                    drops.add(ironEquipment(random, 30 + enchantBonus));
                }
            }
        }
        for (int i = 0; i < stackCount; i++) {
            ItemStack item = roll(middle ? MIDDLE_TABLE : SPAWN_TABLE, random,
                    (middle ? 50 : 30) + enchantBonus);
            if (!item.isEmpty()) drops.add(item);
        }
        addRareDrop(random, drops, tier);
        if (!middle && handicap > 0) {
            int compensation = random.nextInt(400);
            if (compensation == 0) drops.add(makeMiaoRenAxe());
            else if (compensation == 1) drops.add(new ItemStack(Items.TOTEM_OF_UNDYING));
        }
        return drops;
    }

    private static void fillChest(ChestBlockEntity chest, List<ItemStack> drops, Random random) {
        chest.clear();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < chest.size(); i++) {
            slots.add(i);
        }
        Collections.shuffle(slots, random);
        for (int i = 0; i < drops.size() && i < slots.size(); i++) {
            chest.setStack(slots.get(i), drops.get(i));
        }
        chest.markDirty();
    }

    private static ItemStack roll(List<LootEntry> table, Random random, int enchantChance) {
        double total = 0;
        for (LootEntry entry : table) {
            total += entry.weight();
        }
        double roll = random.nextDouble() * total;
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

    /** 一件随机铁质/锁链装备：护甲（60%，铁/锁链各半）/ 铁剑·铁斧（40%），带附魔概率。适当偏向防具。 */
    private static ItemStack ironEquipment(Random random, int enchantChance) {
        if (random.nextInt(10) < 6) {
            boolean chain = random.nextBoolean(); // 锁链与铁套刷新几率相同
            Item[] pieces = chain ? CHAINMAIL_PIECES : IRON_PIECES;
            ItemStack stack = new ItemStack(pieces[random.nextInt(pieces.length)]);
            enchantArmor(stack, random, enchantChance, chain);
            return stack;
        }
        return weapon(random.nextBoolean() ? Items.IRON_SWORD : Items.IRON_AXE, random, enchantChance);
    }

    /** 铁质杂项：铲子/锄头/镐/斧——大多用处不大，少量会附魔。 */
    private static ItemStack junkIron(Random random) {
        Item[] junk = {Items.IRON_SHOVEL, Items.IRON_HOE, Items.IRON_PICKAXE, Items.IRON_AXE};
        ItemStack stack = new ItemStack(junk[random.nextInt(junk.length)]);
        maybeEnchant(stack, random, 25);
        return stack;
    }

    /**
     * 金斧（中等概率）：普通 / 锋利 I / II / III / VII（VII 约 1/10，稀有）。
     * 金斧耐久低——高锋利是伤高但易碎的"玻璃大炮"。
     */
    private static ItemStack goldenAxe(Random random) {
        ItemStack stack = new ItemStack(Items.GOLDEN_AXE);
        int roll = random.nextInt(100);
        int level;
        if (roll < 25) {
            level = 0; // 普通
        } else if (roll < 50) {
            level = 1;
        } else if (roll < 70) {
            level = 2;
        } else if (roll < 90) {
            level = 3;
        } else {
            level = 7; // 锋利 VII
        }
        if (level > 0) {
            applyEnchant(stack, Enchantments.SHARPNESS, level);
        }
        return stack;
    }

    /** 金质护甲：固定保护 II/III，刻意不附耐久，作为轻量高附魔装备。 */
    private static ItemStack goldenArmor(Random random) {
        ItemStack stack = new ItemStack(GOLD_PIECES[random.nextInt(GOLD_PIECES.length)]);
        applyEnchant(stack, Enchantments.PROTECTION, 2 + random.nextInt(2));
        return stack;
    }

    /** 钻石斧：耐久仅剩 1/5，附魔锋利 I~III（随机）。 */
    private static ItemStack diamondAxe(Random random) {
        ItemStack stack = new ItemStack(Items.DIAMOND_AXE);
        stack.setDamage((int) (stack.getMaxDamage() * 0.8)); // 消耗 80%，剩 1/5
        applyEnchant(stack, Enchantments.SHARPNESS, 1 + random.nextInt(3));
        return stack;
    }

    /** 普通钻石工具：镐/铲/锄，带效率附魔概率（"等"不包含钻石斧，斧单独有锋利版）。 */
    private static ItemStack diamondTool(Random random, int enchantChance) {
        Item[] tools = {Items.DIAMOND_PICKAXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE};
        ItemStack stack = new ItemStack(tools[random.nextInt(tools.length)]);
        if (random.nextInt(100) < enchantChance) {
            applyEnchant(stack, Enchantments.EFFICIENCY, 1 + random.nextInt(3));
            if (random.nextInt(100) < 20) {
                applyEnchant(stack, Enchantments.UNBREAKING, 1 + random.nextInt(2));
            }
        }
        return stack;
    }

    /** 专用击退球由命中逻辑处理；不附原版击退，防止额外击退与原版附魔重复叠加。 */
    private static ItemStack slimeBall() {
        ItemStack stack = new ItemStack(Items.SLIME_BALL);
        SkyWarsItems.markKnockbackBall(stack);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§a击退球"));
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal("§7近战命中时增加击退"))));
        return stack;
    }

    /** 弓：力量随附魔概率走；在此基础上低概率叠加冲击 II / 火矢（各 10%，与力量相互独立）。 */
    private static ItemStack bow(Random random, int enchantChance) {
        ItemStack stack = new ItemStack(Items.BOW);
        maybeEnchant(stack, random, enchantChance);
        if (random.nextInt(100) < 10) {
            applyEnchant(stack, Enchantments.PUNCH, 2); // 冲击 II：低概率词缀
        }
        if (random.nextInt(100) < 10) {
            applyEnchant(stack, Enchantments.FLAME, 1); // 火矢：低概率词缀
        }
        return stack;
    }

    /** 弩：概率附魔快速装填 II（70%）/ 多重射击 III（30%），可同时出现。 */
    private static ItemStack crossbow(Random random, int enchantChance) {
        ItemStack stack = new ItemStack(Items.CROSSBOW);
        if (random.nextInt(100) >= enchantChance) {
            return stack;
        }
        if (random.nextInt(100) < 70) {
            applyEnchant(stack, Enchantments.QUICK_CHARGE, 2); // 快速装填 II
        }
        if (random.nextInt(100) < 30) {
            applyEnchant(stack, Enchantments.MULTISHOT, 3);    // 多重射击 III
        }
        return stack;
    }

    /** 一件护甲：钻石 /（铁·锁链各半，刷新几率相同）。 */
    private static ItemStack armor(Random random, int enchantChance, boolean diamond) {
        Item[] pieces;
        boolean chain = false;
        if (diamond) {
            pieces = DIAMOND_PIECES;
        } else if (random.nextBoolean()) {
            pieces = CHAINMAIL_PIECES; // 锁链：与铁套刷新几率相同
            chain = true;
        } else {
            pieces = IRON_PIECES;
        }
        ItemStack stack = new ItemStack(pieces[random.nextInt(pieces.length)]);
        enchantArmor(stack, random, enchantChance, chain);
        return stack;
    }

    /** 给护甲附魔：锁链以 II/III 级为主，其余走常规 I/II；两者都可能附带耐久。 */
    private static void enchantArmor(ItemStack stack, Random random, int enchantChance, boolean chainmail) {
        if (random.nextInt(100) >= enchantChance) {
            return;
        }
        if (chainmail) {
            // 锁链：保护等级主要 2/3（90%），少量 1 或 4
            int roll = random.nextInt(20);
            int level = roll < 1 ? 1 : roll > 18 ? 4 : (random.nextBoolean() ? 2 : 3);
            applyEnchant(stack, Enchantments.PROTECTION, level);
        } else {
            applyEnchant(stack, Enchantments.PROTECTION, rollLevel(random));
        }
        if (random.nextInt(100) < 20) {
            applyEnchant(stack, Enchantments.UNBREAKING, 1 + random.nextInt(2));
        }
    }

    private static ItemStack food(Random random) {
        return random.nextBoolean()
                ? stack(Items.COOKED_BEEF, 4 + random.nextInt(5))
                : stack(Items.BREAD, 4 + random.nextInt(5));
    }

    /** 桥接方块：橡木木板 / 圆石 5~12 的小叠（分散放置，不再一整大组）。 */
    private static ItemStack bridgeBlocks(Random random) {
        return random.nextBoolean()
                ? stack(Items.OAK_PLANKS, 5 + random.nextInt(8))
                : stack(Items.COBBLESTONE, 5 + random.nextInt(8));
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

    /** 按概率给武器/护甲附魔；等级 I 55% / II 45%，无 III；击退最高 1 级。 */
    private static void maybeEnchant(ItemStack stack, Random random, int chancePercent) {
        if (random.nextInt(100) >= chancePercent) {
            return;
        }
        int level = rollLevel(random);
        if (stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem) {
            applyEnchant(stack, Enchantments.SHARPNESS, level);
            if (random.nextInt(100) < 25) {
                applyEnchant(stack, Enchantments.KNOCKBACK, 1); // 击退最高 1 级
            }
        } else if (stack.getItem() instanceof BowItem) {
            applyEnchant(stack, Enchantments.POWER, SkyWarsLootPolicy.bowPower(random));
        } else if (stack.getItem() instanceof ArmorItem) {
            applyEnchant(stack, Enchantments.PROTECTION, level);
            if (random.nextInt(100) < 20) {
                applyEnchant(stack, Enchantments.UNBREAKING, 1 + random.nextInt(2));
            }
        }
    }

    private static int rollLevel(Random random) {
        return random.nextInt(100) < 55 ? 1 : 2;
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

    /** 敌人追踪罗盘：对局中每秒更新指向最近的敌人。 */
    private static ItemStack trackingCompass() {
        ItemStack stack = new ItemStack(Items.COMPASS);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e敌人追踪罗盘"));
        NbtCompound nbt = new NbtCompound();
        nbt.putString("pvp.tracker", "1");
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return stack;
    }

    /** 恢复每箱的独立稀有抽取：外圈秒人斧/图腾各1%，中岛三种稀有物品各1%。 */
    private static void addRareDrop(Random random, List<ItemStack> drops, SkyWarsLootPolicy.Tier tier) {
        switch (SkyWarsLootPolicy.rareDrop(random, tier)) {
            case AXE -> drops.add(makeMiaoRenAxe());
            case TOTEM -> drops.add(new ItemStack(Items.TOTEM_OF_UNDYING));
            case ELYTRA -> {
                drops.add(new ItemStack(Items.ELYTRA));
                drops.add(stack(Items.FIREWORK_ROCKET, 3));
            }
            case ENCHANTED_GOLDEN_APPLE -> drops.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
            case NONE -> { }
        }
    }

    /**
     * 恢复原来的相对弱势补偿：隐藏分最低者2档，三人以上局次低者1档。
     * 作用于出生岛和中途岛的装备升级、附魔及额外稀有抽取，开局与补货相同。
     * 返回与 players 等长的数组（同索引即同玩家）。
     */
    public static int[] handicapForMatch(List<ServerPlayerEntity> players) {
        int n = players == null ? 0 : players.size();
        double[] ratings = new double[n];
        for (int i = 0; i < n; i++) {
            ratings[i] = ratingOf(players.get(i));
        }
        return SkyWarsLootPolicy.supportLevels(ratings);
    }

    /** 玩家隐藏分（无档案/空指针按初始分，视为同一起跑线）。 */
    private static double ratingOf(ServerPlayerEntity player) {
        if (player == null) {
            return EloRating.INITIAL;
        }
        PlayerStats stats = StatsStore.INSTANCE.getStats(player.getUuid());
        return stats.getRating();
    }

    /**
     * 长矛：1.21.1 原版没有独立的长矛物品（{@code Items} 里只有三叉戟和重锤），
     * 用<b>三叉戟</b>代替——模型/手感最接近长矛，而且可以投掷。
     *
     * <p>附<b>忠诚 III</b>：长矛本来就该"扔出去再收回来"，不附忠诚的话投一次就丢，
     * 箱子里的武器会变成一次性消耗品。另附耐久 III，避免几下就坏。
     *
     * <p>与重锤一样仅在中岛生成。
     */
    private static ItemStack spear() {
        ItemStack stack = new ItemStack(Items.TRIDENT);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§b长矛"));
        applyEnchant(stack, Enchantments.LOYALTY, 3);
        applyEnchant(stack, Enchantments.UNBREAKING, 3);
        return stack;
    }

    /** 妙人斧：去掉特殊命名/特殊处理，直接是耐久仅剩 1 的锋利 255 金斧头（一击必杀）。 */
    private static ItemStack makeMiaoRenAxe() {
        ItemStack axe = new ItemStack(Items.GOLDEN_AXE);
        applyEnchant(axe, Enchantments.SHARPNESS, 255);
        axe.setDamage(axe.getMaxDamage() - 1); // 耐久只剩 1
        return axe;
    }
}
