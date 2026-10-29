package com.example.pvp.mygo.practice;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.MapBuildQueue;
import com.example.pvp.gui.PvpGuiManager;
import com.example.pvp.kit.InventorySnapshot;
import com.example.pvp.match.MatchType;
import com.example.pvp.mygo.MyGoMapBlueprint;
import com.example.pvp.mygo.MyGoPracticeRuntime;
import com.example.pvp.mygo.MyGoResourcePack;
import com.example.pvp.mygo.MyGoSession;
import com.example.pvp.queue.MyGoTeamPreference;
import com.example.pvp.text.Messages;
import com.google.gson.Gson;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/**
 * MyGO 练习需要固定的十席房间，而正式 Match 的真人名单不可在战斗中重排。
 * 宿主只负责席位、原状态归还与房间生命周期，枪械/经济/装拆包仍由 MyGoSession 结算。
 * 每个地图与难度按需建立一间共享房，真人加入/离开均替换席位而不增加生命或弹药。
 */
public final class MyGoPracticeManager {
    private static final int REGION_BASE = 200;
    private static final int SEATS = 10;
    private static final Gson GSON = new Gson();
    private static MyGoPracticeManager instance;
    private final MinecraftServer server;
    private final Map<Key, Room> rooms = new LinkedHashMap<>();
    private final Map<UUID, Room> humans = new HashMap<>();
    private final Map<UUID, Room> actors = new HashMap<>();
    private final Map<UUID, InventorySnapshot> snapshots = new HashMap<>();
    private boolean stopping;

    private record Key(MyGoBotDifficulty difficulty, String map) { }
    private static final class Seat {
        final int index;
        ServerPlayerEntity actor;
        MyGoBot controller;
        UUID pending;
        Seat(int index) { this.index = index; }
    }

    private MyGoPracticeManager(MinecraftServer server) { this.server = server; }
    public static MyGoPracticeManager init(MinecraftServer server) {
        if (instance == null || instance.server != server) instance = new MyGoPracticeManager(server);
        return instance;
    }
    public static MyGoPracticeManager get() {
        if (instance == null) throw new IllegalStateException("MyGO practice has not started");
        return instance;
    }
    public static MyGoPracticeManager getOrNull() { return instance; }
    public boolean contains(UUID player) { return humans.containsKey(player); }
    public boolean contains(ServerPlayerEntity player) { return player != null && contains(player.getUuid()); }
    public MyGoSession session(ServerPlayerEntity player) {
        Room room = player == null ? null : actors.get(player.getUuid());
        return room == null || !room.ready || room.closing ? null : room.session;
    }
    public boolean isActor(Entity entity) { return entity != null && actors.containsKey(entity.getUuid()); }

    public boolean join(ServerPlayerEntity player, MyGoBotDifficulty difficulty, MyGoTeamPreference preference, String mapKey) {
        if (stopping || difficulty == null || preference == null || !MyGoMapBlueprint.availableMaps().contains(mapKey)) return false;
        Room current = humans.get(player.getUuid());
        Key key = new Key(difficulty, mapKey);
        if (current != null) {
            if (!current.key.equals(key)) {
                player.sendMessage(Messages.error("请先用 /pvp practice leave 退出当前练习房"), false);
                return false;
            }
            return selectTeam(player, preference);
        }
        if (PvPMod.PRACTICE.isInPractice(player) || PvPMod.MATCH.isInMatch(player.getUuid())
                || PvPMod.QUEUE.contains(player.getUuid())) {
            player.sendMessage(Messages.error("请先退出当前练习、比赛或匹配队列"), false);
            return false;
        }
        if (!MyGoResourcePack.request(player)) return false;
        Room room = rooms.get(key);
        if (room == null) {
            try {
                room = new Room(key, REGION_BASE + difficulty.ordinal() * 3 + MyGoMapBlueprint.availableMaps().indexOf(mapKey));
                rooms.put(key, room);
            } catch (RuntimeException error) {
                PvPMod.LOGGER.error("[MyGO] 练习房创建失败", error);
                player.sendMessage(Messages.error("练习场地创建失败，请稍后重试"), false);
                return false;
            }
        }
        if (room.closing) {
            player.sendMessage(Messages.warn("练习房正在清理，请稍后重试"), false);
            return false;
        }
        Seat seat = room.available(preference);
        if (seat == null) {
            player.sendMessage(Messages.error("所选队伍真人已满（每队最多 5 人），请选择另一队"), false);
            return false;
        }
        player.closeHandledScreen();
        humans.put(player.getUuid(), room);
        seat.pending = player.getUuid();
        if (PvPMod.DUEL != null) PvPMod.DUEL.removeChallengesInvolving(player.getUuid());
        if (room.ready) {
            try { room.admit(seat, player); }
            catch (RuntimeException error) { room.fail(error); return false; }
        } else player.sendMessage(Messages.info("MyGO 5v5 练习场正在生成，完成后自动进入；可用 /pvp practice leave 取消"), false);
        return true;
    }

    public boolean selectTeam(ServerPlayerEntity player, MyGoTeamPreference preference) {
        Room room = humans.get(player.getUuid());
        if (room == null || room.closing || preference == null) return false;
        Seat old = room.humanSeat(player.getUuid());
        if (old == null) return false;
        if (preference == MyGoTeamPreference.AUTO || room.side(old) == (preference == MyGoTeamPreference.T)) return true;
        Seat target = room.available(preference);
        if (target == null) {
            player.sendMessage(Messages.error("目标队伍真人已满"), false);
            return false;
        }
        if (!room.ready) {
            old.pending = null;
            target.pending = player.getUuid();
            return true;
        }
        MyGoSession.BotView state = room.session.botView(player);
        if (state == null || !(state.phase().equals("BUY") || state.phase().equals("POST"))) {
            player.sendMessage(Messages.warn("请在购买或回合结束阶段换队，战斗中不能通过换队重新接管席位"), false);
            return false;
        }
        try {
            room.backfill(old);
            room.admit(target, player);
            return true;
        } catch (RuntimeException error) {
            room.fail(error);
            return false;
        }
    }

    public boolean leave(ServerPlayerEntity player) { return leave(player, false); }
    public void onDisconnect(ServerPlayerEntity player) { leave(player, true); }
    private boolean leave(ServerPlayerEntity player, boolean disconnected) {
        Room room = humans.remove(player.getUuid());
        if (room == null) return false;
        Seat seat = room.humanSeat(player.getUuid());
        if (seat != null) {
            if (seat.pending != null) seat.pending = null;
            else if (!room.closing) {
                try { room.backfill(seat); }
                catch (RuntimeException error) { room.fail(error); }
            }
        }
        actors.remove(player.getUuid());
        ArenaWorldManager.getOrNull().removeVisitor(player.getUuid());
        InventorySnapshot snapshot = snapshots.remove(player.getUuid());
        if (snapshot != null) {
            if (disconnected) PvPMod.MATCH.pendRestore(player.getUuid(), snapshot);
            else {
                player.setCameraEntity(player);
                snapshot.restore(player);
                if (player.getWorld().getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY)
                    PvPMod.MATCH.teleportToOverworldSpawn(player);
                PvPMod.MATCH.applyLobbyProtectionTo(player);
                PvpGuiManager.get().giveMenuItem(player);
            }
        }
        if (!disconnected) {
            MyGoResourcePack.remove(player);
            player.sendMessage(Messages.info("已退出 MyGO 人机练习，空位已由人机补齐"), false);
        }
        return true;
    }

    public int humanCount(MyGoBotDifficulty difficulty, String map, MyGoTeamPreference team) {
        Room room = rooms.get(new Key(difficulty, map));
        return room == null ? 0 : (int) Arrays.stream(room.seats).filter(room::human)
                .filter(seat -> team == MyGoTeamPreference.AUTO || room.side(seat) == (team == MyGoTeamPreference.T)).count();
    }
    public String status(ServerPlayerEntity player) {
        Room room = humans.get(player.getUuid());
        if (room == null) return "未加入 MyGO 练习";
        Seat seat = room.humanSeat(player.getUuid());
        int count = (int) Arrays.stream(room.seats).filter(room::human).count();
        return room.key.difficulty.getDisplayName() + " · " + room.key.map + " · "
                + (seat != null && room.side(seat) ? "T" : "CT") + " · 真人 " + count + " / 人机 " + (SEATS - count)
                + (room.ready ? " · 5v5" : " · 场地生成中");
    }

    /** OP 诊断只读取服务端授权状态，便于验证固定席位和交接时的血量/弹药不变。 */
    public String debugState(ServerPlayerEntity player) {
        if (player == null) return "MyGOPractice {}";
        Room room = humans.get(player.getUuid());
        if (room == null) room = actors.get(player.getUuid());
        if (room == null) return "MyGOPractice {}";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("difficulty", room.key.difficulty.getId()); data.put("map", room.key.map);
        int totalHumans = (int) Arrays.stream(room.seats).filter(room::human).count();
        data.put("humans", totalHumans); data.put("bots", SEATS - totalHumans); data.put("total", SEATS);
        data.put("tHumans", humanCount(room.key.difficulty, room.key.map, MyGoTeamPreference.T));
        data.put("ctHumans", humanCount(room.key.difficulty, room.key.map, MyGoTeamPreference.CT));
        data.put("tTotal", 5); data.put("ctTotal", 5);
        List<Map<String, Object>> roster = new ArrayList<>();
        for (Seat seat : room.seats) {
            MyGoSession.BotView view = room.ready ? room.session.botView(seat.actor) : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seat", seat.index); row.put("uuid", seat.actor.getUuid().toString());
            row.put("name", seat.actor.getGameProfile().getName()); row.put("team", room.side(seat) ? "T" : "CT");
            row.put("bot", seat.actor instanceof MyGoBotPlayer); row.put("alive", view != null && view.alive());
            row.put("hp", view == null ? 0 : view.hp()); row.put("cash", view == null ? 0 : view.cash());
            row.put("ammo", view == null ? 0 : view.ammo()); row.put("reserve", view == null ? 0 : view.reserve());
            row.put("bomb", view != null && view.carryingBomb());
            row.put("ai", seat.controller == null ? "human" : seat.controller.debugState());
            row.put("gun", view == null || view.gun() == null ? "none" : view.gun().name());
            row.put("x", seat.actor.getX()); row.put("y", seat.actor.getY()); row.put("z", seat.actor.getZ());
            roster.add(row);
            if (view != null) { data.put("phase", view.phase()); data.put("round", view.round()); }
        }
        data.putIfAbsent("phase", "BUILDING"); data.put("roster", roster);
        return "MyGOPractice " + GSON.toJson(data);
    }

    public boolean canAccessPosition(Entity actor, World world, BlockPos pos) {
        if (world.getRegistryKey() != ArenaWorldManager.ARENA_WORLD_KEY) return true;
        Room current = actor == null ? null : actors.get(actor.getUuid());
        for (Room room : rooms.values()) {
            BlockPos center = room.template.getCenter(room.region);
            if (Math.abs(pos.getX() - center.getX()) <= 176 && Math.abs(pos.getZ() - center.getZ()) <= 176 && room != current)
                return false;
        }
        return true;
    }

    public void tick() {
        if (stopping) return;
        boolean buildingWorked = false;
        for (Room room : List.copyOf(rooms.values())) {
            try {
                if (room.closing) continue;
                if (!room.ready) {
                    if (!buildingWorked) { room.advanceBuild(); buildingWorked = true; }
                    continue;
                }
                for (Seat seat : room.seats) {
                    if (seat.actor instanceof MyGoBotPlayer) continue;
                    if (server.getPlayerManager().getPlayer(seat.actor.getUuid()) != seat.actor) onDisconnect(seat.actor);
                    else if (seat.actor.getWorld() != room.world()) leave(seat.actor);
                }
                if (server.getTicks() % 400 == 0)
                    for (Seat seat : room.seats) ArenaWorldManager.getOrNull().addVisitor(seat.actor, 3600);
                if (room.restartAt > 0) {
                    for (Seat seat : room.seats) if (seat.actor instanceof MyGoBotPlayer bot) {
                        bot.input(0, 0, false);
                        room.session.botFire(bot, false);
                    }
                    if (server.getTicks() >= room.restartAt) { room.restartAt = 0; room.session.restartPracticeMatch(); }
                } else {
                    for (Seat seat : room.seats) if (seat.controller != null) seat.controller.tick();
                    room.session.tick();
                }
            } catch (RuntimeException error) { room.fail(error); }
        }
    }

    public void shutdown() {
        stopping = true;
        for (Room room : List.copyOf(rooms.values())) room.close();
        rooms.clear(); actors.clear(); humans.clear(); snapshots.clear();
    }

    private final class Room implements MyGoPracticeRuntime {
        final Key key;
        final int region;
        final ArenaTemplate template;
        final Seat[] seats = new Seat[SEATS];
        MyGoSession session;
        MapBuildQueue build;
        boolean ready, closing;
        int restartAt;
        Room(Key key, int region) {
            this.key = key; this.region = region;
            this.template = PvPMod.MATCH.createTemplate(MatchType.MYGO);
            try {
                for (int i = 0; i < SEATS; i++) {
                    seats[i] = new Seat(i);
                    seats[i].actor = createBot(i);
                    actors.put(seats[i].actor.getUuid(), this);
                }
                session = new MyGoSession(this, template, region, region * 31, key.map);
                build = new MapBuildQueue(world(), template.getCenter(region), 176);
                world().beginStaging(build);
                try { session.stageMap(); } finally { world().endStaging(); build.close(); }
            } catch (RuntimeException error) {
                close();
                throw error;
            }
        }
        MyGoBotPlayer createBot(int index) {
            // PlayerManager 缓存玩家统计/成就，固定席位 UUID 避免反复补位无限增加假玩家缓存。
            UUID id = UUID.nameUUIDFromBytes(("pvp:mygo:practice:" + region + ":" + index).getBytes(StandardCharsets.UTF_8));
            return new MyGoBotPlayer(server, world(), id, "MG" + region + "_" + (index + 1));
        }
        void advanceBuild() {
            if (!build.flush(4)) return;
            build = null;
            for (Seat seat : seats) {
                MyGoBotPlayer bot = (MyGoBotPlayer) seat.actor;
                boolean attack = seat.index < 5;
                bot.spawn(session.arena().spawn(attack, seat.index % 5), session.arena().spawnYaw(attack, seat.index % 5));
                ArenaWorldManager.getOrNull().addVisitor(bot, 3600);
                seat.controller = new MyGoBot(session, bot, key.difficulty, seat.index);
            }
            ready = true;
            session.start();
            if (closing) return;
            for (Seat seat : seats) if (seat.pending != null) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(seat.pending);
                if (player == null) { humans.remove(seat.pending); seat.pending = null; }
                else admit(seat, player);
            }
        }
        boolean side(Seat seat) { return session == null ? seat.index < 5 : session.attackerSide(seat.actor); }
        boolean human(Seat seat) { return seat.pending != null || !(seat.actor instanceof MyGoBotPlayer); }
        Seat humanSeat(UUID id) {
            return Arrays.stream(seats).filter(seat -> id.equals(seat.pending) || seat.actor.getUuid().equals(id)).findFirst().orElse(null);
        }
        Seat available(MyGoTeamPreference preference) {
            boolean attack = preference == MyGoTeamPreference.AUTO
                    ? humanCount(key.difficulty, key.map, MyGoTeamPreference.T) <= humanCount(key.difficulty, key.map, MyGoTeamPreference.CT)
                    : preference == MyGoTeamPreference.T;
            Seat found = available(attack);
            return found == null && preference == MyGoTeamPreference.AUTO ? available(!attack) : found;
        }
        Seat available(boolean attack) {
            return Arrays.stream(seats).filter(seat -> side(seat) == attack && !human(seat)).findFirst().orElse(null);
        }
        void admit(Seat seat, ServerPlayerEntity player) {
            snapshots.computeIfAbsent(player.getUuid(), ignored -> InventorySnapshot.capture(player));
            for (Seat member : seats) if (member.actor instanceof MyGoBotPlayer bot) bot.sendProfile(player);
            ArenaWorldManager.getOrNull().addVisitor(player, 3600);
            replace(seat, player);
            seat.pending = null;
            humans.put(player.getUuid(), this);
            player.sendMessage(Messages.gold("已进入 MyGO 5v5 人机练习 · " + key.difficulty.getDisplayName()
                    + " · " + (side(seat) ? "T" : "CT")), false);
            player.sendMessage(Messages.info("接管人机当前血量、弹药和存活状态；每队固定 5 人，不计正式战绩。"), false);
            player.sendMessage(Messages.info("/pvp practice leave 或 /hub 退出；/pvp practice mygo team <t|ct|auto> 在购买阶段选队。"), false);
        }
        void backfill(Seat seat) {
            MyGoBotPlayer bot = createBot(seat.index);
            actors.put(bot.getUuid(), this);
            bot.spawn(session.arena().spawn(side(seat), seat.index % 5), session.arena().spawnYaw(side(seat), seat.index % 5));
            ArenaWorldManager.getOrNull().addVisitor(bot, 3600);
            replace(seat, bot);
        }
        void replace(Seat seat, ServerPlayerEntity incoming) {
            ServerPlayerEntity old = seat.actor;
            actors.put(incoming.getUuid(), this);
            if (!session.replacePracticeFighter(old, incoming)) {
                actors.remove(incoming.getUuid());
                if (incoming instanceof MyGoBotPlayer bot) bot.discardBot();
                throw new IllegalStateException("Cannot replace MyGO practice seat " + seat.index);
            }
            actors.remove(old.getUuid());
            seat.actor = incoming;
            seat.controller = incoming instanceof MyGoBotPlayer bot ? new MyGoBot(session, bot, key.difficulty, seat.index) : null;
            if (old instanceof MyGoBotPlayer bot) {
                ArenaWorldManager.getOrNull().removeVisitor(old.getUuid());
                bot.discardBot();
            }
        }
        void fail(RuntimeException error) {
            PvPMod.LOGGER.error("[MyGO] 人机练习房运行失败 {}", key, error);
            broadcast(Messages.error("练习房发生错误，已退出并归还入场前状态"));
            close();
        }
        void close() {
            if (closing) return;
            closing = true;
            if (session != null) session.onEnd();
            // 换队失败时真人可能暂时不属于任何席位；按房间登记回收，不能只遍历当前十个操作者。
            for (var entry : List.copyOf(humans.entrySet())) {
                if (entry.getValue() != this) continue;
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player != null) leave(player, false);
                else {
                    humans.remove(entry.getKey());
                    InventorySnapshot snapshot = snapshots.remove(entry.getKey());
                    if (snapshot != null) PvPMod.MATCH.pendRestore(entry.getKey(), snapshot);
                }
            }
            for (Seat seat : seats) {
                if (seat == null || seat.actor == null) continue;
                if (seat.pending != null) {
                    ServerPlayerEntity pendingPlayer = server.getPlayerManager().getPlayer(seat.pending);
                    if (pendingPlayer != null) leave(pendingPlayer, false);
                    else { humans.remove(seat.pending); snapshots.remove(seat.pending); seat.pending = null; }
                }
                if (seat.actor instanceof MyGoBotPlayer bot) bot.discardBot();
                else leave(seat.actor, false);
                actors.remove(seat.actor.getUuid());
                ArenaWorldManager.getOrNull().removeVisitor(seat.actor.getUuid());
            }
            if (!stopping && session != null) session.clearArena();
            if (session != null) MyGoNavigator.release(world(), session.arena());
            actors.entrySet().removeIf(entry -> entry.getValue() == this);
            rooms.remove(key, this);
        }
        @Override public ArenaWorld world() { return ArenaWorldManager.getOrNull().getWorld(); }
        @Override public List<ServerPlayerEntity> participants() { return Arrays.stream(seats).filter(java.util.Objects::nonNull).map(seat -> seat.actor).toList(); }
        @Override public ServerPlayerEntity player(UUID id) { return participants().stream().filter(actor -> actor.getUuid().equals(id)).findFirst().orElse(null); }
        @Override public boolean contains(UUID id) { return player(id) != null; }
        @Override public void broadcast(Text message) {
            for (Seat seat : seats) if (seat != null && seat.actor != null && !(seat.actor instanceof MyGoBotPlayer)) seat.actor.sendMessage(message, false);
        }
        @Override public void finish(String reason) {
            if (session != null && session.hasFinalResult()) restartAt = server.getTicks() + 60;
            else fail(new IllegalStateException(reason));
        }
    }
}
