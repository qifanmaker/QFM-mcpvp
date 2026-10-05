package com.example.pvp.arena.race;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchState;
import com.example.pvp.text.Messages;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // ---------- 氮气加速 ----------

    /** 氮气物品的 NBT 标记（与项目其它自研物品一致：自定义数据 + 唯一 key）。 */
    public static final String NITRO_ITEM_TAG = "pvp.boatrace_nitro";
    /**
     * 原版船的推力（格/tick²），与 {@code BoatEntity.controlBoat()} 里的常量一致。
     * 极速 = 推力 / (1 − 冰面保持率)：0.04 / (1 − 0.98) = 2.0 格/tick（40 格/秒）。
     */
    private static final double BOAT_THRUST = 0.04;
    /** packed_ice / ice 的摩擦保持率，与原版方块注册值一致。 */
    private static final double ICE_SLIPPERINESS = 0.98;
    /** 冰面极速（格/tick）。 */
    private static final double ICE_TOP_SPEED = BOAT_THRUST / (1.0 - ICE_SLIPPERINESS);
    /** 起跑格位上方要保证为空气的格数（船高不到 1 格，留 3 格足够）。 */
    private static final int GRID_HEADROOM = 3;
    /** 重新摆位的冷却（tick）：避免"查不到船"这类瞬态导致每 tick discard+respawn 抖动。 */
    private static final long GRID_REPLACE_COOLDOWN = 10L;

    /** 加速期间每隔多少 tick 冒一次粒子（别每 tick 都发包）。 */
    private static final int NITRO_PARTICLE_INTERVAL = 4;
    /**
     * 氮气冰带只往船前方多铺这么多格。
     *
     * <p>刻意压到 2 格：客户端要收到方块更新才会用新滑度算摩擦，所以必须往前留一点；
     * 但留太多就等于在赛道上给所有人铺了一条加速路（别人跟着走也能提速）。
     * 现在船身后面一走就立刻还原，所以"跟着你走"蹭不到，只有正好挡在你前面的人能吃到 2 格。
     */
    private static final int NITRO_ICE_LEAD = 2;
    /**
     * 原版方块的滑度（冰面/浮冰 0.98、蓝冰 0.989，取自原版方块注册值）。
     * 用来反推"氮气方块相对赛道冰面是几倍极速"，这样倍率不用手填、换方块自动跟着变。
     */
    private static final Map<Block, Double> SLIPPERINESS = Map.of(
            Blocks.PACKED_ICE, 0.98,
            Blocks.ICE, 0.98,
            Blocks.FROSTED_ICE, 0.98,
            Blocks.BLUE_ICE, 0.989);


    private final Match match;
    private final ArenaTemplate template;
    private final int regionIndex;
    private final int playerCount;
    private int laps;
    private final RaceTrack track;
    private final List<String> generationNotes;

    private final Map<UUID, RaceProgressTracker> racers = new LinkedHashMap<>();
    private final Map<UUID, UUID> boats = new HashMap<>();
    private final Map<UUID, Long> recoveryCooldownUntil = new HashMap<>();
    private final Map<UUID, Integer> stuckTicks = new HashMap<>();
    private final Map<UUID, Integer> outOfBoatTicks = new HashMap<>();
    private final List<UUID> finishOrder = new ArrayList<>();
    /** 玩家 → 剩余加速 tick 数。 */
    private final Map<UUID, Integer> nitroTicks = new HashMap<>();
    /** 距下次补氮气的计时（tick）。 */
    private int nitroGrantTimer;
    /** 调试用：>0 时每 10 tick 打一条加速测速日志；配合 /pvp debug boatrace nitro。 */
    private int nitroProbeTicks;
    private final Map<UUID, Vec3d> nitroProbeLastPos = new HashMap<>();
    /** 玩家 → 上一 tick 是否按着空格（用于取"按下"的上升沿，实现空格喷氮气）。 */
    private final Map<UUID, Boolean> jumpHeld = new HashMap<>();
    /** 玩家 → 允许下次"重新摆位"的最早 tick（防抖动）。 */
    private final Map<UUID, Long> gridReplaceAt = new HashMap<>();
    /** 玩家 → 他这次加速当前覆盖的方块（每一格都带引用计数，多人重叠时不会互相踩）。 */
    private final Map<UUID, Set<Long>> nitroWindows = new HashMap<>();
    /** 方块 → 原方块；key 存在即表示"这格现在是我们涂的氮气方块"。 */
    private final Map<Long, BlockState> nitroIceRestore = new HashMap<>();
    /** 方块 → 当前有几个玩家的窗口盖着它（归零才还原）。 */
    private final Map<Long, Integer> nitroIceRefs = new HashMap<>();

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
                this.playerCount, cfg.boatRaceMinStraightLength,
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

    /**
     * 开赛前覆盖本场圈数（按参赛玩家的多数意见决定，见 {@code QueueManager#resolveBoatRaceLaps}）。
     * 必须在 {@code stageMap}/{@code finishPrepare} 之前调用 —— 那两处会按圈数建进度追踪器。
     */
    public void setLaps(int laps) {
        this.laps = Math.max(1, laps);
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
        // 摆位之前先把区域里的船扫干净：绝不带着上一局/抖动漏下的空船开局
        this.sweepStrayBoats(arena);
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            if (!this.racers.containsKey(player.getUuid())) {
                this.racers.put(player.getUuid(), new RaceProgressTracker(this.track, this.laps));
            }
            this.placeOnGrid(player);
        }

        if (!this.racers.isEmpty()) {
            ServerPlayerEntity first = this.match.onlineParticipants().stream().findFirst().orElse(null);
            if (first != null) {
                BoatEntity boat = this.boatOf(first);
                LOGGER.info("[PvP] 亦可赛艇 seed {} 起跑检查：玩家 y={} 船 y={}（冰面 Y={}）",
                        this.track.seed(),
                        String.format(java.util.Locale.ROOT, "%.3f", first.getY()),
                        boat == null ? "无船" : String.format(java.util.Locale.ROOT, "%.3f", boat.getY()),
                        this.track.surfaceY());
            }
        }
        this.match.broadcastToMatch(Messages.gold("🏁 亦可赛艇 —— 驾驶原版船，在随机冰面赛道上跑 "
                + this.laps + " 圈！"));
        this.match.broadcastToMatch(Messages.info("赛道：长 §e" + Math.round(this.track.length())
                + "§r 格 / 宽 §e" + Math.round(this.track.width())
                + "§r 格 / §e" + this.track.checkpointCount() + "§r 个 Checkpoint"
                + "｜最小弯半径 §e" + Math.round(this.track.minCornerRadius()) + "§r 格"
                + "｜Seed §7" + this.track.seed()));
        this.match.broadcastToMatch(Messages.info("按顺序穿过所有 Checkpoint 才算一圈；"
                + "掉出赛道或船被毁会自动送回最近 Checkpoint。"));
        this.match.broadcastToMatch(Messages.info("倒计时期间可以在船上自由移动，但下不了船；"
                + "起跑线上的发车挡板会在 GO 那一刻撤掉。"));
    }

    /**
     * 倒计时期间调用：<b>只保证玩家还在自己的船上</b>，不再清速度 / 不再回写坐标。
     *
     * <p>所以玩家在倒计时里可以在格位附近自由划动（也能撞着玩），但过不去起跑线 ——
     * 起跑线上立着 {@link RaceMapGenerator} 铺的发车挡板，GO 那一 tick 才撤掉。
     * 这比"每 tick 把船钉死"自然得多，同时又不会有人抢跑。
     *
     * <p>下船（shift）由这里兜住：一旦发现玩家没骑在自己的船上，立刻重新塞回去。
     * 不在传输层拦截而是"每 tick 复位"，是因为后者不碰原版的乘客集合，零风险；
     * 客户端最多看到一帧的分离，实际体验是下不去船。
     */
    public void tickCountdownHold() {
        if (this.started) {
            return;
        }
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            RaceTrack.GridSlot slot = this.slotOf(player.getUuid());
            if (slot == null) {
                continue;
            }
            // 判据用"玩家此刻是否骑着一条活着的船"，**不要**用 UUID 查表：
            // arena.getEntity(uuid) 有偶发查不到的时刻（实体刚 spawn、区块/查找表还没跟上），
            // 一旦据此判定"船丢了"，就会每 tick 把船 discard 再 respawn ——
            // 而每次 discard 都要 stopRiding() 重新安置玩家位置，
            // 这正是"开局有概率被卡在地里一格深"的来源。
            if (player.getVehicle() instanceof BoatEntity ridden && !ridden.isRemoved()) {
                this.fixStuckOnGrid(player, ridden);
                continue;
            }
            // 没骑船：先看能不能找回原来那条并塞回去；确实找不到才重新摆位，且加冷却防抖动
            BoatEntity boat = this.boatOf(player);
            if (boat != null) {
                player.startRiding(boat, true);
                this.fixStuckOnGrid(player, boat);
                continue;
            }
            if (this.match.matchTicks() < this.gridReplaceAt.getOrDefault(player.getUuid(), 0L)) {
                continue;
            }
            this.gridReplaceAt.put(player.getUuid(), this.match.matchTicks() + GRID_REPLACE_COOLDOWN);
            this.placeOnGrid(player);
        }
    }

    /**
     * 倒计时期间的自愈兜底：只要玩家或他的船陷到冰面以下，就原地重新摆一次。
     *
     * <p>背景：开局有低概率"被卡在地里一格深"——船生成/上船时序和方块占位组合出来的偶发状态，
     * 一旦发生，船在方块里靠物理是推不出来的，玩家只能等回位，体验极差。
     * 与其赌它不复现，不如倒计时期间每 tick 花一次 Y 比较把它兜住：
     * 发现陷下去就清格位上方 + 重新传送 + 重新发船，并打 WARN 留证据。
     *
     * <p>注意只在<b>倒计时</b>里做（发车后玩家在地面以下属于正常驾驶，交给回位逻辑），
     * 所以不会干扰比赛。
     */
    private void fixStuckOnGrid(ServerPlayerEntity player, BoatEntity boat) {
        // 船必须正好停在冰面之上（surfaceY + 1）。
        // 玩家不能用绝对 Y 判：正常骑在船上时玩家 Y 是 boatY - 0.412（约 100.588），
        // 那是"腿在船舱里"的正常姿态，不是陷地；只有比自己的船还低一大截才算异常。
        double expected = this.track.surfaceY() + 1.0;
        boolean boatStuck = boat.getY() < expected - 0.01;
        boolean playerBelowBoat = player.getY() < boat.getY() - 0.6;
        if (!boatStuck && !playerBelowBoat) {
            return;
        }
        LOGGER.warn("[PvP] 亦可赛艇：检测到 {} 起跑陷进地面（玩家 y={} 船 y={}，船应在 {}），已重新摆位",
                player.getGameProfile().getName(),
                String.format(java.util.Locale.ROOT, "%.3f", player.getY()),
                String.format(java.util.Locale.ROOT, "%.3f", boat.getY()),
                String.format(java.util.Locale.ROOT, "%.3f", expected));
        this.placeOnGrid(player);
    }

    /** GO：撤掉起跑线挡板，然后开始计时。 */
    public void start(int matchTicks) {
        if (this.started) {
            return;
        }
        // 挡板必须在任何人压线之前撤掉：它横跨整条走廊，船过不去，也就没人能提前起跑。
        ArenaWorld arena = this.match.arenaWorld();
        if (arena != null) {
            int removed = RaceMapGenerator.clearStartBarrier(arena, this.track);
            LOGGER.info("[PvP] 亦可赛艇 seed {} 撤掉发车挡板 {} 个方块", this.track.seed(), removed);
        }
        this.started = true;
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            RaceProgressTracker tracker = this.racers.get(player.getUuid());
            if (tracker != null) {
                tracker.arm(player.getX(), player.getZ(), matchTicks);
            }
        }
        // 开局先送 1 个，让玩家第一时间就知道有这件道具；之后每 interval 秒补 1 个
        this.nitroGrantTimer = 0;
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            this.giveNitro(player);
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
        this.tickNitro();
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
        this.nitroTicks.clear();
        this.jumpHeld.clear();
        this.releaseAllNitroIce();
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
        this.releaseAllNitroIce();
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
            // 只记账，不动单圈计时（见 RaceProgressTracker#onRecovered 的说明）
            tracker.onRecovered();
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
        this.jumpHeld.remove(player.getUuid());
        this.releaseNitroWindow(player.getUuid());
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
        // 本圈实时计时：回位不会再重置它，所以"被送回去损失了多少时间"一眼可见
        text.append(Text.literal(" §7| §f本圈 §e"
                + RaceProgressTracker.formatTicks(tracker.currentLapTicks(matchTicks))));
        if (tracker.bestLapTicks() >= 0) {
            text.append(Text.literal(" §7| §d最快 "
                    + RaceProgressTracker.formatTicks(tracker.bestLapTicks())));
        }
        int boostTicks = this.nitroBoostTicks(player.getUuid());
        if (boostTicks > 0) {
            text.append(Text.literal(" §7| §b§l加速 "
                    + String.format(java.util.Locale.ROOT, "%.1f", boostTicks / 20.0) + "s"));
        } else {
            int nitro = this.nitroCount(player);
            if (nitro > 0) {
                text.append(Text.literal(" §7| §b氮气§f x" + nitro + " §7(右键/空格)"));
            }
        }
        if (tracker.wrongWay()) {
            text.append(Text.literal(" §7| §c§l⚠ 逆行了！"));
        }
        return text;
    }

    // ==================== 氮气加速 ====================

    /**
     * 造一个氮气道具：火焰粉 + 自定义名字 + 唯一 NBT 标记 + 附魔光效（和烫手山芋一个套路）。
     */
    private ItemStack createNitroItem() {
        ItemStack stack = new ItemStack(Items.BLAZE_POWDER);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§b§l氮气加速"));
        NbtCompound nbt = new NbtCompound();
        nbt.putString(NITRO_ITEM_TAG, "1");
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7右键 或 §f空格§7 使用：§b" + PvPConfig.INSTANCE.boatRaceNitroBoostSeconds
                        + " 秒§7 内极速 §b×" + formatMultiplier(this.nitroSpeedMultiplier())),
                Text.literal("§8必须坐在船上；每 §7"
                        + PvPConfig.INSTANCE.boatRaceNitroIntervalSeconds
                        + "§8 秒自动补充，最多存 §7"
                        + PvPConfig.INSTANCE.boatRaceNitroMaxStack + "§8 个"))));
        MinecraftServer server = this.match.arenaWorld() == null ? null : this.match.arenaWorld().getServer();
        if (server != null) {
            Registry<Enchantment> registry = server.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
            RegistryEntry<Enchantment> unbreaking = registry.getEntry(Enchantments.UNBREAKING).orElse(null);
            if (unbreaking != null) {
                stack.addEnchantment(unbreaking, 1);
            }
        }
        return stack;
    }

    /** 该物品是不是本模式的氮气（按 NBT 标记判定，不会误吃玩家自己的火焰粉）。 */
    public static boolean isNitroItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        NbtComponent nbt = stack.get(DataComponentTypes.CUSTOM_DATA);
        return nbt != null && nbt.copyNbt().contains(NITRO_ITEM_TAG);
    }

    /** 玩家手上囤了几个氮气。 */
    public int nitroCount(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (isNitroItem(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 还剩多少 tick 加速（0 = 没在加速）。 */
    public int nitroBoostTicks(UUID uuid) {
        return this.nitroTicks.getOrDefault(uuid, 0);
    }

    private void giveNitro(ServerPlayerEntity player) {
        ItemStack stack = this.createNitroItem();
        if (!player.getInventory().insertStack(stack)) {
            // 竞速中背包是空的，正常不会走到；真满了就掉在脚边，别凭空消失
            player.dropItem(stack, false);
        }
        player.currentScreenHandler.sendContentUpdates();
    }

    /**
     * 右键使用氮气（由 {@code PvPMod} 的 UseItemCallback 转发）。
     *
     * @return 是否消费了这次使用
     */
    public boolean useNitro(ServerPlayerEntity player, ItemStack stack) {
        if (!this.canUseNitro(player)) {
            if (this.started && !this.matchEnded && this.boatOf(player) == null) {
                player.sendMessage(Messages.warn("必须坐在船上才能使用氮气"), true);
            }
            return false;
        }
        return this.activateNitro(player, stack);
    }

    /** 真正吃掉一颗氮气并点亮加速（右键与空格两条触发路径共用）。 */
    private boolean activateNitro(ServerPlayerEntity player, ItemStack stack) {
        stack.decrement(1);
        int add = Math.max(1, PvPConfig.INSTANCE.boatRaceNitroBoostSeconds) * 20;
        this.nitroTicks.merge(player.getUuid(), add, Integer::sum);
        ArenaWorld arena = this.match.arenaWorld();
        if (arena != null) {
            arena.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.PLAYERS, 1.0F, 1.4F);
            arena.spawnParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.4, player.getZ(),
                    14, 0.3, 0.2, 0.3, 0.05);
        }
        player.sendMessage(Text.literal("§b§l氮气加速！§r §7"
                + PvPConfig.INSTANCE.boatRaceNitroBoostSeconds + " 秒内极速 ×"
                + formatMultiplier(this.nitroSpeedMultiplier())), true);
        return true;
    }

    /**
     * 氮气方块相对赛道冰面的极速倍率：极速 = 推力 / (1 − 滑度)，
     * 所以倍率 = (1 − 冰面滑度) / (1 − 氮气滑度)。浮冰 0.98 与蓝冰 0.989 → 0.02 / 0.011 ≈ 1.82。
     */
    public double nitroSpeedMultiplier() {
        double surface = 1.0 - SLIPPERINESS.getOrDefault(
                PvPConfig.INSTANCE.getBoatRaceSurfaceBlock(), 0.98);
        double nitro = 1.0 - SLIPPERINESS.getOrDefault(
                PvPConfig.INSTANCE.getBoatRaceNitroBlock(), 0.989);
        return (surface <= 1.0e-6 || nitro <= 1.0e-6) ? 1.0 : surface / nitro;
    }

    /**
     * 骑乘输入回调（由 {@code ServerPlayNetworkHandlerMixin} 转发）：检测"空格按下"的上升沿 → 喷氮气。
     *
     * <p><b>为什么要加空格这个触发方式</b>：按住 W 前进时，鼠标右键的物品使用会被客户端吞掉 ——
     * 驾驶时准星常常压在冰面或船身上，右键会先去做方块/实体交互就结束了，
     * 物品使用包根本不发（所以之前的右键"只有停下来/瞄准天空时才有用"）。
     * 而骑乘状态下客户端每 tick 都会发 {@code PlayerInputC2SPacket}（前进/跳跃/潜行），
     * 它不经过准星判定、按住 W 也照发；船又用不到跳跃键，所以空格是最稳的触发键。
     * 右键依然保留可用（瞄天空/终点方向时）。
     */
    public void onRiderJumpInput(ServerPlayerEntity player, boolean jumping) {
        boolean was = Boolean.TRUE.equals(this.jumpHeld.put(player.getUuid(), jumping));
        if (jumping && !was) {
            this.tryUseNitro(player);
        }
    }

    /** 从物品栏里找一颗氮气用掉（空格触发用）。 */
    public boolean tryUseNitro(ServerPlayerEntity player) {
        if (!this.canUseNitro(player)) {
            return false;
        }
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (isNitroItem(stack)) {
                return this.activateNitro(player, stack);
            }
        }
        player.sendMessage(Text.literal("§7没有氮气了（每 §f"
                + PvPConfig.INSTANCE.boatRaceNitroIntervalSeconds + "§7 秒补 1 个）"), true);
        return false;
    }

    /** 能否使用氮气：比赛进行中、未冲线、且正坐在自己的船上。 */
    private boolean canUseNitro(ServerPlayerEntity player) {
        if (this.matchEnded || !this.started || this.match.getState() != MatchState.ACTIVE) {
            return false;
        }
        RaceProgressTracker tracker = this.racers.get(player.getUuid());
        if (tracker == null || tracker.finished()) {
            return false;
        }
        BoatEntity boat = this.boatOf(player);
        return boat != null && player.getVehicle() == boat;
    }

    private static String formatMultiplier(double multiplier) {
        return String.format(java.util.Locale.ROOT, "%.2f", multiplier);
    }

    private static String d1(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String d2(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /**
     * 每 tick：补氮气 + 结算加速。
     *
     * <p><b>为什么必须改船的速度</b>：原版的速度/跳跃药水走的是玩家属性，而船的位移只由
     * {@code BoatEntity.controlBoat()} 自己算，跟玩家属性完全无关 —— 所以"喝速度药水"对船毫无作用。
     * 只能由服务端在 tick 里给船加推力（下面 {@link #applyNitroThrust}），
     * 好处是船本来就是服务端权威的，不需要客户端 Mod。
     */
    private void tickNitro() {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int interval = Math.max(1, cfg.boatRaceNitroIntervalSeconds) * 20;
        int maxStack = Math.max(1, cfg.boatRaceNitroMaxStack);

        if (++this.nitroGrantTimer >= interval) {
            this.nitroGrantTimer = 0;
            for (ServerPlayerEntity player : this.match.onlineParticipants()) {
                RaceProgressTracker tracker = this.racers.get(player.getUuid());
                if (tracker == null || tracker.finished()) {
                    continue;
                }
                if (this.nitroCount(player) >= maxStack) {
                    continue;
                }
                this.giveNitro(player);
                player.sendMessage(Text.literal("§b氮气 +1 §7（右键 或 空格使用）"), true);
            }
        }

        if (this.nitroTicks.isEmpty()) {
            this.releaseAllNitroIce();
            return;
        }
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            UUID uuid = player.getUuid();
            Integer left = this.nitroTicks.get(uuid);
            if (left == null) {
                continue;
            }
            if (left <= 0) {
                this.nitroTicks.remove(uuid);
                this.releaseNitroWindow(uuid);
                continue;
            }
            this.applyNitroIce(player);
            this.nitroTicks.put(uuid, left - 1);
            if (left % NITRO_PARTICLE_INTERVAL == 0) {
                ArenaWorld arena = this.match.arenaWorld();
                if (arena != null) {
                    arena.spawnParticles(ParticleTypes.CLOUD, player.getX() - Math.sin(Math.toRadians(player.getYaw())) * 1.2,
                            player.getY() + 0.3, player.getZ() + Math.cos(Math.toRadians(player.getYaw())) * 1.2,
                            2, 0.1, 0.05, 0.1, 0.01);
                }
            }
            if (this.nitroProbeTicks > 0 && left % 10 == 0) {
                BoatEntity boat = this.boatOf(player);
                if (boat != null) {
                    Vec3d now = boat.getPos();
                    Vec3d last = this.nitroProbeLastPos.put(uuid, now);
                    if (last != null) {
                        // 实测位移才是真速度（velocity 对骑乘中的船没有参考价值）
                        double perTick = now.distanceTo(last) / 10.0;
                        LOGGER.info("[PvP] 氮气测速 {}：剩余 {} tick，实测 {} 格/tick（{} 格/秒），"
                                        + "位置 ({}, {}, {})，氮气方块 {} 格",
                                player.getGameProfile().getName(), left,
                                String.format(java.util.Locale.ROOT, "%.3f", perTick),
                                String.format(java.util.Locale.ROOT, "%.1f", perTick * 20),
                                (int) Math.floor(now.x), (int) Math.floor(now.y), (int) Math.floor(now.z),
                                this.nitroIceRestore.size());
                    }
                }
            }
        }
        this.refreshNitroIce();
        if (this.nitroProbeTicks > 0 && --this.nitroProbeTicks <= 0) {
            LOGGER.info("[PvP] 氮气测速结束");
        }
    }

    /**
     * 铺氮气冰带：把船底（以及前方 {@link #NITRO_ICE_LEAD} 格）的赛道冰面换成氮气方块，
     * 让<b>客户端自己</b>把船开到更高的极速。
     *
     * <p>只记录"这次加速覆盖了哪些格子"，真正的涂/还原交给 {@link #refreshNitroIce()} 按并集统一结算，
     * 这样多个人同时喷氮气时窗口重叠也不会互相踩。
     */
    private void applyNitroIce(ServerPlayerEntity player) {
        BoatEntity boat = this.boatOf(player);
        if (boat == null || player.getVehicle() != boat) {
            this.releaseNitroWindow(player.getUuid());
            return;
        }
        Vec3d velocity = boat.getVelocity();
        double length = velocity.horizontalLength();
        double aheadX = length < 0.01 ? -Math.sin(Math.toRadians(boat.getYaw())) : velocity.x / length;
        double aheadZ = length < 0.01 ? Math.cos(Math.toRadians(boat.getYaw())) : velocity.z / length;
        Set<Long> window = new HashSet<>();
        Box base = boat.getBoundingBox().expand(1.0);
        for (int lead = 0; lead <= NITRO_ICE_LEAD; lead++) {
            Box box = base.offset(aheadX * lead, 0.0, aheadZ * lead);
            for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
                for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                    window.add(BlockPos.asLong(x, this.track.surfaceY(), z));
                }
            }
        }
        this.nitroWindows.put(player.getUuid(), window);
    }

    /**
     * 把"所有加速玩家窗口的并集"刷成氮气方块，并立刻还原已经被窗口抛弃的格子。
     *
     * <p>这是"别人蹭不到"的关键：船一走，身后的格子当 tick 就还原成普通冰面，
     * 所以跟着你走的人得不到任何加成（只有正好在你前方 2 格以内的人会短暂吃到）。
     */
    private void refreshNitroIce() {
        ArenaWorld arena = this.match.arenaWorld();
        if (arena == null) {
            return;
        }
        Block nitroBlock = PvPConfig.INSTANCE.getBoatRaceNitroBlock();
        Block surfaceBlock = PvPConfig.INSTANCE.getBoatRaceSurfaceBlock();
        Set<Long> union = new HashSet<>();
        for (Set<Long> window : this.nitroWindows.values()) {
            union.addAll(window);
        }
        // 1) 不再被任何窗口覆盖的 → 立刻还原
        Iterator<Map.Entry<Long, BlockState>> iterator = this.nitroIceRestore.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, BlockState> entry = iterator.next();
            if (union.contains(entry.getKey())) {
                continue;
            }
            arena.setBlockState(BlockPos.fromLong(entry.getKey()), entry.getValue(), 3);
            this.nitroIceRefs.remove(entry.getKey());
            iterator.remove();
        }
        // 2) 新进入窗口的 → 换成氮气方块（只动赛道冰面，雪地/护栏/门架不碰）
        for (Long key : union) {
            if (this.nitroIceRestore.containsKey(key)) {
                this.nitroIceRefs.merge(key, 1, Integer::sum);
                continue;
            }
            BlockPos pos = BlockPos.fromLong(key);
            BlockState current = arena.getBlockState(pos);
            if (current.isAir() || current.isOf(nitroBlock) || !current.isOf(surfaceBlock)) {
                continue;
            }
            this.nitroIceRestore.put(key, current);
            this.nitroIceRefs.put(key, 1);
            arena.setBlockState(pos, nitroBlock.getDefaultState(), 3);
        }
    }

    /** 某个玩家不再加速：丢掉他的窗口，下一 tick 统一还原。 */
    private void releaseNitroWindow(UUID uuid) {
        this.nitroWindows.remove(uuid);
    }

    /** 全场都不加速了：还原所有氮气方块。 */
    private void releaseAllNitroIce() {
        if (this.nitroWindows.isEmpty() && this.nitroIceRestore.isEmpty()) {
            return;
        }
        this.nitroWindows.clear();
        this.nitroIceRefs.clear();
        ArenaWorld arena = this.match.arenaWorld();
        int restored = 0;
        if (arena != null) {
            for (Map.Entry<Long, BlockState> entry : this.nitroIceRestore.entrySet()) {
                arena.setBlockState(BlockPos.fromLong(entry.getKey()), entry.getValue(), 3);
                restored++;
            }
        }
        this.nitroIceRestore.clear();
        if (restored > 0) {
            LOGGER.info("[PvP] 亦可赛艇 seed {} 氮气冰带已还原 {} 个方块", this.track.seed(), restored);
        }
    }

    /** 调试：为某名玩家开一次加速，并打 3 秒测速日志（{@code /pvp debug boatrace nitro}）。 */
    public boolean debugActivateNitro(ServerPlayerEntity player) {
        RaceProgressTracker tracker = this.racers.get(player.getUuid());
        if (tracker == null || !this.started) {
            return false;
        }
        int ticks = Math.max(1, PvPConfig.INSTANCE.boatRaceNitroBoostSeconds) * 20;
        this.nitroTicks.merge(player.getUuid(), ticks, Integer::sum);
        this.nitroProbeTicks = ticks + 40;
        BoatEntity boat = this.boatOf(player);
        if (boat != null) {
            this.nitroProbeLastPos.put(player.getUuid(), boat.getPos());
        }
        double multiplier = this.nitroSpeedMultiplier();
        LOGGER.info("[PvP] 氮气测速开始：{}，加速 {} tick（{} 秒）；赛道冰面极速 {} → 氮气极速 {} 格/tick"
                        + "（{} 格/秒，×{}；氮气方块 = {}）",
                player.getGameProfile().getName(), ticks, ticks / 20,
                d2(ICE_TOP_SPEED), d2(ICE_TOP_SPEED * multiplier), d1(ICE_TOP_SPEED * multiplier * 20),
                formatMultiplier(multiplier), PvPConfig.INSTANCE.boatRaceNitroBlock);
        return true;
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
        // 格位上方必须全是空气。赛道本身铺完时应该是空的，但只要有**任何**东西占了那一格
        // （上一局残留、环境装饰长到跑道上、异常方块…），船就会连人一起生成在方块内部，
        // 表现就是"开局卡在地里一格深"（船在方块里，物理推不出来，只能等回位）。
        // 所以上船前主动清一遍格位上方 —— 只清 surfaceY 以上，脚下的冰面一格都不动。
        int cleared = this.clearGridHeadroom(arena, slot);
        player.teleport(arena, slot.x(), y, slot.z(), slot.yaw(), 0.0F);
        player.setVelocity(Vec3d.ZERO);
        player.currentScreenHandler.sendContentUpdates();
        this.spawnBoatFor(player, slot.x(), y, slot.z(), slot.yaw());
        // 自检：连人带船都得在冰面之上，否则把现场打进日志（含该格实际方块）
        this.checkGridClearance(arena, player, slot, cleared);
    }

    /**
     * 清掉起跑格位上方 3 格的方块（按船的实际占位算 2×2 列）。
     *
     * <p>为什么必须做：船是被 spawn 在格位坐标上的，若那一格被方块占着，船会直接卡在方块里，
     * 冰面摩擦再滑也没用 —— 玩家只能等"卡住回位"。这类占用不一定是本局造成的
     * （上一局残留、环境装饰、异常方块都可能），所以每局开局都清一遍最稳。
     *
     * @return 实际清掉的方块数（正常应为 0；不为 0 就说明这里本来有东西，日志会记下来）
     */
    private int clearGridHeadroom(ArenaWorld arena, RaceTrack.GridSlot slot) {
        int surfaceY = this.track.surfaceY();
        int minX = (int) Math.floor(slot.x() - 0.7);
        int maxX = (int) Math.floor(slot.x() + 0.7);
        int minZ = (int) Math.floor(slot.z() - 0.7);
        int maxZ = (int) Math.floor(slot.z() + 0.7);
        int cleared = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int dy = 1; dy <= GRID_HEADROOM; dy++) {
                    BlockPos pos = new BlockPos(x, surfaceY + dy, z);
                    if (!arena.getBlockState(pos).isAir()) {
                        arena.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                        cleared++;
                    }
                }
            }
        }
        return cleared;
    }

    /**
     * 自检：玩家与他的船都必须在冰面之上，否则打日志（带该格实际方块）。
     * 出现这条告警基本就等于"开局卡在地里"，所以宁可吵一点也要留下现场。
     */
    private void checkGridClearance(ArenaWorld arena, ServerPlayerEntity player,
                                    RaceTrack.GridSlot slot, int cleared) {
        double floor = this.track.surfaceY() + 0.5;
        BoatEntity boat = this.boatOf(player);
        double boatY = boat == null ? Double.NaN : boat.getY();
        if (player.getY() >= floor && (Double.isNaN(boatY) || boatY >= floor)) {
            if (cleared > 0) {
                LOGGER.warn("[PvP] 亦可赛艇：起跑格位上方清掉 {} 个方块（本应全是空气）；玩家 {}",
                        cleared, player.getGameProfile().getName());
            }
            return;
        }
        BlockPos feet = new BlockPos((int) Math.floor(slot.x()), this.track.surfaceY(), (int) Math.floor(slot.z()));
        LOGGER.warn("[PvP] 亦可赛艇：{} 起跑陷入地面！玩家 y={} 船 y={}（冰面 {}），"
                        + "格位方块={} 上方={}；已清理 {} 格",
                player.getGameProfile().getName(),
                String.format(java.util.Locale.ROOT, "%.3f", player.getY()),
                Double.isNaN(boatY) ? "无船" : String.format(java.util.Locale.ROOT, "%.3f", boatY),
                this.track.surfaceY(),
                net.minecraft.registry.Registries.BLOCK.getId(arena.getBlockState(feet).getBlock()),
                net.minecraft.registry.Registries.BLOCK.getId(
                        arena.getBlockState(feet.up()).getBlock()),
                cleared);
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

    /**
     * 收掉玩家当前这条船。
     *
     * <p>这里踩过一个坑：原来先 {@code boats.remove(uuid)}，为 null 就直接 return，
     * 而 {@code findEntity}（{@code arena.getEntity(uuid)}）又会偶尔查不到刚 spawn 的实体 ——
     * 两个早退加起来的结果是"旧船根本没被移除"，于是地上留下一条<b>空船</b>。
     * 倒计时抖动那几 tick 每次漏一条，开局就能看到玩家身后躺着好几条空船（实测 4 条）。
     *
     * <p>所以现在<b>先处理玩家实际骑着的那条</b>（不依赖任何查找表），
     * 再兜底处理登记过的那条；任何一条都不允许被静默跳过。
     */
    private void discardBoat(ServerPlayerEntity player) {
        // 1) 玩家正骑着的船：这是最可靠的来源，不查表
        if (player.getVehicle() instanceof BoatEntity ridden && !ridden.isRemoved()) {
            player.stopRiding();
            ridden.discard();
        }
        // 2) 登记过的船（可能不是他骑着的那条，也可能是没骑上的）
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

    /**
     * 清掉本场区域里所有"不属于本场登记船"的船（上一局残留、抖动漏掉的空船、
     * 玩家自己放的等等）。
     *
     * <p>这是"开局身后躺着几条空船"的最后一道保险：不管历史上哪个环节漏了，
     * 开局摆位之前先把区域里的船扫干净，就不会出现"还没出发就一堆空船挡在后面"。
     * 清到东西会打 WARN —— 正常应该是 0，不为 0 就说明上游还有路径在漏船。
     */
    private int sweepStrayBoats(ArenaWorld arena) {
        double half = PvPConfig.INSTANCE.boatRaceSize / 2.0;
        Box box = new Box(
                this.track.centerX() - half, arena.getBottomY(), this.track.centerZ() - half,
                this.track.centerX() + half, arena.getTopY(), this.track.centerZ() + half);
        List<BoatEntity> strays = arena.getEntitiesByClass(BoatEntity.class, box,
                boat -> !this.boats.containsValue(boat.getUuid()));
        for (BoatEntity boat : strays) {
            boat.discard();
        }
        if (!strays.isEmpty()) {
            LOGGER.warn("[PvP] 亦可赛艇：开局清扫掉 {} 条残留空船（上游有路径漏掉了 discardBoat）",
                    strays.size());
        }
        return strays.size();
    }

    private BoatEntity boatOf(ServerPlayerEntity player) {
        UUID boatId = this.boats.get(player.getUuid());
        if (boatId != null) {
            Entity entity = this.findEntity(boatId);
            if (entity instanceof BoatEntity boat && !boat.isRemoved()) {
                return boat;
            }
        }
        // 兜底：实体查找表偶尔落后于 spawn（船刚生成时 arena.getEntity(uuid) 可能查不到，
        // 实测在 finishPrepare 里就会遇到）。这时"玩家实际骑的是什么"才是权威信息。
        // 没有这条兜底，"查不到船"会被上游当成"船没了"：倒计时每 tick 重发船（把人塞进地里），
        // 或者氮气判定为"不在船上"而喷不出来。
        return player.getVehicle() instanceof BoatEntity ridden && !ridden.isRemoved() ? ridden : null;
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

    /**
     * 车道两侧门框之间留出的通路是否被挡（调试/测试用）。
     *
     * <p>跳过起终点线：它在 GO 之前本来就立着发车挡板，那是设计的一部分。
     */
    public boolean gatePathClear(ArenaWorld arena) {
        for (RaceTrack.Checkpoint gate : this.track.checkpoints()) {
            if (gate.isFinishLine()) {
                continue;
            }
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
