package com.example.pvp.arena.race;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchState;
import com.example.pvp.text.Messages;
import com.mojang.logging.LogUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「亦可赛艇」的比赛运行时：一场 Match 一个实例，状态全部按 UUID 归属，天然支持多场并发。
 *
 * <p><b>与 MightyRacingMod 的关系</b>：竞速玩法（Checkpoint 顺序守卫、圈数、起终点线、计时、
 * 排名、最快单圈）参考它的思路；但它的实现是"命令方块点判定 + 服务器全局静态状态 + 记分板当排名存储"，
 * 本模式把这三件事分别换成了：
 * <ul>
 *   <li>服务器权威的<b>扫掠式平面穿越</b>（见 {@link RaceProgressTracker}），船速再快也不漏门；</li>
 *   <li><b>每场实例隔离</b>的状态（本类 + Match），多场比赛互不干扰；</li>
 *   <li>连续进度标量 + 完整排序（而不是"只在过门时把一个人往上下挪一格"）。</li>
 * </ul>
 *
 * <p>它没有、而本模式补上的：<b>出生格位</b>（原版船发车排队）、<b>掉出赛道/船被毁后回位到最近
 * Checkpoint</b>、<b>发车锁定</b>、<b>行动栏 HUD</b>、<b>与 Match 生命周期/清场的对接</b>。
 */
public final class BoatRaceSession {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 排名重算间隔（tick）。 */
    private static final int PLACE_INTERVAL = 5;
    /** 行动栏刷新间隔（tick）。 */
    private static final int HUD_INTERVAL = 4;
    /** 异常状态检查间隔（tick）。 */
    private static final int RECOVERY_INTERVAL = 10;
    /** 判定"静止卡住"的速度阈值（格/tick）。 */
    private static final double STUCK_SPEED = 0.03;
    /** 掉到冰面下方这么多格就算掉出赛道（内场地面在 -10，所以留足余量）。 */
    private static final int FALL_THRESHOLD = 18;
    /** 回位后把玩家放在最近 Checkpoint 之后这么多格，避免正好压在门框上。 */
    private static final double RESPAWN_AHEAD = 3.0;

    private final Match match;
    private final ArenaTemplate template;
    private final int regionIndex;
    private final int playerCount;
    private final int laps;
    private final RaceTrack track;
    private final List<String> generationNotes;

    private final Map<UUID, RaceProgressTracker> racers = new LinkedHashMap<>();
    private final Map<UUID, UUID> boats = new HashMap<>();
    private final Map<UUID, Long> recoveryCooldownUntil = new HashMap<>();
    private final Map<UUID, Integer> stuckTicks = new HashMap<>();
    private final Map<UUID, Integer> outOfBoatTicks = new HashMap<>();
    private final List<UUID> finishOrder = new ArrayList<>();

    private boolean started;
    private boolean mapBuilt;
    private boolean matchEnded;
    private int tickCounter;
    private List<UUID> ranking = List.of();

    public BoatRaceSession(Match match, ArenaTemplate template, int regionIndex, long seed, int playerCount) {
        this.match = match;
        this.template = template;
        this.regionIndex = regionIndex;
        this.playerCount = Math.max(1, playerCount);
        PvPConfig cfg = PvPConfig.INSTANCE;
        this.laps = Math.max(1, cfg.boatRaceLaps);

        RaceTrackGenerator.Settings settings = new RaceTrackGenerator.Settings(
                cfg.boatRaceMinTrackLength, cfg.boatRaceMaxTrackLength, cfg.boatRaceTargetTrackLength,
                cfg.boatRaceTrackWidth, cfg.boatRaceMinCornerRadius, cfg.boatRaceMinClearance,
                cfg.boatRaceRunoffWidth, cfg.boatRaceBarrierHeight,
                cfg.boatRaceCheckpoints, cfg.boatRaceMaxGenerationAttempts, cfg.boatRaceEnableRandomTrack,
                this.playerCount,
                template.getCenter(regionIndex).getX() + 0.5,
                template.getCenter(regionIndex).getZ() + 0.5,
                ArenaTemplate.PLATFORM_Y);

        long started = System.nanoTime();
        RaceTrackGenerator.Outcome outcome = RaceTrackGenerator.generate(seed, settings);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        this.track = outcome.track();
        this.generationNotes = outcome.notes();

        // Seed 是最重要的可复现信息：同一 Seed + 同一配置必然生成完全相同的赛道。
        LOGGER.info("[PvP] 亦可赛艇 赛道生成完毕 race seed: {} (请求 seed={}, 候选={}, 兜底={}, 耗时={} ms)",
                this.track.seed(), seed, outcome.attempts(), outcome.usedFallback(), elapsedMs);
        for (String note : outcome.notes()) {
            LOGGER.info("[PvP] 亦可赛艇 seed {}: {}", this.track.seed(), note);
        }
        if (outcome.usedFallback()) {
            LOGGER.warn("[PvP] 亦可赛艇 seed {} 使用了兜底赛道（随机生成全部候选均未通过校验）", this.track.seed());
        }
    }

    // ==================== Match 调用的接口 ====================

    public long seed() {
        return this.track.seed();
    }

    public RaceTrack track() {
        return this.track;
    }

    public int laps() {
        return this.laps;
    }

    public int checkpointCount() {
        return this.track.checkpointCount();
    }

    public List<String> generationNotes() {
        return this.generationNotes;
    }

    /** 起跑格位（转成 BlockPos 供 Match 的 spawns 表使用）。 */
    public List<BlockPos> spawnPositions() {
        List<BlockPos> list = new ArrayList<>(this.track.grid().size());
        for (RaceTrack.GridSlot slot : this.track.grid()) {
            list.add(new BlockPos((int) Math.floor(slot.x()), this.track.surfaceY() + 1,
                    (int) Math.floor(slot.z())));
        }
        return list;
    }

    /**
     * 铺图第一阶段（由 {@code Match.setupPlayers} 在暂存状态下调用）：<b>只生成赛道、不写世界</b>。
     *
     * <p>{@link RaceMapGenerator#build} 里的 {@code arena.setBlockState} / {@code spawnEntity}
     * 会被 {@code ArenaWorld} 记进暂存队列，真正的落盘由 {@code Match.tickBuild()} 分帧做 ——
     * 34000 多个方块因此从"一帧 1 秒"变成"几十帧每帧十毫秒"。
     */
    public void stageMap() {
        ArenaWorld arena = this.match.arenaWorld();
        if (arena == null) {
            LOGGER.error("[PvP] 亦可赛艇：竞技场世界未就绪");
            return;
        }
        long t0 = System.nanoTime();
        int staged = RaceMapGenerator.build(arena, this.track);
        this.mapBuilt = true;
        LOGGER.info("[PvP] 亦可赛艇 seed {} 暂存 {} 个方块（{} ms），{} 道门（含起终点线），起跑格位 {} 个",
                this.track.seed(), staged, (System.nanoTime() - t0) / 1_000_000L,
                this.track.checkpoints().size(), this.track.grid().size());

        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            this.racers.put(player.getUuid(), new RaceProgressTracker(this.track, this.laps));
        }
    }

    /**
     * 铺图第二阶段（方块全部落盘后由 {@code Match.finishSetup} 调用）：
     * 把所有人放到起跑格位、发船并上船，然后播报赛道信息。
     *
     * <p>必须等到场地真的存在 —— 提前传送会让人直接掉进虚空。
     */
    public void finishPrepare() {
        ArenaWorld arena = this.match.arenaWorld();
        if (arena == null) {
            LOGGER.error("[PvP] 亦可赛艇：竞技场世界未就绪，取消比赛");
            this.match.cancelMatch("竞技场世界未就绪");
            return;
        }
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            if (!this.racers.containsKey(player.getUuid())) {
                this.racers.put(player.getUuid(), new RaceProgressTracker(this.track, this.laps));
            }
            this.placeOnGrid(player);
        }

        this.match.broadcastToMatch(Messages.gold("🏁 亦可赛艇 —— 驾驶原版船，在随机冰面赛道上跑 "
                + this.laps + " 圈！"));
        this.match.broadcastToMatch(Messages.info("赛道：长 §e" + Math.round(this.track.length())
                + "§r 格 / 宽 §e" + Math.round(this.track.width())
                + "§r 格 / §e" + this.track.checkpointCount() + "§r 个 Checkpoint"
                + "｜最小弯半径 §e" + Math.round(this.track.minCornerRadius()) + "§r 格"
                + "｜Seed §7" + this.track.seed()));
        this.match.broadcastToMatch(Messages.info("按顺序穿过所有 Checkpoint 才算一圈；"
                + "掉出赛道或船被毁会自动送回最近 Checkpoint。GO 之前不要松开方向键 :)"));
    }

    /** 倒计时期间调用：把每条船钉在格位上（速度清零 + 位置/朝向回写），防止抢跑。 */
    public void lockToGrid() {
        if (this.started) {
            return;
        }
        ArenaWorld arena = this.match.arenaWorld();
        if (arena == null) {
            return;
        }
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            RaceTrack.GridSlot slot = this.slotOf(player.getUuid());
            if (slot == null) {
                continue;
            }
            BoatEntity boat = this.boatOf(player);
            if (boat == null) {
                this.placeOnGrid(player);
                continue;
            }
            boat.setVelocity(Vec3d.ZERO);
            boat.velocityDirty = true;
            boat.refreshPositionAndAngles(slot.x(), this.track.surfaceY() + 1.0, slot.z(), slot.yaw(), 0.0F);
        }
    }

    /** GO：开始计时。 */
    public void start(int matchTicks) {
        if (this.started) {
            return;
        }
        this.started = true;
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            RaceProgressTracker tracker = this.racers.get(player.getUuid());
            if (tracker != null) {
                tracker.arm(player.getX(), player.getZ(), matchTicks);
            }
        }
        this.updateRanking();
    }

    /** 每 tick 调用（仅 ACTIVE）。 */
    public void tick(int matchTicks) {
        if (this.matchEnded) {
            return;
        }
        this.tickCounter++;
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            this.tickRacer(player, matchTicks);
        }
        if (this.tickCounter % PLACE_INTERVAL == 0) {
            this.updateRanking();
        }
        if (this.tickCounter % HUD_INTERVAL == 0) {
            this.updateHud(matchTicks);
        }
        if (this.tickCounter % RECOVERY_INTERVAL == 0) {
            this.checkAnomalies(matchTicks);
        }
        if (this.tickCounter % (PLACE_INTERVAL * 20) == 0) {
            // 每 5 秒做一次自检：兜底补门是"漏判"的信号，出现了就要看赛道/判定是不是有问题
            for (Map.Entry<UUID, RaceProgressTracker> e : this.racers.entrySet()) {
                if (e.getValue().forcedGateAdvances() > 0) {
                    LOGGER.warn("[PvP] 亦可赛艇 seed {}：{} 触发了 {} 次兜底补门（扫掠判定漏判）",
                            this.track.seed(), e.getKey(), e.getValue().forcedGateAdvances());
                }
            }
        }
        if (this.started && !this.matchEnded && this.allFinished()) {
            this.match.broadcastToMatch(Messages.gold("所有选手都已冲线，比赛结束！"));
            this.matchEnded = true;
            this.match.finishMatch(null);
        }
    }

    /** 比赛结束（庆祝阶段开始）时调用：清掉场上的船，别让它们留到下一场。 */
    public void onMatchEnd() {
        this.matchEnded = true;
        for (UUID boatId : List.copyOf(this.boats.values())) {
            Entity entity = this.findEntity(boatId);
            if (entity != null) {
                entity.discard();
            }
        }
        this.boats.clear();
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            if (player.getVehicle() instanceof BoatEntity) {
                player.stopRiding();
            }
        }
    }

    /** 精确清除赛道地形（在延迟清场那一帧调用）。 */
    public void clearArena() {
        if (!this.mapBuilt) {
            return;
        }
        ArenaWorld arena = this.match.arenaWorld();
        if (arena == null) {
            return;
        }
        long t0 = System.nanoTime();
        int removed = RaceMapGenerator.clear(arena, this.track);
        this.mapBuilt = false;
        LOGGER.info("[PvP] 亦可赛艇 seed {} 清除 {} 个方块（{} ms）",
                this.track.seed(), removed, (System.nanoTime() - t0) / 1_000_000L);
        for (Map.Entry<UUID, RaceProgressTracker> e : this.racers.entrySet()) {
            RaceProgressTracker tracker = e.getValue();
            String progressText = tracker.finished()
                    ? ("完赛，用时 " + RaceProgressTracker.formatTicks(tracker.finishTicks())
                            + "，第 " + tracker.place() + " 名")
                    : ("第 " + tracker.currentLap() + " 圈 CP " + tracker.checkpointsPassed()
                            + "/" + this.track.checkpointCount() + "，进度 "
                            + Math.round(tracker.progress()) + "/" + Math.round(this.laps * this.track.length()));
            LOGGER.info("[PvP] 亦可赛艇 seed {} 选手 {}：{}｜回位 {} 次｜兜底补门 {} 次",
                    this.track.seed(), e.getKey(), progressText,
                    tracker.recoveries(), tracker.forcedGateAdvances());
        }
    }

    // ==================== 玩家状态 ====================

    /**
     * 把一名玩家放回最近 Checkpoint（重发一条新船）。
     *
     * <p>触发来源：掉出赛道、掉进虚空、船被毁、离开赛道过远、连续静止（卡在护栏上）、阵亡。
     * 每次回位都会重置这一圈的计时（回位是"重新出发"，不能让玩家白赚/白亏圈时间），
     * 但<b>不会</b>动已完成的圈数与已通过的门。
     */
    public void recover(ServerPlayerEntity player, String reason) {
        if (this.matchEnded) {
            return;
        }
        ArenaWorld arena = this.match.arenaWorld();
        RaceProgressTracker tracker = this.racers.get(player.getUuid());
        if (arena == null || tracker == null) {
            return;
        }
        if (tracker.finished()) {
            return;
        }
        RaceTrack.Checkpoint home = this.track.checkpoint(tracker.lastGate());
        double x = home.x() + home.dirX() * RESPAWN_AHEAD;
        double z = home.z() + home.dirZ() * RESPAWN_AHEAD;
        double y = this.track.surfaceY() + 1.0;
        // 回位点有可能落在门框/护栏方块里（门架压在护栏线上），往上抬一格保底
        BlockPos probe = new BlockPos((int) Math.floor(x), this.track.surfaceY() + 1, (int) Math.floor(z));
        if (!arena.getBlockState(probe).getCollisionShape(arena, probe).isEmpty()) {
            y += 1.0;
        }

        this.discardBoat(player);
        tracker.snapTo(x, z);
        if (this.started) {
            tracker.onRecovered(this.match.matchTicks());
        }
        player.stopRiding();
        player.teleport(arena, x, y, z, RaceTrackGenerator.yawOf(home.dirX(), home.dirZ()), 0.0F);
        player.setVelocity(Vec3d.ZERO);
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.fallDistance = 0;
        player.clearStatusEffects();
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5f);
        if (player.interactionManager.getGameMode() == GameMode.SPECTATOR) {
            player.changeGameMode(GameMode.SURVIVAL);
        }
        this.spawnBoatFor(player, x, y, z, RaceTrackGenerator.yawOf(home.dirX(), home.dirZ()));
        this.recoveryCooldownUntil.put(player.getUuid(),
                this.match.matchTicks() + PvPConfig.INSTANCE.boatRaceRecoveryCooldownSeconds * 20L);
        this.stuckTicks.put(player.getUuid(), 0);
        this.outOfBoatTicks.put(player.getUuid(), 0);
        if (player.networkHandler != null) {
            player.sendMessage(Messages.warn("已送回最近 Checkpoint（" + reason + "）"), true);
        }
        // 回位是"异常状态"事件，通常整场也就几次；打日志方便事后判断赛道哪里有问题
        // （例如同一名玩家在同一个 Checkpoint 反复回位 = 那个弯太急或护栏有缺口）。
        LOGGER.info("[PvP] 亦可赛艇 seed {} 回位：{}（{}）第 {} 圈 CP {}/{} 进度 {}/{}",
                this.track.seed(), player.getGameProfile().getName(), reason,
                tracker.currentLap(), tracker.checkpointsPassed(), this.track.checkpointCount(),
                Math.round(tracker.progress()), Math.round(this.laps * this.track.length()));
    }

    /** 阵亡（ALLOW_DEATH 转发）：当作一次回位，不淘汰。 */
    public void onDeath(ServerPlayerEntity player) {
        this.recover(player, "阵亡");
    }

    /**
     * 掉线退赛：回收他的船并从名次表里摘掉。
     *
     * <p>必须真的移除，否则 {@link #allFinished()}（"全员冲线"这个结束条件）永远不会成立，
     * 比赛只能干等到超时。Match 侧仍会按参赛名单给他记一场败场（见 Match.finalizeMatch）。
     */
    public void onDisconnect(ServerPlayerEntity player) {
        this.discardBoat(player);
        this.racers.remove(player.getUuid());
        this.stuckTicks.remove(player.getUuid());
        this.outOfBoatTicks.remove(player.getUuid());
        this.recoveryCooldownUntil.remove(player.getUuid());
        this.updateRanking();
    }

    // ==================== 每 tick 推进 ====================

    private void tickRacer(ServerPlayerEntity player, int matchTicks) {
        RaceProgressTracker tracker = this.racers.get(player.getUuid());
        if (tracker == null || tracker.finished()) {
            return;
        }
        if (!this.started) {
            tracker.snapTo(player.getX(), player.getZ());
            return;
        }
        RaceProgressTracker.Event event = tracker.update(
                player.getX(), player.getY(), player.getZ(), matchTicks);
        switch (event) {
            case CHECKPOINT -> {
                // 单个 Checkpoint 不刷屏，只在 HUD 里体现进度
            }
            case LAP -> {
                this.match.broadcastToMatch(Messages.info("§e" + player.getGameProfile().getName()
                        + "§r 完成第 §e" + tracker.lapsCompleted() + "§r/§e" + this.laps
                        + "§r 圈（本圈 §e" + RaceProgressTracker.formatTicks(tracker.lastLapTicks()) + "§r）"));
            }
            case FINISH -> this.onRacerFinished(player, tracker);
            default -> {
            }
        }
    }

    private void onRacerFinished(ServerPlayerEntity player, RaceProgressTracker tracker) {
        this.finishOrder.add(player.getUuid());
        this.discardBoat(player);
        this.updateRanking();
        int place = tracker.place();
        this.match.broadcastToMatch(Messages.gold("🏁 §e" + player.getGameProfile().getName()
                + "§r 冲线！第 §e" + place + "§r 名，用时 §e"
                + RaceProgressTracker.formatTicks(tracker.finishTicks())
                + (tracker.bestLapTicks() >= 0
                ? "§r（最快单圈 " + RaceProgressTracker.formatTicks(tracker.bestLapTicks()) + "）" : "")));
        // 完赛者转旁观去观战，船留在赛道上会挡人
        player.stopRiding();
        this.match.makeGhost(player);
        if (player.networkHandler != null) {
            player.sendMessage(Text.literal("§6§l🏁 你完成了比赛！§r 名次 §e#" + place
                    + "§r 用时 §e" + RaceProgressTracker.formatTicks(tracker.finishTicks())), false);
        }
    }

    /**
     * 异常状态检查（每 {@link #RECOVERY_INTERVAL} tick）。
     * 只判断"确实处于异常状态"的几种情况，不做无条件传送。
     */
    private void checkAnomalies(int matchTicks) {
        if (!this.started) {
            return;
        }
        PvPConfig cfg = PvPConfig.INSTANCE;
        int stuckLimit = Math.max(1, cfg.boatRaceStuckSeconds) * 20;
        int outOfBoatLimit = Math.max(1, cfg.boatRaceStuckSeconds) * 20;
        double offTrack = this.track.corridorHalfWidth() + cfg.boatRaceOffTrackMargin;

        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            UUID uuid = player.getUuid();
            RaceProgressTracker tracker = this.racers.get(uuid);
            if (tracker == null || tracker.finished()) {
                continue;
            }
            long cooldown = this.recoveryCooldownUntil.getOrDefault(uuid, 0L);
            if (matchTicks < cooldown) {
                continue;
            }

            // 1. 掉出赛道（低于冰面一定高度）
            if (player.getY() < this.track.surfaceY() - FALL_THRESHOLD) {
                this.recover(player, "掉出赛道");
                continue;
            }
            // 2. 离开赛道过远（横向）
            if (this.track.distanceToCenterline(player.getX(), player.getZ()) > offTrack) {
                this.recover(player, "离开赛道");
                continue;
            }
            // 3. 泡水/着火等异常环境
            if (player.isTouchingWater() || player.isInLava()) {
                this.recover(player, "落水");
                continue;
            }

            BoatEntity boat = this.boatOf(player);
            boolean riding = boat != null && player.getVehicle() == boat;
            if (!riding) {
                int ticks = this.outOfBoatTicks.merge(uuid, RECOVERY_INTERVAL, Integer::sum);
                if (ticks >= outOfBoatLimit) {
                    this.recover(player, "船已损毁");
                }
                continue;
            }
            this.outOfBoatTicks.put(uuid, 0);

            // 4. 卡住：必须是"静止 + 贴在赛道边缘"。
            //    单纯停在赛道中间是玩家的自由（看地图、等对手、故意停），把他传回 Checkpoint
            //    就变成了需求里明确禁止的"无条件传送"；真正卡住的是顶在护栏/缓冲带上动不了的那种。
            double lateral = this.track.distanceToCenterline(player.getX(), player.getZ());
            boolean huggingEdge = lateral > this.track.halfWidth() - 1.0;
            double speed = player.getVelocity().horizontalLength();
            if (speed < STUCK_SPEED && huggingEdge) {
                int ticks = this.stuckTicks.merge(uuid, RECOVERY_INTERVAL, Integer::sum);
                if (ticks >= stuckLimit) {
                    this.recover(player, "卡在护栏上");
                }
            } else {
                this.stuckTicks.put(uuid, 0);
            }
        }
    }

    // ==================== 排名 / HUD ====================

    private void updateRanking() {
        List<UUID> order = new ArrayList<>(this.racers.keySet());
        order.sort((a, b) -> {
            int c = RaceProgressTracker.compareForRanking(this.racers.get(a), this.racers.get(b));
            if (c != 0) {
                return c;
            }
            // 完全并列时用完赛顺序 / UUID 保证排序稳定
            int ia = this.finishOrder.indexOf(a);
            int ib = this.finishOrder.indexOf(b);
            if (ia >= 0 && ib >= 0) {
                return Integer.compare(ia, ib);
            }
            return a.compareTo(b);
        });
        for (int i = 0; i < order.size(); i++) {
            RaceProgressTracker tracker = this.racers.get(order.get(i));
            if (tracker != null) {
                tracker.setPlace(i + 1);
            }
        }
        this.ranking = List.copyOf(order);
    }

    private void updateHud(int matchTicks) {
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            if (player.networkHandler == null) {
                continue;
            }
            RaceProgressTracker tracker = this.racers.get(player.getUuid());
            if (tracker == null) {
                continue;
            }
            player.sendMessage(this.hudText(player, tracker, matchTicks), true);
        }
    }

    private Text hudText(ServerPlayerEntity player, RaceProgressTracker tracker, int matchTicks) {
        MutableText text = Text.literal("");
        if (tracker.finished()) {
            text.append(Text.literal("§6🏁 完赛 §e#" + tracker.place()
                    + "§7 | §a" + RaceProgressTracker.formatTicks(tracker.finishTicks())));
            return text;
        }
        int place = tracker.place() <= 0 ? this.ranking.size() : tracker.place();
        text.append(Text.literal("§6#" + place + "§7/§6" + this.racers.size()));
        text.append(Text.literal(" §7| §e圈 §f" + tracker.currentLap() + "§7/§f" + this.laps));
        text.append(Text.literal(" §7| §bCP §f" + tracker.checkpointsPassed()
                + "§7/§f" + this.track.checkpointCount()));
        text.append(Text.literal(" §7| §a" + RaceProgressTracker.formatTicks(tracker.elapsedTicks(matchTicks))));
        if (tracker.bestLapTicks() >= 0) {
            text.append(Text.literal(" §7| §d最快 "
                    + RaceProgressTracker.formatTicks(tracker.bestLapTicks())));
        }
        if (tracker.wrongWay()) {
            text.append(Text.literal(" §7| §c§l⚠ 逆行了！"));
        }
        return text;
    }

    // ==================== 查询（Match 侧边栏 / 结算用） ====================

    public boolean started() {
        return this.started;
    }

    public int finishedCount() {
        return this.finishOrder.size();
    }

    public boolean allFinished() {
        if (this.racers.isEmpty()) {
            return false;
        }
        for (RaceProgressTracker tracker : this.racers.values()) {
            if (!tracker.finished()) {
                return false;
            }
        }
        return true;
    }

    /** 当前名次顺序（UUID，第一名在前）。 */
    public List<UUID> ranking() {
        return this.ranking;
    }

    public int placeOf(UUID uuid) {
        RaceProgressTracker tracker = this.racers.get(uuid);
        return tracker == null ? 0 : tracker.place();
    }

    public RaceProgressTracker trackerOf(UUID uuid) {
        return this.racers.get(uuid);
    }

    /** 全部选手的进度状态（UUID → 跟踪器）；结算与侧边栏用。 */
    public Map<UUID, RaceProgressTracker> racers() {
        return java.util.Collections.unmodifiableMap(this.racers);
    }

    public String trackSummary() {
        return Math.round(this.track.length()) + " 格 / " + this.track.checkpointCount() + " CP";
    }

    /** 结算用：名次 → "🥇/🥈/🥉/#n" 前缀。 */
    public static String medal(int place) {
        return switch (place) {
            case 1 -> "🥇";
            case 2 -> "🥈";
            case 3 -> "🥉";
            default -> "#" + place;
        };
    }

    // ==================== 内部：船 ====================

    /** 玩家在起跑格位表里的位置（按 Match 的参赛玩家顺序分配）。 */
    private RaceTrack.GridSlot slotOf(UUID uuid) {
        List<RaceTrack.GridSlot> grid = this.track.grid();
        if (grid.isEmpty()) {
            return null;
        }
        int index = -1;
        List<ServerPlayerEntity> players = this.match.players();
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getUuid().equals(uuid)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return null;
        }
        return grid.get(Math.min(index, grid.size() - 1));
    }

    private void placeOnGrid(ServerPlayerEntity player) {
        ArenaWorld arena = this.match.arenaWorld();
        RaceTrack.GridSlot slot = this.slotOf(player.getUuid());
        if (arena == null || slot == null) {
            return;
        }
        // 不发船物品：船由本类统一生成/回收，多给一个物品会出现"自己又放一条船"的重复。
        player.getInventory().clear();
        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(20f);
        player.setAbsorptionAmount(0);
        player.setFireTicks(0);
        player.fallDistance = 0;
        player.clearStatusEffects();
        player.changeGameMode(GameMode.SURVIVAL);
        player.setInvulnerable(true);
        double y = this.track.surfaceY() + 1.0;
        player.teleport(arena, slot.x(), y, slot.z(), slot.yaw(), 0.0F);
        player.setVelocity(Vec3d.ZERO);
        player.currentScreenHandler.sendContentUpdates();
        this.spawnBoatFor(player, slot.x(), y, slot.z(), slot.yaw());
    }

    private void spawnBoatFor(ServerPlayerEntity player, double x, double y, double z, float yaw) {
        ArenaWorld arena = this.match.arenaWorld();
        if (arena == null) {
            return;
        }
        this.discardBoat(player);
        BoatEntity boat = EntityType.BOAT.create(arena);
        if (boat == null) {
            return;
        }
        boat.setVariant(BoatEntity.Type.OAK);
        boat.refreshPositionAndAngles(x, y, z, yaw, 0.0F);
        boat.setVelocity(Vec3d.ZERO);
        arena.spawnEntity(boat);
        this.boats.put(player.getUuid(), boat.getUuid());
        player.startRiding(boat, true);
    }

    private void discardBoat(ServerPlayerEntity player) {
        UUID boatId = this.boats.remove(player.getUuid());
        if (boatId == null) {
            return;
        }
        Entity entity = this.findEntity(boatId);
        if (entity != null) {
            if (entity.hasPassenger(player)) {
                player.stopRiding();
            }
            entity.discard();
        }
    }

    private BoatEntity boatOf(ServerPlayerEntity player) {
        UUID boatId = this.boats.get(player.getUuid());
        if (boatId == null) {
            return null;
        }
        Entity entity = this.findEntity(boatId);
        return entity instanceof BoatEntity boat && !boat.isRemoved() ? boat : null;
    }

    /** 按 UUID 在竞技场世界 + 本场玩家实体范围里找实体（船可能刚被移除）。 */
    private Entity findEntity(UUID uuid) {
        ArenaWorld arena = this.match.arenaWorld();
        if (arena == null) {
            return null;
        }
        Entity entity = arena.getEntity(uuid);
        return entity != null && !entity.isRemoved() ? entity : null;
    }

    /** 车道两侧门框之间留出的通路是否被门架挡住（调试/测试用）。 */
    public boolean gatePathClear(ArenaWorld arena) {
        for (RaceTrack.Checkpoint gate : this.track.checkpoints()) {
            for (double lat = -this.track.halfWidth(); lat <= this.track.halfWidth(); lat += 1.0) {
                BlockPos pos = new BlockPos(
                        (int) Math.floor(gate.x() + gate.normalX() * lat),
                        this.track.surfaceY() + 1,
                        (int) Math.floor(gate.z() + gate.normalZ() * lat));
                if (!arena.getBlockState(pos).getCollisionShape(arena, pos).isEmpty()) {
                    return false;
                }
                if (arena.getBlockState(pos.down()).getCollisionShape(arena, pos.down()).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }
}
