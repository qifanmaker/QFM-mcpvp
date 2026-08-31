package com.example.pvp.match;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.luckypillar.LuckyPillarLayout;
import com.example.pvp.arena.skywars.SkyWarsTheme;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.gui.PvpGuiManager;
import com.example.pvp.kit.InventorySnapshot;
import com.example.pvp.kit.Kit;
import com.example.pvp.text.Messages;
import com.mojang.logging.LogUtils;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 比赛管理器：管理所有并发比赛、区域分配、虚空/闯入者兜底、玩家状态恢复。
 */
public final class MatchManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static MatchManager instance;

    private final MinecraftServer server;
    private final List<Match> matches = new ArrayList<>();
    private final Set<Integer> allocatedRegions = new HashSet<>();
    private final Map<UUID, InventorySnapshot> pendingRestores = new ConcurrentHashMap<>();
    /** OP 强制开赛时指定的一次性空岛主题（下一次 SKYWARS 用，用后清除）。 */
    private SkyWarsTheme pendingSkywarsTheme;
    private int nextMatchId = 0;
    /** 已向其客户端注册过 pvp_info 计分板 objective 的玩家（避免重复发 ADD 包导致客户端崩溃）。 */
    private final Set<UUID> scoreboardObjectiveKnown = ConcurrentHashMap.newKeySet();

    /**
     * 确保玩家客户端已注册计分板 objective：首次发给该玩家 ADD 包（客户端对重复 ADD 会抛异常崩溃），
     * 之后同一会话不再发。objective 不存在时顺带创建。
     */
    public void ensureScoreboardObjective(ServerPlayerEntity player) {
        if (player == null || player.networkHandler == null
                || !this.scoreboardObjectiveKnown.add(player.getUuid())) {
            return;
        }
        MinecraftServer server = this.server;
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(Match.INFO_OBJECTIVE);
        if (objective == null) {
            objective = scoreboard.addObjective(Match.INFO_OBJECTIVE, ScoreboardCriterion.DUMMY,
                    Text.literal("§6§lPvP 对局"), ScoreboardCriterion.RenderType.INTEGER, true, null);
        }
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective, 0));
    }

    /** 上一局幸运之柱平台风格（开新局时跳过相同风格，保证两局地板不一样）。 */
    private LuckyPillarLayout.PlatformStyle lastLuckyPillarStyle;
    /** OP 强制开赛时指定的一次性幸运之柱平台风格（用后清除）。 */
    private LuckyPillarLayout.PlatformStyle pendingLuckyPillarStyle;

    private MatchManager(MinecraftServer server) {
        this.server = server;
    }

    public static MatchManager init(MinecraftServer server) {
        if (instance == null || instance.server != server) {
            instance = new MatchManager(server);
        }
        return instance;
    }

    public static MatchManager get() {
        return instance;
    }

    public MinecraftServer getServer() {
        return this.server;
    }

    public ArenaWorldManager getArenaManager() {
        return ArenaWorldManager.get(this.server);
    }

    /** OP 强制开赛前指定下一次空岛战争的强制主题（null = 随机）。 */
    public void setNextSkywarsTheme(SkyWarsTheme theme) {
        this.pendingSkywarsTheme = theme;
    }

    /** 消费一次性强制主题（Match 构造时调用）。 */
    public SkyWarsTheme consumePendingSkywarsTheme() {
        SkyWarsTheme theme = this.pendingSkywarsTheme;
        this.pendingSkywarsTheme = null;
        return theme;
    }

    /** OP 强制开赛前指定下一次幸运之柱的强制平台风格（null = 随机）。 */
    public void setNextLuckyPillarStyle(LuckyPillarLayout.PlatformStyle style) {
        this.pendingLuckyPillarStyle = style;
    }

    /** 查看一次性强制幸运之柱风格（不消费；用于决定是否跳过"避免重复风格"）。 */
    public LuckyPillarLayout.PlatformStyle peekPendingLuckyPillarStyle() {
        return this.pendingLuckyPillarStyle;
    }

    /** 消费一次性强制幸运之柱风格（Match 构造时调用）。 */
    public LuckyPillarLayout.PlatformStyle consumePendingLuckyPillarStyle() {
        LuckyPillarLayout.PlatformStyle style = this.pendingLuckyPillarStyle;
        this.pendingLuckyPillarStyle = null;
        return style;
    }

    /** 每个服务器 tick 调用。 */
    public void tick() {
        for (Match match : List.copyOf(this.matches)) {
            if (match.getState() != MatchState.ENDED && match.allPlayersOffline()) {
                match.cancelMatch("所有玩家离线");
                continue;
            }
            match.tick();
        }

        this.sweepArenaWorld();
        this.getArenaManager().tickVisitors();
        this.applyLobbyProtection();
    }

    /** 大厅保护：不在对局中的玩家 → 冒险模式 + 无敌 + 饱食度不掉，并强制解除幽灵状态。 */
    private void applyLobbyProtection() {
        if (!PvPConfig.INSTANCE.lobbyProtection) {
            return;
        }
        for (ServerPlayerEntity player : List.copyOf(this.server.getPlayerManager().getPlayerList())) {
            // 竞技场内由对局逻辑管理，不套用大厅保护
            if (player.getWorld().getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY) {
                continue;
            }
            GameMode mode = player.interactionManager.getGameMode();
            if (mode == GameMode.SURVIVAL) {
                player.changeGameMode(GameMode.ADVENTURE);
                mode = GameMode.ADVENTURE;
            }
            player.setInvulnerable(true);
            // 强制非幽灵：显形 + 正常重力 + 取消幽灵飞行（创造/旁观者保留自然飞行）
            player.setInvisible(false);
            player.setNoGravity(false);
            if (mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR
                    && (player.getAbilities().allowFlying || player.getAbilities().flying)) {
                player.getAbilities().allowFlying = false;
                player.getAbilities().flying = false;
                player.sendAbilitiesUpdate();
            }
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(20f);
        }
    }

    /** 尝试开一场比赛（所有人同一套件），成功返回 true。 */
    public boolean startMatch(List<ServerPlayerEntity> players, MatchType type, Kit kit) {
        Map<UUID, Kit> kits = new HashMap<>();
        for (ServerPlayerEntity player : players) {
            kits.put(player.getUuid(), kit);
        }
        return this.startMatch(players, type, kits);
    }

    /** 尝试开一场比赛（支持每名玩家各自套件，FFA 混套件用），成功返回 true。 */
    public boolean startMatch(List<ServerPlayerEntity> players, MatchType type, Map<UUID, Kit> kits) {
        if (this.server == null) {
            return false;
        }
        if (players == null || players.isEmpty()) {
            return false;
        }
        if (type == MatchType.FFA) {
            if (players.size() < PvPConfig.INSTANCE.ffaMinPlayers) {
                return false;
            }
        } else if (type == MatchType.SKYWARS) {
            if (players.size() < PvPConfig.INSTANCE.skywarsMinPlayers
                    || players.size() > PvPConfig.INSTANCE.skywarsMaxPlayers) {
                return false;
            }
        } else if (type == MatchType.LUCKY_PILLAR) {
            if (players.size() < PvPConfig.INSTANCE.luckyPillarMinPlayers
                    || players.size() > PvPConfig.INSTANCE.luckyPillarMaxPlayers) {
                return false;
            }
        } else if (type == MatchType.TNT_RUN) {
            if (players.size() < PvPConfig.INSTANCE.tntRunMinPlayers
                    || players.size() > PvPConfig.INSTANCE.tntRunMaxPlayers) {
                return false;
            }
        } else if (type == MatchType.HEARTBEAT) {
            if (players.size() < PvPConfig.INSTANCE.heartbeatMinPlayers
                    || players.size() > PvPConfig.INSTANCE.heartbeatMaxPlayers) {
                return false;
            }
        } else if (type == MatchType.HOT_POTATO) {
            if (players.size() < PvPConfig.INSTANCE.hotPotatoMinPlayers
                    || players.size() > PvPConfig.INSTANCE.hotPotatoMaxPlayers) {
                return false;
            }
        } else if (type.isBridge()) {
            if (type.isBridgeTeam()) {
                // 混战：总人数/2 分两队，需要偶数且 ≥ 最少人数
                if (players.size() < PvPConfig.INSTANCE.bridgeTeamMinPlayers || players.size() % 2 != 0) {
                    return false;
                }
            } else if (players.size() != type.requiredPlayers()) {
                return false;
            }
        } else if (players.size() != type.requiredPlayers()) {
            return false;
        }

        int maxConcurrent = Math.max(1, PvPConfig.INSTANCE.maxConcurrentMatches);
        if (this.matches.size() >= maxConcurrent) {
            LOGGER.info("[PvP] 场地已满（{} 场进行中），队列等待", this.matches.size());
            for (ServerPlayerEntity player : players) {
                player.sendMessage(Messages.error("当前竞技场已满，请稍后再试"), false);
            }
            return false;
        }

        for (ServerPlayerEntity player : players) {
            if (this.getMatchFor(player.getUuid()) != null) {
                LOGGER.warn("[PvP] 玩家 {} 已在比赛中，无法开新赛", player.getGameProfile().getName());
                return false;
            }
        }

        // 移除排队红石，使其不进入状态快照（赛后不会残留）
        for (ServerPlayerEntity player : players) {
            PvpGuiManager.removeQueueItem(player);
        }

        int regionIndex = this.allocateRegion();
        ArenaTemplate template = this.createTemplate(type);
        int id = this.nextMatchId;
        if (type == MatchType.LUCKY_PILLAR) {
            if (this.pendingLuckyPillarStyle != null) {
                // OP 强制指定地图：直接用该风格，不参与"避免重复"
                this.lastLuckyPillarStyle = this.pendingLuckyPillarStyle;
            } else {
                // 幸运之柱：连续两局不能随机到同一种平台地板——跳过会重复风格的 seed
                id = this.luckyPillarSeedAvoidingRepeat(template, regionIndex, id, players.size());
            }
        }
        Match match = Match.create(this, id, type, players, regionIndex, template, kits);
        if (id >= this.nextMatchId) {
            this.nextMatchId = id + 1; // 被跳过后的 id 也要让下一次递增
        }
        this.matches.add(match);
        match.tick(); // 立即进入倒计时第一帧
        return true;
    }

    public Match getMatchFor(ServerPlayerEntity player) {
        return this.getMatchFor(player.getUuid());
    }

    public Match getMatchFor(UUID uuid) {
        for (Match match : this.matches) {
            if (match.contains(uuid)) {
                return match;
            }
        }
        return null;
    }

    public List<Match> getMatches() {
        return List.copyOf(this.matches);
    }

    public boolean isInMatch(UUID uuid) {
        return this.getMatchFor(uuid) != null;
    }

    /** 是否为低版本(1.8)战斗模式：1.8 经典PvP / 空岛战争 / 战桥 / 幸运之柱（无攻击冷却 + 剑格挡）。 */
    public boolean isLegacyCombat(Match match) {
        return match != null && (match.getType() == MatchType.PVP_1_8
                || match.getType() == MatchType.SKYWARS || match.getType().isBridge()
                || match.getType() == MatchType.LUCKY_PILLAR);
    }

    /** 1.8 战斗模式：玩家是否正在剑格挡（供伤害减免 Mixin 调用）。 */
    public boolean isLegacyBlocking(ServerPlayerEntity player) {
        Match match = this.getMatchFor(player);
        return match != null && this.isLegacyCombat(match) && match.isBlocking(player);
    }

    /** 比赛结束后的清理：从列表移除并释放区域。 */
    public void cleanupMatch(Match match) {
        this.matches.remove(match);
        this.allocatedRegions.remove(match.getRegionIndex());
    }

    /** 玩家离线时暂存其状态，登录后恢复。 */
    public void pendRestore(UUID uuid, InventorySnapshot snapshot) {
        this.pendingRestores.put(uuid, snapshot);
    }

    /** 玩家登录事件：恢复上次对局状态。 */
    public void onPlayerJoin(ServerPlayerEntity player) {
        InventorySnapshot snapshot = this.pendingRestores.remove(player.getUuid());
        if (snapshot != null) {
            snapshot.restore(player);
            player.sendMessage(Messages.info("你上次对局时的状态已恢复"), false);
        }
    }

    /** 玩家死亡事件。 */
    public void onPlayerDeath(ServerPlayerEntity player) {
        Match match = this.getMatchFor(player);
        if (match != null) {
            if (match.getType().isBridge()) {
                return; // 战桥由 ALLOW_DEATH 拦截死亡，这里不做淘汰
            }
            match.eliminate(player, EliminationCause.DEATH);
        }
    }

    /** 玩家重生事件：若仍在对局中，转为幽灵（冒险模式、空物品栏、禁止交互）；战桥则原地重生。 */
    public void onPlayerRespawn(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer, boolean alive) {
        Match match = this.getMatchFor(newPlayer.getUuid());
        if (match != null && match.getState() == MatchState.ACTIVE) {
            if (match.getType().isBridge()) {
                match.bridgeRespawn(newPlayer);
            } else {
                match.makeGhost(newPlayer);
            }
        }
    }

    /** 该玩家是否已在对局中被淘汰（死亡幽灵，禁止一切交互）。 */
    public boolean isEliminated(UUID uuid) {
        Match match = this.getMatchFor(uuid);
        return match != null && match.isEliminated(uuid);
    }

    /** 玩家断线事件。 */
    public void onPlayerDisconnect(ServerPlayerEntity player) {
        this.getArenaManager().removeVisitor(player.getUuid());
        Match match = this.getMatchFor(player);
        if (match != null) {
            if (match.getState() == MatchState.COUNTDOWN) {
                match.cancelMatch(player.getGameProfile().getName() + " 中途退出");
            } else {
                match.eliminate(player, EliminationCause.DISCONNECT);
            }
        }
    }

    /** 获取在线玩家（按 UUID 查询，避免引用已断线对象）。 */
    public ServerPlayerEntity getOnlinePlayer(UUID uuid) {
        return this.server.getPlayerManager().getPlayer(uuid);
    }

    public void teleportToOverworldSpawn(ServerPlayerEntity player) {
        ServerWorld overworld = this.server.getOverworld();
        BlockPos pos = overworld.getSpawnPos();
        player.teleport(overworld, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, overworld.getSpawnAngle(), 0);
    }

    // ---------- 内部 ----------

    private int allocateRegion() {
        int max = Math.max(1, PvPConfig.INSTANCE.maxConcurrentMatches);
        for (int i = 0; i < max * 2; i++) {
            if (!this.allocatedRegions.contains(i)) {
                this.allocatedRegions.add(i);
                return i;
            }
        }
        // 极端情况下兜底分配一个未冲突区域
        for (int i = 0; ; i++) {
            if (!this.allocatedRegions.contains(i)) {
                this.allocatedRegions.add(i);
                return i;
            }
        }
    }

    /**
     * 幸运之柱：从 baseId 起找第一个与上一局平台风格不同的 seed（最多试 20 个），
     * 保证连续两局地板不重复；记录本次风格供下一局跳过。
     */
    private int luckyPillarSeedAvoidingRepeat(ArenaTemplate template, int regionIndex,
                                              int baseId, int playerCount) {
        int id = baseId;
        for (int i = 0; i < 20; i++) {
            LuckyPillarLayout layout = LuckyPillarLayout.compute(
                    template.getCenter(regionIndex), id, playerCount);
            if (this.lastLuckyPillarStyle == null
                    || layout.platformStyle() != this.lastLuckyPillarStyle) {
                this.lastLuckyPillarStyle = layout.platformStyle();
                return id;
            }
            id++;
        }
        return baseId; // 兜底：重试耗尽也按原 id 开（概率极低）
    }

    private ArenaTemplate createTemplate(MatchType type) {
        int size = switch (type) {
            case DUEL_1V1, PVP_1_8 -> PvPConfig.INSTANCE.duel1v1Size;
            case DUEL_2V2 -> PvPConfig.INSTANCE.duel2v2Size;
            case FFA -> PvPConfig.INSTANCE.ffaSize;
            case SUMO -> PvPConfig.INSTANCE.sumoSize;
            case SKYWARS -> PvPConfig.INSTANCE.skywarsSize;
            case BRIDGE_1V1, BRIDGE_2V2, BRIDGE_1V1V1V1, BRIDGE_TEAM -> PvPConfig.INSTANCE.bridgeSize;
            case LUCKY_PILLAR -> PvPConfig.INSTANCE.luckyPillarSize;
            case TNT_RUN -> PvPConfig.INSTANCE.tntRunSize;
            case HEARTBEAT -> PvPConfig.INSTANCE.heartbeatSize;
            case HOT_POTATO -> PvPConfig.INSTANCE.hotPotatoSize;
        };
        ArenaTemplate.Layout layout = switch (type) {
            case DUEL_1V1, SUMO, PVP_1_8 -> ArenaTemplate.Layout.DUEL_1V1;
            case DUEL_2V2 -> ArenaTemplate.Layout.DUEL_2V2;
            case FFA -> ArenaTemplate.Layout.FFA;
            case SKYWARS -> ArenaTemplate.Layout.SKYWARS;
            case BRIDGE_1V1, BRIDGE_2V2, BRIDGE_1V1V1V1, BRIDGE_TEAM -> ArenaTemplate.Layout.BRIDGE;
            case LUCKY_PILLAR -> ArenaTemplate.Layout.LUCKY_PILLAR;
            case TNT_RUN -> ArenaTemplate.Layout.TNT_RUN;
            case HEARTBEAT -> ArenaTemplate.Layout.HEARTBEAT;
            case HOT_POTATO -> ArenaTemplate.Layout.HOT_POTATO;
        };
        // 相扑/空岛/战桥/幸运之柱/TNT 跑酷/心跳水立方/烫手山芋无围墙；其地图本身由各自生成器铺
        boolean hasWalls = type != MatchType.SUMO && type != MatchType.SKYWARS && !type.isBridge()
                && type != MatchType.LUCKY_PILLAR && type != MatchType.TNT_RUN
                && type != MatchType.HEARTBEAT && type != MatchType.HOT_POTATO;
        return new ArenaTemplate(layout, size, PvPConfig.INSTANCE.getFloorBlock(), PvPConfig.INSTANCE.getWallBlock(), hasWalls);
    }

    /** 兜底：竞技场内掉出虚空或非参赛者一律处理。 */
    private void sweepArenaWorld() {
        ArenaWorldManager manager = ArenaWorldManager.getOrNull();
        if (manager == null) {
            return;
        }
        ArenaWorld arena = manager.getWorld();
        if (arena == null) {
            return;
        }

        for (ServerPlayerEntity player : List.copyOf(arena.getPlayers())) {
            Match match = this.getMatchFor(player.getUuid());
            boolean inVoid = player.getY() < arena.getBottomY() - 32;

            if (match == null) {
                // 调试/观览访客不受兜底影响
                if (!manager.isVisitor(player.getUuid())) {
                    this.teleportToOverworldSpawn(player);
                }
            } else if (inVoid) {
                if (match.getState() == MatchState.ACTIVE) {
                    if (match.isEliminated(player.getUuid())) {
                        match.rescueGhost(player); // 幽灵掉出虚空送回观战台
                    } else if (match.getType().isBridge()) {
                        match.bridgeRespawn(player); // 战桥：掉出虚空直接原地重生（兜底）
                    } else if (match.getType() == MatchType.HEARTBEAT) {
                        match.onHeartbeatDeath(player); // 心跳水立方：掉出虚空回当前关塔顶重试（不淘汰）
                    } else {
                        match.eliminate(player, EliminationCause.VOID);
                    }
                } else {
                    match.teleportToSpawn(player);
                }
            }
        }
    }
}
