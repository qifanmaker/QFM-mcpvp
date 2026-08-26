package com.example.pvp.gui;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.skywars.SkyWarsTheme;
import com.example.pvp.config.PlayerStats;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.config.StatsStore;
import com.example.pvp.kit.Kit;
import com.example.pvp.kit.KitManager;
import com.example.pvp.match.MatchType;
import com.example.pvp.queue.QueueEntry;
import com.example.pvp.text.Messages;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 游戏内 GUI 菜单：主菜单 / 套件选择 / 决斗目标 / 战绩 / 套件列表。
 * 纯服务端实现，使用原版容器界面，原版客户端直接可用。
 */
public final class PvpGuiManager {
    public static final String MENU_TAG = "pvp.menu";

    private static PvpGuiManager instance;

    private final Map<UUID, GuiContext> contexts = new HashMap<>();

    private PvpGuiManager() {
    }

    public static void init() {
        if (instance == null) {
            instance = new PvpGuiManager();
        }
    }

    public static PvpGuiManager get() {
        return instance;
    }

    // ---------- 菜单物品 ----------

    public static ItemStack createMenuItem() {
        ItemStack stack = new ItemStack(Items.COMPASS);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§6PvP 竞技场 §7(右键打开)"));
        NbtCompound nbt = new NbtCompound();
        nbt.putString(MENU_TAG, "1");
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return stack;
    }

    public static boolean isMenuItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        NbtComponent nbt = stack.get(DataComponentTypes.CUSTOM_DATA);
        return nbt != null && nbt.copyNbt().contains(MENU_TAG);
    }

    public void giveMenuItem(ServerPlayerEntity player) {
        for (ItemStack stack : player.getInventory().main) {
            if (isMenuItem(stack)) {
                return;
            }
        }
        ItemStack item = createMenuItem();
        if (player.getInventory().getStack(8).isEmpty()) {
            player.getInventory().setStack(8, item);
        } else {
            for (int i = 0; i < 36; i++) {
                if (player.getInventory().getStack(i).isEmpty()) {
                    player.getInventory().setStack(i, item);
                    player.currentScreenHandler.sendContentUpdates();
                    return;
                }
            }
            player.getInventory().setStack(8, item); // 兜底：占用快捷栏第 9 格
        }
        player.currentScreenHandler.sendContentUpdates();
    }

    // ---------- 排队红石 ----------

    public static final String QUEUE_TAG = "pvp.queue";

    public static ItemStack createQueueItem() {
        ItemStack stack = new ItemStack(Items.REDSTONE);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§c离开排队 §7(右键)"));
        NbtCompound nbt = new NbtCompound();
        nbt.putString(QUEUE_TAG, "1");
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return stack;
    }

    public static boolean isQueueItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        NbtComponent nbt = stack.get(DataComponentTypes.CUSTOM_DATA);
        return nbt != null && nbt.copyNbt().contains(QUEUE_TAG);
    }

    /** 放入快捷栏第一个空格（从第 1 格开始找，不覆盖已有物品）。 */
    public static void giveQueueItem(ServerPlayerEntity player) {
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getStack(i).isEmpty()) {
                player.getInventory().setStack(i, createQueueItem());
                player.currentScreenHandler.sendContentUpdates();
                return;
            }
        }
    }

    public static void removeQueueItem(ServerPlayerEntity player) {
        boolean removed = false;
        for (int i = 0; i < 36; i++) {
            if (isQueueItem(player.getInventory().getStack(i))) {
                player.getInventory().setStack(i, ItemStack.EMPTY);
                removed = true;
            }
        }
        if (removed) {
            player.currentScreenHandler.sendContentUpdates();
        }
    }

    // ---------- 旁观者 UI 物品 ----------

    public static final String SPECTATE_TAG = "pvp.spectate";
    public static final String REQUEUE_TAG = "pvp.requeue";
    public static final String EXIT_TAG = "pvp.exit";

    private static ItemStack spectatorItem(Item item, String tag, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        NbtCompound nbt = new NbtCompound();
        nbt.putString(tag, "1");
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return stack;
    }

    public static ItemStack spectatorCompass() {
        return spectatorItem(Items.COMPASS, SPECTATE_TAG, "§b观战 §7(右键切换目标)");
    }

    public static ItemStack spectatorEmerald() {
        return spectatorItem(Items.EMERALD, REQUEUE_TAG, "§a下一把 §7(立即重新匹配)");
    }

    public static ItemStack spectatorRedstone() {
        return spectatorItem(Items.REDSTONE, EXIT_TAG, "§c退出 §7(回主城)");
    }

    public static boolean isSpectatorUiItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        NbtComponent nbt = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (nbt == null) {
            return false;
        }
        NbtCompound c = nbt.copyNbt();
        return c.contains(SPECTATE_TAG) || c.contains(REQUEUE_TAG) || c.contains(EXIT_TAG);
    }

    public static String getSpectatorTag(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        NbtComponent nbt = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (nbt == null) {
            return "";
        }
        NbtCompound c = nbt.copyNbt();
        if (c.contains(SPECTATE_TAG)) {
            return "spectate";
        }
        if (c.contains(REQUEUE_TAG)) {
            return "requeue";
        }
        if (c.contains(EXIT_TAG)) {
            return "exit";
        }
        return "";
    }

    // ---------- 页面 ----------

    public void openMainMenu(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.MAIN;
        ctx.pendingMode = null;
        ctx.duelTargetUuid = null;
        this.openPage(player, ctx, "§6§lPvP 竞技场", inv -> {
            inv.setStack(9, makeButton(Items.IRON_SWORD, "§b1v1 决斗匹配", "铁剑互砍，无护甲", "点击选择套件后加入队列"));
            inv.setStack(10, makeButton(Items.DIAMOND_SWORD, "§b2v2 团队匹配", "4 人随机分队", "点击选择套件后加入队列"));
            inv.setStack(11, makeButton(Items.GOLDEN_SWORD, "§b自由乱斗 (FFA)",
                    PvPConfig.INSTANCE.ffaMinPlayers + " 人起，倒计时 " + PvPConfig.INSTANCE.ffaCountdownSeconds + " 秒",
                    PvPConfig.INSTANCE.ffaEarlyStartPlayers + " 人时加速到 " + PvPConfig.INSTANCE.ffaEarlyStartSeconds + " 秒",
                    "点击选择套件后加入队列"));
            inv.setStack(12, makeButton(Items.STICK, "§b相扑 (Sumo)", "不吃伤害，只吃击退", "落到平台下方 20 格淘汰，末影珍珠可救回", "点击选择套件后加入队列"));
            inv.setStack(13, makeButton(Items.DIAMOND_SWORD, "§b1.8 经典PvP", "无攻击冷却，疯狂点按", "剑可格挡减伤 50%", "点击选择套件后加入队列"));
            inv.setStack(14, makeButton(Items.END_CRYSTAL, "§b空岛战争 (Beta)", "2~8 人，凑齐 "
                    + PvPConfig.INSTANCE.skywarsStartPlayers + " 人开赛",
                    "随机空岛 + 中间主岛，开箱获得装备",
                    "1.8 低版本战斗：无冷却、剑格挡",
                    "3 分钟后缩圈，最后存活者获胜",
                    "点击直接加入"));

            int win = PvPConfig.INSTANCE.bridgeWinScore;
            inv.setStack(15, makeButton(Items.BRICK, "§3战桥 1v1", "2 人，先得 " + win + " 分获胜",
                    "跳进对方球门洞得分", "1.8 低版本战斗：无冷却、剑格挡", "点击直接加入"));
            inv.setStack(16, makeButton(Items.IRON_BARS, "§5战桥 1v1v1v1", "4 人四方混战，各自一个球门",
                    "先得 " + win + " 分获胜", "点击直接加入"));
            inv.setStack(17, makeButton(Items.RED_TERRACOTTA, "§b战桥 2v2", "4 人随机分队",
                    "先得 " + win + " 分获胜", "点击直接加入"));

            inv.setStack(18, makeButton(Items.DIAMOND_PICKAXE, "§e战桥 混战", "偶数人数，总人数/2 分两队",
                    "2v2 / 3v3 / 4v4...", "先得 " + win + " 分获胜", "点击直接加入"));
            inv.setStack(19, makeButton(Items.PAPER, "§e向玩家发起决斗", "选择一名在线玩家", "1v1 单挑"));
            inv.setStack(20, makeButton(Items.BOOK, "§d我的战绩", "查看胜/负/场次"));
            inv.setStack(21, makeButton(Items.CHEST, "§d查看套件列表", "浏览所有装备方案"));

            if (PvPMod.QUEUE.contains(player.getUuid())) {
                String status = "排队中";
                var entry = PvPMod.QUEUE.getEntry(player);
                if (entry != null) {
                    status = "排队中：" + entry.getType().getDisplayName() + " / " + entry.getKit().getDisplayName();
                }
                // OP(2级+) 可立即用当前队列人数开赛
                if (player.hasPermissionLevel(2)) {
                    inv.setStack(22, makeButton(Items.EMERALD, "§a立即开始",
                            "OP 专用：立刻用当前队列人数开赛",
                            "人数不足时无法开始"));
                }
                inv.setStack(23, makeButton(Items.BARRIER, "§c离开队列", status));
            }
        });
    }

    private void openKitPage(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.KIT;
        boolean duel = ctx.duelTargetUuid != null;
        String title = duel ? "§6选择套件 - 决斗" : "§6选择套件 - " + ctx.pendingMode.getDisplayName();
        this.openPage(player, ctx, title, inv -> {
            int slot = 9;
            for (Kit kit : KitManager.getKits()) {
                if (slot >= 26) {
                    break;
                }
                inv.setStack(slot++, kitButton(kit));
            }
            inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
        });
    }

    private void openDuelTargetPage(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.DUEL_TARGET;
        this.openPage(player, ctx, "§6选择决斗目标", inv -> {
            List<ServerPlayerEntity> online = getDuelCandidates(player);
            int page = ctx.duelTargetPage;
            int perPage = 24;
            int start = page * perPage;

            int index = 0;
            for (int i = start; i < online.size() && index < perPage; i++) {
                ServerPlayerEntity target = online.get(i);
                inv.setStack(index, makeButton(Items.PAPER, "§e" + target.getGameProfile().getName(), "点击发起 1v1 决斗"));
                index++;
            }

            if (page > 0) {
                inv.setStack(24, makeButton(Items.ARROW, "§7← 上一页"));
            }
            if (start + perPage < online.size()) {
                inv.setStack(25, makeButton(Items.ARROW, "§7下一页 →"));
            }
            inv.setStack(26, makeButton(Items.BARRIER, "§c返回"));
        });
    }

    private void openStatsPage(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.STATS;
        this.openPage(player, ctx, "§6我的战绩", inv -> {
            PlayerStats stats = StatsStore.INSTANCE.getStats(player.getUuid());
            inv.setStack(11, makeButton(Items.BOOK, "§a我的战绩",
                    "胜：" + stats.getWins(),
                    "负：" + stats.getLosses(),
                    "总场次：" + stats.getMatches()));

            List<Map.Entry<String, PlayerStats>> sorted = StatsStore.INSTANCE.getStatsMap().entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, PlayerStats> e) -> e.getValue().wins).reversed())
                    .limit(5)
                    .toList();

            int slot = 13;
            int rank = 1;
            for (Map.Entry<String, PlayerStats> entry : sorted) {
                if (slot >= 26) {
                    break;
                }
                String name;
                try {
                    name = resolveName(UUID.fromString(entry.getKey()));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                inv.setStack(slot++, makeButton(Items.PAPER, "#" + rank + " §e" + name,
                        "胜 " + entry.getValue().wins + " | 负 " + entry.getValue().losses + " | 总 " + entry.getValue().matches));
                rank++;
            }

            inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
        });
    }

    /** OP 立即开始时的主题选择页（仅排队空岛战争时出现）。 */
    private void openThemePage(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.THEME;
        this.openPage(player, ctx, "§6选择空岛主题（立即开始）", inv -> {
            inv.setStack(10, makeButton(Items.GRASS_BLOCK, "§a主世界", "草方块 + 小橡树"));
            inv.setStack(11, makeButton(Items.NETHERRACK, "§4地狱", "地狱岩，岛面随机刷灵魂沙/岩浆"));
            inv.setStack(12, makeButton(Items.PACKED_ICE, "§b冰原", "全部由雪块/浮冰构成"));
            inv.setStack(13, makeButton(Items.END_STONE, "§d末地", "末地石，中岛为空心环"));
            inv.setStack(14, makeButton(Items.ENDER_PEARL, "§e随机主题", "不指定，交给运气"));
            inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
        });
    }

    private void openKitInfoPage(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.KIT_INFO;
        this.openPage(player, ctx, "§6套件列表", inv -> {
            int slot = 9;
            for (Kit kit : KitManager.getKits()) {
                if (slot >= 26) {
                    break;
                }
                List<String> lore = new ArrayList<>();
                lore.add("类型：" + kit.getType().getDisplayName());
                for (ItemStack stack : kit.getInventory()) {
                    if (stack.isEmpty()) {
                        continue;
                    }
                    lore.add("· " + stack.getItem().getName().getString() + " x" + stack.getCount());
                }
                inv.setStack(slot++, makeButton(iconFor(kit), "§b" + kit.getDisplayName(), lore.toArray(new String[0])));
            }
            inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
        });
    }

    // ---------- 槽位点击 ----------

    public void onMenuSlotClick(ServerPlayerEntity player, int slotIndex) {
        GuiContext ctx = this.contexts.get(player.getUuid());
        if (ctx == null) {
            return;
        }
        switch (ctx.page) {
            case MAIN -> this.onClickMain(player, ctx, slotIndex);
            case KIT -> this.onClickKit(player, ctx, slotIndex);
            case DUEL_TARGET -> this.onClickDuelTarget(player, ctx, slotIndex);
            case THEME -> this.onClickTheme(player, ctx, slotIndex);
            case STATS, KIT_INFO -> {
                if (slotIndex == 26) {
                    this.openMainMenu(player);
                }
            }
        }
    }

    public void onMenuClosed(UUID uuid) {
        GuiContext ctx = this.contexts.get(uuid);
        if (ctx != null && !ctx.navigating) {
            this.contexts.remove(uuid);
        }
    }

    // ---------- 内部：点击逻辑 ----------

    private void onClickMain(ServerPlayerEntity player, GuiContext ctx, int slot) {
        switch (slot) {
            case 9 -> {
                ctx.pendingMode = MatchType.DUEL_1V1;
                this.openKitPage(player);
            }
            case 10 -> {
                ctx.pendingMode = MatchType.DUEL_2V2;
                this.openKitPage(player);
            }
            case 11 -> {
                ctx.pendingMode = MatchType.FFA;
                this.openKitPage(player);
            }
            case 12 -> {
                ctx.pendingMode = MatchType.SUMO;
                this.openKitPage(player);
            }
            case 13 -> {
                ctx.pendingMode = MatchType.PVP_1_8;
                this.openKitPage(player);
            }
            // 空岛战争无套件，直接加入
            case 14 -> this.joinQueue(player, MatchType.SKYWARS, KitManager.skywarsKit());
            // 战桥系列无套件（装备固定），直接加入
            case 15 -> this.joinQueue(player, MatchType.BRIDGE_1V1, KitManager.bridgeKit());
            case 16 -> this.joinQueue(player, MatchType.BRIDGE_1V1V1V1, KitManager.bridgeKit());
            case 17 -> this.joinQueue(player, MatchType.BRIDGE_2V2, KitManager.bridgeKit());
            case 18 -> this.joinQueue(player, MatchType.BRIDGE_TEAM, KitManager.bridgeKit());
            case 19 -> this.openDuelTargetPage(player);
            case 20 -> this.openStatsPage(player);
            case 21 -> this.openKitInfoPage(player);
            case 22 -> {
                // OP 立即开始：排队空岛战争时可先选主题，其余模式直接开
                QueueEntry entry = PvPMod.QUEUE.getEntry(player);
                if (entry != null && entry.getType() == MatchType.SKYWARS) {
                    this.openThemePage(player);
                } else {
                    this.doForceStart(player);
                }
            }
            case 23 -> {
                if (PvPMod.QUEUE.leave(player)) {
                    player.sendMessage(Messages.info("已离开匹配队列"), false);
                    this.openMainMenu(player);
                }
            }
            default -> {
            }
        }
    }

    private void onClickKit(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openMainMenu(player);
            return;
        }
        int kitIndex = slot - 9;
        List<Kit> kits = KitManager.getKits();
        if (kitIndex < 0 || kitIndex >= kits.size()) {
            return;
        }
        Kit kit = kits.get(kitIndex);

        if (ctx.duelTargetUuid != null) {
            ServerPlayerEntity target = player.getServer().getPlayerManager().getPlayer(ctx.duelTargetUuid);
            if (target == null) {
                player.sendMessage(Messages.error("目标玩家已下线"), false);
                this.openMainMenu(player);
                return;
            }
            this.sendDuelChallenge(player, target, kit);
        } else {
            this.joinQueue(player, ctx.pendingMode, kit);
        }
    }

    private void onClickDuelTarget(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openMainMenu(player);
            return;
        }
        if (slot == 24) {
            if (ctx.duelTargetPage > 0) {
                ctx.duelTargetPage--;
                this.openDuelTargetPage(player);
            }
            return;
        }
        if (slot == 25) {
            ctx.duelTargetPage++;
            this.openDuelTargetPage(player);
            return;
        }

        List<ServerPlayerEntity> candidates = this.getDuelCandidates(player);
        int index = ctx.duelTargetPage * 24 + slot;
        if (index < 0 || index >= candidates.size()) {
            return;
        }
        ctx.duelTargetUuid = candidates.get(index).getUuid();
        this.openKitPage(player);
    }

    /** 主题选择页点击：选主题 → 立即开赛；随机 → 不指定主题立即开。 */
    private void onClickTheme(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openMainMenu(player);
            return;
        }
        SkyWarsTheme theme = switch (slot) {
            case 10 -> SkyWarsTheme.OVERWORLD;
            case 11 -> SkyWarsTheme.NETHER;
            case 12 -> SkyWarsTheme.ICE;
            case 13 -> SkyWarsTheme.END;
            default -> null; // 14=随机，不指定
        };
        if (PvPMod.MATCH != null) {
            PvPMod.MATCH.setNextSkywarsTheme(theme);
        }
        this.doForceStart(player);
    }

    /** OP 立即开赛：用当前队列人数开赛并返回主菜单。 */
    private void doForceStart(ServerPlayerEntity player) {
        if (PvPMod.QUEUE.forceStart(PvPMod.MATCH, player)) {
            player.sendMessage(Messages.info("已强制立即开赛！"), false);
        }
        this.openMainMenu(player);
    }

    private void joinQueue(ServerPlayerEntity player, MatchType type, Kit kit) {
        if (isBusy(player)) {
            player.sendMessage(Messages.error("你正在比赛或队列中"), false);
            player.closeHandledScreen();
            return;
        }
        if (PvPMod.QUEUE.join(player, type, kit)) {
            if (type == MatchType.FFA) {
                player.sendMessage(Messages.info("已加入自由乱斗：凑齐 " + PvPConfig.INSTANCE.ffaMinPlayers
                        + " 人后倒计时 " + PvPConfig.INSTANCE.ffaCountdownSeconds + " 秒开赛"), false);
            } else if (type == MatchType.SKYWARS) {
                player.sendMessage(Messages.info("已加入空岛战争：凑齐 " + PvPConfig.INSTANCE.skywarsStartPlayers
                        + " 人开赛，开箱获得装备"), false);
            } else if (type.isBridge()) {
                if (type.isBridgeTeam()) {
                    player.sendMessage(Messages.info("已加入战桥混战：需要偶数人数（≥ "
                            + PvPConfig.INSTANCE.bridgeTeamMinPlayers + "），凑够即开赛"), false);
                } else {
                    int count = PvPMod.QUEUE.queuedCount(type, kit);
                    player.sendMessage(Messages.info("已加入战桥匹配：模式 " + type.getDisplayName()
                            + "（当前 " + count + "/" + type.requiredPlayers() + "）"), false);
                }
            } else {
                int count = PvPMod.QUEUE.queuedCount(type, kit);
                player.sendMessage(Messages.info("已加入匹配队列：模式 " + type.getDisplayName()
                        + "，套件 " + kit.getDisplayName() + "（当前 " + count + "/" + type.requiredPlayers() + "）"), false);
            }
        }
        player.closeHandledScreen();
    }

    private void sendDuelChallenge(ServerPlayerEntity player, ServerPlayerEntity target, Kit kit) {
        if (isBusy(player) || isBusy(target)) {
            player.sendMessage(Messages.error("有人正在比赛或队列中，无法发起决斗"), false);
            player.closeHandledScreen();
            return;
        }
        PvPMod.DUEL.challenge(player, target, MatchType.DUEL_1V1, kit);
        player.sendMessage(Messages.info("已向 §e" + target.getGameProfile().getName()
                + "§r 发起决斗（套件 " + kit.getDisplayName() + "），等待接受..."), false);
        target.sendMessage(Messages.gold("§e" + player.getGameProfile().getName()
                + "§r 向你发起 1v1 决斗（套件 " + kit.getDisplayName() + "）！输入 §e/duel accept§r 接受"), false);
        player.closeHandledScreen();
    }

    // ---------- 内部：工具 ----------

    private void openPage(ServerPlayerEntity player, GuiContext ctx, String title, Consumer<SimpleInventory> filler) {
        ctx.navigating = true;
        NamedScreenHandlerFactory factory = new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal(title);
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                PvpScreenHandler handler = new PvpScreenHandler(syncId, inv, PvpGuiManager.this, player.getUuid());
                filler.accept(handler.getMenu());
                return handler;
            }
        };
        player.openHandledScreen(factory);
        ctx.navigating = false;
    }

    private GuiContext getContext(ServerPlayerEntity player) {
        return this.contexts.computeIfAbsent(player.getUuid(), u -> new GuiContext());
    }

    private boolean isBusy(ServerPlayerEntity player) {
        return PvPMod.MATCH.isInMatch(player.getUuid()) || PvPMod.QUEUE.contains(player.getUuid());
    }

    private List<ServerPlayerEntity> getDuelCandidates(ServerPlayerEntity player) {
        List<ServerPlayerEntity> result = new ArrayList<>();
        for (ServerPlayerEntity p : player.getServer().getPlayerManager().getPlayerList()) {
            if (p.getUuid().equals(player.getUuid()) || isBusy(p)) {
                continue;
            }
            result.add(p);
        }
        return result;
    }

    private String resolveName(UUID uuid) {
        ServerPlayerEntity online = PvPMod.SERVER.getPlayerManager().getPlayer(uuid);
        return online != null ? online.getGameProfile().getName() : "§7(离线)";
    }

    private static ItemStack makeButton(Item item, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        if (lore.length > 0) {
            List<Text> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Text.literal("§7" + line));
            }
            stack.set(DataComponentTypes.LORE, new LoreComponent(lines));
        }
        return stack;
    }

    private static ItemStack kitButton(Kit kit) {
        return makeButton(iconFor(kit), "§b" + kit.getDisplayName() + " §7(" + kit.getId() + ")",
                "类型：" + kit.getType().getDisplayName(),
                "点击使用此套件");
    }

    private static Item iconFor(Kit kit) {
        if (!kit.getInventory().isEmpty()) {
            ItemStack first = kit.getInventory().get(0);
            if (!first.isEmpty()) {
                return first.getItem();
            }
        }
        if (kit.getArmor()[0] != null && !kit.getArmor()[0].isEmpty()) {
            return kit.getArmor()[0].getItem();
        }
        return Items.CHEST;
    }

    // ---------- 内部类型 ----------

    private enum Page {
        MAIN, KIT, DUEL_TARGET, STATS, KIT_INFO, THEME
    }

    private static final class GuiContext {
        Page page = Page.MAIN;
        MatchType pendingMode;
        UUID duelTargetUuid;
        int duelTargetPage;
        boolean navigating;
    }
}
