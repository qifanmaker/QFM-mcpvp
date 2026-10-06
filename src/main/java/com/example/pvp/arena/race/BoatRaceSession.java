package com.example.pvp.arena.race;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchState;
import com.example.pvp.text.Messages;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

    /**
     * 速冻胶铺下去用哪个方块：雪块（滑度 0.6，和赛道两侧的缓冲带同材质）。
     *
     * <p><b>为什么减速道具谈不上"稍慢"</b>：极速 = 0.04 / (1 − 滑度)，而原版只有
     * 0.6（绝大多数方块）、0.8（黏液块）、0.98（冰族）、0.989（蓝冰）四档滑度 ——
     * 0.6 就是 0.1 格/tick（2 格/秒），相对赛道上的 40 格/秒只有"几乎停住"和"正常"两种。
     * 而且滑度越接近 1 速度越敏感（0.98 → 0.975 就从 40 格/秒掉到 32），所以中间档必须靠
     * 自定义方块，而本 Mod 是 {@code environment: server}（客户端不加载），用不了。
     *
     * <p>于是"力度"只能靠<b>你在雪上待几 tick</b> 来调，也就是沿赛道方向的<b>长度</b>：
     * 2 格长只待 1 tick（速度 40 → 约 25 格/秒，然后回到冰面加速）；
     * 5 格长会待 8 tick，一路衰减到 2 格/秒 —— 那就是"卡住"。
     * 默认取 3 格：即使全速喷氮气（3.64 格/tick）也不可能两 tick 都跳过它。
     *
     * <p>选雪块而不是石头/混凝土，是因为它和缓冲带同材质：冰面上出现白色方块，
     * 玩家的直觉就是"那是缓冲区，进去就没速度"。
     */
    private static final Block TRAP_BLOCK = Blocks.SNOW_BLOCK;
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
    /**
     * 左键"用道具"的冷却（tick）。
     *
     * <p>客户端按住左键时会每 tick 重发挖掘续期包，服务端分不清"点了一下"和"一直按着"，
     * 所以只能靠冷却限流；1 秒一次既够用（道具也就 2 件），又不会一按就把手上的道具全烧掉。
     */
    private static final long ATTACK_USE_COOLDOWN = 20L;

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
    /** 玩家 → 左键"用道具"的冷却结束 tick。 */
    private final Map<UUID, Long> attackUseCooldownUntil = new HashMap<>();
    /** 玩家 → 上次"没有道具"提示的 tick（限流，别刷屏）。 */
    private final Map<UUID, Long> emptyItemMessageAt = new HashMap<>();
    /** 玩家 → 他这次加速当前覆盖的方块；真正的落方块由 {@link #overlay} 统一做。 */
    private final Map<UUID, Set<Long>> nitroWindows = new HashMap<>();
    /**
     * 赛道地表的统一覆写层：氮气（加速）与速冻胶（减速）都要改船脚下的方块，
     * 各自维护还原表会互相把对方的覆写当成"原方块"，所以收敛到一层里仲裁。
     */
    private final SurfaceOverlay overlay;
    /** 赛道道具箱：过门横排，扫掠拾取，吃到后原地重生。 */
    private final RaceItemBoxes itemBoxes;
    /** 抽道具用的随机源：以赛道 Seed 派生，同一场的抽取序列可复现，方便事后查证。 */
    private final Random random;

    private boolean started;
    private boolean mapBuilt;
    private boolean matchEnded;
    /** 调试：打开后每次箱子拾取/重生/覆写刷新都打日志（{@code /pvp debug boatrace items} 打开）。 */
    private boolean debugItems;
    /** 调试：最近一次速冻胶铺了哪些格子（自检采样用）。 */
    private Set<Long> lastTrapCells = Set.of();
    /** 调试：>0 时倒计时，归零后打印采样格的方块，用来验证速冻胶"到期还原"。 */
    private int overlayProbeTicks;
    private BlockPos overlayProbePos;
    /** 调试：拾取链路自检——沿中心线逐步把玩家推过第一个道具箱（服务器侧，不需要客户端操作）。 */
    private List<double[]> itemProbePath;
    private int itemProbeStep;
    private ServerPlayerEntity itemProbePlayer;
    private int itemProbeStartCount;
    private int tickCounter;
    private List<UUID> ranking = List.of();

    public BoatRaceSession(Match match, ArenaTemplate template, int regionIndex, long seed, int playerCount) {
        this.match = match;
        this.template = template;
        this.regionIndex = regionIndex;
        this.playerCount = Math.max(1, playerCount);
        PvPConfig cfg = PvPConfig.INSTANCE;
        this.laps = Math.max(1, cfg.boatRaceLaps);
        this.overlay = new SurfaceOverlay(
                cfg.getBoatRaceSurfaceBlock(), cfg.getBoatRaceNitroBlock(), TRAP_BLOCK);
        this.random = new Random(seed * 0x9E3779B97F4A7C15L + 17L);

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
        this.itemBoxes = new RaceItemBoxes(this.track,
                cfg.boatRaceItemBoxGateOffset, cfg.boatRaceItemBoxLaneOffset, cfg.boatRaceItemBoxLanes);

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
        // 开局先送 1 个氮气：让玩家第一时间知道有道具、也知道怎么用（右键/空格）。
        // 之后不再定时补 —— 道具全部来自赛道上的道具箱（见 RaceItemBoxes），
        // 否则"每 15 秒白送一个"会盖过箱子的作用。
        this.nitroGrantTimer = 0;
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            this.giveItem(player, RaceItem.NITRO);
        }
        if (arena != null && PvPConfig.INSTANCE.boatRaceItemBoxesEnabled) {
            int spawned = this.itemBoxes.spawnAll(arena);
            LOGGER.info("[PvP] 亦可赛艇 seed {} 道具箱已生成 {}/{} 个"
                            + "（门后 {} 格 × {} 道门 × {} 车道，拾取半径 {}，重生 {} 秒）",
                    this.track.seed(), spawned, this.itemBoxes.size(),
                    PvPConfig.INSTANCE.boatRaceItemBoxGateOffset, this.track.checkpointCount(),
                    PvPConfig.INSTANCE.boatRaceItemBoxLanes,
                    PvPConfig.INSTANCE.boatRaceItemBoxPickupRadius,
                    PvPConfig.INSTANCE.boatRaceItemBoxRespawnSeconds);
        }
        this.updateRanking();
    }

    /** 每 tick 调用（仅 ACTIVE）。 */
    public void tick(int matchTicks) {
        if (this.matchEnded) {
            return;
        }
        this.tickCounter++;
        if (this.itemProbePath != null) {
            this.stepItemProbe();
        }
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
        this.tickItemBoxes(matchTicks);
        if (this.overlayProbeTicks > 0 && --this.overlayProbeTicks == 0) {
            this.logOverlayProbe();
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

    /** 比赛结束（庆祝阶段开始）时调用：清掉场上的船与道具箱，别让它们留到下一场。 */
    public void onMatchEnd() {
        this.matchEnded = true;
        this.nitroTicks.clear();
        this.jumpHeld.clear();
        this.releaseSurfaceOverlay();
        int boxes = this.itemBoxes.discardAll();
        if (boxes > 0) {
            LOGGER.info("[PvP] 亦可赛艇 seed {} 回收道具箱 {} 个", this.track.seed(), boxes);
        }
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
        this.releaseSurfaceOverlay();
        this.itemBoxes.discardAll();
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
        // 回位是一次传送：清掉道具箱的扫掠历史，别拿"传送前的位置"当线段
        this.itemBoxes.forget(player.getUuid());
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
        this.itemBoxes.forget(player.getUuid());
        this.attackUseCooldownUntil.remove(player.getUuid());
        this.emptyItemMessageAt.remove(player.getUuid());
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
        // 道具箱拾取：必须用扫掠（上一 tick → 本 tick 的线段），
        // 因为氮气时一 tick 能跑 3.64 格，"半径内"这种点判定会整段跳过箱子。
        this.tryPickupBox(player, tracker, matchTicks);
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

    // ==================== 道具箱 ====================

    /**
     * 道具箱拾取判定。
     *
     * <p>三个刻意的限制：<b>只有正向行进才算</b>（逆行/倒车回吃不算，天然反刷）、
     * <b>手上满了就不吃</b>（箱子留在赛道上给别人，而不是吃掉后凭空消失）、
     * <b>两次拾取之间有冷却</b>（一 tick 扫过一整排不会把 3 个箱子全吃掉）。
     */
    private void tryPickupBox(ServerPlayerEntity player, RaceProgressTracker tracker, int matchTicks) {
        PvPConfig cfg = PvPConfig.INSTANCE;
        if (!cfg.boatRaceItemBoxesEnabled || this.itemBoxes.size() == 0) {
            return;
        }
        // 先做扫掠查询（顺便刷新"上一 tick 位置"，别让线段跨度越拖越大），再决定收不收
        RaceItemBoxes.Slot slot = this.itemBoxes.pickup(player, matchTicks,
                !tracker.wrongWay(), cfg.boatRaceItemBoxPickupRadius, 2.0,
                cfg.boatRaceItemBoxPickupCooldownTicks);
        if (slot == null) {
            return;
        }
        if (this.itemCount(player) >= Math.max(1, cfg.boatRaceItemBoxMaxHold)) {
            // 手上满了：箱子留在赛道上给别人，而不是被吃掉后凭空消失
            return;
        }
        this.itemBoxes.consume(slot, matchTicks,
                Math.max(1, cfg.boatRaceItemBoxRespawnSeconds) * 20L);
        RaceItem item = this.drawItem(player);
        this.giveItem(player, item);
        ArenaWorld arena = this.match.arenaWorld();
        if (arena != null) {
            arena.playSound(null, slot.x, slot.surfaceY + 1.0, slot.z,
                    SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.8F, 1.4F);
            arena.spawnParticles(ParticleTypes.CRIT, slot.x, slot.surfaceY + 1.2, slot.z,
                    8, 0.2, 0.2, 0.2, 0.05);
        }
        player.sendMessage(Text.literal("§7道具箱 → " + item.coloredName()), true);
        if (this.debugItems) {
            LOGGER.info("[PvP] 道具箱：{} 吃到 {}（门 {}，位置 {}, {}），存活 {}/{}",
                    player.getGameProfile().getName(), item.id(), slot.gate,
                    (int) slot.x, (int) slot.z, this.itemBoxes.liveCount(), this.itemBoxes.size());
        }
    }

    /** 每 tick：重生到点的箱子。 */
    private void tickItemBoxes(int matchTicks) {
        PvPConfig cfg = PvPConfig.INSTANCE;
        if (!cfg.boatRaceItemBoxesEnabled || this.itemBoxes.size() == 0) {
            return;
        }
        int spawned = this.itemBoxes.tickRespawn(this.match.arenaWorld(), matchTicks);
        if (spawned > 0 && this.debugItems) {
            LOGGER.info("[PvP] 道具箱重生 {} 个，存活 {}/{}",
                    spawned, this.itemBoxes.liveCount(), this.itemBoxes.size());
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
        }
        // 道具栏：只显示手上真有的
        StringBuilder items = new StringBuilder();
        RaceItem held = RaceItem.of(player.getMainHandStack());
        for (RaceItem kind : RaceItem.values()) {
            int count = this.itemCount(player, kind);
            if (count > 0) {
                // 手上拿的那件高亮 + 箭头：骑船时只能靠滚轮选，"当前选中哪件"必须一眼可见
                boolean selected = kind == held;
                items.append(selected ? " §f§l▶" : " §7")
                        .append(kind.coloredShortName()).append("§f x").append(count);
            }
        }
        if (!items.isEmpty()) {
            text.append(Text.literal(" §7|" + items));
            text.append(Text.literal(" §8(滚轮选/空格用)"));
        }
        if (tracker.wrongWay()) {
            text.append(Text.literal(" §7| §c§l⚠ 逆行了！"));
        }
        return text;
    }

    // ==================== 氮气加速 ====================

    /**
     * 造一件道具：图标 + 自定义名字 + 唯一 NBT 标记 + 附魔光效 + 两行 lore（和烫手山芋一个套路）。
     *
     * <p>lore 的第二行会按道具说明不同的"存量规则"：氮气可能来自定时补给，
     * 其余三件只来自赛道道具箱 —— 这两句直接决定玩家知不知道去哪儿弄道具，所以必须准确。
     */
    private ItemStack createItem(RaceItem kind) {
        ItemStack stack = kind.create();
        PvPConfig cfg = PvPConfig.INSTANCE;
        String amount = switch (kind) {
            case NITRO -> "§b" + cfg.boatRaceNitroBoostSeconds + " 秒§7 内极速 §b×"
                    + formatMultiplier(this.nitroSpeedMultiplier());
            case TRAP -> "身后铺一条 §f" + cfg.boatRaceItemTrapWidth + "×"
                    + cfg.boatRaceItemTrapLength + "§7 的短雪带（§f"
                    + cfg.boatRaceItemTrapSeconds + " 秒§7）：压上去会顿一下掉速，但停不下来";
            case INK -> "让前一名玩家失明 §5" + d1(cfg.boatRaceItemInkSeconds) + " 秒";
        };
        String source = cfg.boatRaceNitroIntervalSeconds > 0
                ? "§8坐在船上用；赛道上每 §7" + cfg.boatRaceNitroIntervalSeconds + "§8 秒补 1 个"
                : "§8坐在船上用；道具从赛道上飘着的宝箱里吃";
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7滚轮选中，按 §f空格§7 或 §f左键§7 使用：" + amount),
                Text.literal(source + "，手上最多 §7" + cfg.boatRaceItemBoxMaxHold + "§8 件"))));
        return stack;
    }

    /** 是不是本模式的氮气（按 NBT 标记判定，不会误吃玩家自己的火焰粉）。 */
    public static boolean isNitroItem(ItemStack stack) {
        return RaceItem.of(stack) == RaceItem.NITRO;
    }

    /** 是不是本模式的某件道具；不是则返回 null（右键/空格两条触发路径都用它分发）。 */
    public static RaceItem itemKindOf(ItemStack stack) {
        return RaceItem.of(stack);
    }

    /** 玩家手上囤了几件指定道具。 */
    public int itemCount(ServerPlayerEntity player, RaceItem kind) {
        var inventory = player.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (RaceItem.of(stack) == kind) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 玩家手上所有本模式道具的总数（"手上最多几件"按这个算）。 */
    public int itemCount(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.size(); i++) {
            if (RaceItem.of(inventory.getStack(i)) != null) {
                count += inventory.getStack(i).getCount();
            }
        }
        return count;
    }

    /** 玩家手上囤了几个氮气。 */
    public int nitroCount(ServerPlayerEntity player) {
        return this.itemCount(player, RaceItem.NITRO);
    }

    /** 还剩多少 tick 加速（0 = 没在加速）。 */
    public int nitroBoostTicks(UUID uuid) {
        return this.nitroTicks.getOrDefault(uuid, 0);
    }

    /**
     * 把一件道具塞进玩家背包，并<b>自动把快捷栏选中切到它</b>。
     *
     * <p>为什么自动切：骑船时选道具只能靠滚轮/数字键，而道具是"吃到就想立刻用"的东西
     * （氮气追人、胶甩追兵）。不自动切的话每拿到一件都要多一步滚轮操作，
     * 在 40 格/秒下这一下就是几十格。选中的槽位会同步给客户端（否则客户端手上还是旧物品）。
     */
    private void giveItem(ServerPlayerEntity player, RaceItem kind) {
        ItemStack stack = this.createItem(kind);
        if (!player.getInventory().insertStack(stack)) {
            // 竞速中背包是空的，正常不会走到；真满了就掉在脚边，别凭空消失
            player.dropItem(stack, false);
        }
        this.selectItemSlot(player, kind);
        player.currentScreenHandler.sendContentUpdates();
    }

    /** 把快捷栏选中切到指定道具所在的那一格（找不到就保持不动）。 */
    private void selectItemSlot(ServerPlayerEntity player, RaceItem kind) {
        var inventory = player.getInventory();
        int hotbar = net.minecraft.entity.player.PlayerInventory.getHotbarSize();
        for (int i = 0; i < Math.min(hotbar, inventory.size()); i++) {
            if (RaceItem.of(inventory.getStack(i)) == kind) {
                if (inventory.selectedSlot != i) {
                    inventory.selectedSlot = i;
                    if (player.networkHandler != null) {
                        player.networkHandler.sendPacket(
                                new net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket(i));
                    }
                }
                return;
            }
        }
    }

    /**
     * 右键使用道具（由 {@code PvPMod} 的 UseItemCallback 转发）。按 NBT 标记分发到具体道具。
     *
     * @return 是否消费了这次使用
     */
    public boolean useItem(ServerPlayerEntity player, ItemStack stack, RaceItem kind) {
        if (kind == null) {
            return false;
        }
        if (!this.canUseItem(player)) {
            if (this.started && !this.matchEnded && this.boatOf(player) == null) {
                player.sendMessage(Messages.warn("必须坐在船上才能使用道具"), true);
            }
            return false;
        }
        return switch (kind) {
            case NITRO -> this.activateNitro(player, stack);
            case TRAP -> this.activateTrap(player, stack);
            case INK -> this.activateInk(player, stack);
        };
    }

    /** 兼容旧调用点：等价于用一颗氮气。 */
    public boolean useNitro(ServerPlayerEntity player, ItemStack stack) {
        return this.useItem(player, stack, RaceItem.NITRO);
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

    // ==================== 速冻胶 / 墨水弹 ====================

    /**
     * 速冻胶：在<b>身后</b> {@code boatRaceItemTrapBehind} 格的赛道上铺一条雪带。
     *
     * <p><b>为什么铺身后而不是身前</b>：身前等于给自己下套（40 格/秒下根本来不及绕），
     * 身后则正好落在追你的人的路线上 —— 这也是本模式里唯一"真正能打到人"的攻击手段：
     * 压上去的人速度会从 40 格/秒掉到 2 格/秒（原版只有"滑/不滑"两档，没有轻减速）。
     *
     * <p>位置取<b>中心线</b>而不是"玩家正后方"：写死横向偏移时，弯道上会把雪带铺到缓冲带/护栏外面。
     * 宽度默认 6 格 / 赛道 16 格，永远留得下 10 格绕行空间，任何情况下都不会把路封死；
     * 落在非赛道方块（雪地缓冲带、护栏、门架）上的格子会被 {@link SurfaceOverlay} 自动忽略。
     */
    private boolean activateTrap(ServerPlayerEntity player, ItemStack stack) {
        return this.activateTrapAt(player, stack,
                PvPConfig.INSTANCE.boatRaceItemTrapBehind);
    }

    /** 速冻胶的实际铺设逻辑；{@code behind} 为正表示铺在身后，负值表示铺在前方（调试用）。 */
    private boolean activateTrapAt(ServerPlayerEntity player, ItemStack stack, double behindBlocks) {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int samples = this.track.sampleCount();
        if (samples <= 0) {
            return false;
        }
        int center = this.track.nearestSample(player.getX(), player.getZ());
        int behind = (int) Math.round(behindBlocks);
        int base = Math.floorMod(center - behind, samples);
        int halfLength = Math.max(0, (Math.max(1, cfg.boatRaceItemTrapLength) - 1) / 2);
        double halfWidth = Math.max(1.0, cfg.boatRaceItemTrapWidth / 2.0);

        Set<Long> positions = new HashSet<>();
        for (int step = -halfLength; step <= halfLength; step++) {
            int index = Math.floorMod(base + step, samples);
            double cx = this.track.sampleX(index);
            double cz = this.track.sampleZ(index);
            double nx = this.track.sampleNormalX(index);
            double nz = this.track.sampleNormalZ(index);
            for (double lat = -halfWidth; lat <= halfWidth; lat += 1.0) {
                positions.add(BlockPos.asLong(
                        (int) Math.floor(cx + nx * lat),
                        this.track.surfaceY(),
                        (int) Math.floor(cz + nz * lat)));
            }
        }

        stack.decrement(1);
        this.overlay.addTrap(positions,
                this.match.matchTicks() + Math.max(1, cfg.boatRaceItemTrapSeconds) * 20L);
        this.lastTrapCells = positions;
        this.refreshSurfaceOverlay();
        player.sendMessage(Text.literal("§f§l速冻胶！§r §7已在身后铺开（"
                + cfg.boatRaceItemTrapWidth + "×" + cfg.boatRaceItemTrapLength + " 格，"
                + cfg.boatRaceItemTrapSeconds + " 秒）"), true);
        ArenaWorld arena = this.match.arenaWorld();
        if (arena != null) {
            arena.playSound(null, this.track.sampleX(base), this.track.surfaceY(), this.track.sampleZ(base),
                    SoundEvents.BLOCK_SNOW_PLACE, SoundCategory.PLAYERS, 1.0F, 0.8F);
        }
        if (this.debugItems) {
            LOGGER.info("[PvP] 速冻胶：{} 在身后 {} 格铺了 {} 格（{} 宽 × {} 长），覆写层共 {} 格",
                    player.getGameProfile().getName(), behind, positions.size(),
                    cfg.boatRaceItemTrapWidth, cfg.boatRaceItemTrapLength, this.overlay.cellCount());
        }
        return true;
    }

    /**
     * 墨水弹：让<b>前一名</b>玩家失明（附带一半时长的反胃）。
     *
     * <p>这是唯一的"纯状态效果"道具：玩家属性/效果对船的位移完全无效（实测），
     * 但对<b>观感</b>有效 —— 高速走线靠的就是视野，所以失明在竞速里恰好是恰到好处的攻击。
     *
     * <p>目标选择：名次在你前一位的那名<b>未完赛</b>选手；已经是第一名时改为离你最近的追赶者。
     * 场上没有其他选手时不消耗道具（避免白白用掉）。
     */
    private boolean activateInk(ServerPlayerEntity player, ItemStack stack) {
        ServerPlayerEntity target = this.itemTarget(player);
        if (target == null) {
            player.sendMessage(Text.literal("§7没有可以下手的对手（道具未消耗）"), true);
            return false;
        }
        PvPConfig cfg = PvPConfig.INSTANCE;
        stack.decrement(1);
        int blindTicks = (int) Math.max(1.0, cfg.boatRaceItemInkSeconds * 20.0);
        target.addStatusEffect(new StatusEffectInstance(
                StatusEffects.BLINDNESS, blindTicks, 0, false, false, true));
        target.addStatusEffect(new StatusEffectInstance(
                StatusEffects.NAUSEA, Math.max(1, blindTicks / 2), 0, false, false, true));
        player.sendMessage(Text.literal("§5§l墨水弹！§r §7正中 §f"
                + target.getGameProfile().getName()), true);
        target.sendMessage(Text.literal("§5§l被墨水糊住了！§r §7看不清路（"
                + d1(cfg.boatRaceItemInkSeconds) + " 秒）"), true);
        ArenaWorld arena = this.match.arenaWorld();
        if (arena != null) {
            arena.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.ENTITY_SQUID_SQUIRT, SoundCategory.PLAYERS, 1.0F, 0.7F);
        }
        if (this.debugItems) {
            LOGGER.info("[PvP] 墨水弹：{} → {}（失明 {} tick）",
                    player.getGameProfile().getName(), target.getGameProfile().getName(), blindTicks);
        }
        return true;
    }

    /** 墨水的目标：名次前一位的未完赛选手；自己第一时就打离自己最近的追赶者。 */
    private ServerPlayerEntity itemTarget(ServerPlayerEntity player) {
        List<UUID> order = this.ranking.isEmpty()
                ? new ArrayList<>(this.racers.keySet()) : this.ranking;
        int index = order.indexOf(player.getUuid());
        if (index < 0 || order.size() < 2) {
            return null;
        }
        for (int i = index - 1; i >= 0; i--) {
            ServerPlayerEntity ahead = this.onlineRacer(order.get(i));
            if (ahead != null) {
                return ahead;
            }
        }
        for (int i = index + 1; i < order.size(); i++) {
            ServerPlayerEntity behind = this.onlineRacer(order.get(i));
            if (behind != null) {
                return behind;
            }
        }
        return null;
    }

    /** 名次表里的 UUID → 还在场上、且没冲线的选手（冲线的人已经转旁观，不该再被道具打）。 */
    private ServerPlayerEntity onlineRacer(UUID uuid) {
        RaceProgressTracker tracker = this.racers.get(uuid);
        if (tracker == null || tracker.finished()) {
            return null;
        }
        for (ServerPlayerEntity other : this.match.onlineParticipants()) {
            if (other.getUuid().equals(uuid)) {
                return other;
            }
        }
        return null;
    }

    /**
     * 抽一件道具。
     *
     * <p>默认按名次加权：{@code r} = 0 表示第一名、1 表示最后一名。
     * 落后者拿到攻击类（速冻胶/墨水弹）的权重随 r 线性上升，领先者<b>只出防御类</b>
     * —— 目的是抑制"第一名越跑越远"的滚雪球，让道具战成为追回来的手段，而不是扩大差距的工具。
     * 权重关掉（{@code boatRaceItemRanksWeighted=false}）时四件等概率。
     */
    private RaceItem drawItem(ServerPlayerEntity player) {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int total = Math.max(1, this.racers.size());
        RaceProgressTracker tracker = this.racers.get(player.getUuid());
        int place = tracker == null || tracker.place() <= 0 ? total : tracker.place();
        if (!cfg.boatRaceItemRanksWeighted || total <= 1) {
            return RaceItem.values()[this.random.nextInt(RaceItem.values().length)];
        }
        // 权重下标 = RaceItem.values() 顺序（NITRO / TRAP / INK），策略见 RaceLoot
        double[] weights = RaceLoot.weights(place, total);
        return RaceItem.values()[RaceLoot.pick(weights, this.random.nextDouble())];
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
     * 骑乘输入回调（由 {@code ServerPlayNetworkHandlerMixin} 转发）：检测"空格按下"的上升沿 → 用一件道具。
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
            this.tryUseItem(player);
        }
    }

    /**
     * 从物品栏里找一件道具用掉（空格触发用）。
     *
     * <p>优先用<b>手上拿着</b>的那件（玩家可以用滚轮选定要喷什么），手里不是道具时
     * 退化成"找第一颗氮气"—— 和加道具系统之前的行为保持一致（空格 = 喷氮气）。
     */
    public boolean tryUseItem(ServerPlayerEntity player) {
        if (!this.canUseItem(player)) {
            return false;
        }
        ItemStack held = player.getMainHandStack();
        RaceItem kind = RaceItem.of(held);
        if (kind != null) {
            return this.useItem(player, held, kind);
        }
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (RaceItem.of(stack) == RaceItem.NITRO) {
                return this.activateNitro(player, stack);
            }
        }
        PvPConfig cfg = PvPConfig.INSTANCE;
        if (this.takeEmptyItemMessage(player.getUuid(), this.match.matchTicks())) {
            player.sendMessage(Text.literal(cfg.boatRaceItemBoxesEnabled
                    ? "§7没有道具了（赛道上的宝箱里可以吃；滚轮选中一件再按空格/左键）"
                    : "§7没有氮气了（每 §f" + cfg.boatRaceNitroIntervalSeconds + "§7 秒补 1 个）"), true);
        }
        return false;
    }

    /** 兼容旧调用点：等价于按空格用一件道具。 */
    public boolean tryUseNitro(ServerPlayerEntity player) {
        return this.tryUseItem(player);
    }

    /**
     * 左键回调（由 {@code PvPMod} 的 AttackBlockCallback 转发，返回值会取消破坏方块）。
     *
     * <p>两个职责：
     * <ol>
     *   <li><b>用道具的第二通道</b>：骑船时右键会被客户端吞掉，空格虽然可靠但玩家习惯不一，
     *       左键在驾驶时一定会到（准星基本都压在冰面上）。</li>
     *   <li><b>禁止挖赛道</b>：竞速里破坏赛道方块只有害处 —— 空手挖浮冰 2.5 秒一块，
     *       挖出的坑让跟在后面的人掉出赛道回位。这条保护原来漏了（战桥/床战/幸运之柱/TNT 跑酷都有）。</li>
     * </ol>
     *
     * <p>注意左键<b>按住不放时客户端每 tick 都会重发</b> {@code START_DESTROY_BLOCK}（挖掘续期包），
     * 两种包在服务端无法区分，所以这里给"用道具"加了冷却；不加的话按住左键会瞬间把手上的道具全烧掉。
     */
    public void onAttackInput(ServerPlayerEntity player) {
        if (this.matchEnded || !this.started) {
            return;
        }
        long now = this.match.matchTicks();
        if (now < this.attackUseCooldownUntil.getOrDefault(player.getUuid(), 0L)) {
            return;
        }
        this.attackUseCooldownUntil.put(player.getUuid(), now + ATTACK_USE_COOLDOWN);
        if (this.tryUseItem(player)) {
            return;
        }
        // 没有可用道具：也不要刷屏，"没有道具"的提示交给 takeEmptyItemMessage 限流
        if (this.takeEmptyItemMessage(player.getUuid(), now)) {
            player.sendMessage(Text.literal("§7手上没有道具（滚轮选中一件再按空格/左键）"), true);
        }
    }

    /** "没有道具"这类提示的限流：同一名玩家 1 秒最多收到一次。 */
    private boolean takeEmptyItemMessage(UUID uuid, long now) {
        long last = this.emptyItemMessageAt.getOrDefault(uuid, Long.MIN_VALUE);
        if (now - last < 20) {
            return false;
        }
        this.emptyItemMessageAt.put(uuid, now);
        return true;
    }

    /** 能否使用道具：比赛进行中、未冲线、且正坐在自己的船上。 */
    private boolean canUseItem(ServerPlayerEntity player) {
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
     * 每 tick：定时补氮气（可关）+ 结算加速 + 刷新地表覆写。
     *
     * <p><b>为什么加速只能"换脚下方块"、不能"改船速"</b>：船是被玩家骑的载具，
     * 原版 {@code ServerPlayNetworkHandler.onVehicleMove} 每 tick 都用客户端上报的坐标覆盖服务端位置，
     * 服务端 {@code setVelocity} 的结果下一 tick 就被丢掉（实测给静止的船持续加推力，
     * 船速始终等于"刚加的那一点"，推不动）。客户端唯一真正读的物理输入是<b>方块滑度</b>。
     */
    private void tickNitro() {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int intervalSeconds = cfg.boatRaceNitroIntervalSeconds;
        int maxStack = Math.max(1, cfg.boatRaceNitroMaxStack);

        // 0 = 关闭定时补给（默认）：道具改由赛道上的道具箱产出，这里再送就太多了
        if (intervalSeconds > 0 && ++this.nitroGrantTimer >= intervalSeconds * 20) {
            this.nitroGrantTimer = 0;
            for (ServerPlayerEntity player : this.match.onlineParticipants()) {
                RaceProgressTracker tracker = this.racers.get(player.getUuid());
                if (tracker == null || tracker.finished()) {
                    continue;
                }
                if (this.nitroCount(player) >= maxStack) {
                    continue;
                }
                this.giveItem(player, RaceItem.NITRO);
                player.sendMessage(Text.literal("§b氮气 +1 §7（右键 或 空格使用）"), true);
            }
        }

        if (this.nitroTicks.isEmpty()) {
            // 没人加速：清掉所有加速窗口，但**不能**顺手还原 —— 速冻胶可能还在场
            this.nitroWindows.clear();
            this.refreshSurfaceOverlay();
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
                                        + "位置 ({}, {}, {})，覆写层 {} 格（其中加速 {} 格）",
                                player.getGameProfile().getName(), left,
                                String.format(java.util.Locale.ROOT, "%.3f", perTick),
                                String.format(java.util.Locale.ROOT, "%.1f", perTick * 20),
                                (int) Math.floor(now.x), (int) Math.floor(now.y), (int) Math.floor(now.z),
                                this.overlay.cellCount(), this.overlay.boostCellCount());
                    }
                }
            }
        }
        this.refreshSurfaceOverlay();
        if (this.nitroProbeTicks > 0 && --this.nitroProbeTicks <= 0) {
            LOGGER.info("[PvP] 氮气测速结束");
        }
    }

    /**
     * 铺氮气冰带：把船底（以及前方 {@link #NITRO_ICE_LEAD} 格）的赛道冰面换成氮气方块，
     * 让<b>客户端自己</b>把船开到更高的极速。
     *
     * <p>只记录"这次加速覆盖了哪些格子"，真正的涂/还原交给 {@link #refreshSurfaceOverlay()} 按并集统一结算，
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
     * 把"所有加速玩家窗口的并集"和"还在生效的速冻胶"交给 {@link SurfaceOverlay} 统一落方块。
     *
     * <p>这是"别人蹭不到氮气"的关键：船一走，身后的格子当 tick 就还原成普通冰面，
     * 所以跟着你走的人得不到任何加成（只有正好在你前方 2 格以内的人会短暂吃到）。
     *
     * <p>速冻胶不在这里铺（它是"一次性铺一片、到期还原"），但必须<b>走同一条刷新路径</b>：
     * 否则氮气还原时会把盖在同一格上的雪带一起抹掉。
     */
    private void refreshSurfaceOverlay() {
        ArenaWorld arena = this.match.arenaWorld();
        if (arena == null) {
            return;
        }
        Set<Long> union = new HashSet<>();
        for (Set<Long> window : this.nitroWindows.values()) {
            union.addAll(window);
        }
        int changed = this.overlay.refresh(arena, union, this.match.matchTicks());
        if (changed > 0 && this.debugItems) {
            LOGGER.info("[PvP] 地表覆写刷新：改动 {} 格（加速窗口 {}，减速 {}），覆写层共 {} 格",
                    changed, union.size(), this.overlay.trapCellCount(), this.overlay.cellCount());
        }
    }

    /** 某个玩家不再加速：丢掉他的窗口，下一 tick 统一还原。 */
    private void releaseNitroWindow(UUID uuid) {
        this.nitroWindows.remove(uuid);
    }

    /**
     * 立刻还原全部地表覆写（氮气 + 速冻胶）并清空状态。
     *
     * <p>比赛结束 / 清场前必须调用，且必须在 {@code RaceMapGenerator.clear} <b>之前</b>：
     * 精确清场会把赛道变成空气，之后这里再"还原"就会留下一条多余的冰带。
     */
    private int releaseSurfaceOverlay() {
        this.nitroWindows.clear();
        ArenaWorld arena = this.match.arenaWorld();
        int restored = this.overlay.restoreAll(arena);
        if (restored > 0) {
            LOGGER.info("[PvP] 亦可赛艇 seed {} 地表覆写已还原 {} 个方块（氮气/速冻胶）",
                    this.track.seed(), restored);
        }
        return restored;
    }

    // ==================== 调试入口 ====================

    /**
     * 调试：打印道具箱布局与地表覆写状态（{@code /pvp debug boatrace items}）。
     *
     * <p>离线自检只能证明几何正确，真机上"箱子到底有没有生成/能不能吃到"必须看这些数字，
     * 所以这里把布局、在场数、覆写格数、每个箱子的坐标一起打出来。
     */
    public String debugItemReport(ServerPlayerEntity player) {
        StringBuilder sb = new StringBuilder();
        sb.append("道具箱：在场 ").append(this.itemBoxes.liveCount())
                .append("/").append(this.itemBoxes.size())
                .append("（门后 ").append(d1(PvPConfig.INSTANCE.boatRaceItemBoxGateOffset))
                .append(" 格 × ").append(this.track.checkpointCount()).append(" 道门 × ")
                .append(PvPConfig.INSTANCE.boatRaceItemBoxLanes).append(" 车道）");
        sb.append("｜地表覆写 ").append(this.overlay.cellCount()).append(" 格（加速 ")
                .append(this.overlay.boostCellCount()).append(" / 减速 ")
                .append(this.overlay.trapCellCount()).append("）");
        sb.append("｜").append(player.getGameProfile().getName()).append(" 持有");
        for (RaceItem kind : RaceItem.values()) {
            sb.append(" ").append(kind.id()).append("=").append(this.itemCount(player, kind));
        }
        if (!this.itemBoxes.slots().isEmpty()) {
            RaceItemBoxes.Slot first = this.itemBoxes.slots().get(0);
            sb.append("｜首箱 (").append((int) first.x).append(",").append((int) first.z)
                    .append(") 门").append(first.gate);
        }
        this.debugItems = true;
        LOGGER.info("[PvP] 亦可赛艇 seed {} 道具自检：{}", this.track.seed(), sb);
        return sb.toString();
    }

    /** 调试：立刻在身后铺一条速冻胶（不走物品，直接验证覆写层与还原）。 */
    public boolean debugPlaceTrap(ServerPlayerEntity player) {
        return this.debugPlaceTrap(player, PvPConfig.INSTANCE.boatRaceItemTrapBehind);
    }

    /**
     * 调试：在指定偏移处铺速冻胶（0 = 正好铺在脚下）。
     *
     * <p>日志会打出<b>玩家脚下那一格</b>的方块，用来确认雪带真的落在赛道上而不是缓冲带里。
     */
    public boolean debugPlaceTrap(ServerPlayerEntity player, double behindBlocks) {
        if (!this.started) {
            return false;
        }
        // 复用同一条路径：造一个一次性堆叠，用完即弃
        ItemStack fake = RaceItem.TRAP.create();
        boolean ok = this.activateTrapAt(player, fake, behindBlocks);
        if (ok) {
            this.debugItems = true;
            ArenaWorld arena = this.match.arenaWorld();
            // 自检：采样一格，现在应该是雪块；等速冻胶过期后再采样一次，应该回到原方块
            Long sample = this.lastTrapCells.isEmpty() ? null : this.lastTrapCells.iterator().next();
            this.overlayProbePos = sample == null ? null : BlockPos.fromLong(sample);
            this.overlayProbeTicks = Math.max(1, PvPConfig.INSTANCE.boatRaceItemTrapSeconds) * 20 + 20;
            LOGGER.info("[PvP] 速冻胶调试：{} 铺设完成，覆写层 {} 格（{} 秒后过期，{} tick 后自检采样格）"
                            + "；采样格 {} = {}",
                    player.getGameProfile().getName(), this.overlay.cellCount(),
                    PvPConfig.INSTANCE.boatRaceItemTrapSeconds, this.overlayProbeTicks,
                    this.overlayProbePos, this.blockName(arena, this.overlayProbePos));
            LOGGER.info("[PvP] 速冻胶调试：{} 脚下 ({}, {}, {}) = {}",
                    player.getGameProfile().getName(),
                    player.getBlockX(), this.track.surfaceY(), player.getBlockZ(),
                    this.blockName(arena, new BlockPos(player.getBlockX(), this.track.surfaceY(),
                            player.getBlockZ())));
        }
        return ok;
    }

    /** 调试：等价于按空格用一件道具（走 tryUseItem 的完整分发，不需要客户端按键）。 */
    public boolean debugUseItem(ServerPlayerEntity player) {
        if (!this.started) {
            return false;
        }
        this.debugItems = true;
        boolean used = this.tryUseItem(player);
        LOGGER.info("[PvP] 调试用道具：{} → {}", player.getGameProfile().getName(),
                used ? "成功" : "失败（手上没有道具 / 没有目标）");
        return used;
    }

    /** 调试自检：打印采样格在覆写层清空后的方块（应与铺设前一致）。 */
    private void logOverlayProbe() {
        ArenaWorld arena = this.match.arenaWorld();
        LOGGER.info("[PvP] 速冻胶自检：到期后覆写层 {} 格（加速 {} / 减速 {}），采样格 {} = {}",
                this.overlay.cellCount(), this.overlay.boostCellCount(), this.overlay.trapCellCount(),
                this.overlayProbePos, this.blockName(arena, this.overlayProbePos));
    }

    private String blockName(ArenaWorld arena, BlockPos pos) {
        if (arena == null || pos == null) {
            return "无";
        }
        return net.minecraft.registry.Registries.BLOCK.getId(arena.getBlockState(pos).getBlock()).toString();
    }

    /** 调试：直接给玩家一件道具（{@code /pvp debug boatrace item <id>}）。 */
    public boolean debugGrantItem(ServerPlayerEntity player, RaceItem kind) {
        if (kind == null || !this.started) {
            return false;
        }
        this.giveItem(player, kind);
        LOGGER.info("[PvP] 调试发道具：{} → {}（持有 {} 件）",
                player.getGameProfile().getName(), kind.id(), this.itemCount(player));
        return true;
    }

    /**
     * 调试：拾取链路自检。
     *
     * <p><b>为什么需要它</b>：箱子的几何与扫掠判定可以离线验证（{@code ItemHarness}），
     * 但"箱子实体真的生成了""船开过去真的能吃到""吃到真的进了背包"这三步只有真机能验；
     * 而真机上我们没法注入按键把船开起来（客户端输入被环境禁掉了）。
     *
     * <p>做法：找到第一排里落在中心线上的那个箱子，沿中心线从它前面 4 格开始，
     * 每 tick 把玩家"重新发船"到下一格（船的位移是客户端权威的，所以只能走
     * 回位那套"下船 → 传送 → 重新发船"，直接 teleport 骑着的船会被客户端上报的坐标顶回去）。
     * 每一步都是 1 格/ tick 的正常位移，扫掠判定按真实路径走 —— 吃到箱子会走完整的
     * {@code tryPickupBox} → 抽签 → 入背包流程。
     */
    public boolean debugStartPickupProbe(ServerPlayerEntity player) {
        if (!this.started || this.itemBoxes.size() == 0) {
            return false;
        }
        RaceItemBoxes.Slot target = null;
        int bestGate = Integer.MAX_VALUE;
        double bestLat = Double.MAX_VALUE;
        for (RaceItemBoxes.Slot slot : this.itemBoxes.slots()) {
            double lat = this.track.distanceToCenterline(slot.x, slot.z);
            if (slot.gate < bestGate || (slot.gate == bestGate && lat < bestLat)) {
                bestGate = slot.gate;
                bestLat = lat;
                target = slot;
            }
        }
        if (target == null) {
            return false;
        }
        int samples = this.track.sampleCount();
        int center = this.track.nearestSample(target.x, target.z);
        List<double[]> path = new ArrayList<>();
        for (int offset = -4; offset <= 2; offset++) {
            int index = Math.floorMod(center + offset, samples);
            path.add(new double[]{this.track.sampleX(index), this.track.sampleZ(index)});
        }
        this.itemProbePath = path;
        this.itemProbeStep = 0;
        this.itemProbePlayer = player;
        this.itemProbeStartCount = this.itemCount(player);
        this.debugItems = true;
        LOGGER.info("[PvP] 拾取自检开始：{} 从箱前方 4 格起步，目标箱 ({}, {})（门 {}，横向 {}），"
                        + "当前持有 {} 件",
                player.getGameProfile().getName(), (int) target.x, (int) target.z, target.gate,
                d1(bestLat), this.itemProbeStartCount);
        return true;
    }

    /** 拾取自检每 tick 走一步。 */
    private void stepItemProbe() {
        ArenaWorld arena = this.match.arenaWorld();
        ServerPlayerEntity player = this.itemProbePlayer;
        if (arena == null || player == null || this.itemProbeStep >= this.itemProbePath.size()) {
            int after = player == null ? -1 : this.itemCount(player);
            LOGGER.info("[PvP] 拾取自检结束：持有 {} → {} 件，拾取{}",
                    this.itemProbeStartCount, after,
                    after > this.itemProbeStartCount ? "成功" : "失败（没吃到箱子）");
            this.itemProbePath = null;
            this.itemProbePlayer = null;
            return;
        }
        double[] point = this.itemProbePath.get(this.itemProbeStep++);
        double[] next = this.itemProbeStep < this.itemProbePath.size()
                ? this.itemProbePath.get(this.itemProbeStep) : point;
        float yaw = RaceTrackGenerator.yawOf(next[0] - point[0], next[1] - point[1]);
        double y = this.track.surfaceY() + 1.0;
        this.discardBoat(player);
        player.teleport(arena, point[0], y, point[1], yaw, 0.0F);
        player.setVelocity(Vec3d.ZERO);
        this.spawnBoatFor(player, point[0], y, point[1], yaw);
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
