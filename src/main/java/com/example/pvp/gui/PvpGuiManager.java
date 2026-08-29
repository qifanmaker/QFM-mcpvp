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
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.MinecraftServer;
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

    /** 主菜单：4 大类（PvP 对战 / 空岛战争 / 战桥 / 趣味小游戏）+ 功能入口。 */
    public void openMainMenu(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.MAIN;
        ctx.pendingMode = null;
        ctx.duelTargetUuid = null;
        this.openPage(player, ctx, "§6§lPvP 竞技场", inv -> this.fillMainMenu(inv, player, ctx));
    }

    /** 主菜单按钮填充（打开与实时刷新共用；分类按钮显示该分类排队总人数：附魔光效 + 堆叠数）。 */
    private void fillMainMenu(SimpleInventory inv, ServerPlayerEntity player, GuiContext ctx) {
        // 先铺灰色玻璃板覆盖全槽（刷新时防止旧按钮残留），再放业务按钮
        for (int slot = 0; slot < 36; slot++) {
            inv.setStack(slot, makeButton(Items.GRAY_STAINED_GLASS_PANE, " "));
        }
        // 第 1 行：四大类（显示分类排队总人数）
        ItemStack pvp = makeButton(Items.DIAMOND_SWORD, "§b§lPvP 对战",
                "1v1 / 2v2 / 自由乱斗 / 相扑 / 1.8 经典",
                "点击选择对战模式与套件");
        this.applyQueueIndicator(pvp, player, PvPMod.QUEUE.countQueued(
                MatchType.DUEL_1V1, MatchType.DUEL_2V2, MatchType.FFA, MatchType.SUMO, MatchType.PVP_1_8));
        inv.setStack(9, pvp);

        ItemStack skywars = makeButton(Items.END_CRYSTAL, "§6空岛战争 (Beta)", "2~8 人，凑齐 "
                + PvPConfig.INSTANCE.skywarsStartPlayers + " 人开赛",
                "随机空岛 + 中间主岛，开箱获得装备",
                "1.8 低版本战斗，3 分钟后缩圈",
                "最后存活者获胜，点击直接加入");
        this.applyQueueIndicator(skywars, player, PvPMod.QUEUE.countQueued(MatchType.SKYWARS));
        inv.setStack(10, skywars);

        ItemStack bridge = makeButton(Items.BRICK, "§3§l战桥",
                "1v1 / 1v1v1v1 / 2v2 / 混战",
                "跳进对方球门洞得分，先得 " + PvPConfig.INSTANCE.bridgeWinScore + " 分获胜",
                "点击选择战桥模式");
        this.applyQueueIndicator(bridge, player, PvPMod.QUEUE.countQueued(
                MatchType.BRIDGE_1V1, MatchType.BRIDGE_1V1V1V1, MatchType.BRIDGE_2V2, MatchType.BRIDGE_TEAM));
        inv.setStack(11, bridge);

        ItemStack games = makeButton(Items.FIREWORK_ROCKET, "§d§l趣味小游戏",
                "幸运之柱 / TNT 跑酷 / 心跳水立方 / 烫手山芋",
                "点击选择小游戏");
        this.applyQueueIndicator(games, player, PvPMod.QUEUE.countQueued(
                MatchType.LUCKY_PILLAR, MatchType.TNT_RUN, MatchType.HEARTBEAT, MatchType.HOT_POTATO));
        inv.setStack(12, games);

        // 第 2 行：功能入口
        inv.setStack(13, makeButton(Items.PAPER, "§e向玩家发起决斗", "选择一名在线玩家", "1v1 单挑"));
        inv.setStack(14, makeButton(Items.BOOK, "§d我的战绩", "查看胜/负/场次"));
        inv.setStack(15, makeButton(Items.CHEST, "§d查看套件列表", "浏览所有装备方案"));

        if (PvPMod.QUEUE.contains(player.getUuid())) {
            String status = "排队中";
            var entry = PvPMod.QUEUE.getEntry(player);
            if (entry != null) {
                status = "排队中：" + entry.getType().getDisplayName() + " / " + entry.getKit().getDisplayName();
            }
            // OP(2级+) 可立即用当前队列人数开赛
            if (player.hasPermissionLevel(2)) {
                inv.setStack(21, makeButton(Items.EMERALD, "§a立即开始",
                        "OP 专用：立刻用当前队列人数开赛",
                        "人数不足时无法开始"));
            }
            inv.setStack(22, makeButton(Items.BARRIER, "§c离开队列", status));
        }
    }

    /** PvP 对战分类页：需要套件的对战模式。 */
    private void openPvpCategory(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.PVP_CATEGORY;
        this.openPage(player, ctx, "§6§lPvP 对战", inv -> this.fillPvpCategory(inv, player, ctx));
    }

    /** PvP 对战分类按钮填充（各模式按钮显示该模式排队人数）。 */
    private void fillPvpCategory(SimpleInventory inv, ServerPlayerEntity player, GuiContext ctx) {
        for (int slot = 0; slot < 36; slot++) {
            inv.setStack(slot, makeButton(Items.GRAY_STAINED_GLASS_PANE, " "));
        }
        inv.setStack(9, queueButton(Items.IRON_SWORD, "§b1v1 决斗匹配", player, MatchType.DUEL_1V1,
                "铁剑互砍，无护甲", "点击选择套件后加入队列"));
        inv.setStack(10, queueButton(Items.DIAMOND_SWORD, "§b2v2 团队匹配", player, MatchType.DUEL_2V2,
                "4 人随机分队", "点击选择套件后加入队列"));
        inv.setStack(11, queueButton(Items.GOLDEN_SWORD, "§b自由乱斗 (FFA)", player, MatchType.FFA,
                PvPConfig.INSTANCE.ffaMinPlayers + " 人起，倒计时 " + PvPConfig.INSTANCE.ffaCountdownSeconds + " 秒",
                PvPConfig.INSTANCE.ffaEarlyStartPlayers + " 人时加速到 " + PvPConfig.INSTANCE.ffaEarlyStartSeconds + " 秒",
                "点击选择套件后加入队列"));
        inv.setStack(12, queueButton(Items.STICK, "§b相扑 (Sumo)", player, MatchType.SUMO,
                "不吃伤害，只吃击退", "落到平台下方 20 格淘汰，末影珍珠可救回", "点击选择套件后加入队列"));
        inv.setStack(13, queueButton(Items.NETHERITE_SWORD, "§b1.8 经典PvP", player, MatchType.PVP_1_8,
                "无攻击冷却，疯狂点按", "剑可格挡减伤 50%", "点击选择套件后加入队列"));
        inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
    }

    /** 战桥分类页：四种战桥模式（无套件，装备固定）。 */
    private void openBridgeCategory(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.BRIDGE_CATEGORY;
        this.openPage(player, ctx, "§6§l战桥", inv -> this.fillBridgeCategory(inv, player, ctx));
    }

    /** 战桥分类按钮填充（各模式按钮显示该模式排队人数）。 */
    private void fillBridgeCategory(SimpleInventory inv, ServerPlayerEntity player, GuiContext ctx) {
        for (int slot = 0; slot < 36; slot++) {
            inv.setStack(slot, makeButton(Items.GRAY_STAINED_GLASS_PANE, " "));
        }
        int win = PvPConfig.INSTANCE.bridgeWinScore;
        inv.setStack(9, queueButton(Items.BRICK, "§3战桥 1v1", player, MatchType.BRIDGE_1V1,
                "2 人，先得 " + win + " 分获胜", "跳进对方球门洞得分", "1.8 低版本战斗：无冷却、剑格挡", "点击直接加入"));
        inv.setStack(10, queueButton(Items.IRON_BARS, "§5战桥 1v1v1v1", player, MatchType.BRIDGE_1V1V1V1,
                "4 人四方混战，各自一个球门", "先得 " + win + " 分获胜", "点击直接加入"));
        inv.setStack(11, queueButton(Items.RED_TERRACOTTA, "§b战桥 2v2", player, MatchType.BRIDGE_2V2,
                "4 人随机分队", "先得 " + win + " 分获胜", "点击直接加入"));
        inv.setStack(12, queueButton(Items.DIAMOND_PICKAXE, "§e战桥 混战", player, MatchType.BRIDGE_TEAM,
                "偶数人数，总人数/2 分两队", "2v2 / 3v3 / 4v4...", "先得 " + win + " 分获胜", "点击直接加入"));
        inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
    }

    /** 趣味小游戏分类页：四个无套件小游戏。 */
    private void openGamesCategory(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.GAMES_CATEGORY;
        this.openPage(player, ctx, "§6§l趣味小游戏", inv -> this.fillGamesCategory(inv, player, ctx));
    }

    /** 趣味小游戏分类按钮填充（各模式按钮显示该模式排队人数）。 */
    private void fillGamesCategory(SimpleInventory inv, ServerPlayerEntity player, GuiContext ctx) {
        for (int slot = 0; slot < 36; slot++) {
            inv.setStack(slot, makeButton(Items.GRAY_STAINED_GLASS_PANE, " "));
        }
        inv.setStack(9, queueButton(Items.QUARTZ_PILLAR, "§d幸运之柱", player, MatchType.LUCKY_PILLAR,
                PvPConfig.INSTANCE.luckyPillarMinPlayers + "~" + PvPConfig.INSTANCE.luckyPillarMaxPlayers
                        + " 人，凑齐 " + PvPConfig.INSTANCE.luckyPillarStartPlayers + " 人开赛",
                "每位玩家一根 40 格基岩棍，柱下有大平台",
                "底部平台每局随机 9 种风格",
                "每 3 秒随机获得 1 件物品",
                "随机事件：一击必杀/箭雨/雷击/TNT 雨/位置交换/补给潮",
                "最后存活者获胜，点击直接加入"));
        inv.setStack(10, queueButton(Items.TNT, "§cTNT 跑酷", player, MatchType.TNT_RUN,
                PvPConfig.INSTANCE.tntRunMinPlayers + "~" + PvPConfig.INSTANCE.tntRunMaxPlayers
                        + " 人，凑齐 " + PvPConfig.INSTANCE.tntRunStartPlayers + " 人开赛",
                "5 层彩色平台，踩过的方块 0.2 秒后掉落",
                "地面会刷火焰弹/TNT，捡起来砸人/炸人",
                "掉出底层淘汰，最后幸存者获胜，点击直接加入"));
        inv.setStack(11, queueButton(Items.WATER_BUCKET, "§b心跳水立方", player, MatchType.HEARTBEAT,
                PvPConfig.INSTANCE.heartbeatMinPlayers + "~" + PvPConfig.INSTANCE.heartbeatMaxPlayers
                        + " 人，凑齐 " + PvPConfig.INSTANCE.heartbeatStartPlayers + " 人开赛",
                "从塔顶跳下，穿过每层玻璃地板上的洞",
                "落进水坑过关进下一关（由易到难）",
                "失误回塔顶重试；时间结束完成关卡数最多者胜，点击直接加入"));
        inv.setStack(12, queueButton(Items.BAKED_POTATO, "§c烫手山芋", player, MatchType.HOT_POTATO,
                PvPConfig.INSTANCE.hotPotatoMinPlayers + "~" + PvPConfig.INSTANCE.hotPotatoMaxPlayers
                        + " 人，凑齐 " + PvPConfig.INSTANCE.hotPotatoStartPlayers + " 人开赛",
                "场上唯一一颗山芋，左键点击其他玩家传递",
                "持有时间到会爆炸淘汰，障碍物地图可绕行",
                "最后存活者获胜，点击直接加入"));
        inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
    }

    /** 创建带"排队人数指示"的按钮：有人排队时附魔光效 + 堆叠数 = 排队人数。 */
    private static ItemStack queueButton(Item item, String name, ServerPlayerEntity player,
                                         MatchType type, String... lore) {
        ItemStack stack = makeButton(item, name, lore);
        applyQueueIndicator(stack, player, PvPMod.QUEUE.countQueued(type));
        return stack;
    }

    /** 排队人数指示：count>0 时给物品附魔（发光）并把堆叠数设为排队人数（上限 64）。 */
    private static void applyQueueIndicator(ItemStack stack, ServerPlayerEntity player, int count) {
        if (count <= 0) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server != null) {
            Registry<Enchantment> registry = server.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
            RegistryEntry<Enchantment> unbreaking = registry.getEntry(Enchantments.UNBREAKING).orElse(null);
            if (unbreaking != null) {
                stack.addEnchantment(unbreaking, 1);
            }
        }
        stack.setCount(Math.min(64, count));
    }

    /** 每个服务器 tick 调用（由 PvPMod 挂接）：每秒刷新打开的分类页，实时显示队列人数。 */
    public void tick() {
        if (PvPMod.SERVER == null || PvPMod.SERVER.getTicks() % 20 != 0) {
            return;
        }
        for (UUID uuid : List.copyOf(this.contexts.keySet())) {
            ServerPlayerEntity player = PvPMod.SERVER.getPlayerManager().getPlayer(uuid);
            if (player == null) {
                this.contexts.remove(uuid);
                continue;
            }
            if (!(player.currentScreenHandler instanceof PvpScreenHandler handler)) {
                continue; // 玩家打开的不是 PvP 菜单（对局容器等），跳过
            }
            GuiContext ctx = this.contexts.get(uuid);
            if (ctx == null) {
                continue;
            }
            SimpleInventory inv = handler.getMenu();
            switch (ctx.page) {
                case MAIN -> this.fillMainMenu(inv, player, ctx);
                case PVP_CATEGORY -> this.fillPvpCategory(inv, player, ctx);
                case BRIDGE_CATEGORY -> this.fillBridgeCategory(inv, player, ctx);
                case GAMES_CATEGORY -> this.fillGamesCategory(inv, player, ctx);
                default -> {
                }
            }
            player.currentScreenHandler.sendContentUpdates();
        }
    }

    private void openKitPage(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.kitReturnPage = ctx.page; // 记录进入套件选择前的页面（返回用）
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
            case PVP_CATEGORY -> this.onClickPvpCategory(player, ctx, slotIndex);
            case BRIDGE_CATEGORY -> this.onClickBridgeCategory(player, ctx, slotIndex);
            case GAMES_CATEGORY -> this.onClickGamesCategory(player, ctx, slotIndex);
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
            case 9 -> this.openPvpCategory(player);
            // 空岛战争无套件，直接加入
            case 10 -> this.joinQueue(player, MatchType.SKYWARS, KitManager.skywarsKit());
            case 11 -> this.openBridgeCategory(player);
            case 12 -> this.openGamesCategory(player);
            case 13 -> this.openDuelTargetPage(player);
            case 14 -> this.openStatsPage(player);
            case 15 -> this.openKitInfoPage(player);
            case 21 -> {
                // OP 立即开始：排队空岛战争时可先选主题，其余模式直接开
                QueueEntry entry = PvPMod.QUEUE.getEntry(player);
                if (entry != null && entry.getType() == MatchType.SKYWARS) {
                    this.openThemePage(player);
                } else {
                    this.doForceStart(player);
                }
            }
            case 22 -> {
                if (PvPMod.QUEUE.leave(player)) {
                    player.sendMessage(Messages.info("已离开匹配队列"), false);
                    this.openMainMenu(player);
                }
            }
            default -> {
            }
        }
    }

    /** PvP 对战分类页点击：各模式 → 套件选择。 */
    private void onClickPvpCategory(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openMainMenu(player);
            return;
        }
        MatchType type = switch (slot) {
            case 9 -> MatchType.DUEL_1V1;
            case 10 -> MatchType.DUEL_2V2;
            case 11 -> MatchType.FFA;
            case 12 -> MatchType.SUMO;
            case 13 -> MatchType.PVP_1_8;
            default -> null;
        };
        if (type == null) {
            return;
        }
        ctx.pendingMode = type;
        this.openKitPage(player);
    }

    /** 战桥分类页点击：各模式直接加入队列。 */
    private void onClickBridgeCategory(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openMainMenu(player);
            return;
        }
        MatchType type = switch (slot) {
            case 9 -> MatchType.BRIDGE_1V1;
            case 10 -> MatchType.BRIDGE_1V1V1V1;
            case 11 -> MatchType.BRIDGE_2V2;
            case 12 -> MatchType.BRIDGE_TEAM;
            default -> null;
        };
        if (type != null) {
            this.joinQueue(player, type, KitManager.bridgeKit());
        }
    }

    /** 趣味小游戏分类页点击：各小游戏直接加入队列。 */
    private void onClickGamesCategory(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openMainMenu(player);
            return;
        }
        MatchType type = switch (slot) {
            case 9 -> MatchType.LUCKY_PILLAR;
            case 10 -> MatchType.TNT_RUN;
            case 11 -> MatchType.HEARTBEAT;
            case 12 -> MatchType.HOT_POTATO;
            default -> null;
        };
        if (type == null) {
            return;
        }
        Kit kit = switch (type) {
            case LUCKY_PILLAR -> KitManager.luckyPillarKit();
            case TNT_RUN -> KitManager.tntRunKit();
            case HEARTBEAT -> KitManager.heartbeatKit();
            default -> KitManager.hotPotatoKit();
        };
        this.joinQueue(player, type, kit);
    }

    private void onClickKit(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            // 返回进入套件选择前的页面（PvP 分类 → 回分类；决斗/其他 → 回主菜单）
            if (ctx.kitReturnPage == Page.PVP_CATEGORY) {
                this.openPvpCategory(player);
            } else {
                this.openMainMenu(player);
            }
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
            } else if (type == MatchType.LUCKY_PILLAR) {
                player.sendMessage(Messages.info("已加入幸运之柱：凑齐 " + PvPConfig.INSTANCE.luckyPillarStartPlayers
                        + " 人开赛，空手开局，随机物品与事件"), false);
            } else if (type == MatchType.TNT_RUN) {
                player.sendMessage(Messages.info("已加入 TNT 跑酷：凑齐 " + PvPConfig.INSTANCE.tntRunStartPlayers
                        + " 人开赛，踩过的方块会掉落"), false);
            } else if (type == MatchType.HEARTBEAT) {
                player.sendMessage(Messages.info("已加入心跳水立方：凑齐 " + PvPConfig.INSTANCE.heartbeatStartPlayers
                        + " 人开赛，穿过玻璃洞落水过关，完成关卡数最多者胜"), false);
            } else if (type == MatchType.HOT_POTATO) {
                player.sendMessage(Messages.info("已加入烫手山芋：凑齐 " + PvPConfig.INSTANCE.hotPotatoStartPlayers
                        + " 人开赛，左键传递山芋，时间到爆炸"), false);
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
        MAIN, PVP_CATEGORY, BRIDGE_CATEGORY, GAMES_CATEGORY, KIT, DUEL_TARGET, STATS, KIT_INFO, THEME
    }

    private static final class GuiContext {
        Page page = Page.MAIN;
        Page kitReturnPage = Page.MAIN; // 进入套件选择前的页面（返回用）
        MatchType pendingMode;
        UUID duelTargetUuid;
        int duelTargetPage;
        boolean navigating;
    }
}
