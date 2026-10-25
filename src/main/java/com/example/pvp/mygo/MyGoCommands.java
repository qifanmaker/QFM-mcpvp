package com.example.pvp.mygo;

import com.example.pvp.PvPMod;
import com.example.pvp.match.Match;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 原版没有购买/换弹专用按键，提供与物品交互相同的命令入口。
 * 所有购买仍由本场会话检查存活、购买时间和资金；诊断信息仅限 OP。
 */
public final class MyGoCommands {
    private MyGoCommands() { }

    public static MyGoSession session(ServerPlayerEntity player) {
        Match match = PvPMod.MATCH == null ? null : PvPMod.MATCH.getMatchFor(player);
        return match != null && match.portedSession() instanceof MyGoSession session ? session : null;
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("pvp")
                .then(CommandManager.literal("mygo")
                        .executes(ctx -> shop(ctx.getSource().getPlayerOrThrow()))
                        .then(CommandManager.literal("shop")
                                .executes(ctx -> shop(ctx.getSource().getPlayerOrThrow())))
                        .then(CommandManager.literal("pack").executes(ctx -> {
                            MyGoResourcePack.request(ctx.getSource().getPlayerOrThrow());
                            return 1;
                        }))
                        .then(CommandManager.literal("map")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(() -> Text.literal("§e当前 MyGO 地图：§f"
                                            + MyGoMapBlueprint.selectedKey() + " §7可选：§f"
                                            + String.join(", ", MyGoMapBlueprint.availableMaps())), false);
                                    return 1;
                                })
                                .then(CommandManager.argument("name", StringArgumentType.word())
                                        .suggests((ctx, builder) -> CommandSource.suggestMatching(
                                                MyGoMapBlueprint.availableMaps(), builder))
                                        .executes(ctx -> {
                                            String key = StringArgumentType.getString(ctx, "name").toLowerCase(java.util.Locale.ROOT);
                                            try {
                                                MyGoMapBlueprint map = MyGoMapBlueprint.select(key);
                                                ctx.getSource().sendFeedback(() -> Text.literal("§a下一场 MyGO 地图已设为 §f" + map.name()), true);
                                                return 1;
                                            } catch (java.io.IOException error) {
                                                ctx.getSource().sendError(Text.literal("§c未知或损坏的 MyGO 地图：" + key));
                                                return 0;
                                            }
                                        })))
                        .then(CommandManager.literal("stats").executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                            player.sendMessage(Text.literal(MyGoStats.describe(player.getUuid())), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("reload").executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                            MyGoSession session = requireSession(player);
                            if (session == null) return 0;
                            session.reload(player);
                            return 1;
                        }))
                        .then(CommandManager.literal("buy")
                                .then(CommandManager.argument("item", StringArgumentType.word())
                                        .suggests((ctx, builder) -> CommandSource.suggestMatching(
                                                java.util.stream.Stream.concat(
                                                        java.util.stream.Stream.concat(java.util.Arrays.stream(MyGoWeapon.values())
                                                                .map(weapon -> weapon.name().toLowerCase(java.util.Locale.ROOT)),
                                                                java.util.Arrays.stream(MyGoUtilities.Type.values()).map(type -> type.name().toLowerCase(java.util.Locale.ROOT))),
                                                        java.util.stream.Stream.of("vest", "helmet", "defuser")).toList(), builder))
                                        .executes(ctx -> {
                                            ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                                            MyGoSession session = requireSession(player);
                                            return session != null && session.buy(player, StringArgumentType.getString(ctx, "item")) ? 1 : 0;
                                        })))
                        .then(CommandManager.literal("status")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(ctx -> {
                                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                                    MyGoSession session = session(player);
                                    player.sendMessage(Text.literal(session == null
                                            ? "MyGO 无进行中会话；resourcePackReady=" + MyGoResourcePack.isReady(player)
                                            : session.debugState(player)), false);
                                    return 1;
                                }))));
    }

    private static int shop(ServerPlayerEntity player) {
        MyGoSession session = requireSession(player);
        if (session == null) return 0;
        session.openShop(player);
        return 1;
    }

    private static MyGoSession requireSession(ServerPlayerEntity player) {
        MyGoSession session = session(player);
        if (session == null) player.sendMessage(Text.literal("§e请先用 /pvp join mygo 加入枪战；资源包加载后再次加入。"), false);
        return session;
    }
}
