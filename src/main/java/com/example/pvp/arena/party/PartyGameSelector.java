package com.example.pvp.arena.party;

import com.example.pvp.match.MinecraftPartySession;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
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

/** 102 项的迁移目标不能依赖九格快捷栏；分页菜单只展示真正实现的规则模块。 */
public final class PartyGameSelector {
    private PartyGameSelector() { }
    private static final int PAGE_SIZE = 45;

    public static void open(ServerPlayerEntity player, MinecraftPartySession session) {
        if (!session.isChoosing(player)) return;
        player.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override public Text getDisplayName() { return Text.literal("§6Party 2 · 本轮投票选择小游戏"); }
            @Override public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity ignored) {
                return new Handler(syncId, inventory, session, player);
            }
        });
    }

    public static void close(ServerPlayerEntity player) {
        if (player.currentScreenHandler instanceof Handler) player.closeHandledScreen();
    }

    private static final class Handler extends ScreenHandler {
        private final SimpleInventory grid = new SimpleInventory(54);
        private final MinecraftPartySession session;
        private final ServerPlayerEntity player;
        private List<PartyMinigame> games;
        private int page;

        Handler(int syncId, PlayerInventory inventory, MinecraftPartySession session, ServerPlayerEntity player) {
            super(ScreenHandlerType.GENERIC_9X6, syncId);
            this.session = session;
            this.player = player;
            this.games = session.selectionChoices();
            for (int row = 0; row < 6; row++) for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(this.grid, row * 9 + col, 8 + col * 18, 18 + row * 18));
            }
            for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, row * 9 + col + 9, 8 + col * 18, 140 + row * 18));
            }
            for (int col = 0; col < 9; col++) this.addSlot(new Slot(inventory, col, 8 + col * 18, 198));
            this.render(player);
        }

        private void render(ServerPlayerEntity player) {
            this.grid.clear();
            for (int index = 0; index < PAGE_SIZE && this.page * PAGE_SIZE + index < this.games.size(); index++) {
                PartyMinigame game = this.games.get(this.page * PAGE_SIZE + index);
                ItemStack icon = new ItemStack(game.selectionItem());
                boolean selected = this.session.voteOf(player.getUuid()) == game;
                icon.set(DataComponentTypes.CUSTOM_NAME, Text.literal((selected ? "§a✔ " : "§e") + game.title()));
                List<Text> lore = new ArrayList<>();
                lore.add(Text.literal("§7原版编号 " + game.sourceId()));
                lore.add(Text.literal("§7适用人数：" + game.playerRequirement()));
                for (String rule : game.rules()) lore.add(Text.literal("§f" + rule));
                lore.add(Text.literal(selected ? "§a当前投票" : "§e点击投票"));
                icon.set(DataComponentTypes.LORE, new LoreComponent(lore));
                this.grid.setStack(index, icon);
            }
            if (this.page > 0) this.grid.setStack(45, label(Items.ARROW.getDefaultStack(), "§e上一页"));
            this.grid.setStack(49, label(Items.CLOCK.getDefaultStack(), "§6第 " + (this.page + 1) + " 页 · 同票随机"));
            if ((this.page + 1) * PAGE_SIZE < this.games.size()) this.grid.setStack(53, label(Items.ARROW.getDefaultStack(), "§e下一页"));
            super.sendContentUpdates();
        }

        @Override public void sendContentUpdates() {
            // 投票中退赛会让四人限定项目失效；已打开的菜单也必须同步移除，不能只在重开时更新。
            if (this.player != null && this.session.isChoosing(this.player)) {
                List<PartyMinigame> available = this.session.selectionChoices();
                if (!available.equals(this.games)) {
                    this.games = available;
                    this.page = Math.min(this.page, Math.max(0, (available.size() - 1) / PAGE_SIZE));
                    this.render(this.player);
                    return;
                }
            }
            super.sendContentUpdates();
        }

        @Override public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity sp) || !this.canUse(player)) return;
            if (action != SlotActionType.PICKUP && action != SlotActionType.QUICK_MOVE) return;
            if (slot == 45 && this.page > 0) this.page--;
            else if (slot == 53 && (this.page + 1) * PAGE_SIZE < this.games.size()) this.page++;
            else if (slot >= 0 && slot < PAGE_SIZE && this.page * PAGE_SIZE + slot < this.games.size()) {
                this.session.vote(sp, this.games.get(this.page * PAGE_SIZE + slot));
                sp.closeHandledScreen();
                return;
            }
            this.render(sp);
        }

        @Override public boolean canUse(PlayerEntity player) {
            return player instanceof ServerPlayerEntity sp && this.session.isChoosing(sp);
        }

        @Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
    }

    private static ItemStack label(ItemStack stack, String name) {
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }
}
