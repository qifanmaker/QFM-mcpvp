package com.example.pvp.command;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.bridge.BridgeLayout;
import com.example.pvp.arena.bridge.BridgeMapGenerator;
import com.example.pvp.arena.heartbeat.HeartbeatLayout;
import com.example.pvp.arena.heartbeat.HeartbeatMapGenerator;
import com.example.pvp.arena.hotpotato.HotPotatoLayout;
import com.example.pvp.arena.hotpotato.HotPotatoMapGenerator;
import com.example.pvp.arena.luckypillar.LuckyPillarLayout;
import com.example.pvp.arena.luckypillar.LuckyPillarMapGenerator;
import com.example.pvp.arena.skywars.SkyWarsLayout;
import com.example.pvp.arena.tntrun.TntRunLayout;
import com.example.pvp.arena.tntrun.TntRunMapGenerator;
import com.example.pvp.arena.skywars.SkyWarsMapGenerator;
import com.example.pvp.arena.skywars.SkyWarsTheme;
import com.example.pvp.config.KitConfig;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.config.PlayerStats;
import com.example.pvp.config.StatsStore;
import com.example.pvp.duel.DuelChallenge;
import com.example.pvp.gui.PvpGuiManager;
import com.example.pvp.kit.Kit;
import com.example.pvp.kit.KitManager;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchState;
import com.example.pvp.match.MatchType;
import com.example.pvp.queue.QueueEntry;
import com.example.pvp.text.Messages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /pvp 与 /duel 命令注册。
 */
public final class PvPCommands {
    private static final SuggestionProvider<ServerCommandSource> MODE_SUGGESTIONS =
            (ctx, builder) -> CommandSource.suggestMatching(new String[]{
                    "1v1", "2v2", "ffa", "sumo", "1.8", "skywars",
                    "bridge1v1", "bridge1v1v1v1", "bridge2v2", "bridge", "luckypillar", "tntrun",
                    "heartbeat", "hotpotato"}, builder);

    private static final SuggestionProvider<ServerCommandSource> KIT_SUGGESTIONS =
            (ctx, builder) -> CommandSource.suggestMatching(KitManager.getKitIds(), builder);

    private static final SuggestionProvider<ServerCommandSource> THEME_SUGGESTIONS =
            (ctx, builder) -> CommandSource.suggestMatching(
                    new String[]{"主世界", "地狱", "冰原", "末地", "overworld", "nether", "ice", "end"}, builder);

    private PvPCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("pvp")
                        .executes(PvPCommands::openMenu)
                        .then(CommandManager.literal("menu")
                                .executes(PvPCommands::openMenu))
                        .then(CommandManager.literal("help")
                                .executes(ctx -> showHelp(ctx.getSource())))
                        .then(CommandManager.literal("join")
                                .then(CommandManager.argument("mode", StringArgumentType.word())
                                        .suggests(MODE_SUGGESTIONS)
                                        // 空岛战争无需套件，可直接加入
                                        .executes(ctx -> join(ctx,
                                                StringArgumentType.getString(ctx, "mode"), null))
                                        .then(CommandManager.argument("kit", StringArgumentType.word())
                                                .suggests(KIT_SUGGESTIONS)
                                                .executes(ctx -> join(ctx,
                                                        StringArgumentType.getString(ctx, "mode"),
                                                        StringArgumentType.getString(ctx, "kit"))))))
                        .then(CommandManager.literal("leave")
                                .executes(ctx -> leave(ctx)))
                        .then(CommandManager.literal("tpout")
                                .executes(ctx -> tpOut(ctx)))
                        .then(CommandManager.literal("tpin")
                                .executes(ctx -> tpIn(ctx)))
                        .then(CommandManager.literal("start")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(ctx -> forceStart(ctx, null))
                                .then(CommandManager.argument("theme", StringArgumentType.word())
                                        .executes(ctx -> forceStart(ctx, StringArgumentType.getString(ctx, "theme")))))
                        .then(CommandManager.literal("queue")
                                .executes(ctx -> queueStatus(ctx)))
                        .then(CommandManager.literal("list")
                                .executes(ctx -> listMatches(ctx)))
                        .then(CommandManager.literal("stats")
                                .executes(ctx -> showStats(ctx.getSource(), ctx.getSource().getPlayerOrThrow()))
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(ctx -> showStats(ctx.getSource(), EntityArgumentType.getPlayer(ctx, "player")))))
                        .then(CommandManager.literal("top")
                                .executes(ctx -> showTop(ctx)))
                        .then(CommandManager.literal("kit")
                                .then(CommandManager.literal("list")
                                        .executes(ctx -> listKits(ctx.getSource()))))
                        .then(CommandManager.literal("reload")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(ctx -> reload(ctx.getSource())))
                        .then(CommandManager.literal("debug")
                                .requires(source -> source.hasPermissionLevel(2))
                                .then(CommandManager.literal("skywars")
                                        .executes(ctx -> debugSkywars(ctx, 1, null))
                                        .then(CommandManager.argument("rounds", StringArgumentType.word())
                                                .executes(ctx -> debugSkywars(ctx, parseIntSafe(ctx, "rounds", 1), null)))
                                        .then(CommandManager.literal("theme")
                                                .then(CommandManager.argument("theme", StringArgumentType.word())
                                                        .suggests(THEME_SUGGESTIONS)
                                                        .executes(ctx -> debugSkywars(ctx, 1,
                                                                StringArgumentType.getString(ctx, "theme")))))
                                        .then(CommandManager.literal("all")
                                                .executes(ctx -> debugSkywarsAllThemes(ctx))))
                                .then(CommandManager.literal("bridge")
                                        .executes(ctx -> debugBridge(ctx, 2))
                                        .then(CommandManager.argument("team", StringArgumentType.word())
                                                .executes(ctx -> debugBridge(ctx, parseIntSafe(ctx, "team", 2)))))
                                .then(CommandManager.literal("luckypillar")
                                        .executes(ctx -> debugLuckyPillar(ctx, 4))
                                        .then(CommandManager.argument("count", StringArgumentType.word())
                                                .executes(ctx -> debugLuckyPillar(ctx, parseIntSafe(ctx, "count", 4)))))
                                .then(CommandManager.literal("tntrun")
                                        .executes(ctx -> debugTntRun(ctx)))
                                .then(CommandManager.literal("heartbeat")
                                        .executes(ctx -> debugHeartbeat(ctx)))
                                .then(CommandManager.literal("hotpotato")
                                        .executes(ctx -> debugHotPotato(ctx))))
        );

        dispatcher.register(CommandManager.literal("hub").executes(ctx -> tpOut(ctx)));   // 返回主城
        dispatcher.register(CommandManager.literal("watch").executes(ctx -> tpIn(ctx)));  // 进入竞技场

        dispatcher.register(
                CommandManager.literal("duel")
                        .then(CommandManager.argument("target", EntityArgumentType.player())
                                .executes(ctx -> duel(ctx, EntityArgumentType.getPlayer(ctx, "target"), null))
                                .then(CommandManager.argument("kit", StringArgumentType.word())
                                        .suggests(KIT_SUGGESTIONS)
                                        .executes(ctx -> duel(ctx, EntityArgumentType.getPlayer(ctx, "target"),
                                                StringArgumentType.getString(ctx, "kit")))))
                        .then(CommandManager.literal("accept")
                                .executes(ctx -> acceptDuel(ctx, null))
                                .then(CommandManager.argument("challenger", EntityArgumentType.player())
                                        .executes(ctx -> acceptDuel(ctx, EntityArgumentType.getPlayer(ctx, "challenger")))))
                        .then(CommandManager.literal("deny")
                                .executes(ctx -> denyDuel(ctx, null))
                                .then(CommandManager.argument("challenger", EntityArgumentType.player())
                                        .executes(ctx -> denyDuel(ctx, EntityArgumentType.getPlayer(ctx, "challenger")))))
        );
    }

    // ---------- /pvp ----------

    private static int openMenu(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        PvpGuiManager.get().openMainMenu(ctx.getSource().getPlayerOrThrow());
        return 1;
    }

    private static int showHelp(ServerCommandSource source) {
        source.sendFeedback(() -> Messages.gold(
                "§6§lPvP 匹配 §r命令：\n"
                        + "§e/pvp join <1v1|2v2|ffa|sumo|1.8> <套件>§r 加入匹配队列\n"
                        + "§e/pvp join skywars§r 加入空岛战争（无需套件）\n"
                        + "§e/pvp join bridge1v1|bridge1v1v1v1|bridge2v2|bridge§r 加入战桥（无需套件）\n"
                        + "§e/pvp join luckypillar§r 加入幸运之柱（无需套件，空手开局）\n"
                        + "§e/pvp join tntrun§r 加入 TNT 跑酷（无需套件，踩过的方块掉落）\n"
                        + "§e/pvp join heartbeat§r 加入心跳水立方（无需套件，跳中央洞口穿地板洞落水过关）\n"
                        + "§e/pvp join hotpotato§r 加入烫手山芋（无需套件，左键传递山芋）\n"
                        + "§e/pvp leave§r 离开队列\n"
                        + "§e/pvp tpout§r 从竞技场返回主城（活跃玩家视为弃权退出本场）\n"
                        + "§e/pvp tpin§r 从主城进入竞技场（有对局回对局，无对局访客观看）\n"
                        + "§e/pvp start [主题]§r OP 专用：立即用当前队列人数开赛（排空岛可指定主题）\n"
                        + "§e/pvp queue§r 查看排队状态\n"
                        + "§e/pvp list§r 查看进行中的比赛\n"
                        + "§e/pvp stats [玩家]§r 查看战绩\n"
                        + "§e/pvp top§r 查看排行榜\n"
                        + "§e/pvp kit list§r 查看可用套件\n"
                        + "§e/duel <玩家> [套件]§r 发起 1v1 决斗\n"
                        + "§e/duel accept|deny [挑战者]§r 接受/拒绝决斗"
        ), false);
        return 1;
    }

    private static int join(CommandContext<ServerCommandSource> ctx, String modeId, String kitId) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();

        MatchType type = MatchType.byId(modeId);
        if (type == null) {
            player.sendMessage(Messages.error("未知模式: " + modeId
                    + "（可用: 1v1, 2v2, ffa, sumo, 1.8, skywars, bridge1v1, bridge1v1v1v1, bridge2v2, bridge, luckypillar, tntrun, heartbeat, hotpotato）"), false);
            return 0;
        }
        Kit kit;
        if (type == MatchType.SKYWARS) {
            kit = KitManager.skywarsKit(); // 空岛战争无套件
        } else if (type.isBridge()) {
            kit = KitManager.bridgeKit(); // 战桥装备固定（按队伍色发放），无套件选择
        } else if (type == MatchType.LUCKY_PILLAR) {
            kit = KitManager.luckyPillarKit(); // 幸运之柱空手开局，无套件
        } else if (type == MatchType.TNT_RUN) {
            kit = KitManager.tntRunKit(); // TNT 跑酷空手开局，无套件
        } else if (type == MatchType.HEARTBEAT) {
            kit = KitManager.heartbeatKit(); // 心跳水立方空手开局，无套件
        } else if (type == MatchType.HOT_POTATO) {
            kit = KitManager.hotPotatoKit(); // 烫手山芋空手开局，无套件
        } else {
            if (kitId == null) {
                player.sendMessage(Messages.error("该模式需要指定套件（用 /pvp kit list 查看）"), false);
                return 0;
            }
            kit = KitManager.get(kitId);
            if (kit == null) {
                player.sendMessage(Messages.error("未知套件: " + kitId + "（用 /pvp kit list 查看）"), false);
                return 0;
            }
        }

        if (isBusy(player)) {
            player.sendMessage(Messages.error("你正在比赛或队列中，请先结束或 /pvp leave"), false);
            return 0;
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
                int required = type.requiredPlayers();
                player.sendMessage(Messages.info("已加入匹配队列：模式 " + type.getDisplayName()
                        + "，套件 " + kit.getDisplayName() + "（当前 " + count + "/" + required + "）"), false);
            }
        }
        return 1;
    }

    private static int leave(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        if (PvPMod.QUEUE.leave(player)) {
            player.sendMessage(Messages.info("已离开匹配队列"), false);
        } else {
            player.sendMessage(Messages.warn("你不在匹配队列中"), false);
        }
        return 1;
    }

    /** 竞技场 → 主城：幽灵走旁观离开流程；活跃玩家视为弃权退出本场；访客移除访客身份。 */
    private static int tpOut(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        if (PvPMod.MATCH == null || PvPMod.MATCH.getArenaManager().getWorld() == null) {
            player.sendMessage(Messages.error("服务器尚未就绪"), false);
            return 0;
        }
        if (player.getWorld().getRegistryKey() != ArenaWorldManager.ARENA_WORLD_KEY) {
            player.sendMessage(Messages.error("你不在竞技场中（用 /pvp tpin 进入）"), false);
            return 0;
        }
        Match match = PvPMod.MATCH.getMatchFor(player);
        if (match != null) {
            match.leaveMatch(player);
        } else {
            PvPMod.MATCH.getArenaManager().removeVisitor(player.getUuid());
            // 访客可能被开启了飞行，返回主城时还原
            player.getAbilities().allowFlying = false;
            player.getAbilities().flying = false;
            player.sendAbilitiesUpdate();
        }
        PvPMod.MATCH.teleportToOverworldSpawn(player);
        player.sendMessage(Messages.info("已回到主城"), false);
        return 1;
    }

    /** 主城 → 竞技场：有对局则回到对局（幽灵=观战台，活跃=出生点）；无对局则作为访客传送到竞技场上空。 */
    private static int tpIn(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        if (PvPMod.MATCH == null) {
            player.sendMessage(Messages.error("服务器尚未就绪"), false);
            return 0;
        }
        if (player.getWorld().getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY) {
            player.sendMessage(Messages.error("你已经在竞技场中（用 /pvp tpout 返回主城）"), false);
            return 0;
        }
        ArenaWorld arena = PvPMod.MATCH.getArenaManager().getWorld();
        if (arena == null) {
            player.sendMessage(Messages.error("竞技场世界不可用"), false);
            return 0;
        }
        Match match = PvPMod.MATCH.getMatchFor(player);
        if (match != null) {
            if (match.getState() != MatchState.ACTIVE) {
                player.sendMessage(Messages.error("你的对局尚未开始，暂时无法传送"), false);
                return 0;
            }
            if (match.isEliminated(player.getUuid())) {
                match.makeGhost(player);
            } else {
                match.teleportToSpawn(player);
            }
            player.sendMessage(Messages.info("已回到竞技场对局"), false);
            return 1;
        }
        // 无对局：作为访客传送到竞技场上空观看（开启飞行防掉落，10 分钟后自动回城）
        player.teleport(arena, 128, ArenaTemplate.PLATFORM_Y + 30, 128, 0, 90);
        player.getAbilities().allowFlying = true;
        player.getAbilities().flying = true;
        player.sendAbilitiesUpdate();
        PvPMod.MATCH.getArenaManager().addVisitor(player, 600);
        player.sendMessage(Messages.info("已传送到竞技场（访客视角），用 /hub 返回主城"), false);
        return 1;
    }

    /** OP：立即用当前队列人数开赛（跳过倒计时/等待填人）；可选指定空岛主题。 */
    private static int forceStart(CommandContext<ServerCommandSource> ctx, String themeName) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        if (PvPMod.MATCH == null || PvPMod.QUEUE == null) {
            player.sendMessage(Messages.error("服务器尚未就绪"), false);
            return 0;
        }
        if (themeName != null && !themeName.isBlank()) {
            SkyWarsTheme theme = SkyWarsTheme.byName(themeName);
            if (theme == null) {
                player.sendMessage(Messages.error("未知主题: " + themeName + "（可用: 主世界, 地狱, 冰原, 末地）"), false);
                return 0;
            }
            QueueEntry entry = PvPMod.QUEUE.getEntry(player);
            if (entry == null || entry.getType() != MatchType.SKYWARS) {
                player.sendMessage(Messages.error("只有排空岛战争才能指定主题"), false);
                return 0;
            }
            PvPMod.MATCH.setNextSkywarsTheme(theme);
            player.sendMessage(Messages.info("已设置强制主题：§e" + theme.getDisplayName()), false);
        }
        if (PvPMod.QUEUE.forceStart(PvPMod.MATCH, player)) {
            player.sendMessage(Messages.info("已强制立即开赛！"), false);
        }
        return 1;
    }

    private static int queueStatus(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        QueueEntry entry = PvPMod.QUEUE.getEntry(player);
        if (entry == null) {
            player.sendMessage(Messages.warn("你不在队列中，用 /pvp join <模式> <套件> 加入"), false);
            return 0;
        }
        long waited = (PvPMod.SERVER.getTicks() - entry.getQueuedAtTick()) / 20;
        player.sendMessage(Messages.info("排队中：模式 " + entry.getType().getDisplayName()
                + "，套件 " + entry.getKit().getDisplayName() + "，已等待 " + waited + " 秒"), false);
        return 1;
    }

    private static int listMatches(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        List<Match> matches = PvPMod.MATCH.getMatches();
        if (matches.isEmpty()) {
            player.sendMessage(Messages.info("当前没有进行中的比赛"), false);
        } else {
            player.sendMessage(Messages.gold("§6当前比赛 (" + matches.size() + " 场)§r"), false);
            for (Match match : matches) {
                player.sendMessage(Messages.info("模式 " + match.getType().getDisplayName()
                        + " | 套件 " + match.getKit().getDisplayName()
                        + " | 状态 " + matchStateText(match.getState())), false);
            }
        }

        List<QueueEntry> entries = PvPMod.QUEUE.getEntries();
        if (!entries.isEmpty()) {
            player.sendMessage(Messages.gold("§6队列: " + entries.size() + " 人§r"), false);
            for (QueueEntry entry : entries) {
                player.sendMessage(Messages.info(entry.getPlayer().getGameProfile().getName()
                        + " 排队中：模式 " + entry.getType().getDisplayName()
                        + "，套件 " + entry.getKit().getDisplayName()), false);
            }
        }
        return 1;
    }

    private static String matchStateText(MatchState state) {
        return switch (state) {
            case FORMING -> "组建中";
            case COUNTDOWN -> "倒计时";
            case ACTIVE -> "进行中";
            case CELEBRATING -> "庆祝中";
            case ENDED -> "已结束";
        };
    }

    private static int showStats(ServerCommandSource source, ServerPlayerEntity target) {
        PlayerStats stats = StatsStore.INSTANCE.getStats(target.getUuid());
        source.sendFeedback(() -> Messages.info("§e" + target.getGameProfile().getName() + "§r 战绩：胜 " + stats.getWins()
                + " 负 " + stats.getLosses() + "，共 " + stats.getMatches() + " 场"), false);
        return 1;
    }

    private static int showTop(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        List<Map.Entry<String, PlayerStats>> sorted = StatsStore.INSTANCE.getStatsMap().entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, PlayerStats> e) -> e.getValue().wins).reversed())
                .limit(10)
                .toList();

        player.sendMessage(Messages.gold("§6PvP 胜场排行榜 (前 10)§r"), false);
        if (sorted.isEmpty()) {
            player.sendMessage(Messages.warn("暂无数据"), false);
            return 0;
        }
        int rank = 1;
        for (Map.Entry<String, PlayerStats> entry : sorted) {
            String name = resolveName(UUID.fromString(entry.getKey()));
            player.sendMessage(Messages.info("#" + rank + " §e" + name + "§r 胜 " + entry.getValue().wins
                    + " | 总场次 " + entry.getValue().matches), false);
            rank++;
        }
        return 1;
    }

    private static String resolveName(UUID uuid) {
        ServerPlayerEntity online = PvPMod.SERVER.getPlayerManager().getPlayer(uuid);
        return online != null ? online.getGameProfile().getName() : "§7(离线)";
    }

    private static int listKits(ServerCommandSource source) {
        source.sendFeedback(() -> Messages.gold("§6可用套件:§r"), false);
        for (Kit kit : KitManager.getKits()) {
            source.sendFeedback(() -> Messages.info("§e" + kit.getId() + "§r - " + kit.getDisplayName()), false);
        }
        return 1;
    }

    private static int reload(ServerCommandSource source) {
        PvPConfig.load();
        KitConfig.load();
        KitManager.reload();
        StatsStore.INSTANCE.load();
        source.sendFeedback(() -> Messages.info("配置已重载"), false);
        return 1;
    }

    /** 调试：在竞技场远区生成随机（或指定主题）空岛地图并传送过去查看（不影响正式对局）。 */
    private static int debugSkywars(CommandContext<ServerCommandSource> ctx, int rounds, String themeName)
            throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        SkyWarsTheme forcedTheme = null;
        if (themeName != null) {
            forcedTheme = SkyWarsTheme.byName(themeName);
            if (forcedTheme == null) {
                player.sendMessage(Messages.error("未知主题: " + themeName + "（可用: 主世界, 地狱, 冰原, 末地）"), false);
                return 0;
            }
        }
        ArenaWorldManager arenaManager = PvPMod.MATCH == null ? null : PvPMod.MATCH.getArenaManager();
        ArenaWorld arena = arenaManager == null ? null : arenaManager.getWorld();
        if (arena == null) {
            player.sendMessage(Messages.error("竞技场世界不可用"), false);
            return 0;
        }
        rounds = Math.max(1, Math.min(8, rounds));
        int baseRegion = 900; // 远离正式对局分配的区域，避免冲突
        int lastRegion = baseRegion;
        for (int i = 0; i < rounds; i++) {
            int region = baseRegion + i;
            int seed = 9000 + i;
            if (forcedTheme != null) {
                seed = SkyWarsTheme.alignSeed(seed, forcedTheme); // 指定主题，地图布局仍随 seed 变化
            }
            SkyWarsLayout layout = SkyWarsMapGenerator.generate(arena, region, seed, 4, null);
            lastRegion = region;
            player.sendMessage(Messages.info("测试空岛 #" + (i + 1) + "（主题 "
                    + SkyWarsTheme.pick(seed).getDisplayName() + "）已生成："
                    + layout.spawnIslands().size() + " 个出生岛(每岛 "
                    + layout.spawnIslands().get(0).chests().size() + " 箱)，中间主岛 "
                    + layout.middle().chests().size() + " 箱，最大半径 "
                    + layout.maxRadius()), false);
        }
        BlockPos c = SkyWarsMapGenerator.center(lastRegion);
        player.teleport(arena, c.getX() + 0.5, c.getY() + 12, c.getZ() + 0.5, 0, 90);
        arenaManager.addVisitor(player, 180); // 3 分钟内不被兜底传回主城
        player.sendMessage(Messages.gold("已传送到测试空岛上空（约 3 分钟后自动回城），可下落查看岛屿与箱子战利品"), false);
        return 1;
    }

    /** 调试：4 种主题各生成一张空岛地图（区域 900~903），方便对比查看。 */
    private static int debugSkywarsAllThemes(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ArenaWorldManager arenaManager = PvPMod.MATCH == null ? null : PvPMod.MATCH.getArenaManager();
        ArenaWorld arena = arenaManager == null ? null : arenaManager.getWorld();
        if (arena == null) {
            player.sendMessage(Messages.error("竞技场世界不可用"), false);
            return 0;
        }
        int baseRegion = 900;
        SkyWarsTheme[] themes = SkyWarsTheme.values();
        for (int i = 0; i < themes.length; i++) {
            int seed = SkyWarsTheme.alignSeed(9000 + i, themes[i]);
            SkyWarsMapGenerator.generate(arena, baseRegion + i, seed, 4, null);
            player.sendMessage(Messages.info("测试空岛 #" + (i + 1) + "（主题 " + themes[i].getDisplayName()
                    + "）已生成，区域 " + (baseRegion + i)), false);
        }
        BlockPos c = SkyWarsMapGenerator.center(baseRegion + themes.length - 1);
        player.teleport(arena, c.getX() + 0.5, c.getY() + 12, c.getZ() + 0.5, 0, 90);
        arenaManager.addVisitor(player, 180);
        player.sendMessage(Messages.gold("已传送到最后一张（末地）测试空岛上空；区域 900~903 依次为 主世界/地狱/冰原/末地，可飞行查看"), false);
        return 1;
    }

    private static int parseIntSafe(CommandContext<ServerCommandSource> ctx, String name, int fallback) {
        try {
            return Integer.parseInt(StringArgumentType.getString(ctx, name));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 调试：在竞技场远区生成一张战桥地图（2 队或 4 方）并传送查看（不影响正式对局）。 */
    private static int debugBridge(CommandContext<ServerCommandSource> ctx, int team) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ArenaWorldManager arenaManager = PvPMod.MATCH == null ? null : PvPMod.MATCH.getArenaManager();
        ArenaWorld arena = arenaManager == null ? null : arenaManager.getWorld();
        if (arena == null) {
            player.sendMessage(Messages.error("竞技场世界不可用"), false);
            return 0;
        }
        boolean fourTeam = team != 2;
        int region = 950; // 远离正式对局分配的区域
        int size = PvPConfig.INSTANCE.bridgeSize;
        BlockPos origin = new BlockPos(region * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        BlockPos center = new BlockPos(origin.getX() + size / 2, ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + size / 2);
        BridgeLayout layout = BridgeLayout.compute(center, fourTeam ? 4 : 2, fourTeam);
        BridgeMapGenerator.generate(arena, layout);

        player.sendMessage(Messages.info("测试战桥地图已生成（" + (fourTeam ? "四方" : "双队") + "，"
                + layout.bases().size() + " 座基地，最大半径 " + layout.maxRadius() + "）"), false);
        player.teleport(arena, center.getX() + 0.5, center.getY() + 15, center.getZ() + 0.5, 0, 90);
        arenaManager.addVisitor(player, 180); // 3 分钟内不被兜底传回主城
        player.sendMessage(Messages.gold("已传送到战桥地图上空（约 3 分钟后自动回城），可下落查看基地/球门/桥"), false);
        return 1;
    }

    /** 调试：在竞技场远区生成一张幸运之柱地图（指定柱子数）并传送查看（不影响正式对局）。 */
    private static int debugLuckyPillar(CommandContext<ServerCommandSource> ctx, int count) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ArenaWorldManager arenaManager = PvPMod.MATCH == null ? null : PvPMod.MATCH.getArenaManager();
        ArenaWorld arena = arenaManager == null ? null : arenaManager.getWorld();
        if (arena == null) {
            player.sendMessage(Messages.error("竞技场世界不可用"), false);
            return 0;
        }
        int region = 970; // 远离正式对局分配的区域
        int size = PvPConfig.INSTANCE.luckyPillarSize;
        BlockPos origin = new BlockPos(region * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        BlockPos center = new BlockPos(origin.getX() + size / 2, ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + size / 2);
        count = Math.max(2, Math.min(8, count));
        LuckyPillarLayout layout = LuckyPillarLayout.compute(center, 9000, count);
        LuckyPillarMapGenerator.generate(arena, layout);

        player.sendMessage(Messages.info("测试幸运之柱地图已生成（" + count + " 根柱子，最大半径 "
                + layout.maxRadius() + "）"), false);
        player.teleport(arena, center.getX() + 0.5,
                center.getY() + PvPConfig.INSTANCE.luckyPillarHeight + 15, center.getZ() + 0.5, 0, 90);
        arenaManager.addVisitor(player, 180); // 3 分钟内不被兜底传回主城
        player.sendMessage(Messages.gold("已传送到幸运之柱地图上空（约 3 分钟后自动回城），可下落查看柱子"), false);
        return 1;
    }

    /** 调试：在竞技场远区生成一张 TNT 跑酷地图并传送查看（不影响正式对局）。 */
    private static int debugTntRun(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ArenaWorldManager arenaManager = PvPMod.MATCH == null ? null : PvPMod.MATCH.getArenaManager();
        ArenaWorld arena = arenaManager == null ? null : arenaManager.getWorld();
        if (arena == null) {
            player.sendMessage(Messages.error("竞技场世界不可用"), false);
            return 0;
        }
        int region = 985; // 远离正式对局分配的区域
        int size = PvPConfig.INSTANCE.tntRunSize;
        BlockPos origin = new BlockPos(region * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        BlockPos center = new BlockPos(origin.getX() + size / 2, ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + size / 2);
        TntRunLayout layout = TntRunLayout.compute(center, Math.max(3, size / 2),
                PvPConfig.INSTANCE.tntRunLayerCount, Math.max(2, PvPConfig.INSTANCE.tntRunLayerGap));
        TntRunMapGenerator.generate(arena, layout);

        player.sendMessage(Messages.info("测试 TNT 跑酷地图已生成（" + layout.layerYs.size() + " 层，边长 "
                + (layout.halfSize * 2 + 1) + "）"), false);
        player.teleport(arena, center.getX() + 0.5, layout.topY() + 8, center.getZ() + 0.5, 0, 90);
        arenaManager.addVisitor(player, 180);
        player.sendMessage(Messages.gold("已传送到 TNT 跑酷地图上空（约 3 分钟后自动回城），可下落查看各层平台"), false);
        return 1;
    }

    /** 调试：在竞技场远区生成一张心跳水立方地图并传送查看（不影响正式对局）。 */
    private static int debugHeartbeat(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ArenaWorldManager arenaManager = PvPMod.MATCH == null ? null : PvPMod.MATCH.getArenaManager();
        ArenaWorld arena = arenaManager == null ? null : arenaManager.getWorld();
        if (arena == null) {
            player.sendMessage(Messages.error("竞技场世界不可用"), false);
            return 0;
        }
        int region = 980; // 远离正式对局分配的区域
        int size = PvPConfig.INSTANCE.heartbeatSize;
        BlockPos origin = new BlockPos(region * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        BlockPos center = new BlockPos(origin.getX() + size / 2, ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + size / 2);
        HeartbeatLayout layout = HeartbeatLayout.compute(center, PvPConfig.INSTANCE, 9000);
        HeartbeatMapGenerator.generate(arena, layout);

        // 传送到最高一关（最后一关）塔顶上空俯视全部关卡
        BlockPos lastCenter = layout.center(layout.levelCount - 1);
        player.sendMessage(Messages.info("测试心跳水立方地图已生成（" + layout.levelCount + " 关，塔宽 "
                + (layout.halfSize * 2 + 1) + "，层距 " + layout.floorGap + "，起始层数 " + layout.baseFloors + "）"), false);
        player.teleport(arena, lastCenter.getX() + 0.5, layout.topY(layout.levelCount - 1) + 10,
                lastCenter.getZ() + 0.5, 180, 90);
        arenaManager.addVisitor(player, 180);
        player.sendMessage(Messages.gold("已传送到心跳水立方最后一关塔顶上空（约 3 分钟后自动回城），从中央洞口跳下查看每层红色地板上的洞与底部水池"), false);
        return 1;
    }

    /** 调试：在竞技场远区生成一张烫手山芋地图并传送查看（不影响正式对局）。 */
    private static int debugHotPotato(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ArenaWorldManager arenaManager = PvPMod.MATCH == null ? null : PvPMod.MATCH.getArenaManager();
        ArenaWorld arena = arenaManager == null ? null : arenaManager.getWorld();
        if (arena == null) {
            player.sendMessage(Messages.error("竞技场世界不可用"), false);
            return 0;
        }
        int region = 986; // 远离正式对局分配的区域
        int size = PvPConfig.INSTANCE.hotPotatoSize;
        BlockPos origin = new BlockPos(region * ArenaTemplate.REGION_SPACING, ArenaTemplate.PLATFORM_Y, 0);
        BlockPos center = new BlockPos(origin.getX() + size / 2, ArenaTemplate.PLATFORM_Y + 1, origin.getZ() + size / 2);
        HotPotatoLayout layout = HotPotatoLayout.compute(center, PvPConfig.INSTANCE, 9000);
        HotPotatoMapGenerator.generate(arena, layout);

        player.sendMessage(Messages.info("测试烫手山芋地图已生成（平台半宽 " + layout.halfSize + "，玻璃柱 "
                + layout.pillars().size() + " 根，矮墙方块 " + layout.walls().size() + " 块）"), false);
        player.teleport(arena, center.getX() + 0.5, center.getY() + 15, center.getZ() + 0.5, 0, 90);
        arenaManager.addVisitor(player, 180);
        player.sendMessage(Messages.gold("已传送到烫手山芋地图上空（约 3 分钟后自动回城），可下落查看平台与障碍物"), false);
        return 1;
    }

    // ---------- /duel ----------

    private static int duel(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity target, String kitId) throws CommandSyntaxException {
        ServerPlayerEntity challenger = ctx.getSource().getPlayerOrThrow();

        if (challenger.getUuid().equals(target.getUuid())) {
            challenger.sendMessage(Messages.error("不能向自己发起决斗"), false);
            return 0;
        }
        if (isBusy(challenger)) {
            challenger.sendMessage(Messages.error("你正在比赛或队列中，无法发起决斗"), false);
            return 0;
        }
        if (isBusy(target)) {
            challenger.sendMessage(Messages.error("目标玩家正在比赛或队列中"), false);
            return 0;
        }

        Kit kit = kitId == null ? KitManager.get("sword") : KitManager.get(kitId);
        if (kit == null) {
            challenger.sendMessage(Messages.error("未知套件: " + kitId + "（用 /pvp kit list 查看）"), false);
            return 0;
        }

        DuelChallenge challenge = PvPMod.DUEL.challenge(challenger, target, MatchType.DUEL_1V1, kit);
        challenger.sendMessage(Messages.info("已向 §e" + target.getGameProfile().getName() + "§r 发起决斗（套件 " + kit.getDisplayName() + "），等待接受..."), false);
        target.sendMessage(Messages.gold("§e" + challenger.getGameProfile().getName() + "§r 向你发起 1v1 决斗（套件 " + kit.getDisplayName() + "）！输入 §e/duel accept " + challenger.getGameProfile().getName() + "§r 接受"), false);
        return 1;
    }

    private static int acceptDuel(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity challenger) throws CommandSyntaxException {
        ServerPlayerEntity accepter = ctx.getSource().getPlayerOrThrow();

        if (isBusy(accepter)) {
            accepter.sendMessage(Messages.error("你正在比赛或队列中，无法接受决斗"), false);
            return 0;
        }

        if (challenger == null) {
            DuelChallenge pending = PvPMod.DUEL.findPendingFor(accepter.getUuid());
            if (pending == null) {
                accepter.sendMessage(Messages.warn("没有待接受的决斗挑战"), false);
                return 0;
            }
            challenger = pending.getChallenger();
        }

        if (PvPMod.DUEL.accept(accepter, challenger)) {
            accepter.sendMessage(Messages.info("已接受 §e" + challenger.getGameProfile().getName() + "§r 的决斗，即将开始！"), false);
        } else {
            accepter.sendMessage(Messages.error("无法接受决斗（挑战不存在或对方忙碌）"), false);
        }
        return 1;
    }

    private static int denyDuel(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity challenger) throws CommandSyntaxException {
        ServerPlayerEntity accepter = ctx.getSource().getPlayerOrThrow();

        if (challenger == null) {
            DuelChallenge pending = PvPMod.DUEL.findPendingFor(accepter.getUuid());
            if (pending == null) {
                accepter.sendMessage(Messages.warn("没有待拒绝的决斗挑战"), false);
                return 0;
            }
            challenger = pending.getChallenger();
        }

        PvPMod.DUEL.deny(challenger.getUuid(), accepter.getUuid());
        accepter.sendMessage(Messages.info("已拒绝 §e" + challenger.getGameProfile().getName() + "§r 的决斗"), false);
        if (PvPMod.SERVER.getPlayerManager().getPlayer(challenger.getUuid()) != null) {
            challenger.sendMessage(Messages.warn("§e" + accepter.getGameProfile().getName() + "§r 拒绝了你的决斗"), false);
        }
        return 1;
    }

    private static boolean isBusy(ServerPlayerEntity player) {
        return PvPMod.MATCH.isInMatch(player.getUuid()) || PvPMod.QUEUE.contains(player.getUuid());
    }
}
