package com.example.pvp.match;

import com.example.pvp.PvPMod;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/** 空岛战争职业预览与倒计时选择界面。 */
public final class SkyWarsProfessionGui {
    private SkyWarsProfessionGui() {
    }

    public static void open(ServerPlayerEntity player, Match match) {
        List<SkyWarsProfession> professions = List.of(SkyWarsProfession.values());
        player.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal(match == null ? "§6§l空岛战争职业预览" : "§6§l选择空岛战争职业");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity ignored) {
                Handler handler = new Handler(syncId, inventory, match, professions);
                handler.render(player);
                return handler;
            }
        });
    }

    /** 提前了解开局物资，避免只能在五秒倒计时内阅读全部职业。 */
    public static void openPreview(ServerPlayerEntity player) {
        open(player, null);
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
            SkyWarsProfession selected = this.match == null ? null : this.match.skywarsProfessionOf(player.getUuid());
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
                lore.add(Text.literal("§7" + profession.description()));
                lore.add(Text.literal("§c生命值：" + (int) (20 * profession.maxHealthMultiplier()) + " 点"));
                lore.add(Text.literal("§6开局物资："));
                for (ItemStack item : profession.createLoadout(player)) {
                    lore.add(Text.literal("§f• ").append(item.getName()).append(" §f×" + item.getCount())
                            .append(item.getItem() instanceof ArmorItem ? " §7（自动穿戴）" : ""));
                    for (var enchantment : item.getEnchantments().getEnchantmentEntries()) {
                        lore.add(Text.literal("  §7").append(Enchantment.getName(enchantment.getKey(), enchantment.getIntValue())));
                    }
                    PotionContentsComponent potion = item.get(DataComponentTypes.POTION_CONTENTS);
                    if (potion != null) {
                        for (var effect : potion.getEffects()) {
                            String level = switch (effect.getAmplifier()) {
                                case 0 -> "Ⅰ";
                                case 1 -> "Ⅱ";
                                default -> Integer.toString(effect.getAmplifier() + 1);
                            };
                            int seconds = effect.getDuration() / 20;
                            String duration = seconds > 0 ? "（" + seconds / 60 + ":" + String.format("%02d", seconds % 60) + "）" : "";
                            lore.add(Text.literal("  §b").append(Text.translatable(effect.getTranslationKey()))
                                    .append(" " + level + duration));
                        }
                    }
                }
                lore.add(Text.literal(this.match == null ? "§7预览物资，开局时可选择职业"
                        : profession == selected ? "§a当前选择" : "§e点击选择"));
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
            if (this.match == null) {
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
                    && (this.match == null || this.match.getState() == MatchState.COUNTDOWN);
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slotIndex) {
            return ItemStack.EMPTY;
        }
    }
}
