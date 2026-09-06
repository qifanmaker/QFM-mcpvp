package com.example.pvp.match;

import com.example.pvp.arena.villagedefense.VillageWorldImporter;
import com.example.pvp.text.Messages;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 村庄保卫战商店。优先使用 <b>地图商店箱子内容</b>（原版行为：箱子放什么卖什么、lore 定价格）；
 * 若该图商店为空则退回内置兜底清单。
 */
public final class VillageDefenseShop {

    private VillageDefenseShop() {
    }

    private static final class Entry {
        final ItemStack icon;
        final int cost;
        final boolean golem;
        final boolean wolf;

        Entry(ItemStack icon, int cost, boolean golem, boolean wolf) {
            this.icon = icon;
            this.cost = cost;
            this.golem = golem;
            this.wolf = wolf;
        }
    }

    /** 内置兜底（当地图没有商店箱子/没有带价物品时用）。 */
    private static List<Entry> fallback() {
        List<Entry> list = new ArrayList<>();
        list.add(entry(Items.STONE_SWORD, 60, "石剑"));
        list.add(entry(Items.IRON_SWORD, 130, "铁剑"));
        list.add(entry(Items.DIAMOND_SWORD, 340, "钻石剑"));
        list.add(entry(Items.BOW, 160, "弓"));
        list.add(entry(Items.ARROW, 32, 60, "箭 × 32"));
        list.add(entry(Items.IRON_CHESTPLATE, 160, "铁胸甲"));
        list.add(entry(Items.IRON_LEGGINGS, 130, "铁护腿"));
        list.add(entry(Items.GOLDEN_APPLE, 2, 80, "金苹果 × 2"));
        list.add(entry(Items.OAK_DOOR, 4, 50, "木门 × 4"));
        list.add(new Entry(new ItemStack(Items.IRON_INGOT), 400, true, false));
        list.add(new Entry(new ItemStack(Items.BONE), 250, false, true));
        return list;
    }

    private static Entry entry(Item item, int count, int cost, String name) {
        ItemStack icon = new ItemStack(item, count);
        icon.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, Text.literal("§f" + name));
        return new Entry(icon, cost, false, false);
    }

    private static Entry entry(Item item, int cost, String name) {
        return entry(item, 1, cost, name);
    }

    public static void open(ServerPlayerEntity player, Match match) {
        List<Entry> entries = buildEntries(match);
        NamedScreenHandlerFactory factory = new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("§6§l村庄商店（orbs）");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                ShopHandler handler = new ShopHandler(syncId, inv);
                handler.attach(match, entries);
                return handler;
            }
        };
        player.openHandledScreen(factory);
    }

    private static List<Entry> buildEntries(Match match) {
        List<VillageWorldImporter.ShopItem> src = match.vdShopItems();
        if (!src.isEmpty()) {
            List<Entry> list = new ArrayList<>();
            for (VillageWorldImporter.ShopItem si : src) {
                ItemStack icon = si.icon.copy();
                icon.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                        Text.literal("§f" + si.name));
                list.add(new Entry(icon, si.cost, si.golem, si.wolf));
            }
            return list;
        }
        return fallback();
    }

    static final class ShopHandler extends ScreenHandler {
        private static final int SHOP_SIZE = 18;
        private final SimpleInventory shop = new SimpleInventory(SHOP_SIZE);
        private Match match;
        private List<Entry> entries = List.of();

        ShopHandler(int syncId, PlayerInventory playerInventory) {
            super(ScreenHandlerType.GENERIC_9X2, syncId);
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(this.shop, col + row * 9, 8 + col * 18, 18 + row * 18));
                }
            }
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(playerInventory, 9 + col + row * 9, 8 + col * 18, 59 + row * 18));
                }
            }
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 117));
            }
        }

        void attach(Match match, List<Entry> entries) {
            this.match = match;
            this.entries = entries;
            for (int i = 0; i < SHOP_SIZE; i++) {
                if (i < entries.size()) {
                    ItemStack icon = entries.get(i).icon.copy();
                    List<Text> lore = new ArrayList<>();
                    lore.add(Text.literal("§7价格：§e" + entries.get(i).cost + " orbs"));
                    lore.add(Text.literal("§7点击购买"));
                    icon.set(net.minecraft.component.DataComponentTypes.LORE,
                            new net.minecraft.component.type.LoreComponent(lore));
                    this.shop.setStack(i, icon);
                } else {
                    this.shop.setStack(i, ItemStack.EMPTY);
                }
            }
            this.sendContentUpdates();
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity sp) || this.match == null
                    || slotIndex < 0 || slotIndex >= entries.size()) {
                return;
            }
            if (actionType == SlotActionType.PICKUP || actionType == SlotActionType.QUICK_MOVE) {
                this.buy(sp, this.entries.get(slotIndex));
            }
        }

        private void buy(ServerPlayerEntity sp, Entry e) {
            int orbs = this.match.vdOrbsOf(sp);
            if (orbs < e.cost) {
                sp.sendMessage(Messages.error("货币不足（需要 " + e.cost + "，你有 " + orbs + "）"), false);
                return;
            }
            if (e.golem || e.wolf) {
                this.match.vdAddOrbs(sp, -e.cost, false);
                this.match.vdSpawnPet(sp, e.golem ? "golem" : "wolf");
                return;
            }
            int empty = sp.getInventory().getEmptySlot();
            if (empty == -1) {
                sp.sendMessage(Messages.error("背包已满"), false);
                return;
            }
            sp.getInventory().setStack(empty, e.icon.copy());
            this.match.vdAddOrbs(sp, -e.cost, false);
            sp.sendMessage(Messages.info("已购买！"), false);
            this.sendContentUpdates();
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return true;
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slotIndex) {
            return ItemStack.EMPTY;
        }
    }
}
