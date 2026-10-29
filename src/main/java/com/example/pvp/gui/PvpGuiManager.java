package com.example.pvp.gui;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.party.PartyMinigame;
import com.example.pvp.arena.skywars.SkyWarsTheme;
import com.example.pvp.arena.skywars.SkyWarsMapVariant;
import com.example.pvp.config.PlayerStats;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.config.StatsStore;
import com.example.pvp.kit.Kit;
import com.example.pvp.kit.KitManager;
import com.example.pvp.match.MatchType;
import com.example.pvp.match.VillageDefenseKitGui;
import com.example.pvp.practice.PracticeManager;
import com.example.pvp.practice.PracticeType;
import com.example.pvp.practice.BotDifficulty;
import com.example.pvp.queue.QueueEntry;
import com.example.pvp.queue.MyGoTeamPreference;
import com.example.pvp.text.Messages;
import com.mojang.authlib.GameProfile;
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

    /** 是否是任意 UI 工具（菜单/排队/观战）。 */
    public static boolean isUiItem(ItemStack stack) {
        return isMenuItem(stack) || isQueueItem(stack) || isSpectatorUiItem(stack);
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

    /** 常玩模式按指定顺序直达首页，其余玩法收进“其他游戏”，减少菜单查找层级。 */
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
        // 槽 9~17 按首页顺序排列；操作按钮独占底行，避免排队刷新覆盖游戏入口。
        ItemStack skywars = makeButton(Items.END_CRYSTAL, "§6空岛战争", "2~8 人，凑齐 "
                + PvPConfig.INSTANCE.skywarsStartPlayers + " 人开赛",
                "随机空岛 + 中间主岛，开箱获得装备",
                "1.8 低版本战斗，3 分钟后缩圈",
                "最后存活者获胜，点击直接加入",
                "大厅输入 /pvp professions 预览职业物资");
        this.applyQueueIndicator(skywars, player, PvPMod.QUEUE.countQueued(MatchType.SKYWARS));
        inv.setStack(9, skywars);

        // MyGO 允许两人正常匹配，入口同步提示 1v1；火药可堆叠以完整显示排队人数。
        inv.setStack(10, queueButton(Items.GUNPOWDER, "§6MyGO", player, MatchType.MYGO,
                "1v1~5v5 经典爆破，先赢十六回合",
                "工具枪械 / 经济购买 / 南瓜瞄准镜",
                "左键点射，瞄具枪右键切倍率/关镜，F 换弹",
                "选择 T / CT / 自动平衡后加入",
                "支持 2~10 人，奇数人数两队相差一人"));

        inv.setStack(11, queueButton(Items.CROSSBOW, "§e§l枪械空岛", player, MatchType.SKYWARS_GUNS,
                "2~8 人，复用普通空岛地图",
                "出生只有战术刀，枪械和弹药从箱子获取",
                "普通空岛逻辑独立匹配，暂不与普通空岛混队",
                "需要加载 MyGO 资源包"));

        inv.setStack(12, queueButton(Items.TNT, "§cTNT 跑酷", player, MatchType.TNT_RUN,
                PvPConfig.INSTANCE.tntRunMinPlayers + "~" + PvPConfig.INSTANCE.tntRunMaxPlayers
                        + " 人，凑齐 " + PvPConfig.INSTANCE.tntRunStartPlayers + " 人开赛",
                "5 层彩色平台，踩过的方块 0.2 秒后掉落",
                "地面会刷火焰弹/TNT，捡起来砸人/炸人",
                "掉出底层淘汰，最后幸存者获胜，点击直接加入"));

        ItemStack pvp = makeButton(Items.DIAMOND_SWORD, "§b§lPVP 竞技场",
                "1v1 / 2v2 / 自由乱斗 / 相扑 / 死斗",
                "点击选择对战模式与套件");
        this.applyQueueIndicator(pvp, player, PvPMod.QUEUE.countQueued(
                MatchType.DUEL_1V1, MatchType.DUEL_2V2, MatchType.FFA, MatchType.SUMO, MatchType.DEATHMATCH));
        inv.setStack(13, pvp);

        inv.setStack(14, queueButton(Items.QUARTZ_PILLAR, "§d幸运之柱", player, MatchType.LUCKY_PILLAR,
                PvPConfig.INSTANCE.luckyPillarMinPlayers + "~" + PvPConfig.INSTANCE.luckyPillarMaxPlayers
                        + " 人，凑齐 " + PvPConfig.INSTANCE.luckyPillarStartPlayers + " 人开赛",
                "每位玩家一根 40 格基岩棍，柱下有大平台",
                "底部平台每局随机 10 种风格",
                "每 3 秒随机获得 1 件物品",
                "随机事件：一击必杀/箭雨/雷击/TNT 雨/位置交换/补给潮",
                "最后存活者获胜，点击直接加入"));

        // 亦可赛艇保留赛道信息与入队后的圈数选择。
        ItemStack boatRace = makeButton(Items.OAK_BOAT, "§b§l🏁 亦可赛艇",
                "驾驶船只，在随机生成的冰面赛道上竞速！",
                "当前玩家：" + PvPMod.QUEUE.countQueued(MatchType.BOAT_RACE)
                        + " / " + PvPConfig.INSTANCE.boatRaceMaxPlayers,
                "每场赛道都不一样（可复现的比赛 Seed）",
                "赛道宽 " + PvPConfig.INSTANCE.boatRaceTrackWidth + " 格，长约 "
                        + PvPConfig.INSTANCE.boatRaceMinTrackLength + "~"
                        + PvPConfig.INSTANCE.boatRaceMaxTrackLength + " 格，跑 "
                        + PvPConfig.INSTANCE.boatRaceLaps + " 圈",
                "按顺序穿过 Checkpoint，掉出赛道自动回位",
                "点击加入比赛");
        this.applyQueueIndicator(boatRace, player, PvPMod.QUEUE.countQueued(MatchType.BOAT_RACE));
        inv.setStack(15, boatRace);

        inv.setStack(16, queueButton(Items.FIREWORK_STAR, "§dParty 2", player, MatchType.MINECRAFT_PARTY,
                MatchType.PORTED_MIN_PLAYERS + "~" + MatchType.PORTED_MAX_PLAYERS + " 人，凑齐 "
                        + MatchType.PORTED_START_PLAYERS + " 人立即开赛",
                "人数不足 " + MatchType.PORTED_START_PLAYERS + " 人时等待 " + MatchType.PORTED_FILL_SECONDS + " 秒",
                "分阶段移植：首批 3 个小游戏 Quick Play",
                "Spleef / Tile Capture / Red Light Green Light",
                "棋盘与其余子游戏待移植，点击直接加入"));
        inv.setStack(17, queueButton(Items.RED_BANNER, "§cIsland Siege", player, MatchType.ISLAND_SIEGE,
                MatchType.PORTED_MIN_PLAYERS + "~" + MatchType.PORTED_MAX_PLAYERS + " 人，凑齐 "
                        + MatchType.PORTED_START_PLAYERS + " 人立即开赛",
                "人数不足 " + MatchType.PORTED_START_PLAYERS + " 人时等待 " + MatchType.PORTED_FILL_SECONDS + " 秒",
                "海岛攻防：红队进攻、蓝队防守，先建防线再攻岛",
                "进攻方完成三项目标中的两项获胜",
                "装备由玩法统一发放，点击直接加入"));

        ItemStack otherGames = makeButton(Items.FIREWORK_ROCKET, "§d§l其他游戏",
                "战桥 / 起床战争 / 心跳水立方 / 烫手山芋",
                "村庄保卫战 / 色盲派对 / 练习模式",
                "点击选择其他玩法");
        this.applyQueueIndicator(otherGames, player, PvPMod.QUEUE.countQueued(
                MatchType.BRIDGE_1V1, MatchType.BRIDGE_1V1V1V1, MatchType.BRIDGE_2V2, MatchType.BRIDGE_TEAM,
                MatchType.BED_WARS, MatchType.BED_WARS_DOUBLES, MatchType.HEARTBEAT, MatchType.HOT_POTATO,
                MatchType.VILLAGE_DEFENSE, MatchType.COLORBLIND_PARTY));
        inv.setStack(22, otherGames);

        inv.setStack(27, makeButton(Items.PAPER, "§e向玩家发起决斗", "选择一名在线玩家", "1v1 单挑"));
        inv.setStack(28, makeButton(Items.BOOK, "§d我的战绩", "查看胜/负/场次"));
        inv.setStack(29, makeButton(Items.CHEST, "§d查看套件列表", "浏览所有装备方案"));

        if (PvPMod.QUEUE.contains(player.getUuid())) {
            String status = "排队中";
            var entry = PvPMod.QUEUE.getEntry(player);
            if (entry != null) {
                status = "排队中：" + entry.getType().getDisplayName() + " / " + entry.getKit().getDisplayName();
            }
            // OP(2级+) 可立即用当前队列人数开赛
            if (player.hasPermissionLevel(2)) {
                inv.setStack(33, makeButton(Items.EMERALD, "§a立即开始",
                        "OP 专用：立刻用当前队列人数开赛",
                        "人数不足时无法开始"));
            }
            inv.setStack(34, makeButton(Items.BARRIER, "§c离开队列", status));
        }
    }

    /** 起床战争分类页：Solo / 双人。 */
    private void openBedWarsCategory(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.BED_WARS_CATEGORY;
        this.openPage(player, ctx, "§6§l起床战争", inv -> this.fillBedWarsCategory(inv, player, ctx));
    }

    /** 起床战争分类按钮填充。 */
    private void fillBedWarsCategory(SimpleInventory inv, ServerPlayerEntity player, GuiContext ctx) {
        for (int slot = 0; slot < 36; slot++) {
            inv.setStack(slot, makeButton(Items.GRAY_STAINED_GLASS_PANE, " "));
        }
        inv.setStack(9, queueButton(Items.RED_BED, "§c起床战争 (Solo)", player, MatchType.BED_WARS,
                "每队 1 人，最多 8 队",
                "摧毁敌方床 + 淘汰所有人获胜",
                "Hypixel 风格地图，点击直接加入"));
        inv.setStack(10, queueButton(Items.RED_BED, "§c起床战争 (双人)", player, MatchType.BED_WARS_DOUBLES,
                "每队 2 人，最多 8 队（16 人）",
                "与队友配合守床攻敌",
                "Hypixel 风格地图，点击直接加入"));
        inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
    }

    /** 起床战争分类点击。 */
    private void onClickBedWarsCategory(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openOtherGames(player);
            return;
        }
        MatchType type = switch (slot) {
            case 9 -> MatchType.BED_WARS;
            case 10 -> MatchType.BED_WARS_DOUBLES;
            default -> null;
        };
        if (type != null) {
            this.joinQueue(player, type, KitManager.bedWarsKit());
        }
    }

    /** PvP 对战分类页：需要套件的对战模式。 */
    private void openPvpCategory(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.PVP_CATEGORY;
        this.openPage(player, ctx, "§6§lPVP 竞技场", inv -> this.fillPvpCategory(inv, player, ctx));
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
        inv.setStack(13, queueButton(Items.NETHERITE_CHESTPLATE, "§4死斗 (Deathmatch)", player, MatchType.DEATHMATCH,
                "限时 " + (PvPConfig.INSTANCE.deathmatchDurationSeconds / 60) + " 分钟，死亡立即复活，不淘汰",
                "人头最多者获胜（同人头比死亡数）",
                PvPConfig.INSTANCE.deathmatchMinPlayers + "~" + PvPConfig.INSTANCE.deathmatchMaxPlayers
                        + " 人，凑齐 " + PvPConfig.INSTANCE.deathmatchStartPlayers + " 人开赛",
                "点击选择套件后加入队列"));
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
                "2 人，先得 " + win + " 分获胜", "跳进对方球门洞得分", "1.8 低版本战斗：无攻击冷却", "点击直接加入"));
        inv.setStack(10, queueButton(Items.IRON_BARS, "§5战桥 1v1v1v1", player, MatchType.BRIDGE_1V1V1V1,
                "4 人四方混战，各自一个球门", "先得 " + win + " 分获胜", "点击直接加入"));
        inv.setStack(11, queueButton(Items.RED_TERRACOTTA, "§b战桥 2v2", player, MatchType.BRIDGE_2V2,
                "4 人随机分队", "先得 " + win + " 分获胜", "点击直接加入"));
        inv.setStack(12, queueButton(Items.DIAMOND_PICKAXE, "§e战桥 混战", player, MatchType.BRIDGE_TEAM,
                "偶数人数，总人数/2 分两队", "2v2 / 3v3 / 4v4...", "先得 " + win + " 分获胜", "点击直接加入"));
        inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
    }

    /** 其他游戏二级页：容纳首页以外的玩法与练习入口。 */
    private void openOtherGames(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.OTHER_GAMES;
        this.openPage(player, ctx, "§6§l其他游戏", inv -> this.fillOtherGames(inv, player, ctx));
    }

    /** 其他游戏按钮填充（各模式按钮显示该模式排队人数）。 */
    private void fillOtherGames(SimpleInventory inv, ServerPlayerEntity player, GuiContext ctx) {
        for (int slot = 0; slot < 36; slot++) {
            inv.setStack(slot, makeButton(Items.GRAY_STAINED_GLASS_PANE, " "));
        }
        ItemStack bridge = makeButton(Items.BRICKS, "§3§l战桥",
                "1v1 / 1v1v1v1 / 2v2 / 混战", "点击选择战桥模式");
        this.applyQueueIndicator(bridge, player, PvPMod.QUEUE.countQueued(
                MatchType.BRIDGE_1V1, MatchType.BRIDGE_1V1V1V1, MatchType.BRIDGE_2V2, MatchType.BRIDGE_TEAM));
        inv.setStack(9, bridge);
        ItemStack bedWars = makeButton(Items.RED_BED, "§c§l起床战争",
                "Solo / 双人", "点击选择起床战争模式");
        this.applyQueueIndicator(bedWars, player, PvPMod.QUEUE.countQueued(MatchType.BED_WARS, MatchType.BED_WARS_DOUBLES));
        inv.setStack(10, bedWars);
        inv.setStack(11, queueButton(Items.WATER_BUCKET, "§b心跳水立方", player, MatchType.HEARTBEAT,
                PvPConfig.INSTANCE.heartbeatMinPlayers + "~" + PvPConfig.INSTANCE.heartbeatMaxPlayers
                        + " 人，凑齐 " + PvPConfig.INSTANCE.heartbeatStartPlayers + " 人开赛",
                "从出发台中央洞口跳下，穿过每层红色地板上的洞",
                "落进水坑过关进下一关（由易到难）",
                "失误回塔顶重试；时间结束完成关卡数最多者胜，点击直接加入"));
        inv.setStack(12, queueButton(Items.BAKED_POTATO, "§c烫手山芋", player, MatchType.HOT_POTATO,
                PvPConfig.INSTANCE.hotPotatoMinPlayers + "~" + PvPConfig.INSTANCE.hotPotatoMaxPlayers
                        + " 人，凑齐 " + PvPConfig.INSTANCE.hotPotatoStartPlayers + " 人开赛",
                "场上唯一一颗山芋，左键点击其他玩家传递",
                "持有时间到会爆炸淘汰，障碍物地图可绕行",
                "最后存活者获胜，点击直接加入"));
        inv.setStack(13, queueButton(Items.VILLAGER_SPAWN_EGG, "§2村庄保卫战", player, MatchType.VILLAGE_DEFENSE,
                PvPConfig.INSTANCE.villageDefenseMinPlayers + "~" + PvPConfig.INSTANCE.villageDefenseMaxPlayers
                        + " 人，凑齐 " + PvPConfig.INSTANCE.villageDefenseStartPlayers + " 人开赛",
                "合作守住村庄、保护村民，抵挡一波波僵尸",
                "杀怪/过波得 orbs，右击村民进商店买装备/召唤狼/铁傀儡",
                "潜行右击村民可献祭腐肉给全队加血",
                "守到终波全员获胜，点击直接加入"));
        inv.setStack(14, makeButton(Items.WOODEN_SWORD, "§a选择村庄保卫战职业",
                "从 24 套职业中选一套（装备/技能各异）",
                "点击打开职业选择界面，进对局生效"));
        inv.setStack(15, queueButton(Items.MAGENTA_CONCRETE, "§d色盲派对", player, MatchType.COLORBLIND_PARTY,
                PvPConfig.INSTANCE.colorblindMinPlayers + "~" + PvPConfig.INSTANCE.colorblindMaxPlayers
                        + " 人，凑齐 " + PvPConfig.INSTANCE.colorblindStartPlayers + " 人开赛",
                "一整片彩色地板，每回合从 16 色里随机抽 8 色",
                "标题写着某个颜色名，却被渲染成另一种颜色",
                "站到「文字的颜色」上，别管它写的是什么！",
                "非目标色方块全部消失，站错的掉进虚空淘汰",
                "共 " + PvPConfig.INSTANCE.colorblindRounds + " 回合，最后存活者获胜，点击直接加入"));
        inv.setStack(16, queueButton(Items.OAK_BOAT, "§b🏁 亦可赛艇", player, MatchType.BOAT_RACE,
                "驾驶船只，在随机冰面赛道上竞速！",
                "当前玩家：" + PvPMod.QUEUE.countQueued(MatchType.BOAT_RACE)
                        + " / " + PvPConfig.INSTANCE.boatRaceMaxPlayers,
                PvPConfig.INSTANCE.boatRaceMinPlayers + "~" + PvPConfig.INSTANCE.boatRaceMaxPlayers
                        + " 人，凑齐 " + PvPConfig.INSTANCE.boatRaceStartPlayers + " 人开赛",
                "驾驶原版船，在随机生成的冰面赛道上竞速",
                "每场比赛的赛道都不一样（可复现的比赛 Seed）",
                "赛道宽 " + PvPConfig.INSTANCE.boatRaceTrackWidth + " 格，"
                        + PvPConfig.INSTANCE.boatRaceMinTrackLength + "~"
                        + PvPConfig.INSTANCE.boatRaceMaxTrackLength + " 格长，"
                        + PvPConfig.INSTANCE.boatRaceLaps + " 圈定胜负",
                "按顺序穿过 Checkpoint，掉出赛道自动回位，点击直接加入"));
        // 明确分阶段移植范围，避免入口名称让玩家误以为整张原版地图已经全部上线。
        inv.setStack(17, queueButton(Items.FIREWORK_STAR, "§dMinecraft Party 2（移植中）",
                player, MatchType.MINECRAFT_PARTY,
                MatchType.PORTED_MIN_PLAYERS + "~" + MatchType.PORTED_MAX_PLAYERS + " 人，凑齐 "
                        + MatchType.PORTED_START_PLAYERS + " 人立即开赛",
                "人数不足 " + MatchType.PORTED_START_PLAYERS + " 人时等待 " + MatchType.PORTED_FILL_SECONDS + " 秒",
                "分阶段移植：已实现 " + PartyMinigame.values().length + " 个可选小游戏，每局五轮累计积分",
                "每轮菜单投票，已玩项目不再出现",
                "五轮后公布总排名，点击直接加入"));
        inv.setStack(18, queueButton(Items.RED_BANNER, "§c海岛攻防", player, MatchType.ISLAND_SIEGE,
                MatchType.PORTED_MIN_PLAYERS + "~" + MatchType.PORTED_MAX_PLAYERS + " 人，凑齐 "
                        + MatchType.PORTED_START_PLAYERS + " 人立即开赛",
                "人数不足 " + MatchType.PORTED_START_PLAYERS + " 人时等待 " + MatchType.PORTED_FILL_SECONDS + " 秒",
                "红队进攻、蓝队防守，先建防线再攻岛",
                "进攻方完成三项目标中的两项获胜",
                "装备由玩法统一发放，点击直接加入"));
        inv.setStack(16, makeButton(Items.WHITE_WOOL, "§a§l练习模式",
                "PvP 人机 1v1 / 搭桥 / 末影珍珠", "单人练习，点击选择项目"));
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
                case OTHER_GAMES -> this.fillOtherGames(inv, player, ctx);
                case BED_WARS_CATEGORY -> this.fillBedWarsCategory(inv, player, ctx);
                case PRACTICE_CATEGORY -> this.fillPracticeCategory(inv, player, ctx);
                default -> {
                }
            }
            player.currentScreenHandler.sendContentUpdates();
        }
    }

    // 人机练习需要先选难度和双方共用套件，避免在进入竞技场后才发现配置不符合预期。
    /** 练习模式分类页。 */
    private void openPracticeCategory(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.PRACTICE_CATEGORY;
        this.openPage(player, ctx, "§6§l练习模式", inv -> this.fillPracticeCategory(inv, player, ctx));
    }

    /** 练习模式按钮填充。 */
    private void fillPracticeCategory(SimpleInventory inv, ServerPlayerEntity player, GuiContext ctx) {
        for (int slot = 0; slot < 36; slot++) {
            inv.setStack(slot, makeButton(Items.GRAY_STAINED_GLASS_PANE, " "));
        }

        PracticeType bridge = PracticeType.BRIDGE;
        inv.setStack(11, makeButton(Items.WHITE_WOOL, "§b" + bridge.getDisplayName(),
                "§7从出发点铺到 §f" + bridge.getGap() + " 格§7外的目标台",
                "§7补给 §f" + bridge.getSupply() + " §7个方块",
                "§7抵达目标台计时，掉下去自动重来",
                "§a点击开始练习"));

        PracticeType pearl = PracticeType.ENDER_PEARL;
        inv.setStack(13, makeButton(Items.ENDER_PEARL, "§d" + pearl.getDisplayName(),
                "§7用珍珠位移到 §f" + pearl.getGap() + " 格§7外的目标台",
                "§7补给 §f" + pearl.getSupply() + " §7颗珍珠，珠间 2 秒冷却",
                "§7抵达目标台计时，掉下去自动重来",
                "§a点击开始练习"));

        inv.setStack(15, makeButton(Items.DIAMOND_SWORD, "§6§lPvP 人机 1v1",
                "§7使用正式 1v1 竞技场与双方相同的套件",
                "§7五档难度：新手 → 专家",
                "§7练习走位、攻击节奏与攻防应对",
                "§a点击选择人机难度"));

        inv.setStack(26, makeButton(Items.ARROW, "§c← 返回其他游戏"));
    }

    /** 练习模式分类页点击。 */
    private void onClickPracticeCategory(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openOtherGames(player);
            return;
        }
        if (slot == 15) {
            this.openPracticeDifficulty(player);
            return;
        }
        PracticeType type = switch (slot) {
            case 11 -> PracticeType.BRIDGE;
            case 13 -> PracticeType.ENDER_PEARL;
            default -> null;
        };
        if (type != null) {
            // 必须先关界面再传送：跨维度传送时界面还开着，客户端会残留一个鬼影容器
            player.closeHandledScreen();
            PracticeManager.get().startPractice(player, type);
        }
    }

    private void openPracticeDifficulty(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.PRACTICE_DIFFICULTY;
        this.openPage(player, ctx, "§6PvP 练习 · 选择难度", inv -> {
            Item[] icons = {Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
                    Items.DIAMOND_SWORD, Items.NETHERITE_SWORD};
            inv.setStack(4, makeButton(Items.PLAYER_HEAD, "§e玩家 vs 人机 · 1v1",
                    "§7难度影响反应、瞄准、走位与战术选择",
                    "§7双方使用同一套装备，练习不计正式战绩",
                    "§7退出：/pvp practice leave 或 /hub"));
            for (BotDifficulty difficulty : BotDifficulty.values()) {
                int index = difficulty.ordinal();
                inv.setStack(11 + index, makeButton(icons[index],
                        "§e" + difficulty.getLevel() + " / 5 · " + difficulty.getDisplayName(),
                        difficulty.getDescription(), "§a点击选择套件"));
            }
            inv.setStack(26, makeButton(Items.ARROW, "§c← 返回练习模式"));
        });
    }

    private void onClickPracticeDifficulty(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openPracticeCategory(player);
            return;
        }
        int index = slot - 11;
        if (index >= 0 && index < BotDifficulty.values().length) {
            ctx.practiceDifficulty = BotDifficulty.values()[index];
            ctx.practiceKitPage = 0;
            this.openPracticeKit(player);
        }
    }

    private void openPracticeKit(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.PRACTICE_KIT;
        List<Kit> kits = PracticeManager.getPvpKits();
        int perPage = 17;
        int lastPage = Math.max(0, (kits.size() - 1) / perPage);
        ctx.practiceKitPage = Math.min(ctx.practiceKitPage, lastPage);
        this.openPage(player, ctx, "§6PvP 练习 · 选择套件", inv -> {
            inv.setStack(4, makeButton(Items.PLAYER_HEAD,
                    "§e难度 " + ctx.practiceDifficulty.getLevel() + " / 5 · "
                            + ctx.practiceDifficulty.getDisplayName(),
                    "§7你与人机使用相同装备", "§7选择套件后立即进入独立竞技场"));
            int start = ctx.practiceKitPage * perPage;
            for (int i = start; i < kits.size() && i < start + perPage; i++) {
                Kit kit = kits.get(i);
                inv.setStack(9 + i - start, makeButton(iconFor(kit), "§b" + kit.getDisplayName(),
                        "§7套件：" + kit.getId(),
                        "§7人机难度：" + ctx.practiceDifficulty.getDisplayName(),
                        "§a点击开始人机 1v1"));
            }
            inv.setStack(27, makeButton(Items.ARROW, "§c← 返回难度选择"));
            if (ctx.practiceKitPage > 0) {
                inv.setStack(30, makeButton(Items.ARROW, "§e上一页"));
            }
            inv.setStack(31, makeButton(Items.PAPER,
                    "§7第 " + (ctx.practiceKitPage + 1) + " / " + (lastPage + 1) + " 页"));
            if (ctx.practiceKitPage < lastPage) {
                inv.setStack(32, makeButton(Items.ARROW, "§e下一页"));
            }
        });
    }

    private void onClickPracticeKit(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 27) {
            this.openPracticeDifficulty(player);
            return;
        }
        List<Kit> kits = PracticeManager.getPvpKits();
        if (slot == 30 && ctx.practiceKitPage > 0) {
            ctx.practiceKitPage--;
            this.openPracticeKit(player);
        } else if (slot == 32 && (ctx.practiceKitPage + 1) * 17 < kits.size()) {
            ctx.practiceKitPage++;
            this.openPracticeKit(player);
        } else if (slot >= 9 && slot <= 25) {
            int index = ctx.practiceKitPage * 17 + slot - 9;
            if (index < kits.size()) {
                Kit kit = kits.get(index);
                BotDifficulty difficulty = ctx.practiceDifficulty;
                player.closeHandledScreen();
                PracticeManager.get().startPvpPractice(player, difficulty, kit);
            }
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

    /** 战绩排行榜页：顶部显示我的战绩，中间按胜率排名（玩家头颅），底部翻页。 */
    private void openStatsPage(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.STATS;
        this.openPage(player, ctx, "§6战绩排行榜（按胜率）", inv -> this.fillStatsPage(inv, player, ctx));
    }

    /** 战绩排行榜页填充（每页 18 名，底部上一页/页码/下一页/返回）。 */
    private void fillStatsPage(SimpleInventory inv, ServerPlayerEntity player, GuiContext ctx) {
        for (int slot = 0; slot < 36; slot++) {
            inv.setStack(slot, makeButton(Items.GRAY_STAINED_GLASS_PANE, " "));
        }
        // 顶部中央：我的战绩（隐藏分不展示给玩家）
        PlayerStats mine = StatsStore.INSTANCE.getStats(player.getUuid());
        inv.setStack(4, makeButton(Items.BOOK, "§a我的战绩",
                "胜 " + mine.getWins() + " / 负 " + mine.getLosses() + " / 总 " + mine.getMatches(),
                "胜率 " + formatRate(mine.winRate())));

        List<Map.Entry<String, PlayerStats>> board = StatsStore.INSTANCE.getLeaderboard();
        if (board.isEmpty()) {
            inv.setStack(13, makeButton(Items.PAPER, "§7暂无战绩数据"));
        } else {
            int perPage = 18; // 第 2~3 行（槽 9~26）
            int pages = (board.size() + perPage - 1) / perPage;
            if (ctx.statsPage >= pages) {
                ctx.statsPage = pages - 1;
            }
            int start = ctx.statsPage * perPage;
            int slot = 9;
            for (int i = start; i < Math.min(board.size(), start + perPage); i++, slot++) {
                Map.Entry<String, PlayerStats> entry = board.get(i);
                String name;
                try {
                    name = resolveName(UUID.fromString(entry.getKey()));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                PlayerStats s = entry.getValue();
                boolean self = entry.getKey().equals(player.getUuid().toString());
                ItemStack head = makeButton(Items.PLAYER_HEAD,
                        (self ? "§a" : "§e") + "#" + (i + 1) + " " + name,
                        "胜率 " + formatRate(s.winRate()),
                        "胜 " + s.wins + " / 负 " + s.losses + " / 总 " + s.matches);
                if (self) {
                    head.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                }
                inv.setStack(slot, head);
            }
            // 底部：翻页与返回
            inv.setStack(27, makeButton(Items.ARROW, "§c← 返回主菜单"));
            if (ctx.statsPage > 0) {
                inv.setStack(30, makeButton(Items.ARROW, "§7← 上一页"));
            }
            inv.setStack(31, makeButton(Items.PAPER, "§e第 " + (ctx.statsPage + 1) + " / " + pages + " 页",
                    "共 " + board.size() + " 名玩家"));
            if (start + perPage < board.size()) {
                inv.setStack(32, makeButton(Items.ARROW, "§7下一页 →"));
            }
        }
    }

    private static String formatRate(double rate) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", rate * 100);
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
            inv.setStack(15, makeButton(Items.BELL, "§6村庄", "中岛放大成一整座村庄（原版房屋 + 原版箱子）"));
            inv.setStack(14, makeButton(Items.ENDER_PEARL, "§e随机主题", "不指定，交给运气"));
            inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
        });
    }

    /** OP 立即开始时的幸运之柱地图选择页（仅排队幸运之柱时出现）。 */
    private void openLuckyPillarMapPage(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.LUCKY_PILLAR_MAP;
        this.openPage(player, ctx, "§6选择幸运之柱地图（立即开始）", inv -> {
            com.example.pvp.arena.luckypillar.LuckyPillarLayout.PlatformStyle[] styles =
                    com.example.pvp.arena.luckypillar.LuckyPillarLayout.PlatformStyle.values();
            int slot = 9;
            for (com.example.pvp.arena.luckypillar.LuckyPillarLayout.PlatformStyle style : styles) {
                if (slot >= 26) {
                    break;
                }
                inv.setStack(slot++, makeButton(iconForLuckyPillarStyle(style), "§e" + style.getDisplayName(), "点击指定该地图"));
            }
            inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
        });
    }

    /** OP 立即开始时的床战地图选择页（仅排队床战时出现）。 */
    private void openBedwarsMapPage(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.BEDWARS_MAP;
        this.openPage(player, ctx, "§6选择床战地图（立即开始）", inv -> {
            java.util.List<java.nio.file.Path> maps = com.example.pvp.arena.bedwars.BedWarsMaps.listMaps();
            int slot = 9;
            for (java.nio.file.Path mapDir : maps) {
                if (slot >= 26) {
                    break;
                }
                inv.setStack(slot++, makeButton(Items.RED_BED, "§e" + mapDir.getFileName().toString(), "点击指定该地图"));
            }
            inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
        });
    }

    /** OP 立即开始时的 MyGO 地图选择页；随机按钮交给会话从地图目录选择。 */
    private void openMyGoMapPage(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.MYGO_MAP;
        this.openPage(player, ctx, "§6选择 MyGO 地图（立即开始）", inv -> {
            inv.setStack(9, makeButton(Items.SANDSTONE, "§eDust II", "经典沙漠地图", "点击指定并立即开始"));
            inv.setStack(11, makeButton(Items.SANDSTONE_WALL, "§bMirage", "中路 / A 大 / B 小", "点击指定并立即开始"));
            inv.setStack(13, makeButton(Items.BRICKS, "§cInferno", "香蕉道 / 中路 / 双包点", "点击指定并立即开始"));
            inv.setStack(15, makeButton(Items.ENDER_EYE, "§d随机地图", "从 Dust II、Mirage、Inferno 中随机选择", "点击立即开始"));
            inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
        });
    }

    /** MyGO 入队前选择队伍偏好；资源包尚未加载时偏好会暂存到下一次入队。 */
    private void openMyGoTeamPage(ServerPlayerEntity player) {
        GuiContext ctx = getContext(player);
        ctx.page = Page.MYGO_TEAM;
        this.openPage(player, ctx, "§6选择 MyGO 队伍", inv -> {
            inv.setStack(10, makeButton(Items.RED_WOOL, "§cT 进攻方", "优先安排到 T，奇数人数时最多一人差",
                    "点击选择并加入 MyGO"));
            inv.setStack(13, makeButton(Items.BLUE_WOOL, "§9CT 防守方", "优先安排到 CT，奇数人数时最多一人差",
                    "点击选择并加入 MyGO"));
            inv.setStack(16, makeButton(Items.WHITE_WOOL, "§f自动平衡", "由服务器按人数平衡分队",
                    "点击选择并加入 MyGO"));
            inv.setStack(26, makeButton(Items.ARROW, "§c← 返回"));
        });
    }

    /** 幸运之柱地图风格对应的展示图标。 */
    private static net.minecraft.item.Item iconForLuckyPillarStyle(com.example.pvp.arena.luckypillar.LuckyPillarLayout.PlatformStyle style) {
        return switch (style) {
            case MAGMA -> Items.MAGMA_BLOCK;
            case LAVA_CAULDRON -> Items.CAULDRON;
            case SNOW_POWDER -> Items.SNOW_BLOCK;
            case COBWEB -> Items.COBWEB;
            case SAND_CACTUS -> Items.CACTUS;
            case LEAVES -> Items.OAK_LEAVES;
            case TRAPDOOR -> Items.OAK_TRAPDOOR;
            case SLAB -> Items.SMOOTH_STONE_SLAB;
            case SLIME_HONEY -> Items.SLIME_BLOCK;
            case VOID -> Items.BLACK_STAINED_GLASS;
        };
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
            case OTHER_GAMES -> this.onClickOtherGames(player, ctx, slotIndex);
            case BED_WARS_CATEGORY -> this.onClickBedWarsCategory(player, ctx, slotIndex);
            case KIT -> this.onClickKit(player, ctx, slotIndex);
            case DUEL_TARGET -> this.onClickDuelTarget(player, ctx, slotIndex);
            case THEME -> this.onClickTheme(player, ctx, slotIndex);
            case LUCKY_PILLAR_MAP -> this.onClickLuckyPillarMap(player, ctx, slotIndex);
            case BEDWARS_MAP -> this.onClickBedwarsMap(player, ctx, slotIndex);
            case MYGO_MAP -> this.onClickMyGoMap(player, ctx, slotIndex);
            case MYGO_TEAM -> this.onClickMyGoTeam(player, ctx, slotIndex);
            case PRACTICE_CATEGORY -> this.onClickPracticeCategory(player, ctx, slotIndex);
            case PRACTICE_DIFFICULTY -> this.onClickPracticeDifficulty(player, ctx, slotIndex);
            case PRACTICE_KIT -> this.onClickPracticeKit(player, ctx, slotIndex);
            case STATS -> {
                if (slotIndex == 27) {
                    this.openMainMenu(player);
                } else if (slotIndex == 30 && ctx.statsPage > 0) {
                    ctx.statsPage--;
                    this.openStatsPage(player);
                } else if (slotIndex == 32) {
                    ctx.statsPage++;
                    this.openStatsPage(player); // fillStatsPage 内部会夹紧到最后一页
                }
            }
            case KIT_INFO -> {
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

    /**
     * 亦可赛艇：把圈数选项做成可点击聊天菜单（点一下 = {@code /pvp racinglaps N}）。
     *
     * <p>没有单独做 GUI 页：排队到开赛有几十秒倒计时，聊天里点一下足够，
     * 而且不耦合 GUI 框架，回归风险小。开赛前按全场多数意见生效。
     */
    private void sendRaceLapsChooser(ServerPlayerEntity player) {
        net.minecraft.text.MutableText text = Text.literal("§b§l亦可赛艇 §r§7想跑几圈？ ");
        for (int laps : new int[]{3, 5, 7, 10}) {
            text.append(Text.literal("§a[" + laps + " 圈] ")
                    .setStyle(net.minecraft.text.Style.EMPTY
                            .withClickEvent(new net.minecraft.text.ClickEvent(
                                    net.minecraft.text.ClickEvent.Action.RUN_COMMAND,
                                    "/pvp racinglaps " + laps))
                            .withHoverEvent(new net.minecraft.text.HoverEvent(
                                    net.minecraft.text.HoverEvent.Action.SHOW_TEXT,
                                    Text.literal("把本场设为 " + laps + " 圈")))));
        }
        text.append(Text.literal("§7（开赛前可随时改，按多数意见生效）"));
        player.sendMessage(text, false);
    }

    private void onClickMain(ServerPlayerEntity player, GuiContext ctx, int slot) {
        switch (slot) {
            case 9 -> this.joinQueue(player, MatchType.SKYWARS, KitManager.skywarsKit());
            case 10 -> this.openMyGoTeamPage(player);
            case 11 -> this.joinQueue(player, MatchType.SKYWARS_GUNS, KitManager.skywarsKit());
            case 12 -> this.joinQueue(player, MatchType.TNT_RUN, KitManager.tntRunKit());
            case 13 -> this.openPvpCategory(player);
            case 14 -> this.joinQueue(player, MatchType.LUCKY_PILLAR, KitManager.luckyPillarKit());
            case 15 -> {
                this.joinQueue(player, MatchType.BOAT_RACE, KitManager.boatRaceKit());
                this.sendRaceLapsChooser(player);
            }
            case 16 -> this.joinQueue(player, MatchType.MINECRAFT_PARTY, KitManager.portedGameKit());
            case 17 -> this.joinQueue(player, MatchType.ISLAND_SIEGE, KitManager.portedGameKit());
            case 22 -> this.openOtherGames(player);
            case 27 -> this.openDuelTargetPage(player);
            case 28 -> this.openStatsPage(player);
            case 29 -> this.openKitInfoPage(player);
            case 33 -> {
                // OP 立即开始：排队空岛/幸运之柱/床战时可先选地图/主题，其余模式直接开
                QueueEntry entry = PvPMod.QUEUE.getEntry(player);
                if (entry != null && entry.getType().isSkyWars()) {
                    this.openThemePage(player);
                } else if (entry != null && entry.getType() == MatchType.LUCKY_PILLAR) {
                    this.openLuckyPillarMapPage(player);
                } else if (entry != null && entry.getType().isBedWars()) {
                    this.openBedwarsMapPage(player);
                } else if (entry != null && entry.getType() == MatchType.MYGO) {
                    this.openMyGoMapPage(player);
                } else {
                    this.doForceStart(player);
                }
            }
            case 34 -> {
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
            case 13 -> MatchType.DEATHMATCH;
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
            this.openOtherGames(player);
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

    /** 其他游戏页点击：分类进入子页，独立玩法直接加入队列。 */
    private void onClickOtherGames(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openMainMenu(player);
            return;
        }
        if (slot == 9) {
            this.openBridgeCategory(player);
            return;
        }
        if (slot == 10) {
            this.openBedWarsCategory(player);
            return;
        }
        if (slot == 16) {
            this.openPracticeCategory(player);
            return;
        }
        if (slot == 14) {
            // 打开村庄保卫战职业选择界面
            VillageDefenseKitGui.open(player);
            return;
        }
        MatchType type = switch (slot) {
            case 11 -> MatchType.HEARTBEAT;
            case 12 -> MatchType.HOT_POTATO;
            case 13 -> MatchType.VILLAGE_DEFENSE;
            case 15 -> MatchType.COLORBLIND_PARTY;
            default -> null;
        };
        if (type == null) {
            return;
        }
        Kit kit = switch (type) {
            case HEARTBEAT -> KitManager.heartbeatKit();
            case VILLAGE_DEFENSE -> KitManager.villageDefenseKit();
            case COLORBLIND_PARTY -> KitManager.colorblindPartyKit();
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
            case 15 -> SkyWarsTheme.VILLAGE;
            default -> null; // 14=随机，不指定
        };
        if (PvPMod.MATCH != null) {
            PvPMod.MATCH.setNextSkywarsTheme(theme);
        }
        this.doForceStart(player);
    }

    /** 幸运之柱地图选择页点击：选地图 → 立即开赛。 */
    private void onClickLuckyPillarMap(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openMainMenu(player);
            return;
        }
        com.example.pvp.arena.luckypillar.LuckyPillarLayout.PlatformStyle[] styles =
                com.example.pvp.arena.luckypillar.LuckyPillarLayout.PlatformStyle.values();
        int index = slot - 9;
        if (index < 0 || index >= styles.length) {
            return;
        }
        if (PvPMod.MATCH != null) {
            PvPMod.MATCH.setNextLuckyPillarStyle(styles[index]);
        }
        this.doForceStart(player);
    }

    /** 床战地图选择页点击：选地图 → 立即开赛。 */
    private void onClickBedwarsMap(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openMainMenu(player);
            return;
        }
        java.util.List<java.nio.file.Path> maps = com.example.pvp.arena.bedwars.BedWarsMaps.listMaps();
        int index = slot - 9;
        if (index < 0 || index >= maps.size()) {
            return;
        }
        if (PvPMod.MATCH != null) {
            PvPMod.MATCH.setNextBedwarsMap(maps.get(index).getFileName().toString());
        }
        this.doForceStart(player);
    }

    /** MyGO 地图选择页点击：指定项只对当前强制开赛消费一次。 */
    private void onClickMyGoMap(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openMainMenu(player);
            return;
        }
        String mapKey = switch (slot) {
            case 9 -> "dust2";
            case 11 -> "mirage";
            case 13 -> "inferno";
            case 15 -> null;
            default -> "";
        };
        if (mapKey.isEmpty()) return;
        if (PvPMod.MATCH != null) PvPMod.MATCH.setNextMyGoMap(mapKey);
        this.doForceStart(player);
    }

    /** MyGO 队伍选择：先保存偏好，再发起资源包握手/入队。 */
    private void onClickMyGoTeam(ServerPlayerEntity player, GuiContext ctx, int slot) {
        if (slot == 26) {
            this.openMainMenu(player);
            return;
        }
        MyGoTeamPreference preference = switch (slot) {
            case 10 -> MyGoTeamPreference.T;
            case 13 -> MyGoTeamPreference.CT;
            case 16 -> MyGoTeamPreference.AUTO;
            default -> null;
        };
        if (preference == null) return;
        PvPMod.QUEUE.setMyGoTeam(player.getUuid(), preference);
        this.joinQueue(player, MatchType.MYGO, KitManager.portedGameKit());
    }

    /** OP 立即开赛：用当前队列人数开赛并返回主菜单。 */
    private void doForceStart(ServerPlayerEntity player) {
        if (PvPMod.QUEUE.forceStart(PvPMod.MATCH, player)) {
            player.sendMessage(Messages.info("已强制立即开赛！"), false);
        }
        this.openMainMenu(player);
    }

    private void joinQueue(ServerPlayerEntity player, MatchType type, Kit kit) {
        this.joinQueue(player, type, kit, SkyWarsMapVariant.CLASSIC);
    }

    private void joinQueue(ServerPlayerEntity player, MatchType type, Kit kit, SkyWarsMapVariant skyWarsVariant) {
        if (isBusy(player)) {
            player.sendMessage(Messages.error("你正在比赛或队列中"), false);
            player.closeHandledScreen();
            return;
        }
        if (PvPMod.QUEUE.join(player, type, kit, skyWarsVariant)) {
            if (type == MatchType.FFA) {
                player.sendMessage(Messages.info("已加入自由乱斗：凑齐 " + PvPConfig.INSTANCE.ffaMinPlayers
                        + " 人后倒计时 " + PvPConfig.INSTANCE.ffaCountdownSeconds + " 秒开赛"), false);
            } else if (type == MatchType.SKYWARS) {
                String mapLabel = skyWarsVariant == SkyWarsMapVariant.BETA ? "空岛 Beta 地图" : "空岛战争";
                player.sendMessage(Messages.info("已加入" + mapLabel + "：凑齐 "
                        + PvPConfig.INSTANCE.skywarsStartPlayers + " 人开赛，开箱获得装备"), false);
            } else if (type == MatchType.SKYWARS_GUNS) {
                player.sendMessage(Messages.info("已加入枪械空岛：凑齐 "
                        + PvPConfig.INSTANCE.skywarsStartPlayers + " 人开赛，出生只有战术刀，枪械从箱子获取"), false);
            } else if (type == MatchType.LUCKY_PILLAR) {
                player.sendMessage(Messages.info("已加入幸运之柱：凑齐 " + PvPConfig.INSTANCE.luckyPillarStartPlayers
                        + " 人开赛，空手开局，随机物品与事件"), false);
            } else if (type == MatchType.TNT_RUN) {
                player.sendMessage(Messages.info("已加入 TNT 跑酷：凑齐 " + PvPConfig.INSTANCE.tntRunStartPlayers
                        + " 人开赛，踩过的方块会掉落"), false);
            } else if (type == MatchType.HEARTBEAT) {
                player.sendMessage(Messages.info("已加入心跳水立方：凑齐 " + PvPConfig.INSTANCE.heartbeatStartPlayers
                        + " 人开赛，跳中央洞口穿地板洞落水过关，完成关卡数最多者胜"), false);
            } else if (type == MatchType.HOT_POTATO) {
                player.sendMessage(Messages.info("已加入烫手山芋：凑齐 " + PvPConfig.INSTANCE.hotPotatoStartPlayers
                        + " 人开赛，左键传递山芋，时间到爆炸"), false);
            } else if (type.isBoatRace()) {
                player.sendMessage(Messages.info("已加入亦可赛艇：凑齐 "
                        + PvPConfig.INSTANCE.boatRaceStartPlayers + " 人开赛；原版船 + 随机冰面赛道，跑 "
                        + PvPConfig.INSTANCE.boatRaceLaps + " 圈"), false);
            } else if (type == MatchType.MYGO) {
                player.sendMessage(Messages.info("已加入 MyGO：2~10 人均可开局，奇数人数两队相差一人；"
                        + "当前队伍偏好：" + PvPMod.QUEUE.myGoTeam(player.getUuid()).displayName()
                        + "；可用 /pvp mygo team <t|ct|auto> 修改，左键点射、瞄具枪右键切倍率/关镜、F 换弹"), false);
            } else if (type.isPortedGame()) {
                player.sendMessage(Messages.info("已加入" + type.getDisplayName() + "：凑齐 "
                        + MatchType.PORTED_START_PLAYERS + " 人立即开赛；至少 " + MatchType.PORTED_MIN_PLAYERS
                        + " 人等待 " + MatchType.PORTED_FILL_SECONDS + " 秒也可开始"), false);
                if (type == MatchType.MINECRAFT_PARTY) {
                    player.sendMessage(Messages.info("分阶段移植：已实现 " + PartyMinigame.values().length
                            + " 个可选小游戏；每局五轮不重复，按累计积分排名"), false);
                }
            } else if (type.isBedWars()) {
                player.sendMessage(Messages.info("已加入起床战争（" + (type == MatchType.BED_WARS_DOUBLES ? "双人" : "Solo")
                        + "）：凑 2 人即开始倒计时，摧毁敌方床获胜"), false);
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
        return (PvPMod.PRACTICE != null && PvPMod.PRACTICE.isInPractice(player))
                || PvPMod.MATCH.isInMatch(player.getUuid())
                || PvPMod.QUEUE.contains(player.getUuid());
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

    /**
     * 把 UUID 解析成显示用的名字。
     *
     * <p><b>离线玩家也要把用户名显示出来</b>：原来离线一律显示 {@code §7(离线)}，
     * 等于把名字藏了 —— 战绩榜上看到一排"(离线)"根本不知道是谁。
     * 离线时改从服务器用户缓存（{@code UserCache}，服务端会缓存见过的玩家档案）里取历史名字，
     * 只有连缓存里都查不到（例如换了 UUID / 缓存被清）才退回占位文字。
     */
    private String resolveName(UUID uuid) {
        ServerPlayerEntity online = PvPMod.SERVER.getPlayerManager().getPlayer(uuid);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return PvPMod.SERVER.getUserCache().getByUuid(uuid)
                .map(GameProfile::getName)
                .filter(name -> name != null && !name.isBlank())
                .orElse("§7(离线)");
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
        MAIN, PVP_CATEGORY, BRIDGE_CATEGORY, OTHER_GAMES, BED_WARS_CATEGORY,
        KIT, DUEL_TARGET, STATS, KIT_INFO, THEME, LUCKY_PILLAR_MAP, BEDWARS_MAP, MYGO_MAP, MYGO_TEAM,
        PRACTICE_CATEGORY, PRACTICE_DIFFICULTY, PRACTICE_KIT
    }

    private static final class GuiContext {
        Page page = Page.MAIN;
        Page kitReturnPage = Page.MAIN; // 进入套件选择前的页面（返回用）
        MatchType pendingMode;
        UUID duelTargetUuid;
        int duelTargetPage;
        int statsPage; // 战绩排行榜当前页码
        BotDifficulty practiceDifficulty = BotDifficulty.NORMAL;
        int practiceKitPage;
        boolean navigating;
    }
}
