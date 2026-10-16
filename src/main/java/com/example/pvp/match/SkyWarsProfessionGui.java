package com.example.pvp.match;

import com.example.pvp.PvPMod;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/** 空岛战争倒计时职业选择界面。 */
public final class SkyWarsProfessionGui {
    private SkyWarsProfessionGui() {
    }

    public static void open(ServerPlayerEntity player, Match match) {
        List<SkyWarsProfession> professions = List.of(SkyWarsProfession.values());
        player.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("§6§l选择空岛战争职业");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity ignored) {
                Handler handler = new Handler(syncId, inventory, match, professions);
                handler.render(player);
                return handler;
            }
        });
    }

    private static final class Handler extends ScreenHandler {
        private final SimpleInventory grid = new SimpleInventory(27);
        private final Match match;
        private final List<SkyWarsProfession> professions;

        private Handler(int syncId, PlayerInventory inventory, Match match, List<SkyWarsProfession> professions) {
            super(ScreenHandlerType.GENERIC_9X3, syncId);
            this.match = match;
            this.professions = professions;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(this.grid, col + row * 9, 8 + col * 18, 18 + row * 18));
                }
            }
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(inventory, 9 + col + row * 9, 8 + col * 18, 86 + row * 18));
                }
            }
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, col, 8 + col * 18, 144));
            }
        }

        private void render(ServerPlayerEntity player) {
            SkyWarsProfession selected = this.match.skywarsProfessionOf(player.getUuid());
            for (int i = 0; i < this.grid.size(); i++) {
                if (i >= this.professions.size()) {
                    this.grid.setStack(i, ItemStack.EMPTY);
                    continue;
                }
                SkyWarsProfession profession = this.professions.get(i);
                ItemStack icon = new ItemStack(profession.icon());
                icon.set(DataComponentTypes.CUSTOM_NAME,
                        Text.literal((profession == selected ? "§a✔ " : "§f") + profession.displayName()));
                List<Text> lore = new java.util.ArrayList<>();
                lore.add(Text.literal("§7开局获得固定装备"));
                if (profession.maxHealthMultiplier() > 1.0) {
                    lore.add(Text.literal("§c最大生命值 ×1.5"));
                }
                lore.add(Text.literal(profession == selected ? "§a当前选择" : "§e点击选择"));
                icon.set(DataComponentTypes.LORE, new LoreComponent(lore));
                this.grid.setStack(i, icon);
            }
            this.sendContentUpdates();
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity sp)
                    || slotIndex < 0 || slotIndex >= this.professions.size()
                    || (actionType != SlotActionType.PICKUP && actionType != SlotActionType.QUICK_MOVE)) {
                return;
            }
            if (this.match.getState() != MatchState.COUNTDOWN) {
                sp.closeHandledScreen();
                return;
            }
            SkyWarsProfession profession = this.professions.get(slotIndex);
            this.match.setSkywarsProfession(sp.getUuid(), profession);
            sp.sendMessage(Text.literal("§a已选择空岛战争职业：§e" + profession.displayName()), false);
            sp.closeHandledScreen();
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return player instanceof ServerPlayerEntity sp
                    && PvPMod.MATCH != null
                    && PvPMod.MATCH.getMatchFor(sp) == this.match
                    && this.match.getState() == MatchState.COUNTDOWN;
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slotIndex) {
            return ItemStack.EMPTY;
        }
    }
}
