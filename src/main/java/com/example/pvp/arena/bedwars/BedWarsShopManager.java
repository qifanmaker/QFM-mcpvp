package com.example.pvp.arena.bedwars;

import com.example.pvp.match.Match;
import com.example.pvp.text.Messages;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bed Wars 商店：服务端容器 GUI。
 * 普通商店顶部分类标签页（方块/近战/盔甲/工具/远程/药水/实用），点击切换；
 * 团队升级每种升级只占一格、按等级顺序逐级购买（钻石），用专属图标展示当前等级。
 */
public final class BedWarsShopManager {

    /** 资源类型。 */
    public enum Currency {
        IRON("§f铁锭", Items.IRON_INGOT),
        GOLD("§6金锭", Items.GOLD_INGOT),
        DIAMOND("§b钻石", Items.DIAMOND),
        EMERALD("§a绿宝石", Items.EMERALD);

        final String displayName;
        final Item item;

        Currency(String displayName, Item item) {
            this.displayName = displayName;
            this.item = item;
        }
    }

    /** 普通商店分类。 */
    public enum Category {
        BLOCKS("§e方块", Items.WHITE_WOOL),
        MELEE("§c近战武器", Items.IRON_SWORD),
        ARMOR("§f盔甲", Items.IRON_CHESTPLATE),
        TOOLS("§6工具", Items.IRON_PICKAXE),
        RANGED("§6远程", Items.BOW),
        POTIONS("§d药水", Items.POTION),
        UTILITY("§a实用道具", Items.TNT);

        final String displayName;
        final Item icon;

        Category(String displayName, Item icon) {
            this.displayName = displayName;
            this.icon = icon;
        }
    }

    /** 普通商店商品。 */
    public static final class ShopItem {
        final Category category;
        final Item item;
        final int count;
        final Currency currency;
        final int price;
        final String name;
        final String[] lore;
        final boolean woolColor; // 是否换队伍色羊毛

        ShopItem(Category category, Item item, int count, Currency currency, int price, String name,
                 boolean woolColor, String... lore) {
            this.category = category;
            this.item = item;
            this.count = count;
            this.currency = currency;
            this.price = price;
            this.name = name;
            this.woolColor = woolColor;
            this.lore = lore;
        }
    }

    /**
     * 团队升级项：一条升级链占一格，tierPrices 长度即满级级数，
     * 只能按顺序逐级购买（买第 N 级需已有 N-1 级）。
     */
    public static final class Upgrade {
        final String id;          // 基础 ID（等级存在 Match 里）
        final String name;
        final Item icon;          // 专属展示图标
        final int[] tierPrices;   // 每级钻石价
        final String lore;

        Upgrade(String id, String name, Item icon, int[] tierPrices, String lore) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.tierPrices = tierPrices;
            this.lore = lore;
        }

        int maxLevel() {
            return this.tierPrices.length;
        }
    }

    // ---------- 普通商店商品 ----------
    private static final List<ShopItem> ITEMS = new ArrayList<>();
    // ---------- 团队升级 ----------
    private static final List<Upgrade> UPGRADES = new ArrayList<>();

    static {
        // 方块
        ITEMS.add(new ShopItem(Category.BLOCKS, Items.WHITE_WOOL, 16, Currency.IRON, 4, "§f羊毛", true, "队伍色，搭桥/护床用"));
        ITEMS.add(new ShopItem(Category.BLOCKS, Items.TERRACOTTA, 16, Currency.IRON, 12, "§6硬化黏土", false, "坚固建筑方块"));
        ITEMS.add(new ShopItem(Category.BLOCKS, Items.OAK_PLANKS, 16, Currency.GOLD, 4, "§e木板", false, "廉价建筑方块"));
        ITEMS.add(new ShopItem(Category.BLOCKS, Items.GLASS, 4, Currency.IRON, 12, "§f防爆玻璃", false, "护床用"));
        ITEMS.add(new ShopItem(Category.BLOCKS, Items.END_STONE, 12, Currency.IRON, 24, "§e末地石", false, "抗炸建筑方块"));
        ITEMS.add(new ShopItem(Category.BLOCKS, Items.LADDER, 8, Currency.IRON, 4, "§e梯子", false, "攀爬"));
        ITEMS.add(new ShopItem(Category.BLOCKS, Items.OBSIDIAN, 4, Currency.EMERALD, 4, "§5黑曜石", false, "极抗炸，护床神器"));
        // 近战
        ITEMS.add(new ShopItem(Category.MELEE, Items.STONE_SWORD, 1, Currency.IRON, 10, "§7石剑", false, "基础武器"));
        ITEMS.add(new ShopItem(Category.MELEE, Items.IRON_SWORD, 1, Currency.GOLD, 7, "§f铁剑", false, "强力武器"));
        ITEMS.add(new ShopItem(Category.MELEE, Items.DIAMOND_SWORD, 1, Currency.EMERALD, 4, "§b钻石剑", false, "顶级武器"));
        ITEMS.add(new ShopItem(Category.MELEE, Items.STICK, 1, Currency.GOLD, 5, "§e击退棒", false, "击退 I，把敌人推下虚空"));
        // 盔甲
        ITEMS.add(new ShopItem(Category.ARMOR, Items.CHAINMAIL_LEGGINGS, 1, Currency.IRON, 24, "§7锁链套", false, "永久锁链护腿+靴子"));
        ITEMS.add(new ShopItem(Category.ARMOR, Items.IRON_CHESTPLATE, 1, Currency.GOLD, 12, "§f铁套", false, "永久铁盔甲四件套"));
        ITEMS.add(new ShopItem(Category.ARMOR, Items.DIAMOND_CHESTPLATE, 1, Currency.EMERALD, 6, "§b钻石套", false, "永久钻石盔甲四件套"));
        // 工具
        ITEMS.add(new ShopItem(Category.TOOLS, Items.SHEARS, 1, Currency.IRON, 20, "§f永久剪刀", false, "无限耐久，剪羊毛"));
        ITEMS.add(new ShopItem(Category.TOOLS, Items.WOODEN_AXE, 1, Currency.IRON, 10, "§6木斧", false, "拆方块"));
        ITEMS.add(new ShopItem(Category.TOOLS, Items.IRON_AXE, 1, Currency.IRON, 10, "§f铁斧", false, "拆方块"));
        ITEMS.add(new ShopItem(Category.TOOLS, Items.GOLDEN_AXE, 1, Currency.GOLD, 3, "§e金斧", false, "拆方块快"));
        ITEMS.add(new ShopItem(Category.TOOLS, Items.DIAMOND_AXE, 1, Currency.GOLD, 6, "§b钻石斧", false, "拆方块最快"));
        ITEMS.add(new ShopItem(Category.TOOLS, Items.WOODEN_PICKAXE, 1, Currency.IRON, 10, "§6木镐", false, "挖矿"));
        ITEMS.add(new ShopItem(Category.TOOLS, Items.IRON_PICKAXE, 1, Currency.IRON, 10, "§f铁镐", false, "挖矿"));
        ITEMS.add(new ShopItem(Category.TOOLS, Items.GOLDEN_PICKAXE, 1, Currency.GOLD, 3, "§e金镐", false, "挖矿快"));
        ITEMS.add(new ShopItem(Category.TOOLS, Items.DIAMOND_PICKAXE, 1, Currency.GOLD, 6, "§b钻石镐", false, "挖矿最快"));
        // 远程
        ITEMS.add(new ShopItem(Category.RANGED, Items.ARROW, 8, Currency.GOLD, 2, "§6箭", false, "弓的弹药"));
        ITEMS.add(new ShopItem(Category.RANGED, Items.BOW, 1, Currency.GOLD, 12, "§6普通弓", false, "远程武器"));
        ITEMS.add(new ShopItem(Category.RANGED, Items.BOW, 1, Currency.GOLD, 20, "§6力量 I 弓", false, "力量 I 附魔"));
        ITEMS.add(new ShopItem(Category.RANGED, Items.BOW, 1, Currency.EMERALD, 6, "§5力量+冲击 I 弓", false, "力量 I + 冲击 I"));
        // 药水
        ITEMS.add(new ShopItem(Category.POTIONS, Items.POTION, 1, Currency.EMERALD, 1, "§b速度 II（45秒）", false, "喝下立即生效"));
        ITEMS.add(new ShopItem(Category.POTIONS, Items.POTION, 1, Currency.EMERALD, 1, "§a跳跃提升 V（45秒）", false, "喝下立即生效"));
        ITEMS.add(new ShopItem(Category.POTIONS, Items.POTION, 1, Currency.EMERALD, 2, "§7隐身（30秒）", false, "喝下立即生效"));
        // 实用道具
        ITEMS.add(new ShopItem(Category.UTILITY, Items.GOLDEN_APPLE, 1, Currency.GOLD, 3, "§6金苹果", false, "回血"));
        ITEMS.add(new ShopItem(Category.UTILITY, Items.SILVERFISH_SPAWN_EGG, 1, Currency.IRON, 40, "§7床虱", false, "召唤蠹虫骚扰敌人"));
        ITEMS.add(new ShopItem(Category.UTILITY, Items.FIRE_CHARGE, 1, Currency.IRON, 40, "§c火球", false, "投掷爆炸火球"));
        ITEMS.add(new ShopItem(Category.UTILITY, Items.IRON_GOLEM_SPAWN_EGG, 1, Currency.IRON, 120, "§f梦境守护者", false, "召唤铁傀儡守卫"));
        ITEMS.add(new ShopItem(Category.UTILITY, Items.TNT, 1, Currency.GOLD, 4, "§cTNT", false, "炸床利器"));
        ITEMS.add(new ShopItem(Category.UTILITY, Items.ENDER_PEARL, 1, Currency.EMERALD, 4, "§5末影珍珠", false, "瞬移逃命"));
        ITEMS.add(new ShopItem(Category.UTILITY, Items.WATER_BUCKET, 1, Currency.GOLD, 3, "§b水桶", false, "落地缓冲"));
        ITEMS.add(new ShopItem(Category.UTILITY, Items.EGG, 1, Currency.EMERALD, 1, "§e搭桥蛋", false, "投掷生成搭桥鸡"));
        ITEMS.add(new ShopItem(Category.UTILITY, Items.SPONGE, 4, Currency.GOLD, 3, "§e海绵", false, "吸水"));
        ITEMS.add(new ShopItem(Category.UTILITY, Items.MILK_BUCKET, 1, Currency.GOLD, 4, "§f魔法牛奶", false, "清除所有效果"));
    }

    static {
        // 团队升级（钻石，逐级按序购买；图标专属）
        UPGRADES.add(new Upgrade("sharp", "§e锋利之剑", Items.DIAMOND_SWORD, new int[]{4, 8, 16, 24}, "全队剑附加锋利 I~IV，逐级提升"));
        UPGRADES.add(new Upgrade("prot", "§b保护", Items.IRON_CHESTPLATE, new int[]{2, 4, 8, 16}, "全队盔甲保护 I~IV，逐级提升"));
        UPGRADES.add(new Upgrade("haste", "§e疯狂矿工", Items.GOLDEN_PICKAXE, new int[]{2, 4}, "全队急迫 I~II，逐级提升"));
        UPGRADES.add(new Upgrade("forge", "§e锻炉", Items.FURNACE, new int[]{2, 4, 6, 8}, "资源生成器每次额外多掉 N 份，逐级提升"));
        UPGRADES.add(new Upgrade("heal", "§d治愈池", Items.BEACON, new int[]{1}, "基地床附近持续回血"));
        UPGRADES.add(new Upgrade("dragon", "§5末影龙增益", Items.DRAGON_HEAD, new int[]{5}, "全队短暂获得力量与抗性"));
        UPGRADES.add(new Upgrade("trap", "§c陷阱", Items.TRIPWIRE_HOOK, new int[]{1}, "敌人进入基地触发警报与减速"));
    }

    private BedWarsShopManager() {
    }

    /** 打开普通商店（顶部分类标签页）。 */
    public static void openShop(ServerPlayerEntity player, Match match, int teamIndex) {
        // 用 Match 分队的实际颜色（与名字颜色/出生岛一致），不能用静态索引调色板
        Formatting color = match.teamColorOf(teamIndex);
        NamedScreenHandlerFactory factory = new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("§6§l道具商店");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                ShopScreenHandler handler = new ShopScreenHandler(syncId, inv, false);
                handler.teamColor = color;
                handler.renderShop();
                return handler;
            }
        };
        player.openHandledScreen(factory);
    }

    /** 打开团队升级商店。 */
    public static void openUpgradeShop(ServerPlayerEntity player, Match match, int teamIndex) {
        NamedScreenHandlerFactory factory = new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("§5§l团队升级");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                ShopScreenHandler handler = new ShopScreenHandler(syncId, inv, true);
                handler.renderUpgrades(player);
                return handler;
            }
        };
        player.openHandledScreen(factory);
    }

    /** 某分类下的商品列表（按定义顺序）。 */
    private static List<ShopItem> itemsOf(Category category) {
        List<ShopItem> list = new ArrayList<>();
        for (ShopItem item : ITEMS) {
            if (item.category == category) {
                list.add(item);
            }
        }
        return list;
    }

    /** 制作普通商店展示物品。 */
    private static ItemStack makeShopItem(ShopItem item, Formatting color) {
        Item itemBase = item.item;
        if (item.woolColor) {
            itemBase = woolFor(color);
        }
        ItemStack stack = new ItemStack(itemBase, item.count);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(item.name));
        List<Text> lore = new ArrayList<>();
        for (String line : item.lore) {
            lore.add(Text.literal("§7" + line));
        }
        lore.add(Text.literal(""));
        lore.add(Text.literal("§e价格：§r" + item.currency.displayName + " §ex" + item.price));
        lore.add(Text.literal("§c点击购买"));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    /** 队伍色羊毛。 */
    private static Item woolFor(Formatting color) {
        return switch (color) {
            case RED -> Items.RED_WOOL;
            case BLUE -> Items.BLUE_WOOL;
            case YELLOW -> Items.YELLOW_WOOL;
            case GREEN -> Items.GREEN_WOOL;
            case AQUA -> Items.CYAN_WOOL;
            case WHITE -> Items.WHITE_WOOL;
            case LIGHT_PURPLE -> Items.PINK_WOOL;
            default -> Items.BLACK_WOOL;
        };
    }

    /** 制作升级商店展示物品（专属图标 + 当前等级 + 下一级价格）。 */
    private static ItemStack makeUpgradeItem(Upgrade u, int currentLevel) {
        ItemStack stack = new ItemStack(u.icon);
        boolean maxed = currentLevel >= u.maxLevel();
        String levelText = u.maxLevel() > 1
                ? " §7(" + currentLevel + "/" + u.maxLevel() + ")"
                : (maxed ? " §7(已购买)" : "");
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(u.name + levelText));
        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("§7" + u.lore));
        lore.add(Text.literal(""));
        if (maxed) {
            lore.add(Text.literal("§a已满级"));
            stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        } else {
            if (currentLevel > 0) {
                lore.add(Text.literal("§7当前等级：§e" + currentLevel));
                stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            lore.add(Text.literal("§b下一级价格：钻石 §ex" + u.tierPrices[currentLevel]));
            lore.add(Text.literal("§c点击升级"));
        }
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    /** 尝试购买普通商品。 */
    public static boolean buy(ServerPlayerEntity player, Match match, int teamIndex, ShopItem item) {
        if (!deduct(player, item.currency.item, item.price)) {
            player.sendMessage(Messages.error("资源不足！需要 " + item.currency.displayName + " x" + item.price), false);
            return false;
        }
        give(player, item, match.teamColorOf(teamIndex));
        // 新购买的装备应用团队升级（锋利/保护）
        match.applyUpgradeGearToPlayer(player);
        player.sendMessage(Messages.info("已购买 " + item.name), false);
        return true;
    }

    /** 发放商品（按商品类型特殊处理；teamColor 为队伍实际颜色）。 */
    private static void give(ServerPlayerEntity player, ShopItem item, Formatting teamColor) {
        Item i = item.item;
        if (item.woolColor) {
            // 按队伍色发羊毛
            giveStack(player, new ItemStack(woolFor(teamColor), item.count));
        } else if (i == Items.STICK && item.name.contains("击退棒")) {
            ItemStack kb = new ItemStack(Items.STICK);
            kb.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e击退棒"));
            applyEnchant(kb, player, Enchantments.KNOCKBACK, 1);
            giveStack(player, kb);
        } else if (i == Items.CHAINMAIL_LEGGINGS) {
            equipArmor(player, new ItemStack(Items.CHAINMAIL_LEGGINGS), 2); // 护腿
            equipArmor(player, new ItemStack(Items.CHAINMAIL_BOOTS), 0);   // 靴子
        } else if (i == Items.IRON_CHESTPLATE) {
            equipArmor(player, new ItemStack(Items.IRON_HELMET), 3);     // 头盔
            equipArmor(player, new ItemStack(Items.IRON_CHESTPLATE), 2); // 胸甲
            equipArmor(player, new ItemStack(Items.IRON_LEGGINGS), 1);   // 护腿
            equipArmor(player, new ItemStack(Items.IRON_BOOTS), 0);      // 靴子
        } else if (i == Items.DIAMOND_CHESTPLATE) {
            equipArmor(player, new ItemStack(Items.DIAMOND_HELMET), 3);
            equipArmor(player, new ItemStack(Items.DIAMOND_CHESTPLATE), 2);
            equipArmor(player, new ItemStack(Items.DIAMOND_LEGGINGS), 1);
            equipArmor(player, new ItemStack(Items.DIAMOND_BOOTS), 0);
        } else if (i == Items.SHEARS) {
            ItemStack s = new ItemStack(Items.SHEARS);
            s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f永久剪刀"));
            giveStack(player, s);
        } else if (i == Items.BOW && item.name.contains("力量+冲击")) {
            ItemStack b = new ItemStack(Items.BOW);
            applyEnchant(b, player, Enchantments.POWER, 1);
            applyEnchant(b, player, Enchantments.PUNCH, 1);
            giveStack(player, b);
        } else if (i == Items.BOW && item.name.contains("力量 I")) {
            ItemStack b = new ItemStack(Items.BOW);
            applyEnchant(b, player, Enchantments.POWER, 1);
            giveStack(player, b);
        } else if (i == Items.POTION && item.name.contains("速度")) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 45 * 20, 1, false, false, true));
        } else if (i == Items.POTION && item.name.contains("跳跃")) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 45 * 20, 4, false, false, true));
        } else if (i == Items.POTION && item.name.contains("隐身")) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 30 * 20, 0, false, false, true));
        } else if (i == Items.MILK_BUCKET) {
            player.clearStatusEffects();
        } else {
            giveStack(player, new ItemStack(i, item.count));
        }
        player.currentScreenHandler.sendContentUpdates();
    }

    /** 尝试购买团队升级（按序逐级：当前等级即已购级数，买下一级）。 */
    public static boolean buyUpgrade(ServerPlayerEntity player, Match match, int teamIndex, Upgrade u) {
        int current = match.teamUpgradeLevel(teamIndex, u.id);
        if (current >= u.maxLevel()) {
            player.sendMessage(Messages.error("该升级已满级！"), false);
            return false;
        }
        int price = u.tierPrices[current];
        if (!deduct(player, Items.DIAMOND, price)) {
            player.sendMessage(Messages.error("钻石不足！需要 x" + price), false);
            return false;
        }
        match.applyTeamUpgrade(teamIndex, u.id);
        return true;
    }

    /** 扣除资源。先确认数量足够再扣，避免不够时扣掉部分。 */
    private static boolean deduct(ServerPlayerEntity player, Item item, int amount) {
        var inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        if (total < amount) {
            return false;
        }
        int remaining = amount;
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(item)) {
                int take = Math.min(stack.getCount(), remaining);
                stack.decrement(take);
                remaining -= take;
            }
        }
        return true;
    }

    private static void giveStack(ServerPlayerEntity player, ItemStack stack) {
        if (!player.getInventory().insertStack(stack)) {
            player.getWorld().spawnEntity(new net.minecraft.entity.ItemEntity(
                    player.getWorld(), player.getX(), player.getY(), player.getZ(), stack));
        }
    }

    /** 装备盔甲到指定槽位（0=靴子 1=护腿 2=胸甲 3=头盔），旧装备直接覆盖。 */
    private static void equipArmor(ServerPlayerEntity player, ItemStack stack, int slot) {
        player.getInventory().armor.set(slot, stack);
    }

    private static void applyEnchant(ItemStack stack, ServerPlayerEntity player,
                                     net.minecraft.registry.RegistryKey<Enchantment> key, int level) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Registry<Enchantment> reg = server.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
        RegistryEntry<Enchantment> entry = reg.getEntry(key).orElse(null);
        if (entry != null) {
            stack.addEnchantment(entry, level);
        }
    }

    private static ItemStack filler() {
        ItemStack stack = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
        return stack;
    }

    /** 商店容器 handler：GENERIC_9X6。普通商店顶部 7 个分类标签 + 商品区；升级商店每升级一格。 */
    public static final class ShopScreenHandler extends ScreenHandler {
        public static final int SHOP_SIZE = 54;
        private static final int ITEM_SLOT_START = 9; // 商品区起始槽（第 2 行起）
        private final SimpleInventory shop = new SimpleInventory(SHOP_SIZE);
        private final boolean upgradeShop;
        /** 普通商店：当前分类。 */
        private int category = 0;
        /** 普通商店：当前分类下商品（与显示槽一一对应）。 */
        private List<ShopItem> displayedItems = List.of();
        /** 队伍颜色（普通商店渲染羊毛用）。 */
        Formatting teamColor = Formatting.WHITE;
        /** 升级商店：槽位 → 升级索引。 */
        private final Map<Integer, Integer> upgradeSlots = new HashMap<>();

        public ShopScreenHandler(int syncId, PlayerInventory playerInventory, boolean upgradeShop) {
            super(ScreenHandlerType.GENERIC_9X6, syncId);
            this.upgradeShop = upgradeShop;
            // 商品槽 0-53（6 行）
            for (int row = 0; row < 6; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(this.shop, col + row * 9, 8 + col * 18, 18 + row * 18));
                }
            }
            // 玩家背包 54-80（3 行）+ 快捷栏 81-89
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(playerInventory, 9 + col + row * 9, 8 + col * 18, 140 + row * 18));
                }
            }
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 198));
            }
        }

        public SimpleInventory getMenu() {
            return this.shop;
        }

        /** 重绘普通商店：顶部标签 + 当前分类商品。 */
        void renderShop() {
            for (int i = 0; i < SHOP_SIZE; i++) {
                this.shop.setStack(i, ItemStack.EMPTY);
            }
            Category[] categories = Category.values();
            for (int i = 0; i < categories.length; i++) {
                ItemStack tab = new ItemStack(categories[i].icon);
                boolean selected = i == this.category;
                tab.set(DataComponentTypes.CUSTOM_NAME,
                        Text.literal((selected ? "§a§l▶ " : "") + categories[i].displayName));
                if (selected) {
                    tab.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                }
                this.shop.setStack(i, tab);
            }
            for (int i = categories.length; i < ITEM_SLOT_START; i++) {
                this.shop.setStack(i, filler());
            }
            this.displayedItems = itemsOf(categories[this.category]);
            for (int i = 0; i < this.displayedItems.size() && ITEM_SLOT_START + i < SHOP_SIZE; i++) {
                this.shop.setStack(ITEM_SLOT_START + i, makeShopItem(this.displayedItems.get(i), this.teamColor));
            }
            this.sendContentUpdates();
        }

        /** 重绘升级商店：中间行展示各升级（含当前等级），其余灰色玻璃。 */
        void renderUpgrades(ServerPlayerEntity viewer) {
            for (int i = 0; i < SHOP_SIZE; i++) {
                this.shop.setStack(i, filler());
            }
            this.upgradeSlots.clear();
            var match = com.example.pvp.PvPMod.MATCH == null ? null
                    : com.example.pvp.PvPMod.MATCH.getMatchFor(viewer);
            int teamIdx = match == null ? -1 : match.teamIndexOf(viewer);
            int slot = 10;
            for (int i = 0; i < UPGRADES.size() && slot < SHOP_SIZE; i++, slot++) {
                if (slot % 9 == 0 || slot % 9 == 8) {
                    slot++; // 跳过每行边缘
                }
                Upgrade u = UPGRADES.get(i);
                int level = match != null && teamIdx >= 0 ? match.teamUpgradeLevel(teamIdx, u.id) : 0;
                this.shop.setStack(slot, makeUpgradeItem(u, level));
                this.upgradeSlots.put(slot, i);
            }
            this.sendContentUpdates();
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity sp) || slotIndex < 0 || slotIndex >= SHOP_SIZE) {
                return;
            }
            if (!this.upgradeShop && slotIndex < Category.values().length) {
                // 分类标签：切换并重绘
                this.category = slotIndex;
                this.renderShop();
                return;
            }
            var match = com.example.pvp.PvPMod.MATCH == null ? null
                    : com.example.pvp.PvPMod.MATCH.getMatchFor(sp);
            if (match == null) {
                return;
            }
            int teamIdx = match.teamIndexOf(sp);
            if (this.upgradeShop) {
                Integer upgradeIdx = this.upgradeSlots.get(slotIndex);
                if (upgradeIdx != null
                        && BedWarsShopManager.buyUpgrade(sp, match, teamIdx, UPGRADES.get(upgradeIdx))) {
                    this.renderUpgrades(sp); // 购买成功立即刷新等级显示
                }
            } else {
                int itemIdx = slotIndex - ITEM_SLOT_START;
                if (itemIdx >= 0 && itemIdx < this.displayedItems.size()) {
                    BedWarsShopManager.buy(sp, match, teamIdx, this.displayedItems.get(itemIdx));
                }
            }
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return true;
        }
    }
}
