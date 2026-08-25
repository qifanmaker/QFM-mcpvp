package com.example.pvp.match;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.skywars.SkyWarsLayout;
import com.example.pvp.arena.skywars.SkyWarsMapGenerator;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.config.StatsStore;
import com.example.pvp.gui.PvpGuiManager;
import com.example.pvp.kit.InventorySnapshot;
import com.example.pvp.kit.Kit;
import com.example.pvp.kit.KitApplicator;
import com.example.pvp.text.Messages;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
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
    private final Kit kit; // 代表性套件（取第一名玩家），供展示用
    private final Map<UUID, Kit> playerKits; // 每名玩家各自的套件（FFA 混套件）
    private final List<ServerPlayerEntity> players;
    private final Map<UUID, InventorySnapshot> snapshots = new HashMap<>();
    private final Map<UUID, BlockPos> spawns = new HashMap<>();
    private final List<MatchTeam> teams;
    private final ArenaTemplate template;
    private final int regionIndex;
    private final Set<UUID> eliminated = new HashSet<>();
    private final Set<UUID> blocking = new HashSet<>(); // 1.8 模式剑格挡
    private final Set<UUID> leftEarly = new HashSet<>(); // 旁观者提前离场
    private final Set<String> infoLines = new HashSet<>(); // 计分板信息栏行
    private final int initialCountdownTicks;

    /** 空岛战争地图布局（仅在 SKYWARS 模式非空，缩圈用）。 */
    private final SkyWarsLayout skywarsLayout;
    private int skywarsShrinkStage; // 已执行的缩圈档数
    private int skywarsLastKeepRadius = Integer.MAX_VALUE; // 上一档安全半径

    private MatchTeam winnerTeam;
    private int celebrationTicks;
    private MatchState state;
    private int countdownTicks;
    private int ticks;

    private Match(MatchManager manager, int id, MatchType type,
                  List<ServerPlayerEntity> players, int regionIndex, ArenaTemplate template,
                  Map<UUID, Kit> kits) {
        this.manager = manager;
        this.id = id;
        this.type = type;
        this.playerKits = new HashMap<>(kits);
        this.kit = kits.get(players.get(0).getUuid());
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

        // 计算出生点（空岛战争用同一份确定性布局，保证与地图生成一致）
        List<BlockPos> spawnPositions;
        if (type == MatchType.SKYWARS) {
            this.skywarsLayout = SkyWarsLayout.compute(template.getCenter(regionIndex), id, this.players.size());
            spawnPositions = this.skywarsLayout.spawns();
        } else {
            this.skywarsLayout = null;
            spawnPositions = template.computeSpawns(regionIndex, this.players.size());
        }
        for (int i = 0; i < this.players.size(); i++) {
            this.spawns.put(this.players.get(i).getUuid(), spawnPositions.get(i));
        }

        this.state = MatchState.COUNTDOWN;
    }

    public static Match create(MatchManager manager, int id, MatchType type,
                               List<ServerPlayerEntity> players, int regionIndex, ArenaTemplate template,
                               Map<UUID, Kit> kits) {
        return new Match(manager, id, type, players, regionIndex, template, kits);
    }

    private static List<MatchTeam> buildTeams(MatchType type, List<ServerPlayerEntity> players) {
        List<MatchTeam> teams = new ArrayList<>();
        if (type == MatchType.DUEL_1V1 || type == MatchType.SUMO || type == MatchType.PVP_1_8) {
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
        int activeTimeout = this.type == MatchType.SKYWARS
                ? Math.max(100, PvPConfig.INSTANCE.skywarsTimeoutSeconds * 20)
                : Math.max(100, PvPConfig.INSTANCE.matchTimeoutSeconds * 20);
        if (this.state == MatchState.COUNTDOWN && this.ticks > countdownStuckThreshold) {
            LOGGER.warn("[PvP] 比赛 #{} 倒计时异常，强制取消", this.id);
            this.cancelMatch("倒计时异常");
            return;
        }
        if (this.state == MatchState.ACTIVE && this.ticks > this.initialCountdownTicks + activeTimeout) {
            LOGGER.warn("[PvP] 比赛 #{} 超时（{} 秒）强制平局结束", this.id, activeTimeout / 20);
            this.finishMatch(null);
            return;
        }

        // 每 20 tick 更新右侧信息栏
        if (this.ticks % 20 == 0) {
            this.updateInfoScoreboard();
        }

        switch (this.state) {
            case COUNTDOWN -> this.tickCountdown();
            case ACTIVE -> {
                if (this.type == MatchType.SUMO) {
                    this.checkSumoRingOut();
                }
                if (this.type == MatchType.PVP_1_8) {
                    this.tickLegacyBlocking();
                }
                if (this.type == MatchType.SKYWARS) {
                    this.tickSkywarsShrink();
                }
                this.checkWinCondition();
            }
            case CELEBRATING -> this.tickCelebration();
            default -> {
            }
        }
    }

    /** 胜利庆祝倒计时，结束后结算。 */
    private void tickCelebration() {
        this.celebrationTicks--;
        if (this.celebrationTicks <= 0) {
            this.finalizeMatch();
        }
    }

    /** 空岛战争缩圈：开赛一段时间后每隔 N 秒塌一圈，清掉圈外方块并淘汰圈外玩家。 */
    private void tickSkywarsShrink() {
        if (this.skywarsLayout == null) {
            return;
        }
        PvPConfig cfg = PvPConfig.INSTANCE;
        int elapsed = this.ticks - this.initialCountdownTicks;
        int startTick = cfg.skywarsShrinkStartSeconds * 20;
        if (elapsed < startTick) {
            return;
        }
        int stage = (elapsed - startTick) / (cfg.skywarsShrinkIntervalSeconds * 20) + 1;
        if (stage == this.skywarsShrinkStage) {
            return;
        }
        this.skywarsShrinkStage = stage;
        int keepRadius = this.skywarsLayout.maxRadius() - stage * cfg.skywarsShrinkBlocksPerStage;
        if (keepRadius < cfg.skywarsShrinkMinRadius) {
            keepRadius = cfg.skywarsShrinkMinRadius;
        }
        if (keepRadius >= this.skywarsLastKeepRadius) {
            return; // 已缩到最小安全半径，不再重复
        }
        this.skywarsLastKeepRadius = keepRadius;

        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena != null) {
            SkyWarsMapGenerator.removeRing(arena, this.skywarsLayout.mapCenter(),
                    this.skywarsLayout.maxRadius(), keepRadius);
        }
        this.broadcast(Messages.gold("§c§l缩圈！§r 安全区缩小到半径 §e" + keepRadius + "§r 格"));

        if (arena != null) {
            for (ServerPlayerEntity player : this.players) {
                if (this.eliminated.contains(player.getUuid())) {
                    continue;
                }
                ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
                if (online == null || online.getWorld() != arena) {
                    continue;
                }
                double dist = Math.hypot(online.getX() - this.skywarsLayout.mapCenter().getX(),
                        online.getZ() - this.skywarsLayout.mapCenter().getZ());
                if (dist > keepRadius) {
                    this.eliminate(online, EliminationCause.SHRINK);
                    online.sendMessage(Messages.error("你被缩圈淘汰了！"), false);
                }
            }
        }
    }

    /** 相扑：掉落到平台下方 20 格才淘汰（可用末影珍珠救回；不吃伤害，只吃击退）。 */
    private void checkSumoRingOut() {
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }
        double deathY = ArenaTemplate.PLATFORM_Y - 20;

        for (ServerPlayerEntity player : this.players) {
            if (this.eliminated.contains(player.getUuid())) {
                continue;
            }
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null) {
                continue;
            }
            if (online.getY() < deathY) {
                this.eliminate(online, EliminationCause.RING_OUT);
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
            // 空岛战争：被淘汰时把装备掉落在地（供击杀者拾取），再转旁观
            if (this.type == MatchType.SKYWARS) {
                this.dropSkywarsLoot(online);
            }
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
            this.removeInfoScoreboard();
        } catch (Exception e) {
            LOGGER.error("[PvP] 比赛取消处理出错", e);
        } finally {
            this.cleanupArenaAndRelease();
        }
    }

    /** 胜负结算：进入 5 秒庆祝（烟花 + 大字），随后恢复状态并清理。 */
    public void finishMatch(MatchTeam winnerTeam) {
        if (this.state == MatchState.ENDED || this.state == MatchState.CELEBRATING) {
            return;
        }
        this.winnerTeam = winnerTeam;
        this.celebrationTicks = 100; // 5 秒庆祝
        this.state = MatchState.CELEBRATING;
        this.startCelebration(winnerTeam);
    }

    private void startCelebration(MatchTeam winnerTeam) {
        Set<UUID> winners = new HashSet<>();
        if (winnerTeam != null) {
            for (ServerPlayerEntity player : winnerTeam.getAlivePlayers()) {
                winners.add(player.getUuid());
            }
        }
        this.announceResult(winners);

        // 获胜大字（动作栏）
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online != null) {
                if (winners.contains(player.getUuid())) {
                    online.sendMessage(Text.literal("§6§l你赢了！"), true);
                } else {
                    online.sendMessage(Text.literal("§c§l你输了！"), true);
                }
            }
        }

        this.spawnCelebrationFireworks(winners);
    }

    /** 庆祝结束后的实际结算：恢复、战绩、清场、释放。 */
    private void finalizeMatch() {
        Set<UUID> winners = new HashSet<>();
        if (this.winnerTeam != null) {
            for (ServerPlayerEntity player : this.winnerTeam.getAlivePlayers()) {
                winners.add(player.getUuid());
            }
        }
        try {
            this.restoreAllPlayers();
            this.removeScoreboardTeams();
            this.removeInfoScoreboard();
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
        this.state = MatchState.ENDED;
    }

    /** 在胜利玩家头顶生成向上飞行的烟花。 */
    private void spawnCelebrationFireworks(Set<UUID> winners) {
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }
        for (UUID uuid : winners) {
            ServerPlayerEntity winner = this.manager.getOnlinePlayer(uuid);
            if (winner == null || winner.getWorld() != arena) {
                continue;
            }
            this.spawnFirework(arena, winner.getX(), winner.getY() + 2, winner.getZ());
            this.spawnFirework(arena, winner.getX() + 1, winner.getY() + 3, winner.getZ() + 1);
            this.spawnFirework(arena, winner.getX() - 1, winner.getY() + 3, winner.getZ() - 1);
        }
    }

    private void spawnFirework(ArenaWorld world, double x, double y, double z) {
        IntList colors = IntList.of(0xFFD700, 0xFF0000, 0x00FF00);
        FireworkExplosionComponent explosion = new FireworkExplosionComponent(
                FireworkExplosionComponent.Type.BURST, colors, IntList.of(), false, true);
        FireworksComponent fireworks = new FireworksComponent(2, List.of(explosion));
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        stack.set(DataComponentTypes.FIREWORKS, fireworks);
        FireworkRocketEntity rocket = new FireworkRocketEntity(world, x, y, z, stack);
        world.spawnEntity(rocket);
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

    /** 空岛战争：把玩家背包/护甲/副手物品以掉落物形式丢在原地（死后装备可被拾取）。 */
    private void dropSkywarsLoot(ServerPlayerEntity player) {
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }
        var inventory = player.getInventory();
        double x = player.getX();
        double y = player.getY() + 0.5;
        double z = player.getZ();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemEntity item = new ItemEntity(arena, x, y, z, stack.copy());
            item.setVelocity((Math.random() - 0.5) * 0.3, 0.2, (Math.random() - 0.5) * 0.3);
            arena.spawnEntity(item);
        }
        inventory.clear();
    }

    /** 将玩家转为旁观者并传送到观众平台（淘汰/重生兜底），发放观战 UI。 */
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
        this.giveSpectatorItems(player);
    }

    /** 给旁观者发放观战 UI：左指南针 / 中绿宝石 / 右红石。 */
    public void giveSpectatorItems(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setStack(0, PvpGuiManager.spectatorCompass());
        inventory.setStack(4, PvpGuiManager.spectatorEmerald());
        inventory.setStack(8, PvpGuiManager.spectatorRedstone());
        player.currentScreenHandler.sendContentUpdates();
    }

    /** 旁观者：切换到下一个存活玩家观战。 */
    public void cycleSpectate(ServerPlayerEntity player) {
        List<ServerPlayerEntity> targets = new ArrayList<>();
        for (MatchTeam team : this.teams) {
            for (ServerPlayerEntity p : team.getAlivePlayers()) {
                ServerPlayerEntity online = this.manager.getOnlinePlayer(p.getUuid());
                if (online != null) {
                    targets.add(online);
                }
            }
        }
        if (targets.isEmpty()) {
            player.sendMessage(Messages.warn("当前没有可观战的目标"), false);
            return;
        }
        Entity current = player.getCameraEntity();
        int idx = -1;
        if (current != null) {
            for (int i = 0; i < targets.size(); i++) {
                if (targets.get(i) == current) {
                    idx = i;
                    break;
                }
            }
        }
        ServerPlayerEntity next = targets.get((idx + 1) % targets.size());
        player.setCameraEntity(next);
        player.sendMessage(Messages.info("正在观战 §e" + next.getGameProfile().getName()), false);
    }

    /** 旁观者提前离场：requeue=true 立即重排当前模式，false 回主城。 */
    public void spectatorLeave(ServerPlayerEntity player, boolean requeue) {
        if (!this.leftEarly.add(player.getUuid())) {
            return;
        }
        InventorySnapshot snapshot = this.snapshots.get(player.getUuid());
        if (snapshot != null) {
            snapshot.restore(player);
        } else {
            this.manager.teleportToOverworldSpawn(player);
        }
        if (requeue) {
            Kit kit = this.playerKits.get(player.getUuid());
            if (kit != null && PvPMod.QUEUE != null) {
                PvPMod.QUEUE.join(player, this.type, kit);
                player.sendMessage(Messages.info("已重新进入匹配队列：模式 " + this.type.getDisplayName()), false);
            }
        }
    }

    /** 1.8 模式：玩家是否正在剑格挡。 */
    public boolean isBlocking(ServerPlayerEntity player) {
        return this.blocking.contains(player.getUuid());
    }

    /** 1.8 模式：切换剑格挡状态（格挡时减速）。 */
    public void setBlocking(ServerPlayerEntity player, boolean block) {
        if (block) {
            this.blocking.add(player.getUuid());
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 200, 1));
        } else {
            this.blocking.remove(player.getUuid());
            player.removeStatusEffect(StatusEffects.SLOWNESS);
        }
    }

    /** 每帧维护 1.8 格挡：不拿剑则取消，格挡则保持减速。 */
    private void tickLegacyBlocking() {
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null) {
                continue;
            }
            boolean isBlocking = this.blocking.contains(player.getUuid());
            boolean holdingSword = online.getMainHandStack().getItem() instanceof SwordItem;
            if (isBlocking && !holdingSword) {
                this.setBlocking(online, false);
            } else if (isBlocking) {
                online.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 200, 1));
            }
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
        this.manager.getArenaManager().buildArena(this.regionIndex, this.template, this.id, this.players.size());
        ArenaWorld arena = this.manager.getArenaManager().getWorld();

        boolean skywars = this.type == MatchType.SKYWARS;
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null) {
                continue;
            }
            BlockPos spawn = this.spawns.get(player.getUuid());
            float yaw = this.faceCenter(spawn);
            online.teleport(arena, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, yaw, 0);
            if (skywars) {
                // 空岛战争：无套件，生存模式空手开局，开箱搜刮装备
                online.getInventory().clear();
                online.setHealth(online.getMaxHealth());
                online.getHungerManager().setFoodLevel(20);
                online.getHungerManager().setSaturationLevel(5f);
                online.setAbsorptionAmount(0);
                online.setFireTicks(0);
                online.fallDistance = 0;
                online.clearStatusEffects();
                online.changeGameMode(GameMode.SURVIVAL);
                online.currentScreenHandler.sendContentUpdates();
            } else {
                Kit playerKit = this.playerKits.get(player.getUuid());
                if (playerKit == null) {
                    playerKit = this.kit;
                }
                KitApplicator.apply(online, playerKit);
            }
            online.setInvulnerable(true);
            online.setNoGravity(false);
        }

        this.createScoreboardTeams();
        if (skywars) {
            this.broadcast(Messages.info("空岛战争开始！搜刮空岛，成为最后幸存者！"));
        } else {
            this.broadcast(Messages.info("对局开始！模式：" + this.type.getDisplayName() + "，套件：" + this.kit.getDisplayName()));
        }
    }

    private void checkWinCondition() {
        MatchTeam winner = this.computeWinner();
        if (winner != null) {
            this.finishMatch(winner);
        }
    }

    private MatchTeam computeWinner() {
        if (this.type == MatchType.FFA || this.type == MatchType.SKYWARS) {
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
        if (this.type == MatchType.FFA || this.type == MatchType.SKYWARS) {
            ServerPlayerEntity winner = this.getWinnerPlayer(winners);
            if (winner != null) {
                String modeName = this.type == MatchType.SKYWARS ? "空岛战争" : "自由乱斗";
                this.broadcast(Messages.gold("§6" + winner.getGameProfile().getName() + "§r 在" + modeName + "中获胜！"));
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
            if (this.leftEarly.contains(player.getUuid())) {
                continue; // 旁观者已提前恢复
            }
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

    // ---------- 右侧信息栏（计分板） ----------

    private static final String INFO_OBJECTIVE = "pvp_info";

    public void updateInfoScoreboard() {
        MinecraftServer server = this.manager.getServer();
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(INFO_OBJECTIVE);
        if (objective == null) {
            objective = scoreboard.addObjective(INFO_OBJECTIVE, ScoreboardCriterion.DUMMY,
                    Text.literal("§6§lPvP 对局"), ScoreboardCriterion.RenderType.INTEGER, true, null);
        }
        scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, objective);

        for (String line : this.infoLines) {
            scoreboard.removeScore(ScoreHolder.fromName(line), objective);
        }
        this.infoLines.clear();

        int score = 10;
        this.setInfoLine(scoreboard, objective, "模式: " + this.type.getDisplayName(), score--);
        if (this.type == MatchType.SKYWARS) {
            this.setInfoLine(scoreboard, objective, "装备: 开箱获得", score--);
        } else {
            Kit displayKit = this.kit;
            this.setInfoLine(scoreboard, objective, "套件: " + (displayKit == null ? "-" : displayKit.getDisplayName()), score--);
        }
        if (this.type == MatchType.FFA || this.type == MatchType.SKYWARS) {
            int alive = this.teams.isEmpty() ? 0 : this.teams.get(0).aliveCount();
            this.setInfoLine(scoreboard, objective, "存活: " + alive + "/" + this.players.size(), score--);
        } else {
            for (MatchTeam team : this.teams) {
                this.setInfoLine(scoreboard, objective, team.getName() + ": " + team.aliveCount(), score--);
            }
        }
    }

    private void setInfoLine(Scoreboard scoreboard, ScoreboardObjective objective, String text, int score) {
        scoreboard.getOrCreateScore(ScoreHolder.fromName(text), objective).setScore(score);
        this.infoLines.add(text);
    }

    private void removeInfoScoreboard() {
        MinecraftServer server = this.manager.getServer();
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(INFO_OBJECTIVE);
        if (objective == null) {
            return;
        }
        for (String line : this.infoLines) {
            scoreboard.removeScore(ScoreHolder.fromName(line), objective);
        }
        this.infoLines.clear();
        // 若还有其他对局在跑，保留侧边栏供其继续显示
        boolean otherRunning = this.manager.getMatches().stream()
                .anyMatch(m -> m != this && m.getState() != MatchState.ENDED);
        if (!otherRunning && scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR) == objective) {
            scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, null);
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
