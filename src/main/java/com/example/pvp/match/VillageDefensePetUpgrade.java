package com.example.pvp.match;

import net.minecraft.entity.LivingEntity;
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

import java.util.List;

/**
 * 宠物升级菜单（潜行+右击自己的狼/铁傀儡打开）：伤害/生命/速度三系逐级购买。
 * 复刻 BedWarsShopManager 的 ScreenHandler 模式。
 */
public final class VillageDefensePetUpgrade {

    private VillageDefensePetUpgrade() {
    }

    private static final String[] NAMES = {"伤害", "生命", "速度"};
    private static final ItemStack[] ICONS = {
            new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.APPLE), new ItemStack(Items.SUGAR)};

    public static void open(ServerPlayerEntity player, Match match, LivingEntity pet) {
        NamedScreenHandlerFactory factory = new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("§6§l宠物升级");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                Handler h = new Handler(syncId, inv);
                h.attach(match, pet);
                h.render();
                return h;
            }
        };
        player.openHandledScreen(factory);
    }

    static final class Handler extends ScreenHandler {
        private static final int SHOP_SIZE = 9;
        private final SimpleInventory shop = new SimpleInventory(SHOP_SIZE);
        private Match match;
        private LivingEntity pet;

        Handler(int syncId, PlayerInventory playerInventory) {
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

        void attach(Match match, LivingEntity pet) {
            this.match = match;
            this.pet = pet;
        }

        void render() {
            for (int i = 0; i < SHOP_SIZE; i++) {
                this.shop.setStack(i, ItemStack.EMPTY);
            }
            int[] st = this.match.vdPetStatsOf(this.pet);
            for (int k = 0; k < 3; k++) {
                ItemStack item = ICONS[k].copy();
                int next = st[k] + 1;
                int cost = this.match.vdPetUpgradeCost(this.pet, k);
                item.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                        Text.literal("§f" + NAMES[k] + " §7Lv " + st[k] + " → " + next));
                item.set(net.minecraft.component.DataComponentTypes.LORE,
                        new net.minecraft.component.type.LoreComponent(List.of(
                                Text.literal("§7升级费用：§e" + cost + " orbs"),
                                Text.literal("§7点击升级"))));
                this.shop.setStack(k, item);
            }
            this.sendContentUpdates();
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity sp) || this.match == null || this.pet == null
                    || slotIndex < 0 || slotIndex >= 3) {
                return;
            }
            if (actionType == SlotActionType.PICKUP || actionType == SlotActionType.QUICK_MOVE) {
                if (this.match.vdPetBuyUpgrade(sp, this.pet, slotIndex)) {
                    this.render();
                }
            }
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
