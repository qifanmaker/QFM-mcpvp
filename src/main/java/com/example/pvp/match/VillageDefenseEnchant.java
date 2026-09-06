package com.example.pvp.match;

import com.example.pvp.text.Messages;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义附魔台（原版无此玩法，按你需求新增）：
 * 手持武器/装备/工具右击场上"附魔台"方块 → 打开此界面，
 * 把主手装备直接附到满级，消耗 orbs。不依赖原版经验等级/青金石（竞技场无经验体系）。
 */
public final class VillageDefenseEnchant {

    private VillageDefenseEnchant() {
    }

    private static final Map<RegistryKey<Enchantment>, Integer> MAX = new LinkedHashMap<>();

    static {
        MAX.put(Enchantments.SHARPNESS, 5);
        MAX.put(Enchantments.KNOCKBACK, 2);
        MAX.put(Enchantments.FIRE_ASPECT, 2);
        MAX.put(Enchantments.LOOTING, 3);
        MAX.put(Enchantments.PROTECTION, 4);
        MAX.put(Enchantments.THORNS, 3);
        MAX.put(Enchantments.UNBREAKING, 3);
        MAX.put(Enchantments.POWER, 5);
        MAX.put(Enchantments.PUNCH, 2);
        MAX.put(Enchantments.FLAME, 1);
        MAX.put(Enchantments.INFINITY, 1);
        MAX.put(Enchantments.EFFICIENCY, 5);
        MAX.put(Enchantments.FORTUNE, 3);
        MAX.put(Enchantments.SILK_TOUCH, 1);
        MAX.put(Enchantments.FEATHER_FALLING, 4);
    }

    /** 按装备类别给可用附魔列表。 */
    private static List<RegistryKey<Enchantment>> keysFor(ItemStack held) {
        List<RegistryKey<Enchantment>> list = new ArrayList<>();
        boolean sword = held.getItem() instanceof net.minecraft.item.SwordItem;
        boolean axe = held.getItem() instanceof net.minecraft.item.AxeItem;
        boolean bow = held.getItem() instanceof net.minecraft.item.BowItem;
        boolean tool = held.getItem() instanceof net.minecraft.item.ToolItem && !axe;
        boolean armor = held.getItem() instanceof net.minecraft.item.ArmorItem;
        boolean boots = armor && (held.getItem() == Items.DIAMOND_BOOTS || held.getItem() == Items.IRON_BOOTS
                || held.getItem() == Items.LEATHER_BOOTS || held.getItem() == Items.CHAINMAIL_BOOTS
                || held.getItem() == Items.GOLDEN_BOOTS || held.getItem() == Items.NETHERITE_BOOTS);
        add(list, Enchantments.UNBREAKING);
        if (sword) {
            add(list, Enchantments.SHARPNESS);
            add(list, Enchantments.KNOCKBACK);
            add(list, Enchantments.FIRE_ASPECT);
            add(list, Enchantments.LOOTING);
        }
        if (axe) {
            add(list, Enchantments.SHARPNESS);
            add(list, Enchantments.EFFICIENCY);
        }
        if (bow) {
            add(list, Enchantments.POWER);
            add(list, Enchantments.PUNCH);
            add(list, Enchantments.FLAME);
            add(list, Enchantments.INFINITY);
        }
        if (tool) {
            add(list, Enchantments.EFFICIENCY);
            add(list, Enchantments.FORTUNE);
            add(list, Enchantments.SILK_TOUCH);
        }
        if (armor) {
            add(list, Enchantments.PROTECTION);
            add(list, Enchantments.THORNS);
        }
        if (boots) {
            add(list, Enchantments.FEATHER_FALLING);
        }
        return list;
    }

    private static void add(List<RegistryKey<Enchantment>> l, RegistryKey<Enchantment> k) {
        if (MAX.containsKey(k) && !l.contains(k)) {
            l.add(k);
        }
    }

    public static void open(ServerPlayerEntity player, Match match) {
        ItemStack held = player.getMainHandStack();
        List<RegistryKey<Enchantment>> available = keysFor(held);
        if (held.isEmpty() || available.isEmpty()) {
            player.sendMessage(Messages.error("请手持武器/装备/工具（或该装备无可附魔项）再打开附魔台"), false);
            return;
        }
        NamedScreenHandlerFactory factory = new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("§d§l附魔台");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                Handler h = new Handler(syncId, inv);
                h.attach(match, available);
                return h;
            }
        };
        player.openHandledScreen(factory);
    }

    static final class Handler extends ScreenHandler {
        private static final int SIZE = 27;
        private final SimpleInventory grid = new SimpleInventory(SIZE);
        private Match match;
        private List<RegistryKey<Enchantment>> available = List.of();

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

        void attach(Match match, List<RegistryKey<Enchantment>> available) {
            this.match = match;
            this.available = available;
            for (int i = 0; i < SIZE; i++) {
                if (i < available.size()) {
                    RegistryKey<Enchantment> key = available.get(i);
                    int lv = MAX.getOrDefault(key, 1);
                    ItemStack icon = new ItemStack(Items.ENCHANTED_BOOK);
                    icon.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                            Text.literal("§e" + key.getValue().getPath() + " " + roman(lv)));
                    icon.set(net.minecraft.component.DataComponentTypes.LORE,
                            new net.minecraft.component.type.LoreComponent(List.of(
                                    Text.literal("§7满级附魔，费用：§e" + cost(lv) + " orbs"),
                                    Text.literal("§7点击附到手持装备上"))));
                    this.grid.setStack(i, icon);
                } else {
                    this.grid.setStack(i, ItemStack.EMPTY);
                }
            }
            this.sendContentUpdates();
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity sp) || slotIndex < 0 || slotIndex >= available.size()) {
                return;
            }
            if (actionType != SlotActionType.PICKUP && actionType != SlotActionType.QUICK_MOVE) {
                return;
            }
            RegistryKey<Enchantment> key = available.get(slotIndex);
            ItemStack held = sp.getMainHandStack();
            int lv = MAX.getOrDefault(key, 1);
            if (held.isEmpty()) {
                return;
            }
            int cost = cost(lv);
            if (this.match.vdOrbsOf(sp) < cost) {
                sp.sendMessage(Messages.error("货币不足（需 " + cost + " orbs）"), false);
                return;
            }
            this.match.vdAddOrbs(sp, -cost, false);
            VillageDefenseKits.enchantItem(held, key, lv);
            sp.sendMessage(Messages.gold("已附魔 §e" + key.getValue().getPath() + " " + roman(lv) + "§r！"), false);
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

    private static int cost(int lv) {
        return 25 + lv * 20;
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(n);
        };
    }
}
