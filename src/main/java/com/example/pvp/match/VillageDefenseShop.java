package com.example.pvp.match;

import com.example.pvp.arena.villagedefense.VillageWorldImporter;
import com.example.pvp.text.Messages;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
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
 * 村庄保卫战商店。优先使用 <b>地图商店箱子内容</b>（原版行为）；随后始终追加两件系统道具：
 * 合金锭(锻造用)、附魔之书-经验修补。若图商店为空则退回内置兜底清单。
 */
public final class VillageDefenseShop {

    private VillageDefenseShop() {
    }

    private static final class Entry {
        final ItemStack icon;
        final int cost;
        final boolean golem;
        final boolean wolf;
        final boolean mending;

        Entry(ItemStack icon, int cost) {
            this(icon, cost, false, false, false);
        }

        Entry(ItemStack icon, int cost, boolean golem, boolean wolf, boolean mending) {
            this.icon = icon;
            this.cost = cost;
            this.golem = golem;
            this.wolf = wolf;
            this.mending = mending;
        }
    }

    private static List<Entry> fallback() {
        List<Entry> list = new ArrayList<>();
        list.add(item(Items.STONE_SWORD, 1, 60, "石剑"));
        list.add(item(Items.IRON_SWORD, 1, 130, "铁剑"));
        list.add(item(Items.DIAMOND_SWORD, 1, 340, "钻石剑"));
        list.add(item(Items.BOW, 1, 160, "弓"));
        list.add(item(Items.ARROW, 32, 60, "箭 × 32"));
        list.add(item(Items.IRON_CHESTPLATE, 1, 160, "铁胸甲"));
        list.add(item(Items.GOLDEN_APPLE, 2, 80, "金苹果 × 2"));
        list.add(item(Items.OAK_DOOR, 4, 50, "木门 × 4"));
        return list;
    }

    private static Entry item(ItemStack icon, int cost, String name) {
        icon.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, Text.literal("§f" + name));
        return new Entry(icon, cost);
    }

    private static Entry item(net.minecraft.item.Item it, int count, int cost, String name) {
        return item(new ItemStack(it, count), cost, name);
    }

    /** 在列表末尾追加系统道具：合金锭 + 经验修补书。 */
    private static void appendSystem(List<Entry> list) {
        list.add(item(Items.NETHERITE_INGOT, 1, 120, "下界合金锭（锻造台用）"));
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        book.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, Text.literal("§d附魔之书：经验修补"));
        list.add(new Entry(book, 150, false, false, true));
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
                Handler h = new Handler(syncId, inv);
                h.attach(match, entries);
                return h;
            }
        };
        player.openHandledScreen(factory);
    }

    private static List<Entry> buildEntries(Match match) {
        List<Entry> list = new ArrayList<>();
        List<VillageWorldImporter.ShopItem> src = match.vdShopItems();
        if (!src.isEmpty()) {
            for (VillageWorldImporter.ShopItem si : src) {
                ItemStack icon = si.icon.copy();
                icon.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                        Text.literal("§f" + si.name));
                list.add(new Entry(icon, si.cost, si.golem, si.wolf, false));
            }
        } else {
            list.addAll(fallback());
        }
        appendSystem(list);
        return list;
    }

    static final class Handler extends ScreenHandler {
        private static final int SIZE = 27;
        private final SimpleInventory shop = new SimpleInventory(SIZE);
        private Match match;
        private List<Entry> entries = List.of();

        Handler(int syncId, PlayerInventory playerInventory) {
            super(ScreenHandlerType.GENERIC_9X3, syncId);
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(this.shop, col + row * 9, 8 + col * 18, 18 + row * 18));
                }
            }
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(playerInventory, 9 + col + row * 9, 8 + col * 18, 86 + row * 18));
                }
            }
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 144));
            }
        }

        void attach(Match match, List<Entry> entries) {
            this.match = match;
            this.entries = entries;
            for (int i = 0; i < SIZE; i++) {
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
            if (e.mending) {
                ItemStack held = sp.getMainHandStack();
                if (held.isEmpty()) {
                    sp.sendMessage(Messages.error("请手持想加经验修补的装备再买"), false);
                    return;
                }
                this.match.vdAddOrbs(sp, -e.cost, false);
                VillageDefenseKits.enchantItem(held, Enchantments.MENDING, 1);
                sp.sendMessage(Messages.gold("已给主手装备附上经验修补！"), false);
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
