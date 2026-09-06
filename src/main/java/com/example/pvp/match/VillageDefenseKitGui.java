package com.example.pvp.match;

import com.example.pvp.PvPMod;
import com.example.pvp.text.Messages;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;

/**
 * 村庄保卫战"选择职业"GUI：列出全部 24 套 Kit，点击即选定（存入 MatchManager，进对局生效）。
 */
public final class VillageDefenseKitGui {

    private VillageDefenseKitGui() {
    }

    /** 每个 kit 的展示图标（对齐 VD display_item；缺省用主武器）。 */
    private static final Map<String, Item> ICONS = Map.ofEntries(
            Map.entry("archer", net.minecraft.item.Items.BOW),
            Map.entry("blocker", net.minecraft.item.Items.OAK_FENCE),
            Map.entry("cleaner", net.minecraft.item.Items.BLAZE_POWDER),
            Map.entry("dog_friend", net.minecraft.item.Items.BONE),
            Map.entry("golem_friend", net.minecraft.item.Items.IRON_INGOT),
            Map.entry("healer", net.minecraft.item.Items.POPPY),
            Map.entry("looter", net.minecraft.item.Items.ROTTEN_FLESH),
            Map.entry("medic", net.minecraft.item.Items.GHAST_TEAR),
            Map.entry("puncher", net.minecraft.item.Items.DIAMOND_SHOVEL),
            Map.entry("runner", net.minecraft.item.Items.FIREWORK_ROCKET),
            Map.entry("shotbow_master", net.minecraft.item.Items.ARROW),
            Map.entry("teleporter", net.minecraft.item.Items.ENDER_PEARL),
            Map.entry("tornado", net.minecraft.item.Items.COBWEB),
            Map.entry("worker", net.minecraft.item.Items.OAK_DOOR),
            Map.entry("wizard", net.minecraft.item.Items.BLAZE_ROD),
            Map.entry("zombie_teleporter", net.minecraft.item.Items.FISHING_ROD),
            Map.entry("heavy_tank", net.minecraft.item.Items.DIAMOND_CHESTPLATE),
            Map.entry("light_tank", net.minecraft.item.Items.IRON_CHESTPLATE),
            Map.entry("medium_tank", net.minecraft.item.Items.IRON_CHESTPLATE),
            Map.entry("terminator", net.minecraft.item.Items.BONE)
    );

    public static void open(ServerPlayerEntity player) {
        List<VillageDefenseKits.KitSpec> specs = VillageDefenseKits.all();
        NamedScreenHandlerFactory factory = new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("§6§l选择村庄保卫战职业");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                Handler h = new Handler(syncId, inv);
                h.render(player, specs);
                return h;
            }
        };
        player.openHandledScreen(factory);
    }

    static final class Handler extends ScreenHandler {
        private static final int SIZE = 27;
        private final SimpleInventory grid = new SimpleInventory(SIZE);
        private List<VillageDefenseKits.KitSpec> specs = List.of();

        Handler(int syncId, PlayerInventory playerInventory) {
            super(ScreenHandlerType.GENERIC_9X3, syncId);
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(this.grid, col + row * 9, 8 + col * 18, 18 + row * 18));
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

        void render(ServerPlayerEntity viewer, List<VillageDefenseKits.KitSpec> specs) {
            this.specs = specs;
            String current = PvPMod.MATCH == null ? "knight" : PvPMod.MATCH.villageDefenseKitOf(viewer.getUuid());
            for (int i = 0; i < SIZE; i++) {
                if (i >= specs.size()) {
                    this.grid.setStack(i, ItemStack.EMPTY);
                    continue;
                }
                VillageDefenseKits.KitSpec spec = specs.get(i);
                Item icon = ICONS.getOrDefault(spec.id(), spec.main());
                ItemStack stack = new ItemStack(icon);
                boolean sel = spec.id().equals(current);
                stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                        Text.literal((sel ? "§a✔ " : "") + "§f" + spec.display()));
                stack.set(net.minecraft.component.DataComponentTypes.LORE,
                        new net.minecraft.component.type.LoreComponent(List.of(
                                Text.literal("§7护甲：" + armorName(spec.armor())
                                        + (spec.extraHp() > 0 ? "  §c+♥" + (spec.extraHp() / 2) : "")),
                                Text.literal(sel ? "§7当前职业（点击保持）" : "§7点击选择"))));
                this.grid.setStack(i, stack);
            }
            this.sendContentUpdates();
        }

        private static String armorName(char c) {
            return switch (c) {
                case 'I' -> "铁甲";
                case 'G' -> "金甲";
                case 'D' -> "钻甲";
                case 'N' -> "无甲";
                default -> "皮甲";
            };
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity sp) || slotIndex < 0 || slotIndex >= this.specs.size()) {
                return;
            }
            if (actionType == SlotActionType.PICKUP || actionType == SlotActionType.QUICK_MOVE) {
                VillageDefenseKits.KitSpec spec = this.specs.get(slotIndex);
                if (PvPMod.MATCH != null) {
                    PvPMod.MATCH.setVillageDefenseKit(sp.getUuid(), spec.id());
                    com.example.pvp.match.Match m = PvPMod.MATCH.getMatchFor(sp);
                    if (m != null && m.getType() == com.example.pvp.match.MatchType.VILLAGE_DEFENSE) {
                        // 游戏内换职业：存活立即换装（equipVillageKit 内部只在 ACTIVE 且非等待时生效）
                        m.equipVillageKit(sp);
                    } else {
                        sp.sendMessage(Messages.gold("职业已设为 §e" + spec.display()
                                + "§r（进入村庄保卫战后生效）"), false);
                    }
                }
                sp.closeHandledScreen();
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
