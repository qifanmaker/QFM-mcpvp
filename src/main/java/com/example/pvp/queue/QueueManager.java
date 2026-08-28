package com.example.pvp.queue;

import com.example.pvp.config.PvPConfig;
import com.example.pvp.gui.PvpGuiManager;
import com.example.pvp.kit.Kit;
import com.example.pvp.kit.KitManager;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchType;
import com.example.pvp.text.Messages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 匹配队列：按（模式, 套件）分组，凑齐人数自动开赛。
 */
public final class QueueManager {
    private final MinecraftServer server;
    private final List<QueueEntry> entries = new ArrayList<>();
    /** 自由乱斗开赛倒计时（tick 数）；null 表示未开始。 */
    private Integer ffaCountdownTicks;
    /** 空岛战争开赛倒计时 / 等待填人计时（tick 数）；null 表示未开始。 */
    private Integer skywarsCountdownTicks;
    private Integer skywarsFillTicks;
    /** 幸运之柱开赛倒计时 / 等待填人计时（tick 数）；null 表示未开始。 */
    private Integer luckyPillarCountdownTicks;
    private Integer luckyPillarFillTicks;
    /** TNT 跑酷开赛倒计时 / 等待填人计时（tick 数）；null 表示未开始。 */
    private Integer tntRunCountdownTicks;
    private Integer tntRunFillTicks;

    public QueueManager(MinecraftServer server) {
        this.server = server;
    }

    public boolean join(ServerPlayerEntity player, MatchType type, Kit kit) {
        if (this.contains(player.getUuid())) {
            return false;
        }
        this.entries.add(new QueueEntry(player, type, kit, this.server.getTicks()));
        PvpGuiManager.giveQueueItem(player); // 快捷栏第一格放「离开排队」红石
        return true;
    }

    public boolean leave(ServerPlayerEntity player) {
        boolean removed = this.leave(player.getUuid());
        if (removed) {
            PvpGuiManager.removeQueueItem(player);
        }
        return removed;
    }

    public boolean leave(UUID uuid) {
        return this.entries.removeIf(e -> e.getPlayer().getUuid().equals(uuid));
    }

    public boolean contains(UUID uuid) {
        for (QueueEntry entry : this.entries) {
            if (entry.getPlayer().getUuid().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    public QueueEntry getEntry(ServerPlayerEntity player) {
        for (QueueEntry entry : this.entries) {
            if (entry.getPlayer().getUuid().equals(player.getUuid())) {
                return entry;
            }
        }
        return null;
    }

    public List<QueueEntry> getEntries() {
        return List.copyOf(this.entries);
    }

    public int queuedCount(MatchType type, Kit kit) {
        int count = 0;
        for (QueueEntry entry : this.entries) {
            if (entry.getType() == type && entry.getKit().getId().equals(kit.getId())) {
                count++;
            }
        }
        return count;
    }

    /** 每个服务器 tick 调用：自由乱斗倒计时 + 非 FFA 即时凑齐开赛。 */
    public void tick(MatchManager matchManager) {
        // 自愈：清理已离线或已在比赛中的排队条目（防止残留状态导致无法再次开赛）
        this.entries.removeIf(e -> {
            ServerPlayerEntity online = matchManager.getOnlinePlayer(e.getPlayer().getUuid());
            return online == null || matchManager.isInMatch(e.getPlayer().getUuid());
        });

        this.tickFfa(matchManager);
        this.tickSkywars(matchManager);
        this.tickLuckyPillar(matchManager);
        this.tickTntRun(matchManager);
        this.tickInstantMatches(matchManager);
    }

    /** 自由乱斗：≥3 人开始倒计时，≥6 人缩短为 min(剩余,10s)，倒计时结束开赛。 */
    private void tickFfa(MatchManager matchManager) {
        PvPConfig config = PvPConfig.INSTANCE;
        long ffaCount = this.countFfa();

        if (this.ffaCountdownTicks == null) {
            if (ffaCount >= config.ffaMinPlayers) {
                this.ffaCountdownTicks = config.ffaCountdownSeconds * 20;
                this.broadcastFfa(matchManager, Messages.info(
                        "§e" + ffaCount + "§r 人已就绪，§e" + config.ffaCountdownSeconds + "§r 秒后开始自由乱斗（达 "
                                + config.ffaEarlyStartPlayers + " 人将加速）"));
            }
            return;
        }

        if (ffaCount >= config.ffaEarlyStartPlayers || ffaCount >= config.ffaMaxPlayers) {
            this.ffaCountdownTicks = Math.min(this.ffaCountdownTicks, config.ffaEarlyStartSeconds * 20);
        }

        this.ffaCountdownTicks--;
        if (this.ffaCountdownTicks > 0 && this.ffaCountdownTicks % 20 == 0) {
            this.broadcastFfa(matchManager, Messages.info(
                    "自由乱斗将在 §e" + (this.ffaCountdownTicks / 20) + "§r 秒后开始（当前 " + ffaCount + " 人）"));
        }

        if (this.ffaCountdownTicks <= 0) {
            this.ffaCountdownTicks = null;
            this.startFfaMatch(matchManager);
        }
    }

    /** 倒计时结束，把当前所有 FFA 玩家拉进同一场比赛（每人各自套件）。 */
    private void startFfaMatch(MatchManager matchManager) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        Map<UUID, Kit> kits = new HashMap<>();
        List<QueueEntry> toRemove = new ArrayList<>();

        for (QueueEntry entry : List.copyOf(this.entries)) {
            if (entry.getType() != MatchType.FFA) {
                continue;
            }
            ServerPlayerEntity online = matchManager.getOnlinePlayer(entry.getPlayer().getUuid());
            toRemove.add(entry);
            if (online != null) {
                players.add(online);
                kits.put(entry.getPlayer().getUuid(), entry.getKit());
            }
        }

        if (players.size() < PvPConfig.INSTANCE.ffaMinPlayers) {
            // 人数不足，清掉这些残留条目
            this.entries.removeAll(toRemove);
            return;
        }
        if (matchManager.startMatch(players, MatchType.FFA, kits)) {
            this.entries.removeAll(toRemove);
        }
        // 开赛失败（场地已满）则保留排队，等待下一轮倒计时
    }

    /**
     * 空岛战争队列：凑齐 startPlayers 人开始倒计时，达到 maxPlayers 立即开赛；
     * 2~startPlayers-1 人时进入等待填人计时，超时按当前人数开赛。
     */
    private void tickSkywars(MatchManager matchManager) {
        PvPConfig config = PvPConfig.INSTANCE;
        long count = this.countSkywars();

        if (this.skywarsCountdownTicks != null) {
            // 倒计时中，满员立即开赛
            this.skywarsCountdownTicks = count >= config.skywarsMaxPlayers
                    ? 0 : this.skywarsCountdownTicks - 1;
            if (this.skywarsCountdownTicks <= 0) {
                this.skywarsCountdownTicks = null;
                this.skywarsFillTicks = null;
                this.startSkywarsMatch(matchManager);
            }
            return;
        }

        if (count >= config.skywarsStartPlayers) {
            this.skywarsCountdownTicks = config.skywarsCountdownSeconds * 20;
            this.broadcastSkywars(matchManager, Messages.info(
                    "§e" + count + "§r 人已就绪，§e" + config.skywarsCountdownSeconds + "§r 秒后开始空岛战争！"));
            this.skywarsFillTicks = null;
            return;
        }

        // 人数在 minPlayers 与 startPlayers 之间：等待填人
        if (count >= config.skywarsMinPlayers) {
            if (this.skywarsFillTicks == null) {
                this.skywarsFillTicks = config.skywarsFillTimeoutSeconds * 20;
            }
            if (this.skywarsFillTicks % 40 == 0) {
                this.broadcastSkywars(matchManager, Messages.info(
                        "等待更多玩家加入空岛战争（当前 " + count + "/" + config.skywarsStartPlayers + "）..."));
            }
            this.skywarsFillTicks--;
            if (this.skywarsFillTicks <= 0) {
                this.skywarsFillTicks = null;
                this.startSkywarsMatch(matchManager);
            }
        } else {
            this.skywarsFillTicks = null;
        }
    }

    /** 开一场空岛战争：取队列前 maxPlayers 人，每人发哨兵套件（实际上空手开局）。 */
    private void startSkywarsMatch(MatchManager matchManager) {
        PvPConfig config = PvPConfig.INSTANCE;
        List<ServerPlayerEntity> players = new ArrayList<>();
        List<QueueEntry> toRemove = new ArrayList<>();
        Kit sentinel = KitManager.skywarsKit();
        if (sentinel == null) {
            return;
        }

        for (QueueEntry entry : List.copyOf(this.entries)) {
            if (entry.getType() != MatchType.SKYWARS) {
                continue;
            }
            if (players.size() >= config.skywarsMaxPlayers) {
                break;
            }
            toRemove.add(entry);
            ServerPlayerEntity online = matchManager.getOnlinePlayer(entry.getPlayer().getUuid());
            if (online != null) {
                players.add(online);
            }
        }

        if (players.size() < config.skywarsMinPlayers) {
            return; // 人数不足：保留排队，等待下一轮倒计时（离线残留由 tick 自愈清理）
        }

        Map<UUID, Kit> kits = new HashMap<>();
        for (ServerPlayerEntity player : players) {
            kits.put(player.getUuid(), sentinel);
        }
        if (matchManager.startMatch(players, MatchType.SKYWARS, kits)) {
            this.entries.removeAll(toRemove);
        }
        // 开赛失败（场地已满）则保留排队，等待下一轮
    }

    private long countSkywars() {
        return this.entries.stream().filter(e -> e.getType() == MatchType.SKYWARS).count();
    }

    private void broadcastSkywars(MatchManager matchManager, Text message) {
        for (QueueEntry entry : this.entries) {
            if (entry.getType() != MatchType.SKYWARS) {
                continue;
            }
            ServerPlayerEntity online = matchManager.getOnlinePlayer(entry.getPlayer().getUuid());
            if (online != null) {
                online.sendMessage(message, false);
            }
        }
    }

    /** 幸运之柱队列：与空岛战争相同——凑齐 startPlayers 倒计时、满 maxPlayers 立即开、之间等待填人。 */
    private void tickLuckyPillar(MatchManager matchManager) {
        PvPConfig config = PvPConfig.INSTANCE;
        long count = this.countLuckyPillar();

        if (this.luckyPillarCountdownTicks != null) {
            this.luckyPillarCountdownTicks = count >= config.luckyPillarMaxPlayers
                    ? 0 : this.luckyPillarCountdownTicks - 1;
            if (this.luckyPillarCountdownTicks <= 0) {
                this.luckyPillarCountdownTicks = null;
                this.luckyPillarFillTicks = null;
                this.startLuckyPillarMatch(matchManager);
            }
            return;
        }

        if (count >= config.luckyPillarStartPlayers) {
            this.luckyPillarCountdownTicks = config.luckyPillarCountdownSeconds * 20;
            this.broadcastLuckyPillar(matchManager, Messages.info(
                    "§e" + count + "§r 人已就绪，§e" + config.luckyPillarCountdownSeconds + "§r 秒后开始幸运之柱！"));
            this.luckyPillarFillTicks = null;
            return;
        }

        // 人数在 minPlayers 与 startPlayers 之间：等待填人
        if (count >= config.luckyPillarMinPlayers) {
            if (this.luckyPillarFillTicks == null) {
                this.luckyPillarFillTicks = config.luckyPillarFillTimeoutSeconds * 20;
            }
            if (this.luckyPillarFillTicks % 40 == 0) {
                this.broadcastLuckyPillar(matchManager, Messages.info(
                        "等待更多玩家加入幸运之柱（当前 " + count + "/" + config.luckyPillarStartPlayers + "）..."));
            }
            this.luckyPillarFillTicks--;
            if (this.luckyPillarFillTicks <= 0) {
                this.luckyPillarFillTicks = null;
                this.startLuckyPillarMatch(matchManager);
            }
        } else {
            this.luckyPillarFillTicks = null;
        }
    }

    /** 开一场幸运之柱：取队列前 maxPlayers 人，每人发哨兵套件（实际上空手开局）。 */
    private void startLuckyPillarMatch(MatchManager matchManager) {
        PvPConfig config = PvPConfig.INSTANCE;
        List<ServerPlayerEntity> players = new ArrayList<>();
        List<QueueEntry> toRemove = new ArrayList<>();
        Kit sentinel = KitManager.luckyPillarKit();
        if (sentinel == null) {
            return;
        }

        for (QueueEntry entry : List.copyOf(this.entries)) {
            if (entry.getType() != MatchType.LUCKY_PILLAR) {
                continue;
            }
            if (players.size() >= config.luckyPillarMaxPlayers) {
                break;
            }
            toRemove.add(entry);
            ServerPlayerEntity online = matchManager.getOnlinePlayer(entry.getPlayer().getUuid());
            if (online != null) {
                players.add(online);
            }
        }

        if (players.size() < config.luckyPillarMinPlayers) {
            return; // 人数不足：保留排队，等待下一轮倒计时（离线残留由 tick 自愈清理）
        }

        Map<UUID, Kit> kits = new HashMap<>();
        for (ServerPlayerEntity player : players) {
            kits.put(player.getUuid(), sentinel);
        }
        if (matchManager.startMatch(players, MatchType.LUCKY_PILLAR, kits)) {
            this.entries.removeAll(toRemove);
        }
        // 开赛失败（场地已满）则保留排队，等待下一轮
    }

    private long countLuckyPillar() {
        return this.entries.stream().filter(e -> e.getType() == MatchType.LUCKY_PILLAR).count();
    }

    private void broadcastLuckyPillar(MatchManager matchManager, Text message) {
        for (QueueEntry entry : this.entries) {
            if (entry.getType() != MatchType.LUCKY_PILLAR) {
                continue;
            }
            ServerPlayerEntity online = matchManager.getOnlinePlayer(entry.getPlayer().getUuid());
            if (online != null) {
                online.sendMessage(message, false);
            }
        }
    }

    /** TNT 跑酷队列：与幸运之柱相同——凑齐 startPlayers 倒计时、满 maxPlayers 立即开、之间等待填人。 */
    private void tickTntRun(MatchManager matchManager) {
        PvPConfig config = PvPConfig.INSTANCE;
        long count = this.countTntRun();

        if (this.tntRunCountdownTicks != null) {
            this.tntRunCountdownTicks = count >= config.tntRunMaxPlayers
                    ? 0 : this.tntRunCountdownTicks - 1;
            if (this.tntRunCountdownTicks <= 0) {
                this.tntRunCountdownTicks = null;
                this.tntRunFillTicks = null;
                this.startTntRunMatch(matchManager);
            }
            return;
        }

        if (count >= config.tntRunStartPlayers) {
            this.tntRunCountdownTicks = config.tntRunCountdownSeconds * 20;
            this.broadcastTntRun(matchManager, Messages.info(
                    "§e" + count + "§r 人已就绪，§e" + config.tntRunCountdownSeconds + "§r 秒后开始 TNT 跑酷！"));
            this.tntRunFillTicks = null;
            return;
        }

        if (count >= config.tntRunMinPlayers) {
            if (this.tntRunFillTicks == null) {
                this.tntRunFillTicks = config.tntRunFillTimeoutSeconds * 20;
            }
            if (this.tntRunFillTicks % 40 == 0) {
                this.broadcastTntRun(matchManager, Messages.info(
                        "等待更多玩家加入 TNT 跑酷（当前 " + count + "/" + config.tntRunStartPlayers + "）..."));
            }
            this.tntRunFillTicks--;
            if (this.tntRunFillTicks <= 0) {
                this.tntRunFillTicks = null;
                this.startTntRunMatch(matchManager);
            }
        } else {
            this.tntRunFillTicks = null;
        }
    }

    /** 开一场 TNT 跑酷：取队列前 maxPlayers 人，每人发哨兵套件（实际上空手开局）。 */
    private void startTntRunMatch(MatchManager matchManager) {
        PvPConfig config = PvPConfig.INSTANCE;
        List<ServerPlayerEntity> players = new ArrayList<>();
        List<QueueEntry> toRemove = new ArrayList<>();
        Kit sentinel = KitManager.tntRunKit();
        if (sentinel == null) {
            return;
        }

        for (QueueEntry entry : List.copyOf(this.entries)) {
            if (entry.getType() != MatchType.TNT_RUN) {
                continue;
            }
            if (players.size() >= config.tntRunMaxPlayers) {
                break;
            }
            toRemove.add(entry);
            ServerPlayerEntity online = matchManager.getOnlinePlayer(entry.getPlayer().getUuid());
            if (online != null) {
                players.add(online);
            }
        }

        if (players.size() < config.tntRunMinPlayers) {
            return; // 人数不足：保留排队，等待下一轮倒计时
        }

        Map<UUID, Kit> kits = new HashMap<>();
        for (ServerPlayerEntity player : players) {
            kits.put(player.getUuid(), sentinel);
        }
        if (matchManager.startMatch(players, MatchType.TNT_RUN, kits)) {
            this.entries.removeAll(toRemove);
        }
    }

    private long countTntRun() {
        return this.entries.stream().filter(e -> e.getType() == MatchType.TNT_RUN).count();
    }

    private void broadcastTntRun(MatchManager matchManager, Text message) {
        for (QueueEntry entry : this.entries) {
            if (entry.getType() != MatchType.TNT_RUN) {
                continue;
            }
            ServerPlayerEntity online = matchManager.getOnlinePlayer(entry.getPlayer().getUuid());
            if (online != null) {
                online.sendMessage(message, false);
            }
        }
    }

    /** 非 FFA/SkyWars/幸运之柱/TNT 跑酷（1v1/2v2/相扑/1.8）的即时凑齐开赛。 */
    private void tickInstantMatches(MatchManager matchManager) {
        Map<String, List<QueueEntry>> groups = new LinkedHashMap<>();
        for (QueueEntry entry : this.entries) {
            if (entry.getType() == MatchType.FFA || entry.getType() == MatchType.SKYWARS
                    || entry.getType() == MatchType.LUCKY_PILLAR || entry.getType() == MatchType.TNT_RUN) {
                continue;
            }
            groups.computeIfAbsent(entry.getType().getId() + "|" + entry.getKit().getId(), k -> new ArrayList<>()).add(entry);
        }

        for (List<QueueEntry> group : groups.values()) {
            if (group.isEmpty()) {
                continue;
            }
            MatchType type = group.get(0).getType();
            int required;
            if (type.isBridgeTeam()) {
                // 战桥混战：需偶数且 ≥ 最少人数，用当前全部人数开赛（总人数/2 分两队）
                if (group.size() < PvPConfig.INSTANCE.bridgeTeamMinPlayers || group.size() % 2 != 0) {
                    continue;
                }
                required = group.size();
            } else {
                required = type.requiredPlayers();
                if (group.size() < required) {
                    continue;
                }
            }

            List<ServerPlayerEntity> players = new ArrayList<>();
            List<QueueEntry> toRemove = new ArrayList<>();
            for (QueueEntry entry : group) {
                if (players.size() >= required) {
                    break;
                }
                ServerPlayerEntity online = matchManager.getOnlinePlayer(entry.getPlayer().getUuid());
                toRemove.add(entry); // 离线者也一并移出队列
                if (online != null) {
                    players.add(online);
                }
            }

            if (players.size() < required) {
                this.entries.removeAll(toRemove);
                continue;
            }

            // 2v2 / 战桥组队随机分队（1v1、1v1v1v1 洗牌不影响公平）
            if (type == MatchType.DUEL_2V2 || type.isBridge()) {
                Collections.shuffle(players);
            }

            Kit kit = group.get(0).getKit();
            if (matchManager.startMatch(players, type, kit)) {
                this.entries.removeAll(toRemove);
            }
        }
    }

    private long countFfa() {
        return this.entries.stream().filter(e -> e.getType() == MatchType.FFA).count();
    }

    private long countType(MatchType type) {
        return this.entries.stream().filter(e -> e.getType() == type).count();
    }

    /**
     * OP 强制立即开赛：用当前队列同组玩家直接开赛（跳过倒计时/等待填人）。
     * 人数不足时发消息并返回 false。
     */
    public boolean forceStart(MatchManager matchManager, ServerPlayerEntity player) {
        QueueEntry entry = this.getEntry(player);
        if (entry == null) {
            player.sendMessage(Messages.warn("你不在匹配队列中"), false);
            return false;
        }
        MatchType type = entry.getType();

        // 自由乱斗 / 空岛战争 / 幸运之柱 / TNT 跑酷：直接以当前队列所有人开赛
        if (type == MatchType.FFA || type == MatchType.SKYWARS || type == MatchType.LUCKY_PILLAR
                || type == MatchType.TNT_RUN) {
            int min = switch (type) {
                case FFA -> PvPConfig.INSTANCE.ffaMinPlayers;
                case SKYWARS -> PvPConfig.INSTANCE.skywarsMinPlayers;
                case LUCKY_PILLAR -> PvPConfig.INSTANCE.luckyPillarMinPlayers;
                default -> PvPConfig.INSTANCE.tntRunMinPlayers;
            };
            int count = (int) this.countType(type);
            if (count < min) {
                player.sendMessage(Messages.warn("当前 " + type.getDisplayName() + " 队列人数不足（" + count + "/" + min + "），无法立即开始"), false);
                return false;
            }
            this.ffaCountdownTicks = null;
            this.skywarsCountdownTicks = null;
            this.skywarsFillTicks = null;
            this.luckyPillarCountdownTicks = null;
            this.luckyPillarFillTicks = null;
            this.tntRunCountdownTicks = null;
            this.tntRunFillTicks = null;
            switch (type) {
                case FFA -> this.startFfaMatch(matchManager);
                case SKYWARS -> this.startSkywarsMatch(matchManager);
                case LUCKY_PILLAR -> this.startLuckyPillarMatch(matchManager);
                default -> this.startTntRunMatch(matchManager);
            }
            return true;
        }

        // 即时匹配模式（1v1/2v2/相扑/1.8/战桥）：取同 (模式+套件) 分组，人数够就开
        List<QueueEntry> group = new ArrayList<>();
        for (QueueEntry e : this.entries) {
            if (e.getType() == type && e.getKit().getId().equals(entry.getKit().getId())) {
                group.add(e);
            }
        }
        int required;
        if (type.isBridgeTeam()) {
            if (group.size() < PvPConfig.INSTANCE.bridgeTeamMinPlayers || group.size() % 2 != 0) {
                player.sendMessage(Messages.warn("当前 " + type.getDisplayName() + " 队列人数为 " + group.size()
                        + "，需要偶数且 ≥ " + PvPConfig.INSTANCE.bridgeTeamMinPlayers + "，无法立即开始"), false);
                return false;
            }
            required = group.size();
        } else {
            required = type.requiredPlayers();
        }
        if (group.size() < required) {
            player.sendMessage(Messages.warn("当前 " + type.getDisplayName() + " 队列人数不足（" + group.size() + "/" + required
                    + "），无法立即开始"), false);
            return false;
        }

        List<ServerPlayerEntity> players = new ArrayList<>();
        List<QueueEntry> toRemove = new ArrayList<>();
        for (QueueEntry e : group) {
            if (players.size() >= required) {
                break;
            }
            toRemove.add(e);
            ServerPlayerEntity online = matchManager.getOnlinePlayer(e.getPlayer().getUuid());
            if (online != null) {
                players.add(online);
            }
        }
        if (players.size() < required) {
            this.entries.removeAll(toRemove);
            player.sendMessage(Messages.warn("队列中有玩家离线，无法立即开始"), false);
            return false;
        }
        if (type == MatchType.DUEL_2V2 || type.isBridge()) {
            Collections.shuffle(players);
        }
        if (matchManager.startMatch(players, type, entry.getKit())) {
            this.entries.removeAll(toRemove);
            return true;
        }
        return false; // 场地已满等情况，保留排队
    }

    private void broadcastFfa(MatchManager matchManager, Text message) {
        for (QueueEntry entry : this.entries) {
            if (entry.getType() != MatchType.FFA) {
                continue;
            }
            ServerPlayerEntity online = matchManager.getOnlinePlayer(entry.getPlayer().getUuid());
            if (online != null) {
                online.sendMessage(message, false);
            }
        }
    }
}
