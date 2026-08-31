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
import java.util.List;

/**
 * Bed Wars 商店：服务端容器 GUI，分普通商店（铁/金/绿宝石买物品）与团队升级商店（钻石买全队升级）。
 * 商品槽点击即购买。
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

    /** 普通商店商品。 */
    public static final class ShopItem {
        final Item item;
        final int count;
        final Currency currency;
        final int price;
        final String name;
        final String[] lore;
        final boolean woolColor; // 是否换队伍色羊毛

        ShopItem(Item item, int count, Currency currency, int price, String name, boolean woolColor, String... lore) {
            this.item = item;
            this.count = count;
            this.currency = currency;
            this.price = price;
            this.name = name;
            this.woolColor = woolColor;
            this.lore = lore;
        }
    }

    /** 团队升级商店升级项。 */
    public static final class Upgrade {
        final String id;        // 唯一 ID
        final String name;
        final int price;        // 钻石价
        final String[] lore;

        Upgrade(String id, String name, int price, String... lore) {
            this.id = id;
            this.name = name;
            this.price = price;
            this.lore = lore;
        }
    }

    // ---------- 普通商店商品 ----------
    private static final List<ShopItem> ITEMS = new ArrayList<>();
    // ---------- 团队升级 ----------
    private static final List<Upgrade> UPGRADES = new ArrayList<>();

    static {
        // 方块
        ITEMS.add(new ShopItem(Items.WHITE_WOOL, 16, Currency.IRON, 4, "§f羊毛", true, "队伍色，搭桥/护床用"));
        ITEMS.add(new ShopItem(Items.TERRACOTTA, 16, Currency.IRON, 12, "§6硬化黏土", false, "坚固建筑方块"));
        ITEMS.add(new ShopItem(Items.OAK_PLANKS, 16, Currency.GOLD, 4, "§e木板", false, "廉价建筑方块"));
        ITEMS.add(new ShopItem(Items.GLASS, 4, Currency.IRON, 12, "§f防爆玻璃", false, "护床用"));
        ITEMS.add(new ShopItem(Items.END_STONE, 12, Currency.IRON, 24, "§e末地石", false, "抗炸建筑方块"));
        ITEMS.add(new ShopItem(Items.LADDER, 8, Currency.IRON, 4, "§e梯子", false, "攀爬"));
        ITEMS.add(new ShopItem(Items.OBSIDIAN, 4, Currency.EMERALD, 4, "§5黑曜石", false, "极抗炸，护床神器"));
        // 近战
        ITEMS.add(new ShopItem(Items.STONE_SWORD, 1, Currency.IRON, 10, "§7石剑", false, "基础武器"));
        ITEMS.add(new ShopItem(Items.IRON_SWORD, 1, Currency.GOLD, 7, "§f铁剑", false, "强力武器"));
        ITEMS.add(new ShopItem(Items.DIAMOND_SWORD, 1, Currency.EMERALD, 4, "§b钻石剑", false, "顶级武器"));
        ITEMS.add(new ShopItem(Items.STICK, 1, Currency.GOLD, 5, "§e击退棒", false, "击退 I，把敌人推下虚空"));
        // 盔甲
        ITEMS.add(new ShopItem(Items.CHAINMAIL_LEGGINGS, 1, Currency.IRON, 24, "§7锁链套", false, "永久锁链护腿+靴子"));
        ITEMS.add(new ShopItem(Items.IRON_CHESTPLATE, 1, Currency.GOLD, 12, "§f铁套", false, "永久铁盔甲四件套"));
        ITEMS.add(new ShopItem(Items.DIAMOND_CHESTPLATE, 1, Currency.EMERALD, 6, "§b钻石套", false, "永久钻石盔甲四件套"));
        // 工具
        ITEMS.add(new ShopItem(Items.SHEARS, 1, Currency.IRON, 20, "§f永久剪刀", false, "无限耐久，剪羊毛"));
        ITEMS.add(new ShopItem(Items.WOODEN_AXE, 1, Currency.IRON, 10, "§6木斧", false, "拆方块"));
        ITEMS.add(new ShopItem(Items.IRON_AXE, 1, Currency.IRON, 10, "§f铁斧", false, "拆方块"));
        ITEMS.add(new ShopItem(Items.GOLDEN_AXE, 1, Currency.GOLD, 3, "§e金斧", false, "拆方块快"));
        ITEMS.add(new ShopItem(Items.DIAMOND_AXE, 1, Currency.GOLD, 6, "§b钻石斧", false, "拆方块最快"));
        ITEMS.add(new ShopItem(Items.WOODEN_PICKAXE, 1, Currency.IRON, 10, "§6木镐", false, "挖矿"));
        ITEMS.add(new ShopItem(Items.IRON_PICKAXE, 1, Currency.IRON, 10, "§f铁镐", false, "挖矿"));
        ITEMS.add(new ShopItem(Items.GOLDEN_PICKAXE, 1, Currency.GOLD, 3, "§e金镐", false, "挖矿快"));
        ITEMS.add(new ShopItem(Items.DIAMOND_PICKAXE, 1, Currency.GOLD, 6, "§b钻石镐", false, "挖矿最快"));
        // 远程
        ITEMS.add(new ShopItem(Items.ARROW, 8, Currency.GOLD, 2, "§6箭", false, "弓的弹药"));
        ITEMS.add(new ShopItem(Items.BOW, 1, Currency.GOLD, 12, "§6普通弓", false, "远程武器"));
        ITEMS.add(new ShopItem(Items.BOW, 1, Currency.GOLD, 20, "§6力量 I 弓", false, "力量 I 附魔"));
        ITEMS.add(new ShopItem(Items.BOW, 1, Currency.EMERALD, 6, "§5力量+冲击 I 弓", false, "力量 I + 冲击 I"));
        // 药水
        ITEMS.add(new ShopItem(Items.POTION, 1, Currency.EMERALD, 1, "§b速度 II（45秒）", false, "喝下立即生效"));
        ITEMS.add(new ShopItem(Items.POTION, 1, Currency.EMERALD, 1, "§a跳跃提升 V（45秒）", false, "喝下立即生效"));
        ITEMS.add(new ShopItem(Items.POTION, 1, Currency.EMERALD, 2, "§7隐身（30秒）", false, "喝下立即生效"));
        // 实用道具
        ITEMS.add(new ShopItem(Items.GOLDEN_APPLE, 1, Currency.GOLD, 3, "§6金苹果", false, "回血"));
        ITEMS.add(new ShopItem(Items.SILVERFISH_SPAWN_EGG, 1, Currency.IRON, 40, "§7床虱", false, "召唤蠹虫骚扰敌人"));
        ITEMS.add(new ShopItem(Items.FIRE_CHARGE, 1, Currency.IRON, 40, "§c火球", false, "投掷爆炸火球"));
        ITEMS.add(new ShopItem(Items.IRON_GOLEM_SPAWN_EGG, 1, Currency.IRON, 120, "§f梦境守护者", false, "召唤铁傀儡守卫"));
        ITEMS.add(new ShopItem(Items.TNT, 1, Currency.GOLD, 4, "§cTNT", false, "炸床利器"));
        ITEMS.add(new ShopItem(Items.ENDER_PEARL, 1, Currency.EMERALD, 4, "§5末影珍珠", false, "瞬移逃命"));
        ITEMS.add(new ShopItem(Items.WATER_BUCKET, 1, Currency.GOLD, 3, "§b水桶", false, "落地缓冲"));
        ITEMS.add(new ShopItem(Items.EGG, 1, Currency.EMERALD, 1, "§e搭桥蛋", false, "投掷生成搭桥鸡"));
        ITEMS.add(new ShopItem(Items.SPONGE, 4, Currency.GOLD, 3, "§e海绵", false, "吸水"));
        ITEMS.add(new ShopItem(Items.MILK_BUCKET, 1, Currency.GOLD, 4, "§f魔法牛奶", false, "清除所有效果"));
    }

    static {
        // 团队升级（钻石）
        UPGRADES.add(new Upgrade("sharp", "§e锋利之剑", 4, "全队剑附加锋利附魔"));
        UPGRADES.add(new Upgrade("prot1", "§b保护 I", 2, "全队盔甲保护 I"));
        UPGRADES.add(new Upgrade("prot2", "§b保护 II", 4, "全队盔甲保护 II"));
        UPGRADES.add(new Upgrade("prot3", "§b保护 III", 8, "全队盔甲保护 III"));
        UPGRADES.add(new Upgrade("prot4", "§b保护 IV", 16, "全队盔甲保护 IV"));
        UPGRADES.add(new Upgrade("haste1", "§e疯狂矿工 I", 2, "全队急迫 I"));
        UPGRADES.add(new Upgrade("haste2", "§e疯狂矿工 II", 4, "全队急迫 II"));
        UPGRADES.add(new Upgrade("forge1", "§e锻炉 I", 2, "资源生成加速"));
        UPGRADES.add(new Upgrade("forge2", "§e锻炉 II", 4, "资源生成更快"));
        UPGRADES.add(new Upgrade("forge3", "§e锻炉 III", 6, "资源生成再加速"));
        UPGRADES.add(new Upgrade("forge4", "§e锻炉 IV", 8, "资源生成最快"));
        UPGRADES.add(new Upgrade("heal", "§d治愈池", 1, "基地范围内持续回血"));
        UPGRADES.add(new Upgrade("dragon", "§5末影龙增益", 5, "全队短暂获得力量与抗性"));
        UPGRADES.add(new Upgrade("trap", "§c陷阱", 1, "敌人进入基地触发警报与减速"));
    }

    private BedWarsShopManager() {
    }

    /** 打开普通商店。 */
    public static void openShop(ServerPlayerEntity player, Match match, int teamIndex) {
        Formatting color = BedWarsLayout.color(teamIndex);
        NamedScreenHandlerFactory factory = new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("§6§l道具商店");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                ShopScreenHandler handler = new ShopScreenHandler(syncId, inv, false);
                int slot = 0;
                for (ShopItem item : ITEMS) {
                    handler.getMenu().setStack(slot++, makeShopItem(item, color));
                }
                return handler;
            }
        };
        player.openHandledScreen(factory);
    }

    /** 打开团队升级商店。 */
    public static void openUpgradeShop(ServerPlayerEntity player, Match match, int teamIndex) {
        Formatting color = BedWarsLayout.color(teamIndex);
        NamedScreenHandlerFactory factory = new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("§5§l团队升级");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                ShopScreenHandler handler = new ShopScreenHandler(syncId, inv, true);
                int slot = 0;
                for (Upgrade u : UPGRADES) {
                    handler.getMenu().setStack(slot++, makeUpgradeItem(u));
                }
                return handler;
            }
        };
        player.openHandledScreen(factory);
    }

    /** 制作普通商店展示物品。 */
    private static ItemStack makeShopItem(ShopItem item, Formatting color) {
        Item itemBase = item.item;
        if (item.woolColor) {
            itemBase = switch (color) {
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

    /** 制作升级商店展示物品。 */
    private static ItemStack makeUpgradeItem(Upgrade u) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(u.name));
        List<Text> lore = new ArrayList<>();
        for (String line : u.lore) {
            lore.add(Text.literal("§7" + line));
        }
        lore.add(Text.literal(""));
        lore.add(Text.literal("§b价格：钻石 §ex" + u.price));
        lore.add(Text.literal("§c点击升级"));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    /** 尝试购买普通商品。 */
    public static boolean buy(ServerPlayerEntity player, Match match, ShopItem item) {
        if (!deduct(player, item.currency.item, item.price)) {
            player.sendMessage(Messages.error("资源不足！需要 " + item.currency.displayName + " x" + item.price), false);
            return false;
        }
        give(player, item);
        player.sendMessage(Messages.info("已购买 " + item.name), false);
        return true;
    }

    /** 发放商品（按商品类型特殊处理）。 */
    private static void give(ServerPlayerEntity player, ShopItem item) {
        Item i = item.item;
        if (i == Items.WHITE_WOOL || i == Items.RED_WOOL) {
            // 羊毛已被换色，正常发
            giveStack(player, new ItemStack(i, item.count));
        } else if (i == Items.STICK && item.name.contains("击退棒")) {
            ItemStack kb = new ItemStack(Items.STICK);
            kb.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e击退棒"));
            applyEnchant(kb, player, Enchantments.KNOCKBACK, 1);
            giveStack(player, kb);
        } else if (i == Items.CHAINMAIL_LEGGINGS) {
            giveStack(player, new ItemStack(Items.CHAINMAIL_LEGGINGS));
            giveStack(player, new ItemStack(Items.CHAINMAIL_BOOTS));
        } else if (i == Items.IRON_CHESTPLATE) {
            giveStack(player, new ItemStack(Items.IRON_HELMET));
            giveStack(player, new ItemStack(Items.IRON_CHESTPLATE));
            giveStack(player, new ItemStack(Items.IRON_LEGGINGS));
            giveStack(player, new ItemStack(Items.IRON_BOOTS));
        } else if (i == Items.DIAMOND_CHESTPLATE) {
            giveStack(player, new ItemStack(Items.DIAMOND_HELMET));
            giveStack(player, new ItemStack(Items.DIAMOND_CHESTPLATE));
            giveStack(player, new ItemStack(Items.DIAMOND_LEGGINGS));
            giveStack(player, new ItemStack(Items.DIAMOND_BOOTS));
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

    /** 尝试购买团队升级。 */
    public static boolean buyUpgrade(ServerPlayerEntity player, Match match, int teamIndex, Upgrade u) {
        if (!deduct(player, Items.DIAMOND, u.price)) {
            player.sendMessage(Messages.error("钻石不足！需要 x" + u.price), false);
            return false;
        }
        match.applyTeamUpgrade(teamIndex, u.id);
        player.sendMessage(Messages.gold("§a已为全队升级：§r" + u.name), false);
        return true;
    }

    /** 扣除资源。 */
    private static boolean deduct(ServerPlayerEntity player, Item item, int amount) {
        var inventory = player.getInventory();
        int remaining = amount;
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(item)) {
                int take = Math.min(stack.getCount(), remaining);
                stack.decrement(take);
                remaining -= take;
            }
        }
        return remaining <= 0;
    }

    private static void giveStack(ServerPlayerEntity player, ItemStack stack) {
        if (!player.getInventory().insertStack(stack)) {
            player.getWorld().spawnEntity(new net.minecraft.entity.ItemEntity(
                    player.getWorld(), player.getX(), player.getY(), player.getZ(), stack));
        }
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

    /** 商店容器 handler：GENERIC_9X6（54 格商品，普通商店 40 项 / 升级商店 15 项）。 */
    public static final class ShopScreenHandler extends ScreenHandler {
        public static final int SHOP_SIZE = 54;
        private final SimpleInventory shop = new SimpleInventory(SHOP_SIZE);
        private final boolean upgradeShop;

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

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (slotIndex >= 0 && slotIndex < SHOP_SIZE && player instanceof ServerPlayerEntity sp) {
                var match = com.example.pvp.PvPMod.MATCH == null ? null
                        : com.example.pvp.PvPMod.MATCH.getMatchFor(sp);
                if (match != null) {
                    int teamIdx = match.teamIndexOf(sp);
                    if (this.upgradeShop) {
                        if (slotIndex < UPGRADES.size()) {
                            BedWarsShopManager.buyUpgrade(sp, match, teamIdx, UPGRADES.get(slotIndex));
                        }
                    } else {
                        if (slotIndex < ITEMS.size()) {
                            BedWarsShopManager.buy(sp, match, ITEMS.get(slotIndex));
                        }
                    }
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
