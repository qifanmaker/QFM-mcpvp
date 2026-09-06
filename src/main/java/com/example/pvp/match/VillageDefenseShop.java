package com.example.pvp.match;

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
 * 村庄保卫战商店：右击村民打开。用 orbs(货币) 购买装备/门/狼/傀儡。
 * 复刻 BedWarsShopManager 的 ScreenHandler 模式；点按即买，货币不足给提示。
 */
public final class VillageDefenseShop {

    private VillageDefenseShop() {
    }

    public record Offer(String name, Item item, int count, int cost) {
    }

    private static final List<Offer> OFFERS = List.of(
            new Offer("石剑", Items.STONE_SWORD, 1, 60),
            new Offer("铁剑", Items.IRON_SWORD, 1, 130),
            new Offer("钻石剑", Items.DIAMOND_SWORD, 1, 340),
            new Offer("弓 + 箭", Items.BOW, 1, 160),
            new Offer("箭 × 16", Items.ARROW, 16, 60),
            new Offer("铁胸甲", Items.IRON_CHESTPLATE, 1, 160),
            new Offer("铁护腿", Items.IRON_LEGGINGS, 1, 130),
            new Offer("金苹果 × 2", Items.GOLDEN_APPLE, 2, 80),
            new Offer("木门 × 4", Items.OAK_DOOR, 4, 50)
    );

    public static void open(ServerPlayerEntity player, Match match) {
        NamedScreenHandlerFactory factory = new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("§6§l村庄商店（货币 orbs）");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                ShopHandler handler = new ShopHandler(syncId, inv);
                handler.render(match);
                return handler;
            }
        };
        player.openHandledScreen(factory);
    }

    static final class ShopHandler extends ScreenHandler {
        private static final int SHOP_SIZE = 9;
        private final SimpleInventory shop = new SimpleInventory(SHOP_SIZE);
        private Match match;

        ShopHandler(int syncId, PlayerInventory playerInventory) {
            super(ScreenHandlerType.GENERIC_9X1, syncId);
            for (int i = 0; i < SHOP_SIZE; i++) {
                this.addSlot(new Slot(this.shop, i, 8 + i * 18, 20));
            }
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(playerInventory, 9 + col + row * 9, 8 + col * 18, 51 + row * 18));
                }
            }
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 109));
            }
        }

        void render(Match match) {
            this.match = match;
            for (int i = 0; i < SHOP_SIZE; i++) {
                if (i < OFFERS.size()) {
                    this.shop.setStack(i, makeItem(OFFERS.get(i)));
                } else {
                    this.shop.setStack(i, ItemStack.EMPTY);
                }
            }
            this.sendContentUpdates();
        }

        private static ItemStack makeItem(Offer offer) {
            ItemStack stack = new ItemStack(offer.item, offer.count);
            stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, Text.literal("§f" + offer.name));
            stack.set(net.minecraft.component.DataComponentTypes.LORE,
                    new net.minecraft.component.type.LoreComponent(List.of(
                            Text.literal("§7价格：§e" + offer.cost + " orbs"),
                            Text.literal("§7点击购买"))));
            return stack;
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity sp) || this.match == null
                    || slotIndex < 0 || slotIndex >= SHOP_SIZE) {
                return;
            }
            if (actionType == SlotActionType.PICKUP || actionType == SlotActionType.QUICK_MOVE) {
                if (slotIndex < OFFERS.size()) {
                    this.buy(sp, OFFERS.get(slotIndex));
                }
            }
        }

        private void buy(ServerPlayerEntity sp, Offer offer) {
            int orbs = this.match.vdOrbsOf(sp);
            if (orbs < offer.cost) {
                sp.sendMessage(Messages.error("货币不足（需要 " + offer.cost + "，你有 " + orbs + "）"), false);
                return;
            }
            ItemStack toGive = new ItemStack(offer.item, offer.count);
            int empty = sp.getInventory().getEmptySlot();
            if (empty == -1) {
                sp.sendMessage(Messages.error("背包已满，无法购买"), false);
                return;
            }
            sp.getInventory().setStack(empty, toGive);
            this.match.vdAddOrbs(sp, -offer.cost, false);
            sp.sendMessage(Messages.info("已购买 §f" + offer.name + "§r！"), false);
            this.sendContentUpdates();
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return true;
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slotIndex) {
            return ItemStack.EMPTY; // 商店不支持 Shift 快速移动
        }
    }
}
