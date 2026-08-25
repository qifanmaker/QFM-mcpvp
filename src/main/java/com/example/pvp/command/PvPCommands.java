package com.example.pvp.command;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.skywars.SkyWarsLayout;
import com.example.pvp.arena.skywars.SkyWarsMapGenerator;
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
            (ctx, builder) -> CommandSource.suggestMatching(new String[]{"1v1", "2v2", "ffa", "sumo", "1.8", "skywars"}, builder);

    private static final SuggestionProvider<ServerCommandSource> KIT_SUGGESTIONS =
            (ctx, builder) -> CommandSource.suggestMatching(KitManager.getKitIds(), builder);

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
                        .then(CommandManager.literal("start")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(ctx -> forceStart(ctx)))
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
                                        .executes(ctx -> debugSkywars(ctx, 1))
                                        .then(CommandManager.argument("rounds", StringArgumentType.word())
                                                .executes(ctx -> debugSkywars(ctx, parseIntSafe(ctx, "rounds", 1))))))
        );

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
                        + "§e/pvp leave§r 离开队列\n"
                        + "§e/pvp start§r OP 专用：立即用当前队列人数开赛\n"
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
            player.sendMessage(Messages.error("未知模式: " + modeId + "（可用: 1v1, 2v2, ffa, sumo, 1.8, skywars）"), false);
            return 0;
        }
        Kit kit;
        if (type == MatchType.SKYWARS) {
            kit = KitManager.skywarsKit(); // 空岛战争无套件
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

    /** OP：立即用当前队列人数开赛（跳过倒计时/等待填人）。 */
    private static int forceStart(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        if (PvPMod.MATCH == null || PvPMod.QUEUE == null) {
            player.sendMessage(Messages.error("服务器尚未就绪"), false);
            return 0;
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

    /** 调试：在竞技场远区生成随机空岛地图并传送过去查看（不影响正式对局）。 */
    private static int debugSkywars(CommandContext<ServerCommandSource> ctx, int rounds) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
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
            SkyWarsLayout layout = SkyWarsMapGenerator.generate(arena, region, 9000 + i, 4);
            lastRegion = region;
            player.sendMessage(Messages.info("测试空岛 #" + (i + 1) + " 已生成："
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

    private static int parseIntSafe(CommandContext<ServerCommandSource> ctx, String name, int fallback) {
        try {
            return Integer.parseInt(StringArgumentType.getString(ctx, name));
        } catch (NumberFormatException e) {
            return fallback;
        }
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
