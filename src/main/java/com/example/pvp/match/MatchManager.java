package com.example.pvp.match;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.kit.InventorySnapshot;
import com.example.pvp.kit.Kit;
import com.example.pvp.text.Messages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
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
    private static MatchManager instance;

    private final MinecraftServer server;
    private final List<Match> matches = new ArrayList<>();
    private final Set<Integer> allocatedRegions = new HashSet<>();
    private final Map<UUID, InventorySnapshot> pendingRestores = new ConcurrentHashMap<>();
    private int nextMatchId = 0;

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

    /** 每个服务器 tick 调用。 */
    public void tick() {
        for (Match match : List.copyOf(this.matches)) {
            match.tick();
        }

        this.sweepArenaWorld();
    }

    /** 尝试开一场比赛，成功返回 true（场地已满/参数错误时返回 false）。 */
    public boolean startMatch(List<ServerPlayerEntity> players, MatchType type, Kit kit) {
        if (this.server == null) {
            return false;
        }
        if (players == null || players.size() != type.requiredPlayers()) {
            return false;
        }

        int maxConcurrent = Math.max(1, PvPConfig.INSTANCE.maxConcurrentMatches);
        if (this.matches.size() >= maxConcurrent) {
            for (ServerPlayerEntity player : players) {
                player.sendMessage(Messages.error("当前竞技场已满，请稍后再试"), false);
            }
            return false;
        }

        for (ServerPlayerEntity player : players) {
            if (this.getMatchFor(player.getUuid()) != null) {
                return false;
            }
        }

        int regionIndex = this.allocateRegion();
        ArenaTemplate template = this.createTemplate(type);
        Match match = Match.create(this, this.nextMatchId++, type, kit, players, regionIndex, template);
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
            match.eliminate(player, EliminationCause.DEATH);
        }
    }

    /** 玩家重生事件：若仍在对局中，转为旁观并送回观众平台。 */
    public void onPlayerRespawn(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer, boolean alive) {
        Match match = this.getMatchFor(newPlayer.getUuid());
        if (match != null && match.getState() == MatchState.ACTIVE) {
            match.makeSpectator(newPlayer);
        }
    }

    /** 玩家断线事件。 */
    public void onPlayerDisconnect(ServerPlayerEntity player) {
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

    private ArenaTemplate createTemplate(MatchType type) {
        int size = switch (type) {
            case DUEL_1V1 -> PvPConfig.INSTANCE.duel1v1Size;
            case DUEL_2V2 -> PvPConfig.INSTANCE.duel2v2Size;
            case FFA -> PvPConfig.INSTANCE.ffaSize;
        };
        ArenaTemplate.Layout layout = switch (type) {
            case DUEL_1V1 -> ArenaTemplate.Layout.DUEL_1V1;
            case DUEL_2V2 -> ArenaTemplate.Layout.DUEL_2V2;
            case FFA -> ArenaTemplate.Layout.FFA;
        };
        return new ArenaTemplate(layout, size, PvPConfig.INSTANCE.getFloorBlock(), PvPConfig.INSTANCE.getWallBlock());
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
                this.teleportToOverworldSpawn(player);
            } else if (inVoid) {
                if (match.getState() == MatchState.ACTIVE) {
                    match.eliminate(player, EliminationCause.VOID);
                } else {
                    match.teleportToSpawn(player);
                }
            }
        }
    }
}
