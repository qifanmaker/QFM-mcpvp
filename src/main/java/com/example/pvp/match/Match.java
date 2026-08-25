package com.example.pvp.match;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.config.StatsStore;
import com.example.pvp.kit.InventorySnapshot;
import com.example.pvp.kit.Kit;
import com.example.pvp.kit.KitApplicator;
import com.example.pvp.text.Messages;
import com.mojang.logging.LogUtils;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一场比赛：FORMING → COUNTDOWN → ACTIVE → ENDED。
 * 负责倒计时、传送、淘汰判定、胜负结算、状态恢复与地形清理。
 */
public final class Match {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MatchManager manager;
    private final int id;
    private final MatchType type;
    private final Kit kit;
    private final List<ServerPlayerEntity> players;
    private final Map<UUID, InventorySnapshot> snapshots = new HashMap<>();
    private final Map<UUID, BlockPos> spawns = new HashMap<>();
    private final List<MatchTeam> teams;
    private final ArenaTemplate template;
    private final int regionIndex;
    private final Set<UUID> eliminated = new HashSet<>();
    private final int initialCountdownTicks;

    private MatchState state;
    private int countdownTicks;
    private int ticks;

    private Match(MatchManager manager, int id, MatchType type, Kit kit,
                  List<ServerPlayerEntity> players, int regionIndex, ArenaTemplate template) {
        this.manager = manager;
        this.id = id;
        this.type = type;
        this.kit = kit;
        this.players = List.copyOf(players);
        this.teams = buildTeams(type, this.players);
        this.template = template;
        this.regionIndex = regionIndex;
        this.initialCountdownTicks = PvPConfig.INSTANCE.countdownSeconds * 20;
        this.countdownTicks = this.initialCountdownTicks;

        // 捕获玩家战斗前状态
        for (ServerPlayerEntity player : this.players) {
            this.snapshots.put(player.getUuid(), InventorySnapshot.capture(player));
        }

        // 计算出生点
        List<BlockPos> spawnPositions = template.computeSpawns(regionIndex, this.players.size());
        for (int i = 0; i < this.players.size(); i++) {
            this.spawns.put(this.players.get(i).getUuid(), spawnPositions.get(i));
        }

        this.state = MatchState.COUNTDOWN;
    }

    public static Match create(MatchManager manager, int id, MatchType type, Kit kit,
                               List<ServerPlayerEntity> players, int regionIndex, ArenaTemplate template) {
        return new Match(manager, id, type, kit, players, regionIndex, template);
    }

    private static List<MatchTeam> buildTeams(MatchType type, List<ServerPlayerEntity> players) {
        List<MatchTeam> teams = new ArrayList<>();
        if (type == MatchType.DUEL_1V1) {
            teams.add(new MatchTeam("红队", Formatting.RED, List.of(players.get(0))));
            teams.add(new MatchTeam("蓝队", Formatting.BLUE, List.of(players.get(1))));
        } else if (type == MatchType.DUEL_2V2) {
            teams.add(new MatchTeam("红队", Formatting.RED, List.of(players.get(0), players.get(1))));
            teams.add(new MatchTeam("蓝队", Formatting.BLUE, List.of(players.get(2), players.get(3))));
        } else {
            teams.add(new MatchTeam("全员", Formatting.WHITE, players));
        }
        return teams;
    }

    // ---------- 对外接口 ----------

    public MatchState getState() {
        return this.state;
    }

    public MatchType getType() {
        return this.type;
    }

    public Kit getKit() {
        return this.kit;
    }

    public int getRegionIndex() {
        return this.regionIndex;
    }

    public boolean contains(UUID uuid) {
        for (ServerPlayerEntity player : this.players) {
            if (player.getUuid().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    public void tick() {
        if (this.state == MatchState.ENDED) {
            return;
        }
        this.ticks++;

        // 超时保护：防止卡死的对局一直占用场地导致后续无法开赛
        int countdownStuckThreshold = this.initialCountdownTicks + 20 * 10;
        int activeTimeout = Math.max(100, PvPConfig.INSTANCE.matchTimeoutSeconds * 20);
        if (this.state == MatchState.COUNTDOWN && this.ticks > countdownStuckThreshold) {
            LOGGER.warn("[PvP] 比赛 #{} 倒计时异常，强制取消", this.id);
            this.cancelMatch("倒计时异常");
            return;
        }
        if (this.state == MatchState.ACTIVE && this.ticks > this.initialCountdownTicks + activeTimeout) {
            LOGGER.warn("[PvP] 比赛 #{} 超时（{} 秒）强制平局结束", this.id, PvPConfig.INSTANCE.matchTimeoutSeconds);
            this.finishMatch(null);
            return;
        }

        switch (this.state) {
            case COUNTDOWN -> this.tickCountdown();
            case ACTIVE -> this.checkWinCondition();
            default -> {
            }
        }
    }

    /** 所有参赛玩家是否都已离线。 */
    public boolean allPlayersOffline() {
        for (ServerPlayerEntity player : this.players) {
            if (this.manager.getOnlinePlayer(player.getUuid()) != null) {
                return false;
            }
        }
        return true;
    }

    /** 淘汰一名玩家（仅在 ACTIVE 阶段生效）。 */
    public void eliminate(ServerPlayerEntity player, EliminationCause cause) {
        if (this.state != MatchState.ACTIVE) {
            return;
        }
        if (!this.eliminated.add(player.getUuid())) {
            return; // 已淘汰
        }
        for (MatchTeam team : this.teams) {
            team.eliminate(player);
        }

        ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
        if (online != null) {
            this.makeSpectator(online);
        }

        this.broadcast(Messages.warn("§e" + player.getGameProfile().getName() + "§r 被淘汰（" + cause.getDisplayName() + "）"));

        this.checkWinCondition();
    }

    /** 取消比赛（倒计时阶段有人退出），恢复所有玩家状态。 */
    public void cancelMatch(String reason) {
        if (this.state == MatchState.ENDED) {
            return;
        }
        this.state = MatchState.ENDED;
        try {
            this.broadcast(Messages.error("比赛取消：" + reason));
            this.restoreAllPlayers();
            this.removeScoreboardTeams();
        } catch (Exception e) {
            LOGGER.error("[PvP] 比赛取消处理出错", e);
        } finally {
            this.cleanupArenaAndRelease();
        }
    }

    /** 胜负结算并清理。 */
    public void finishMatch(MatchTeam winnerTeam) {
        if (this.state == MatchState.ENDED) {
            return;
        }
        this.state = MatchState.ENDED;

        Set<UUID> winners = new HashSet<>();
        if (winnerTeam != null) {
            for (ServerPlayerEntity player : winnerTeam.getAlivePlayers()) {
                winners.add(player.getUuid());
            }
        }

        try {
            this.announceResult(winners);
            this.restoreAllPlayers();
            this.removeScoreboardTeams();

            // 统计战绩
            for (ServerPlayerEntity player : this.players) {
                boolean won = winners.contains(player.getUuid());
                StatsStore.INSTANCE.recordResult(player.getUuid(), won);
            }
            StatsStore.INSTANCE.save();
        } catch (Exception e) {
            LOGGER.error("[PvP] 比赛结束处理出错", e);
        } finally {
            this.cleanupArenaAndRelease();
        }
    }

    /** 清理竞技场地形并释放场地（无论结束流程是否出错都必须执行）。 */
    private void cleanupArenaAndRelease() {
        try {
            this.manager.getArenaManager().clearArena(this.regionIndex, this.template);
        } catch (Exception e) {
            LOGGER.error("[PvP] 清理竞技场出错", e);
        }
        this.manager.cleanupMatch(this);
        LOGGER.info("[PvP] 比赛 #{} 已结束并清理", this.id);
    }

    /** 将玩家转为旁观者并传送到观众平台（淘汰/重生兜底）。 */
    public void makeSpectator(ServerPlayerEntity player) {
        player.changeGameMode(GameMode.SPECTATOR);
        player.setInvulnerable(false);
        player.setHealth(20f);
        player.setNoGravity(true);
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena != null) {
            BlockPos center = this.template.getCenter(this.regionIndex);
            player.teleport(arena, center.getX(), center.getY() + ArenaTemplate.WALL_HEIGHT + 5, center.getZ(), 0, 0);
        }
    }

    /** 把仍在倒计时的玩家传送回自己的出生点（掉入虚空时使用）。 */
    public void teleportToSpawn(ServerPlayerEntity player) {
        BlockPos spawn = this.spawns.get(player.getUuid());
        if (spawn == null) {
            return;
        }
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        float yaw = this.faceCenter(spawn);
        player.teleport(arena, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, yaw, 0);
    }

    // ---------- 内部逻辑 ----------

    private void tickCountdown() {
        if (this.countdownTicks == this.initialCountdownTicks) {
            this.setupPlayers();
        }

        if (this.countdownTicks > 0) {
            if (this.countdownTicks % 20 == 0) {
                int seconds = this.countdownTicks / 20;
                this.broadcastTitle(String.valueOf(seconds));
            }
            this.countdownTicks--;
        } else {
            this.state = MatchState.ACTIVE;
            this.broadcast(Messages.gold("战斗开始！"));
            this.broadcastTitle("开始！");
            for (ServerPlayerEntity player : this.players) {
                ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
                if (online != null) {
                    online.setInvulnerable(false);
                    online.setNoGravity(false);
                }
            }
        }
    }

    private void setupPlayers() {
        this.manager.getArenaManager().buildArena(this.regionIndex, this.template);
        ArenaWorld arena = this.manager.getArenaManager().getWorld();

        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null) {
                continue;
            }
            BlockPos spawn = this.spawns.get(player.getUuid());
            float yaw = this.faceCenter(spawn);
            online.teleport(arena, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, yaw, 0);
            KitApplicator.apply(online, this.kit);
            online.setInvulnerable(true);
            online.setNoGravity(false);
        }

        this.createScoreboardTeams();
        this.broadcast(Messages.info("对局开始！模式：" + this.type.getDisplayName() + "，套件：" + this.kit.getDisplayName()));
    }

    private void checkWinCondition() {
        MatchTeam winner = this.computeWinner();
        if (winner != null) {
            this.finishMatch(winner);
        }
    }

    private MatchTeam computeWinner() {
        if (this.type == MatchType.FFA) {
            List<ServerPlayerEntity> alive = this.teams.get(0).getAlivePlayers();
            return alive.size() <= 1 ? this.teams.get(0) : null;
        }
        List<MatchTeam> aliveTeams = new ArrayList<>();
        for (MatchTeam team : this.teams) {
            if (!team.isDefeated()) {
                aliveTeams.add(team);
            }
        }
        return aliveTeams.size() == 1 ? aliveTeams.get(0) : null;
    }

    private void announceResult(Set<UUID> winners) {
        if (this.type == MatchType.FFA) {
            ServerPlayerEntity winner = this.getWinnerPlayer(winners);
            if (winner != null) {
                this.broadcast(Messages.gold("§6" + winner.getGameProfile().getName() + "§r 在自由乱斗中获胜！"));
            } else {
                this.broadcast(Messages.warn("本局平局"));
            }
        } else {
            MatchTeam winningTeam = null;
            for (MatchTeam team : this.teams) {
                if (!team.isDefeated()) {
                    winningTeam = team;
                    break;
                }
            }
            if (winningTeam != null && !winners.isEmpty()) {
                MutableText msg = Text.literal(winningTeam.getName()).formatted(winningTeam.getColor())
                        .append(Text.literal(" 获胜！").formatted(Formatting.GOLD));
                this.broadcast(Messages.prefix(msg));
            } else {
                this.broadcast(Messages.warn("本局平局"));
            }
        }
    }

    private ServerPlayerEntity getWinnerPlayer(Set<UUID> winners) {
        for (ServerPlayerEntity player : this.players) {
            if (winners.contains(player.getUuid())) {
                return player;
            }
        }
        return null;
    }

    private void restoreAllPlayers() {
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            InventorySnapshot snapshot = this.snapshots.get(player.getUuid());
            if (online != null && snapshot != null) {
                snapshot.restore(online);
            } else if (snapshot != null) {
                this.manager.pendRestore(player.getUuid(), snapshot);
            }
        }
    }

    private void createScoreboardTeams() {
        MinecraftServer server = this.manager.getServer();
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        for (int i = 0; i < this.teams.size(); i++) {
            MatchTeam team = this.teams.get(i);
            String teamName = "pvp_" + this.id + "_" + i;
            Team sbTeam = scoreboard.getTeam(teamName);
            if (sbTeam == null) {
                sbTeam = scoreboard.addTeam(teamName);
            }
            sbTeam.setColor(team.getColor());
            sbTeam.setCollisionRule(AbstractTeam.CollisionRule.NEVER);
            for (ServerPlayerEntity player : team.getPlayers()) {
                ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
                if (online != null) {
                    scoreboard.addScoreHolderToTeam(online.getGameProfile().getName(), sbTeam);
                }
            }
        }
    }

    private void removeScoreboardTeams() {
        MinecraftServer server = this.manager.getServer();
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        for (int i = 0; i < this.teams.size(); i++) {
            String teamName = "pvp_" + this.id + "_" + i;
            Team sbTeam = scoreboard.getTeam(teamName);
            if (sbTeam != null) {
                for (ServerPlayerEntity player : this.players) {
                    ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
                    if (online != null) {
                        scoreboard.removeScoreHolderFromTeam(online.getGameProfile().getName(), sbTeam);
                    }
                }
                scoreboard.removeTeam(sbTeam);
            }
        }
    }

    private float faceCenter(BlockPos spawn) {
        BlockPos center = this.template.getCenter(this.regionIndex);
        double dx = center.getX() - spawn.getX();
        double dz = center.getZ() - spawn.getZ();
        return (float) Math.toDegrees(Math.atan2(dx, dz));
    }

    private void broadcast(Text message) {
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online != null) {
                online.sendMessage(message, false);
            }
        }
    }

    private void broadcastTitle(String subtitle) {
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online != null) {
                online.sendMessage(Text.literal("§6§l" + subtitle), true);
            }
        }
    }
}
