package com.example.pvp.arena.bedwars;

import com.example.pvp.match.Match;
import com.example.pvp.text.Messages;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
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
 * Bed Wars 商店：服务端容器 GUI，用铁/金/钻石/绿宝石购买道具。
 * 商品槽点击即购买（扣除背包中的资源并发放商品）。
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

    /** 商品条目。 */
    public record ShopItem(Item item, int count, Currency currency, int price, String name, String... lore) {
    }

    private static final List<ShopItem> ITEMS = new ArrayList<>();

    static {
        // 方块
        ITEMS.add(new ShopItem(Items.WHITE_WOOL, 16, Currency.IRON, 4, "§f羊毛", "队伍色，搭桥/护床用"));
        ITEMS.add(new ShopItem(Items.STONE_BRICKS, 16, Currency.IRON, 16, "§7石砖", "坚固建筑方块"));
        ITEMS.add(new ShopItem(Items.GLASS, 8, Currency.IRON, 12, "§f玻璃", "护床用，看得见打不着"));
        ITEMS.add(new ShopItem(Items.OAK_PLANKS, 16, Currency.IRON, 4, "§6橡木木板", "廉价建筑方块"));
        // 武器
        ITEMS.add(new ShopItem(Items.STONE_SWORD, 1, Currency.IRON, 8, "§7石剑", "基础武器"));
        ITEMS.add(new ShopItem(Items.IRON_SWORD, 1, Currency.IRON, 16, "§f铁剑", "强力武器"));
        ITEMS.add(new ShopItem(Items.DIAMOND_SWORD, 1, Currency.DIAMOND, 8, "§b钻石剑", "顶级武器"));
        ITEMS.add(new ShopItem(Items.BOW, 1, Currency.GOLD, 10, "§6弓", "远程武器"));
        ITEMS.add(new ShopItem(Items.ARROW, 8, Currency.GOLD, 2, "§6箭", "弓的弹药"));
        // 工具
        ITEMS.add(new ShopItem(Items.IRON_PICKAXE, 1, Currency.IRON, 10, "§f铁镐", "破坏敌方方块"));
        ITEMS.add(new ShopItem(Items.IRON_AXE, 1, Currency.IRON, 10, "§f铁斧", "近战 + 拆方块"));
        // 护甲
        ITEMS.add(new ShopItem(Items.IRON_HELMET, 1, Currency.IRON, 16, "§f铁头盔", "护甲"));
        ITEMS.add(new ShopItem(Items.IRON_CHESTPLATE, 1, Currency.IRON, 24, "§f铁胸甲", "护甲"));
        ITEMS.add(new ShopItem(Items.IRON_LEGGINGS, 1, Currency.IRON, 20, "§f铁护腿", "护甲"));
        ITEMS.add(new ShopItem(Items.IRON_BOOTS, 1, Currency.IRON, 16, "§f铁靴子", "护甲"));
        // 消耗品
        ITEMS.add(new ShopItem(Items.GOLDEN_APPLE, 1, Currency.GOLD, 6, "§6金苹果", "回血"));
        ITEMS.add(new ShopItem(Items.ENDER_PEARL, 1, Currency.DIAMOND, 2, "§5末影珍珠", "瞬移逃命"));
        ITEMS.add(new ShopItem(Items.TNT, 4, Currency.GOLD, 8, "§cTNT", "炸床利器"));
    }

    private BedWarsShopManager() {
    }

    /** 商品列表。 */
    public static List<ShopItem> items() {
        return ITEMS;
    }

    /** 打开商店（服务端容器）。 */
    public static void open(ServerPlayerEntity player, Match match, int teamIndex) {
        Formatting color = BedWarsLayout.color(teamIndex);
        NamedScreenHandlerFactory factory = new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("§6§l商店");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                BedWarsShopScreenHandler handler = new BedWarsShopScreenHandler(syncId, inv, match, teamIndex, color);
                int slot = 0;
                for (ShopItem item : ITEMS) {
                    handler.getMenu().setStack(slot++, makeShopItem(item, color));
                }
                return handler;
            }
        };
        player.openHandledScreen(factory);
    }

    /** 制作商店展示物品：队伍色羊毛 + 价格 lore。 */
    private static ItemStack makeShopItem(ShopItem item, Formatting color) {
        Item itemBase = item.item();
        // 羊毛换队伍色
        if (itemBase == Items.WHITE_WOOL) {
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
        ItemStack stack = new ItemStack(itemBase, item.count());
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(item.name()));
        List<Text> lore = new ArrayList<>();
        for (String line : item.lore()) {
            lore.add(Text.literal("§7" + line));
        }
        lore.add(Text.literal(""));
        lore.add(Text.literal("§e价格：§r" + item.currency().displayName + " §ex" + item.price()));
        lore.add(Text.literal("§c点击购买"));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    /** 尝试购买：检查资源并扣除、发放商品。返回是否成功。 */
    public static boolean buy(ServerPlayerEntity player, ShopItem item) {
        // 扣除资源
        int remaining = item.price();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(item.currency().item)) {
                int take = Math.min(stack.getCount(), remaining);
                stack.decrement(take);
                remaining -= take;
            }
        }
        if (remaining > 0) {
            player.sendMessage(Messages.error("资源不足！需要 " + item.currency().displayName + " x" + item.price()), false);
            // 归还已扣除的资源
            ItemStack refund = new ItemStack(item.currency().item, item.price() - remaining);
            if (!inventory.insertStack(refund)) {
                player.getWorld().spawnEntity(new net.minecraft.entity.ItemEntity(
                        player.getWorld(), player.getX(), player.getY(), player.getZ(), refund));
            }
            return false;
        }
        // 发放商品
        ItemStack product = new ItemStack(item.item(), item.count());
        if (item.item() == Items.IRON_PICKAXE || item.item() == Items.IRON_AXE) {
            // 效率 II 附魔
            MinecraftServer server = player.getServer();
            if (server != null) {
                Registry<Enchantment> reg = server.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
                RegistryEntry<Enchantment> eff = reg.getEntry(Enchantments.EFFICIENCY).orElse(null);
                if (eff != null) {
                    product.addEnchantment(eff, 2);
                }
            }
        }
        if (!inventory.insertStack(product)) {
            player.getWorld().spawnEntity(new net.minecraft.entity.ItemEntity(
                    player.getWorld(), player.getX(), player.getY(), player.getZ(), product));
        }
        player.sendMessage(Messages.info("已购买 " + item.name()), false);
        player.currentScreenHandler.sendContentUpdates();
        return true;
    }

    /** 商店容器 handler：GENERIC_9X5（45 格，前 18 格为商品，其余为玩家背包）。 */
    public static final class BedWarsShopScreenHandler extends ScreenHandler {
        public static final int SHOP_SIZE = 18;
        private final SimpleInventory shop = new SimpleInventory(SHOP_SIZE);

        public BedWarsShopScreenHandler(int syncId, PlayerInventory playerInventory, Match match,
                                        int teamIndex, Formatting color) {
            super(ScreenHandlerType.GENERIC_9X5, syncId);
            // 商品槽 0-17（2 行）
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(this.shop, col + row * 9, 8 + col * 18, 18 + row * 18));
                }
            }
            // 玩家背包 18-44（3 行）
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(playerInventory, 9 + col + row * 9, 8 + col * 18, 84 + row * 18));
                }
            }
        }

        public SimpleInventory getMenu() {
            return this.shop;
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (slotIndex >= 0 && slotIndex < SHOP_SIZE && player instanceof ServerPlayerEntity sp) {
                int itemIndex = slotIndex;
                if (itemIndex < BedWarsShopManager.items().size()) {
                    BedWarsShopManager.buy(sp, BedWarsShopManager.items().get(itemIndex));
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
