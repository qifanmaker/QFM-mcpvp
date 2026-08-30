package com.example.pvp.match;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.bridge.BridgeLayout;
import com.example.pvp.arena.heartbeat.HeartbeatLayout;
import com.example.pvp.arena.hotpotato.HotPotatoLayout;
import com.example.pvp.arena.luckypillar.LuckyPillarLayout;
import com.example.pvp.arena.luckypillar.LuckyPillarLoot;
import com.example.pvp.arena.skywars.SkyWarsLayout;
import com.example.pvp.arena.tntrun.TntRunLayout;
import com.example.pvp.arena.skywars.SkyWarsMapGenerator;
import com.example.pvp.arena.skywars.SkyWarsTheme;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.config.StatsStore;
import com.example.pvp.gui.PvpGuiManager;
import com.example.pvp.kit.BridgeGear;
import com.example.pvp.kit.InventorySnapshot;
import com.example.pvp.kit.Kit;
import com.example.pvp.kit.KitApplicator;
import com.example.pvp.text.Messages;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
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
    private final Map<UUID, Integer> blockingRefresh = new HashMap<>(); // 格挡刷新倒计时
    private final Set<UUID> leftEarly = new HashSet<>(); // 旁观者提前离场
    private final Set<String> infoLines = new HashSet<>(); // 计分板信息栏行
    private final int initialCountdownTicks;

    /** 空岛战争地图布局（仅在 SKYWARS 模式非空，缩圈用）。 */
    private final SkyWarsLayout skywarsLayout;
    /** 空岛战争地图主题（仅 SKYWARS 模式非空，由比赛种子抽取，图腾救回点/展示用）。 */
    private final SkyWarsTheme skywarsTheme;
    /** 空岛战争地图种子：比赛 ID，OP 强制指定主题时会对齐低位使 pick 结果等于指定主题。 */
    private final int skywarsSeed;
    private int skywarsShrinkStage; // 已执行的缩圈档数
    private int skywarsLastKeepRadius = Integer.MAX_VALUE; // 上一档安全半径

    /** 战桥地图布局（仅战桥模式非空，进球判定/重生/清理用）。 */
    private final BridgeLayout bridgeLayout;
    private int bridgeRoundTicks; // 进球后回合间歇倒计时
    private int bridgeArrowRegenTicks; // 箭矢回复计时
    private final Set<UUID> bridgePendingRespawns = new HashSet<>(); // ALLOW_DEATH 拦截后延迟重生

    /** 幸运之柱地图布局（仅 LUCKY_PILLAR 模式非空，柱子保护/清理用）。 */
    private final LuckyPillarLayout luckyPillarLayout;
    private int luckyPillarItemTicks;   // 距下次随机物品发放（tick）
    private int luckyPillarEventTicks;  // 距下次随机事件（tick）
    private int luckyPillarOneHitTicks; // 一击必杀剩余时长（>0 表示生效中）
    private int luckyPillarArrowRainTicks; // 箭雨剩余时长
    private final Map<UUID, Integer> luckyPillarKills = new HashMap<>(); // 击杀数（超时决胜用）
    private int luckyPillarSwapTicks; // 位置交换倒计时（>0 表示即将交换）
    private UUID luckyPillarSwapA;
    private UUID luckyPillarSwapB;
    private final Random random = new Random();

    /** TNT 跑酷地图布局（仅 TNT_RUN 模式非空，方块消失判定/清理用）。 */
    private final TntRunLayout tntRunLayout;
    private int tntRunDropTimer; // 掉落物刷新计时（tick）
    private final Map<BlockPos, Integer> tntRunVanish = new HashMap<>(); // 待消失方块 → 到期 tick
    /** TNT 跑酷道具掉落物识别 tag（火焰弹/TNT）：带此 tag 的掉落物保留，其余（TNT 炸掉的羊毛等）全部清除。 */
    public static final String TNT_RUN_ITEM_TAG = "pvp.tntrun_item";
    /** 不死图腾非掉虚空复活后的摔落保护（UUID → 剩余 tick，期间每 tick 清零 fallDistance）。 */
    private final Map<UUID, Integer> totemFallSafeTicks = new HashMap<>();

    /** 心跳水立方地图布局（仅 HEARTBEAT 模式非空，多关卡塔/水池/玻璃地板判定用）。 */
    private HeartbeatLayout heartbeatLayout;
    /** 心跳水立方：玩家当前关卡（0 起，即已完成关卡数）。 */
    private final Map<UUID, Integer> heartbeatProgress = new HashMap<>();
    /** 心跳水立方：玩家到达当前进度的 tick（并列决胜：越小越早）。 */
    private final Map<UUID, Integer> heartbeatReachTick = new HashMap<>();
    /** 心跳水立方：已通关全部关卡的玩家（转幽灵观战）。 */
    private final Set<UUID> heartbeatFinished = new HashSet<>();
    /** 心跳水立方：通关全部关卡的先后顺序。 */
    private final List<UUID> heartbeatFinishOrder = new ArrayList<>();

    /** 烫手山芋地图布局（仅 HOT_POTATO 模式非空，障碍物保护用）。 */
    private HotPotatoLayout hotPotatoLayout;
    private UUID hotPotatoHolder;             // 当前山芋持有者
    private int hotPotatoTicks;               // 山芋爆炸倒计时（tick，自发放起累计；传递不重置）
    private int hotPotatoRespawnTicks;        // 山芋重生倒计时（tick，爆炸后）
    private int hotPotatoPassCooldown;        // 传递冷却剩余 tick（>0 时不可传递）
    private boolean hotPotatoTimeoutTriggered; // 超时爆炸已触发（防每 tick 重复）
    private static final int HOT_POTATO_PASS_COOLDOWN_TICKS = 4; // 传递冷却：0.2 秒
    private static final String HOT_POTATO_TAG = "pvp.hotpotato";

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

        // 计算出生点（空岛战争/战桥用同一份确定性布局，保证与地图生成一致）
        List<BlockPos> spawnPositions;
        if (type == MatchType.SKYWARS) {
            // OP 强制开赛可指定主题：消费一次性的强制主题；否则由 seed 随机抽取
            SkyWarsTheme forced = manager.consumePendingSkywarsTheme();
            int seed = id;
            if (forced != null) {
                this.skywarsTheme = forced;
                seed = SkyWarsTheme.alignSeed(seed, forced); // 地图布局仍随 id 变化
            } else {
                this.skywarsTheme = SkyWarsTheme.pick(seed);
            }
            this.skywarsSeed = seed;
            this.skywarsLayout = SkyWarsLayout.compute(template.getCenter(regionIndex), seed, this.players.size());
            this.bridgeLayout = null;
            this.luckyPillarLayout = null;
            this.tntRunLayout = null;
            spawnPositions = this.skywarsLayout.spawns();
        } else if (type.isBridge()) {
            this.skywarsLayout = null;
            this.skywarsTheme = null;
            this.skywarsSeed = id;
            this.luckyPillarLayout = null;
            this.tntRunLayout = null;
            this.bridgeLayout = BridgeLayout.compute(template.getCenter(regionIndex),
                    this.players.size(), type == MatchType.BRIDGE_1V1V1V1);
            // 每人出生点 = 所属队伍（teams 顺序即基地顺序）对应的笼位
            List<BridgeLayout.BridgeBase> bases = this.bridgeLayout.bases();
            spawnPositions = new ArrayList<>();
            for (ServerPlayerEntity player : this.players) {
                int teamIdx = 0;
                for (int i = 0; i < this.teams.size(); i++) {
                    if (this.teams.get(i).contains(player.getUuid())) {
                        teamIdx = i;
                        break;
                    }
                }
                spawnPositions.add(bases.get(Math.min(teamIdx, bases.size() - 1)).spawn());
            }
        } else if (type == MatchType.LUCKY_PILLAR) {
            this.skywarsLayout = null;
            this.skywarsTheme = null;
            this.skywarsSeed = id;
            this.bridgeLayout = null;
            this.tntRunLayout = null;
            // OP 可强制指定平台风格（地图）；null = 由 seed 随机
            LuckyPillarLayout.PlatformStyle forced = manager.consumePendingLuckyPillarStyle();
            this.luckyPillarLayout = LuckyPillarLayout.compute(template.getCenter(regionIndex),
                    id, this.players.size(), forced);
            spawnPositions = this.luckyPillarLayout.spawns();
        } else if (type == MatchType.TNT_RUN) {
            this.skywarsLayout = null;
            this.skywarsTheme = null;
            this.skywarsSeed = id;
            this.bridgeLayout = null;
            this.luckyPillarLayout = null;
            PvPConfig cfg = PvPConfig.INSTANCE;
            this.tntRunLayout = TntRunLayout.compute(template.getCenter(regionIndex),
                    Math.max(3, cfg.tntRunSize / 2), cfg.tntRunLayerCount, Math.max(2, cfg.tntRunLayerGap));
            spawnPositions = this.tntRunLayout.spawns;
        } else if (type == MatchType.HEARTBEAT) {
            this.skywarsLayout = null;
            this.skywarsTheme = null;
            this.skywarsSeed = id;
            this.bridgeLayout = null;
            this.luckyPillarLayout = null;
            this.tntRunLayout = null;
            PvPConfig cfg = PvPConfig.INSTANCE;
            this.heartbeatLayout = HeartbeatLayout.compute(template.getCenter(regionIndex), cfg, id);
            spawnPositions = this.heartbeatLayout.spawns;
        } else if (type == MatchType.HOT_POTATO) {
            this.skywarsLayout = null;
            this.skywarsTheme = null;
            this.skywarsSeed = id;
            this.bridgeLayout = null;
            this.luckyPillarLayout = null;
            this.tntRunLayout = null;
            this.heartbeatLayout = null;
            PvPConfig cfg = PvPConfig.INSTANCE;
            this.hotPotatoLayout = HotPotatoLayout.compute(template.getCenter(regionIndex), cfg, id);
            spawnPositions = this.hotPotatoLayout.spawns;
        } else {
            this.skywarsLayout = null;
            this.skywarsTheme = null;
            this.skywarsSeed = id;
            this.bridgeLayout = null;
            this.luckyPillarLayout = null;
            this.tntRunLayout = null;
            this.heartbeatLayout = null;
            this.hotPotatoLayout = null;
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
        if (type == MatchType.DUEL_1V1 || type == MatchType.SUMO || type == MatchType.PVP_1_8
                || type == MatchType.BRIDGE_1V1) {
            teams.add(new MatchTeam("红队", Formatting.RED, List.of(players.get(0))));
            teams.add(new MatchTeam("蓝队", Formatting.BLUE, List.of(players.get(1))));
        } else if (type == MatchType.DUEL_2V2 || type == MatchType.BRIDGE_2V2 || type == MatchType.BRIDGE_TEAM) {
            // 战桥 2v2 / 混战：总人数/2 平均分两队（队列已随机洗牌）
            teams.add(new MatchTeam("红队", Formatting.RED, players.subList(0, players.size() / 2)));
            teams.add(new MatchTeam("蓝队", Formatting.BLUE, players.subList(players.size() / 2, players.size())));
        } else if (type == MatchType.BRIDGE_1V1V1V1) {
            teams.add(new MatchTeam("红队", Formatting.RED, List.of(players.get(0))));
            teams.add(new MatchTeam("蓝队", Formatting.BLUE, List.of(players.get(1))));
            teams.add(new MatchTeam("绿队", Formatting.GREEN, List.of(players.get(2))));
            teams.add(new MatchTeam("黄队", Formatting.YELLOW, List.of(players.get(3))));
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

        // 不死图腾复活后的摔落保护：期间每 tick 清零 fallDistance，防止在高空复活后摔死
        if (!this.totemFallSafeTicks.isEmpty()) {
            for (UUID uuid : List.copyOf(this.totemFallSafeTicks.keySet())) {
                int left = this.totemFallSafeTicks.get(uuid) - 1;
                ServerPlayerEntity online = this.manager.getOnlinePlayer(uuid);
                if (online != null) {
                    online.fallDistance = 0;
                }
                if (left <= 0) {
                    this.totemFallSafeTicks.remove(uuid);
                } else {
                    this.totemFallSafeTicks.put(uuid, left);
                }
            }
        }

        // 超时保护：防止卡死的对局一直占用场地导致后续无法开赛
        int countdownStuckThreshold = this.initialCountdownTicks + 20 * 10;
        int activeTimeout;
        if (this.type == MatchType.SKYWARS) {
            activeTimeout = Math.max(100, PvPConfig.INSTANCE.skywarsTimeoutSeconds * 20);
        } else if (this.type.isBridge()) {
            activeTimeout = Math.max(100, PvPConfig.INSTANCE.bridgeTimeoutSeconds * 20);
        } else if (this.type == MatchType.LUCKY_PILLAR) {
            activeTimeout = Math.max(100, PvPConfig.INSTANCE.luckyPillarTimeoutSeconds * 20);
        } else if (this.type == MatchType.TNT_RUN) {
            activeTimeout = Math.max(100, PvPConfig.INSTANCE.tntRunTimeoutSeconds * 20);
        } else if (this.type == MatchType.HEARTBEAT) {
            activeTimeout = Math.max(100, PvPConfig.INSTANCE.heartbeatTimeoutSeconds * 20);
        } else if (this.type == MatchType.HOT_POTATO) {
            activeTimeout = Math.max(100, PvPConfig.INSTANCE.hotPotatoTimeoutSeconds * 20);
        } else {
            activeTimeout = Math.max(100, PvPConfig.INSTANCE.matchTimeoutSeconds * 20);
        }
        if (this.state == MatchState.COUNTDOWN && this.ticks > countdownStuckThreshold) {
            LOGGER.warn("[PvP] 比赛 #{} 倒计时异常，强制取消", this.id);
            this.cancelMatch("倒计时异常");
            return;
        }
        if (this.state == MatchState.ACTIVE && this.ticks > this.initialCountdownTicks + activeTimeout) {
            LOGGER.warn("[PvP] 比赛 #{} 超时（{} 秒）结束", this.id, activeTimeout / 20);
            if (this.type == MatchType.HOT_POTATO) {
                // 烫手山芋超时：当前持有者爆炸淘汰（一次性），若之后仍互传拖时间则 60 秒后强制平局
                if (!this.hotPotatoTimeoutTriggered) {
                    this.hotPotatoTimeoutTriggered = true;
                    ServerPlayerEntity holder = this.onlineHotPotatoHolder();
                    if (holder != null) {
                        this.explodeHotPotato(holder);
                    } else {
                        this.finishMatch(this.timeoutWinner());
                    }
                } else if (this.ticks > this.initialCountdownTicks + activeTimeout + 60 * 20) {
                    this.finishMatch(this.timeoutWinner());
                }
                return;
            }
            this.finishMatch(this.timeoutWinner());
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
                if (this.type.isBridge()) {
                    this.tickBridge();
                }
                if (this.type == MatchType.PVP_1_8 || this.type == MatchType.SKYWARS || this.type.isBridge()
                        || this.type == MatchType.LUCKY_PILLAR) {
                    this.tickLegacyBlocking();
                }
                if (this.type == MatchType.SKYWARS) {
                    this.tickSkywarsShrink();
                    this.tickSkywarsTotem();
                    this.tickSkywarsCompass();
                }
                if (this.type == MatchType.LUCKY_PILLAR) {
                    this.tickLuckyPillar();
                }
                if (this.type == MatchType.TNT_RUN) {
                    this.tickTntRun();
                }
                if (this.type == MatchType.HEARTBEAT) {
                    this.tickHeartbeat();
                }
                if (this.type == MatchType.HOT_POTATO) {
                    this.tickHotPotato();
                }
                this.checkWinCondition();
            }
            case CELEBRATING -> this.tickCelebration();
            default -> {
            }
        }

        this.tickGhosts();
    }

    /** 幽灵被动行为兜底：脚下被放方块 / 靠近掉落物（会被吸取）时弹开。 */
    private void tickGhosts() {
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }
        for (ServerPlayerEntity player : this.players) {
            if (!this.eliminated.contains(player.getUuid())) {
                continue;
            }
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null || online.getWorld() != arena) {
                continue;
            }
            // 1) 脚下/身体内出现方块（场上玩家往幽灵脚下搭方块）→ 弹开
            BlockPos feet = online.getBlockPos();
            boolean solidBelow = !arena.getBlockState(feet).isAir()
                    || !arena.getBlockState(feet.down()).isAir()
                    || !arena.getBlockState(feet.down(2)).isAir();
            // 2) 附近有掉落物（幽灵会吸取）→ 弹开
            boolean itemsNearby = !arena.getEntitiesByClass(ItemEntity.class,
                    online.getBoundingBox().expand(1.0), e -> true).isEmpty();
            if (solidBelow || itemsNearby) {
                this.knockGhostAway(online);
            }
        }
    }

    /** 把幽灵向上弹开 3 格（配合飞行能力脱离方块/掉落物范围）。 */
    private void knockGhostAway(ServerPlayerEntity ghost) {
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }
        ghost.teleport(arena, ghost.getX(), ghost.getY() + 3.0, ghost.getZ(), ghost.getYaw(), ghost.getPitch());
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
        if (keepRadius < this.skywarsMinShrinkRadius()) {
            keepRadius = this.skywarsMinShrinkRadius();
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
        this.broadcastTitleBig("§c§l缩圈！", "§f安全区缩小到半径 " + keepRadius + " 格");

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

    /** 空岛战争：掉入虚空且持有不死图腾 → 消耗一个，把玩家传送到中岛中心救回。 */
    private void tickSkywarsTotem() {
        if (this.skywarsLayout == null) {
            return;
        }
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }
        double rescueY = ArenaTemplate.PLATFORM_Y - 8; // 岛面下 8 格且不在地面视为掉入虚空
        for (ServerPlayerEntity player : this.players) {
            if (this.eliminated.contains(player.getUuid())) {
                continue;
            }
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null || online.getWorld() != arena) {
                continue;
            }
            if (online.getY() >= rescueY || online.isOnGround()) {
                continue;
            }
            this.tryTotemSave(online, true); // 掉虚空：传送救回
        }
    }

    /**
     * 不死图腾救场：消耗背包中任意位置的一个图腾，清状态回满血、给吸收/再生。
     * 空岛/幸运之柱通用——掉入虚空由各模式 tick 调用（voidFall=true：传送到救回点），
     * 受到致死伤害由 PvPMod 的 ALLOW_DEATH 调用（voidFall=false：按原版逻辑原地复活）。
     * 返回 true 表示成功消耗并救回（调用方据此取消死亡/淘汰）。
     */
    public boolean tryTotemSave(ServerPlayerEntity player, boolean voidFall) {
        if (this.type != MatchType.SKYWARS && this.type != MatchType.LUCKY_PILLAR) {
            return false;
        }
        if (this.state != MatchState.ACTIVE || this.eliminated.contains(player.getUuid())) {
            return false;
        }
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null || player.getWorld() != arena) {
            return false;
        }
        int slot = this.findTotemSlot(player);
        if (slot < 0) {
            return false;
        }
        player.getInventory().getStack(slot).decrement(1); // 消耗一个图腾（背包里任意位置都算）
        player.getWorld().sendEntityStatus(player, (byte) 35); // 播放不死图腾激活动画
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.clearStatusEffects();
        if (voidFall) {
            // 掉虚空：传送到安全点救回（原有逻辑），给吸收/再生
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 100, 1)); // 吸收 II
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 400, 1)); // 再生 II
            BlockPos rescue = this.totemRescuePoint(player);
            int y = Math.max(rescue.getY() + 2, ArenaTemplate.PLATFORM_Y + 1);
            player.teleport(arena, rescue.getX() + 0.5, y, rescue.getZ() + 0.5, player.getYaw(), player.getPitch());
            player.sendMessage(Messages.gold("不死图腾生效！你被传回安全点！"), false);
        } else {
            // 非掉虚空（被击杀等致死伤害）：按原版图腾逻辑——原地复活，给原版效果（吸收II/再生II/抗火）
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 2400, 1));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 900, 1));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 800, 0));
            // 可能在半空/高处被救活（被打落/坠落中），给 5 秒摔落保护（tick 里清零 fallDistance），避免落地摔死
            player.fallDistance = 0;
            this.totemFallSafeTicks.put(player.getUuid(), 100);
            player.sendMessage(Messages.gold("不死图腾救了你一命！"), false);
        }
        this.broadcast(Messages.warn("§e" + player.getGameProfile().getName() + "§r 依靠不死图腾死里逃生！"));
        return true;
    }

    /** 不死图腾救回点：空岛=中岛（末地主题为中岛环上的安全点，避免掉进空心中央），幸运之柱=自己的柱顶。 */
    private BlockPos totemRescuePoint(ServerPlayerEntity player) {
        if (this.type == MatchType.SKYWARS && this.skywarsLayout != null && this.skywarsTheme != null) {
            return this.skywarsTheme.rescuePoint(this.skywarsLayout.middle());
        }
        if (this.type == MatchType.LUCKY_PILLAR) {
            BlockPos spawn = this.spawns.get(player.getUuid());
            if (spawn != null) {
                return spawn;
            }
        }
        return this.template.getCenter(this.regionIndex);
    }

    /** 玩家背包中第一个不死图腾的槽位（主背包+副手），没有则 -1。 */
    private int findTotemSlot(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(Items.TOTEM_OF_UNDYING) && stack.getCount() > 0) {
                return i;
            }
        }
        return -1;
    }

    /** 空岛战争：追踪罗盘每秒指向最近的敌人（未持有罗盘的玩家跳过）。 */
    private void tickSkywarsCompass() {
        if (this.skywarsLayout == null || this.ticks % 20 != 0) {
            return;
        }
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }
        for (ServerPlayerEntity player : this.players) {
            if (this.eliminated.contains(player.getUuid())) {
                continue;
            }
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null || online.getWorld() != arena) {
                continue;
            }
            ServerPlayerEntity nearest = this.nearestSkywarsEnemy(online);
            if (nearest == null) {
                continue;
            }
            boolean updated = false;
            for (int i = 0; i < online.getInventory().size(); i++) {
                ItemStack stack = online.getInventory().getStack(i);
                if (!stack.isOf(Items.COMPASS) || PvpGuiManager.isMenuItem(stack)) {
                    continue; // 跳过主菜单指南针（竞技场内一般没有）
                }
                stack.set(DataComponentTypes.LODESTONE_TRACKER,
                        new LodestoneTrackerComponent(Optional.of(
                                GlobalPos.create(ArenaWorldManager.ARENA_WORLD_KEY, nearest.getBlockPos())), true));
                updated = true;
            }
            if (updated) {
                online.currentScreenHandler.sendContentUpdates();
            }
        }
    }

    /** 距离自己最近的未淘汰在线敌人（FFA 模式下其余玩家全是敌人）。 */
    private ServerPlayerEntity nearestSkywarsEnemy(ServerPlayerEntity self) {
        ServerPlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayerEntity player : this.players) {
            if (player.getUuid().equals(self.getUuid())) {
                continue;
            }
            if (this.eliminated.contains(player.getUuid())) {
                continue;
            }
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null || online.getWorld() != self.getWorld()) {
                continue;
            }
            double dist = online.squaredDistanceTo(self);
            if (dist < bestDist) {
                bestDist = dist;
                best = online;
            }
        }
        return best;
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

    // ---------- 幸运之柱 (Lucky Pillar) ----------

    /** 幸运之柱每帧逻辑：随机物品发放、随机事件、一击必杀/箭雨倒计时。 */
    private void tickLuckyPillar() {
        PvPConfig cfg = PvPConfig.INSTANCE;

        // 随机物品发放：每隔 N 秒每名存活玩家获得 1 件（动作栏提示，不广播刷屏）
        if (--this.luckyPillarItemTicks <= 0) {
            this.luckyPillarItemTicks = cfg.luckyPillarItemIntervalSeconds * 20;
            for (ServerPlayerEntity online : this.aliveOnlineInArena()) {
                LuckyPillarLoot.giveRandomItem(online, this.random);
            }
        }

        // 随机事件
        if (cfg.luckyPillarEvents) {
            if (--this.luckyPillarEventTicks <= 0) {
                this.luckyPillarEventTicks = cfg.luckyPillarEventIntervalSeconds * 20;
                this.triggerLuckyPillarEvent();
            }
        }

        // 一击必杀倒计时：结束关闭全局标记
        if (this.luckyPillarOneHitTicks > 0) {
            this.luckyPillarOneHitTicks--;
            if (this.luckyPillarOneHitTicks <= 0) {
                PvPMod.oneHitKillActive = false;
                this.broadcastTitleBig("§c一击必杀结束", null);
            }
        }

        // 箭雨倒计时：期间每 10 tick 在随机存活玩家头顶落一支箭
        if (this.luckyPillarArrowRainTicks > 0) {
            this.luckyPillarArrowRainTicks--;
            if (this.luckyPillarArrowRainTicks % 10 == 0) {
                this.spawnRainArrow();
            }
        }

        // 位置交换倒计时：3 秒后执行互换（目标失效则重新挑选）
        if (this.luckyPillarSwapTicks > 0) {
            this.luckyPillarSwapTicks--;
            if (this.luckyPillarSwapTicks % 20 == 0) {
                this.broadcastTitle("位置交换 " + ((this.luckyPillarSwapTicks + 19) / 20) + "s");
            }
            if (this.luckyPillarSwapTicks <= 0) {
                this.performPositionSwap();
            }
        }

        // 掉入虚空且持有不死图腾 → 消耗救回自己柱顶（每 tick 检查，先于死亡判定保证必被救到）
        double rescueY = ArenaTemplate.PLATFORM_Y - 8;
        for (ServerPlayerEntity online : this.aliveOnlineInArena()) {
            if (online.getY() < rescueY && !online.isOnGround()) {
                this.tryTotemSave(online, true); // 掉虚空：传送救回
            }
        }

        // 掉出平台下方 20 格 → 淘汰（"掉下平台 20 格死亡"）
        if (this.luckyPillarLayout != null) {
            int deathY = this.luckyPillarLayout.platformY() - LuckyPillarLayout.FALL_DEATH_BELOW_PLATFORM;
            for (ServerPlayerEntity online : this.aliveOnlineInArena()) {
                if (online.getY() < deathY) {
                    this.eliminate(online, EliminationCause.VOID);
                }
            }
        }
    }

    /** 当前存活的在线玩家列表（竞技场内、排除幽灵/离线，幸运之柱/TNT 跑酷通用）。 */
    private List<ServerPlayerEntity> aliveOnlineInArena() {
        List<ServerPlayerEntity> list = new ArrayList<>();
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        for (ServerPlayerEntity player : this.players) {
            if (this.eliminated.contains(player.getUuid())) {
                continue;
            }
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null || online.getWorld() != arena) {
                continue;
            }
            list.add(online);
        }
        return list;
    }

    /** 触发一个随机事件（广播 + 生效）。 */
    private void triggerLuckyPillarEvent() {
        switch (this.random.nextInt(6)) {
            case 0 -> this.triggerOneHitKill();
            case 1 -> this.triggerArrowRain();
            case 2 -> this.triggerLightning();
            case 3 -> this.triggerTntRain();
            case 4 -> this.triggerPositionSwap();
            case 5 -> this.triggerSupplyRush();
        }
    }

    /** 一击必杀：持续 N 秒内所有伤害致死（LivingEntityMixin 检查全局标记）。 */
    private void triggerOneHitKill() {
        PvPMod.oneHitKillActive = true;
        this.luckyPillarOneHitTicks = Math.max(20, PvPConfig.INSTANCE.luckyPillarOneHitSeconds * 20);
        this.broadcastTitleBig("§4§l一击必杀！！",
                "§c" + PvPConfig.INSTANCE.luckyPillarOneHitSeconds + " 秒内所有攻击直接致死！");
    }

    /** 箭雨：5 秒内每 10 tick 在随机存活玩家头顶落一支下坠的箭。 */
    private void triggerArrowRain() {
        this.luckyPillarArrowRainTicks = 100;
        this.broadcastTitleBig("§e§l箭雨来袭！！", "§f快躲开！");
    }

    private void spawnRainArrow() {
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }
        List<ServerPlayerEntity> alive = this.aliveOnlineInArena();
        if (alive.isEmpty()) {
            return;
        }
        ServerPlayerEntity target = alive.get(this.random.nextInt(alive.size()));
        double x = target.getX() + (this.random.nextDouble() - 0.5) * 4.0;
        double y = target.getY() + 14 + this.random.nextInt(5);
        double z = target.getZ() + (this.random.nextDouble() - 0.5) * 4.0;
        ArrowEntity arrow = EntityType.ARROW.create(arena);
        if (arrow != null) {
            arrow.refreshPositionAndAngles(x, y, z, 0, 0);
            arrow.setVelocity((this.random.nextDouble() - 0.5) * 0.3, -1.2,
                    (this.random.nextDouble() - 0.5) * 0.3);
            arrow.setDamage(2.0 + this.random.nextDouble() * 5.0);
            arrow.setOwner(null);
            arena.spawnEntity(arrow);
        }
    }

    /** 雷击：3 道闪电劈向随机存活玩家。 */
    private void triggerLightning() {
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena != null) {
            List<ServerPlayerEntity> alive = this.aliveOnlineInArena();
            for (int i = 0; i < 3 && !alive.isEmpty(); i++) {
                ServerPlayerEntity target = alive.get(this.random.nextInt(alive.size()));
                LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(arena);
                if (bolt != null) {
                    bolt.setPos(target.getX(), target.getY(), target.getZ());
                    arena.spawnEntity(bolt);
                }
            }
        }
        this.broadcastTitleBig("§e§l天雷滚滚！！", null);
    }

    /** TNT 雨：随机存活玩家头顶落下 TNT（4 秒爆炸，可把玩家炸落柱子）。 */
    private void triggerTntRain() {
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena != null) {
            List<ServerPlayerEntity> alive = this.aliveOnlineInArena();
            for (int i = 0; i < 4 && !alive.isEmpty(); i++) {
                ServerPlayerEntity target = alive.get(this.random.nextInt(alive.size()));
                for (int k = 0; k < 2; k++) {
                    TntEntity tnt = new TntEntity(arena,
                            target.getX() + (this.random.nextDouble() - 0.5) * 2.0,
                            target.getY() + 10 + this.random.nextInt(3),
                            target.getZ() + (this.random.nextDouble() - 0.5) * 2.0, null);
                    tnt.setFuse(80); // 4 秒爆炸（比默认 2 秒多 2 秒，给人反应时间）
                    arena.spawnEntity(tnt);
                }
            }
        }
        this.broadcastTitleBig("§6§lTNT 雨！！", "§f快躲开！");
    }

    /** 位置交换：随机两名存活玩家，3 秒倒计时后互换位置（清速度，避免互换后飞出柱子）。 */
    private void triggerPositionSwap() {
        List<ServerPlayerEntity> alive = this.aliveOnlineInArena();
        if (alive.size() < 2) {
            return;
        }
        ServerPlayerEntity a = alive.get(this.random.nextInt(alive.size()));
        ServerPlayerEntity b = alive.get(this.random.nextInt(alive.size()));
        if (a == b) {
            b = alive.get((alive.indexOf(a) + 1) % alive.size());
        }
        this.luckyPillarSwapA = a.getUuid();
        this.luckyPillarSwapB = b.getUuid();
        this.luckyPillarSwapTicks = 60; // 3 秒倒计时
        // 交换前提示：明确告知谁和谁在 3 秒后互换
        this.broadcastTitleBig("§d§l位置交换！！",
                "§f3 秒后 " + a.getGameProfile().getName() + " 与 " + b.getGameProfile().getName() + " 互换位置！");
    }

    /** 位置交换倒计时结束：执行互换；目标下线/被淘汰则重新挑两人。 */
    private void performPositionSwap() {
        this.luckyPillarSwapTicks = 0;
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }
        ServerPlayerEntity a = this.manager.getOnlinePlayer(this.luckyPillarSwapA);
        ServerPlayerEntity b = this.manager.getOnlinePlayer(this.luckyPillarSwapB);
        if (a == null || b == null || a == b
                || this.eliminated.contains(a.getUuid()) || this.eliminated.contains(b.getUuid())) {
            List<ServerPlayerEntity> alive = this.aliveOnlineInArena();
            if (alive.size() < 2) {
                return;
            }
            a = alive.get(this.random.nextInt(alive.size()));
            b = alive.get(this.random.nextInt(alive.size()));
            if (a == b) {
                b = alive.get((alive.indexOf(a) + 1) % alive.size());
            }
        }
        double ax = a.getX(), ay = a.getY(), az = a.getZ();
        float ayaw = a.getYaw(), apitch = a.getPitch();
        a.teleport(arena, b.getX(), b.getY(), b.getZ(), b.getYaw(), b.getPitch());
        b.teleport(arena, ax, ay, az, ayaw, apitch);
        a.setVelocity(Vec3d.ZERO);
        b.setVelocity(Vec3d.ZERO);
        a.velocityDirty = true;
        b.velocityDirty = true;
        this.broadcast(Messages.warn("§d§l位置互换！！"));
    }

    /** 补给潮：全体存活玩家立即各得 3 件随机物品。 */
    private void triggerSupplyRush() {
        for (ServerPlayerEntity online : this.aliveOnlineInArena()) {
            LuckyPillarLoot.giveRandomItems(online, this.random, 3);
        }
        this.broadcastTitleBig("§a§l补给潮！！", "§f全员获得 3 件随机物品！");
    }

    /** 击杀登记（ALLOW_DEATH 回调），超时决胜用。 */
    public void registerLuckyPillarKill(ServerPlayerEntity killer) {
        this.luckyPillarKills.merge(killer.getUuid(), 1, Integer::sum);
    }

    /** 超时结算：存活者中击杀数最高者获胜；无人有击杀或并列则平局（返回 null）。 */
    private MatchTeam luckyPillarTimeoutWinner() {
        MatchTeam team = this.teams.get(0);
        ServerPlayerEntity best = null;
        int bestKills = 0;
        for (ServerPlayerEntity player : team.getAlivePlayers()) {
            int kills = this.luckyPillarKills.getOrDefault(player.getUuid(), 0);
            if (kills > bestKills) {
                bestKills = kills;
                best = player;
            } else if (kills == bestKills && kills > 0) {
                best = null; // 并列有击杀 → 平局
            }
        }
        if (best == null || bestKills == 0) {
            return null;
        }
        return team;
    }

    /** 超时结算入口：战桥按比分、幸运之柱按击杀、心跳水立方按当前进度排名、其余平局。 */
    private MatchTeam timeoutWinner() {
        if (this.type.isBridge()) {
            return this.bridgeTimeoutWinner();
        }
        if (this.type == MatchType.LUCKY_PILLAR) {
            return this.luckyPillarTimeoutWinner();
        }
        if (this.type == MatchType.HEARTBEAT) {
            // 超时按当前进度排名结算（announceResult 显示完整排名）
            return this.teams.get(0);
        }
        return null;
    }

    // ---------- 心跳水立方 (Heartbeat) ----------

    /** 心跳水立方每帧逻辑：过关/失误判定、摔落免疫、通关转幽灵。 */
    private void tickHeartbeat() {
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null || this.heartbeatLayout == null) {
            return;
        }
        HeartbeatLayout layout = this.heartbeatLayout;
        for (ServerPlayerEntity online : this.aliveOnlineInArena()) {
            UUID uuid = online.getUuid();
            int level = this.heartbeatProgress.getOrDefault(uuid, 0);
            if (level >= layout.levelCount) {
                continue; // 已通关全部关卡（转幽灵，正常不会出现在存活列表）
            }
            online.fallDistance = 0; // 摔落免疫：纯精度挑战，不因摔伤而死
            double y = online.getY();
            BlockPos c = layout.center(level);
            double dx = online.getX() - (c.getX() + 0.5);
            double dz = online.getZ() - (c.getZ() + 0.5);

            // 1) 过关：低于池面且落在中央水池内
            if (y < layout.poolY + 0.3) {
                if (layout.isInPool(level, online.getX(), online.getZ())) {
                    this.advanceHeartbeatLevel(online);
                } else {
                    // 落到池面平台外 / 掉穿水池 → 失误
                    this.failHeartbeatLevel(online);
                }
                continue;
            }
            // 2) 飞出塔边（塔外 3 格兜底）→ 失误
            if (Math.abs(dx) > layout.halfSize + 3 || Math.abs(dz) > layout.halfSize + 3) {
                this.failHeartbeatLevel(online);
                continue;
            }
            // 3) 落地时脚下是玻璃地板 → 撞上地板，失误
            if (online.isOnGround()) {
                BlockPos feet = online.getBlockPos();
                if (arena.getBlockState(feet).getBlock() == layout.floorBlock
                        || arena.getBlockState(feet.down()).getBlock() == layout.floorBlock) {
                    this.failHeartbeatLevel(online);
                }
            }
        }
    }

    /** 过关：进度+1，传送到下一关塔顶；通关全部关卡则转幽灵观战。 */
    private void advanceHeartbeatLevel(ServerPlayerEntity online) {
        UUID uuid = online.getUuid();
        HeartbeatLayout layout = this.heartbeatLayout;
        int level = this.heartbeatProgress.getOrDefault(uuid, 0); // 0 起当前关
        int next = level + 1;
        String name = online.getGameProfile().getName();

        if (next >= layout.levelCount) {
            // 通关全部关卡
            if (this.heartbeatFinished.add(uuid)) {
                this.heartbeatProgress.put(uuid, layout.levelCount);
                this.heartbeatReachTick.put(uuid, this.ticks);
                this.heartbeatFinishOrder.add(uuid);
                this.eliminated.add(uuid);
                for (MatchTeam team : this.teams) {
                    team.eliminate(online);
                }
                this.makeGhost(online); // 转幽灵观战
                // 幽灵传送到最后一关塔顶上空俯视全图（makeGhost 默认观战点在普通平台高度，会落在塔内）
                ArenaWorld arena = this.manager.getArenaManager().getWorld();
                if (arena != null) {
                    int last = layout.levelCount - 1;
                    BlockPos topCenter = layout.center(last);
                    online.teleport(arena, topCenter.getX() + 0.5, layout.topY(last) + 15,
                            topCenter.getZ() + 0.5, 0, 90);
                }
                this.broadcast(Messages.gold("§e" + name + "§r 完成了全部 " + layout.levelCount + " 关！"));
                this.broadcastTitleBig("§a§l全部关卡完成！", "§f" + name + " 通关！");
            }
            this.checkWinCondition();
            return;
        }

        this.heartbeatProgress.put(uuid, next);
        this.heartbeatReachTick.put(uuid, this.ticks);
        this.teleportHeartbeatLevel(online, next);
        this.broadcastTitleBig("§b§l第 " + (next + 1) + " 关！", "§f" + name + " 进入第 " + (next + 1) + " 关");
        this.broadcast(Messages.gold("§e" + name + "§r 完成第 " + (level + 1) + " 关，进入第 " + (next + 1) + " 关！"));
    }

    /** 失误：传送回当前关塔顶重试（不淘汰），并中心大字提示。 */
    private void failHeartbeatLevel(ServerPlayerEntity online) {
        UUID uuid = online.getUuid();
        int level = this.heartbeatProgress.getOrDefault(uuid, 0);
        if (level >= this.heartbeatLayout.levelCount) {
            return; // 已通关
        }
        this.teleportHeartbeatLevel(online, level);
        online.setVelocity(Vec3d.ZERO);
        online.velocityDirty = true;
        if (online.networkHandler != null) {
            online.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 25, 10));
            online.networkHandler.sendPacket(new SubtitleS2CPacket(
                    Text.literal("§f回到第 " + (level + 1) + " 关起点")));
            online.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§c§l失误了！")));
        }
    }

    /** 心跳水立方玩家死亡（ALLOW_DEATH 回调）：回当前关塔顶，不淘汰。 */
    public void onHeartbeatDeath(ServerPlayerEntity player) {
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.fallDistance = 0;
        if (this.state == MatchState.ACTIVE) {
            this.failHeartbeatLevel(player);
        }
    }

    /** 传送到第 level 关的出发台（按玩家在 players 中的序号取环上出生点）。 */
    private void teleportHeartbeatLevel(ServerPlayerEntity online, int level) {
        int idx = 0;
        for (int i = 0; i < this.players.size(); i++) {
            if (this.players.get(i).getUuid().equals(online.getUuid())) {
                idx = i;
                break;
            }
        }
        BlockPos spawn = this.heartbeatLayout.levelTopSpawn(level, idx);
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        BlockPos c = this.heartbeatLayout.center(level);
        float yaw = (float) Math.toDegrees(Math.atan2(c.getX() - spawn.getX(), c.getZ() - spawn.getZ()));
        online.teleport(arena, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, yaw, 0);
        online.setVelocity(Vec3d.ZERO);
        online.velocityDirty = true;
        online.fallDistance = 0;
    }

    /** 心跳水立方胜者：关卡进度最高；并列取先到达者（reachTick 最小）。 */
    private ServerPlayerEntity bestHeartbeatPlayer() {
        ServerPlayerEntity best = null;
        int bestProgress = -1;
        int bestTick = Integer.MAX_VALUE;
        for (ServerPlayerEntity player : this.players) {
            int progress = this.heartbeatProgress.getOrDefault(player.getUuid(), 0);
            int reach = this.heartbeatReachTick.getOrDefault(player.getUuid(), 0);
            if (progress > bestProgress || (progress == bestProgress && reach < bestTick)) {
                bestProgress = progress;
                bestTick = reach;
                best = player;
            }
        }
        return best;
    }

    /** 心跳水立方结算：广播完整排名（进度降序，并列按先到达），并公告胜者。 */
    private void announceHeartbeatResult() {
        List<UUID> order = new ArrayList<>(this.players.stream()
                .map(ServerPlayerEntity::getUuid).toList());
        order.sort((a, b) -> {
            int pa = this.heartbeatProgress.getOrDefault(a, 0);
            int pb = this.heartbeatProgress.getOrDefault(b, 0);
            if (pa != pb) {
                return Integer.compare(pb, pa);
            }
            return Integer.compare(this.heartbeatReachTick.getOrDefault(a, 0),
                    this.heartbeatReachTick.getOrDefault(b, 0));
        });
        HeartbeatLayout layout = this.heartbeatLayout;

        this.broadcast(Messages.gold("§6§l心跳水立方结束！"));
        for (int i = 0; i < order.size(); i++) {
            UUID uuid = order.get(i);
            int progress = this.heartbeatProgress.getOrDefault(uuid, 0);
            ServerPlayerEntity online = this.manager.getOnlinePlayer(uuid);
            String name = online != null ? online.getGameProfile().getName() : "§7(离线)";
            String medal = switch (i) {
                case 0 -> "§6§l#1";
                case 1 -> "§e§l#2";
                case 2 -> "§b§l#3";
                default -> "§7#" + (i + 1);
            };
            String prog = this.heartbeatFinished.contains(uuid)
                    ? "全部通关"
                    : "第 " + Math.min(progress + 1, layout.levelCount) + "/" + layout.levelCount + " 关";
            this.broadcast(Text.literal(medal + " §f" + name + " §7(" + prog + ")"));
        }
        ServerPlayerEntity winner = this.bestHeartbeatPlayer();
        if (winner != null) {
            this.broadcast(Messages.gold("§6" + winner.getGameProfile().getName() + "§r 以最高关卡进度获胜！"));
        } else {
            this.broadcast(Messages.warn("本局平局"));
        }
    }

    // ---------- 烫手山芋 (Hot Potato) ----------

    /** 烫手山芋每帧逻辑：持有倒计时/爆炸、山芋粘主手、重生、掉落物清理、掉出平台兜底。 */
    private void tickHotPotato() {
        PvPConfig cfg = PvPConfig.INSTANCE;
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }

        // 1) 持有者状态检查（离线/被淘汰 → 山芋重生）
        ServerPlayerEntity holder = this.onlineHotPotatoHolder();
        if (this.hotPotatoHolder != null && holder == null) {
            this.clearHotPotatoItems();
            this.hotPotatoHolder = null;
            this.scheduleHotPotatoRespawn();
        }

        // 2) 山芋倒计时、爆炸与顶部提示
        if (holder != null) {
            this.ensureHotPotatoInHand(holder);
            this.hotPotatoTicks++;
            int total = cfg.hotPotatoExplodeSeconds * 20;
            int left = total - this.hotPotatoTicks;
            // 顶部动作栏：给全部存活玩家持续显示山芋爆炸倒计时（每 5 tick 更新一次）
            if (this.ticks % 5 == 0) {
                int seconds = Math.max(0, (left + 19) / 20);
                for (ServerPlayerEntity online : this.aliveOnlineInArena()) {
                    boolean isHolder = online.getUuid().equals(holder.getUuid());
                    online.sendMessage(Text.literal("§6烫手山芋 §8| §c爆炸倒计时 §e" + seconds + "s"
                            + (isHolder ? " §f← 你持有！左键传给别人" : "")), true);
                }
            }
            if (left <= cfg.hotPotatoWarnSeconds * 20 && left > 0 && left % 10 == 0) {
                holder.sendMessage(Text.literal("§c§l山芋要爆炸了！ §e" + ((left + 19) / 20) + "s"), true);
                holder.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.PLAYERS, 1.0F, 0.6F);
                if (arena instanceof ServerWorld sw) {
                    sw.spawnParticles(ParticleTypes.FLAME, holder.getX(), holder.getY() + 1.2,
                            holder.getZ(), 6, 0.3, 0.3, 0.3, 0.01);
                }
            }
            if (this.hotPotatoTicks >= total) {
                this.explodeHotPotato(holder);
            }
            // 持有者加速（追逐传递）
            if (cfg.hotPotatoHolderSpeed && holder != null
                    && !this.eliminated.contains(holder.getUuid())) {
                holder.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.SPEED, 40, 0, false, false, true));
            }
        }

        // 2.5) 传递冷却递减
        if (this.hotPotatoPassCooldown > 0) {
            this.hotPotatoPassCooldown--;
        }

        // 3) 山芋重生计时
        if (this.hotPotatoRespawnTicks > 0) {
            this.hotPotatoRespawnTicks--;
            if (this.hotPotatoRespawnTicks == 0) {
                ServerPlayerEntity next = this.pickRandomAlive();
                if (next != null) {
                    this.giveHotPotato(next);
                }
            }
        }

        // 4) 清理场上的山芋掉落物（防 Q 丢弃/爆炸掉落）
        this.clearHotPotatoDrops(arena);

        // 5) 掉出平台 → 淘汰（兜底）
        if (this.hotPotatoLayout != null) {
            for (ServerPlayerEntity online : this.aliveOnlineInArena()) {
                if (online.getY() < this.hotPotatoLayout.platformY - 15) {
                    this.eliminate(online, EliminationCause.VOID);
                }
            }
        }
    }

    /** 左键（攻击）传递山芋：持有者攻击其他存活玩家时把山芋传过去（攻击不造成伤害）。
     * 传递不会重置山芋爆炸倒计时（山芋总寿命从发放起算，时间到就炸当前持有者），有 0.5 秒传递冷却。 */
    public void tryPassHotPotato(ServerPlayerEntity attacker, Entity target) {
        if (this.type != MatchType.HOT_POTATO || this.state != MatchState.ACTIVE) {
            return;
        }
        if (this.hotPotatoHolder == null || !this.hotPotatoHolder.equals(attacker.getUuid())) {
            return; // 只有持有者能传递
        }
        if (this.hotPotatoPassCooldown > 0) {
            attacker.sendMessage(Messages.warn("§c山芋传递冷却中..."), true);
            return; // 传递冷却
        }
        if (!(target instanceof ServerPlayerEntity targetPlayer)
                || this.eliminated.contains(targetPlayer.getUuid())
                || attacker.getUuid().equals(targetPlayer.getUuid())) {
            return;
        }
        if (attacker.squaredDistanceTo(targetPlayer) > 25) {
            return; // 距离保险
        }
        this.clearHotPotatoItems(attacker);
        attacker.removeStatusEffect(StatusEffects.SPEED); // 传递后不再加速
        this.hotPotatoHolder = targetPlayer.getUuid();
        // 注意：不重置 hotPotatoTicks——山芋总寿命从发放起算，传递只是换持有者
        this.hotPotatoPassCooldown = HOT_POTATO_PASS_COOLDOWN_TICKS;
        this.giveHotPotatoItem(targetPlayer);
        this.broadcast(Messages.warn("§e" + attacker.getGameProfile().getName()
                + "§r 把烫手山芋传给了 §e" + targetPlayer.getGameProfile().getName() + "§r！"));
        attacker.playSoundToPlayer(SoundEvents.ENTITY_ENDER_PEARL_THROW, SoundCategory.PLAYERS, 1.0F, 1.0F);
        targetPlayer.playSoundToPlayer(SoundEvents.ENTITY_ENDER_PEARL_THROW, SoundCategory.PLAYERS, 1.0F, 1.4F);
    }

    /** 山芋爆炸：无方块破坏的爆炸特效 + 淘汰持有者 + 安排山芋重生。 */
    private void explodeHotPotato(ServerPlayerEntity holder) {
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.EXPLOSION_EMITTER,
                    holder.getX(), holder.getY() + 0.5, holder.getZ(), 1, 0, 0, 0, 0);
            sw.spawnParticles(ParticleTypes.FLAME,
                    holder.getX(), holder.getY() + 0.5, holder.getZ(), 30, 0.6, 0.6, 0.6, 0.05);
        }
        holder.playSoundToPlayer(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1.5F, 1.0F);
        holder.clearStatusEffects();
        this.hotPotatoHolder = null;
        this.eliminate(holder, EliminationCause.HOT_POTATO_EXPLODE);
        this.clearHotPotatoItems(); // 爆炸后清掉可能掉出的山芋
        this.scheduleHotPotatoRespawn();
    }

    /** 随机给一名存活玩家发放山芋（无存活者则不动）。 */
    private void giveHotPotato(ServerPlayerEntity player) {
        this.hotPotatoHolder = player.getUuid();
        this.hotPotatoTicks = 0;
        this.giveHotPotatoItem(player);
        this.broadcast(Messages.gold("§6烫手山芋出现了！§e" + player.getGameProfile().getName()
                + "§r 持有它！§c左键点击其他玩家传递，时间到会爆炸！"));
        player.playSoundToPlayer(SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.0F, 1.5F);
    }

    /** 把山芋放到玩家主手（快捷栏第 1 格）。 */
    private void giveHotPotatoItem(ServerPlayerEntity player) {
        player.getInventory().setStack(0, createHotPotatoItem());
        player.currentScreenHandler.sendContentUpdates();
    }

    /** 山芋粘主手：若不在主手则从物品栏移除并放回主手（防丢弃/换格）。 */
    private void ensureHotPotatoInHand(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        if (isHotPotatoItem(inventory.getStack(0))) {
            return;
        }
        // 从物品栏所有槽位移除山芋（含护甲/副手）
        for (int i = 0; i < inventory.size(); i++) {
            if (isHotPotatoItem(inventory.getStack(i))) {
                inventory.setStack(i, ItemStack.EMPTY);
            }
        }
        inventory.setStack(0, createHotPotatoItem());
        player.currentScreenHandler.sendContentUpdates();
    }

    /** 清掉指定玩家（或所有玩家）身上的山芋物品。 */
    private void clearHotPotatoItems() {
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online != null) {
                var inventory = online.getInventory();
                boolean changed = false;
                for (int i = 0; i < inventory.size(); i++) {
                    if (isHotPotatoItem(inventory.getStack(i))) {
                        inventory.setStack(i, ItemStack.EMPTY);
                        changed = true;
                    }
                }
                if (changed) {
                    online.currentScreenHandler.sendContentUpdates();
                }
            }
        }
    }

    private void clearHotPotatoItems(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        boolean changed = false;
        for (int i = 0; i < inventory.size(); i++) {
            if (isHotPotatoItem(inventory.getStack(i))) {
                inventory.setStack(i, ItemStack.EMPTY);
                changed = true;
            }
        }
        if (changed) {
            player.currentScreenHandler.sendContentUpdates();
        }
    }

    /** 清掉竞技场内掉落的山芋物品实体（防 Q 丢弃/爆炸掉落残留）。 */
    private void clearHotPotatoDrops(ArenaWorld arena) {
        Box box = new Box(
                this.hotPotatoLayout.mapCenter.getX() - this.hotPotatoLayout.maxRadius, arena.getBottomY(),
                this.hotPotatoLayout.mapCenter.getZ() - this.hotPotatoLayout.maxRadius,
                this.hotPotatoLayout.mapCenter.getX() + this.hotPotatoLayout.maxRadius, arena.getTopY(),
                this.hotPotatoLayout.mapCenter.getZ() + this.hotPotatoLayout.maxRadius);
        for (ItemEntity entity : arena.getEntitiesByClass(ItemEntity.class, box,
                e -> isHotPotatoItem(e.getStack()))) {
            entity.discard();
        }
    }

    /** 安排山芋重生（若场上有存活玩家）。 */
    private void scheduleHotPotatoRespawn() {
        this.hotPotatoRespawnTicks = PvPConfig.INSTANCE.hotPotatoRespawnSeconds * 20;
    }

    /** 随机选一名存活在线且在场内的玩家。 */
    private ServerPlayerEntity pickRandomAlive() {
        List<ServerPlayerEntity> alive = this.aliveOnlineInArena();
        if (alive.isEmpty()) {
            return null;
        }
        return alive.get(this.random.nextInt(alive.size()));
    }

    /** 当前山芋持有者（在线且在竞技场内），无则 null。 */
    private ServerPlayerEntity onlineHotPotatoHolder() {
        if (this.hotPotatoHolder == null) {
            return null;
        }
        ServerPlayerEntity online = this.manager.getOnlinePlayer(this.hotPotatoHolder);
        if (online == null || this.eliminated.contains(this.hotPotatoHolder)) {
            return null;
        }
        return online;
    }

    /** 创建"烫手山芋"物品（烤马铃薯 + 自定义名 + 附魔光效 + 识别 NBT）。 */
    private ItemStack createHotPotatoItem() {
        ItemStack stack = new ItemStack(Items.BAKED_POTATO);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§6§l烫手山芋"));
        NbtCompound nbt = new NbtCompound();
        nbt.putString(HOT_POTATO_TAG, "1");
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        // 附魔光效（耐久 I 附魔模拟，注册表服务器启动后可用）
        MinecraftServer server = this.manager.getServer();
        if (server != null) {
            Registry<Enchantment> registry = server.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
            RegistryEntry<Enchantment> unbreaking = registry.getEntry(Enchantments.UNBREAKING).orElse(null);
            if (unbreaking != null) {
                stack.addEnchantment(unbreaking, 1);
            }
        }
        return stack;
    }

    /** 是否为"烫手山芋"物品（按识别 NBT 判断）。 */
    public static boolean isHotPotatoItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        NbtComponent nbt = stack.get(DataComponentTypes.CUSTOM_DATA);
        return nbt != null && nbt.copyNbt().contains(HOT_POTATO_TAG);
    }

    // ---------- TNT 跑酷 (TNT Run) ----------

    /** TNT 跑酷每帧逻辑：方块消失、掉落物刷新、掉出底层淘汰、羊毛掉落物清理。 */
    private void tickTntRun() {
        PvPConfig cfg = PvPConfig.INSTANCE;
        ArenaWorld arena = this.manager.getArenaManager().getWorld();

        // 0) TNT 跑酷免疫摔落伤害：每 tick 清零 fallDistance，层间自由下落不掉血
        for (ServerPlayerEntity online : this.aliveOnlineInArena()) {
            online.fallDistance = 0;
        }

        // 1) 处理待消失方块（到点置空气 + 粒子提示）
        if (arena != null) {
            for (Map.Entry<BlockPos, Integer> entry : List.copyOf(this.tntRunVanish.entrySet())) {
                if (this.ticks >= entry.getValue()) {
                    BlockPos pos = entry.getKey();
                    this.tntRunVanish.remove(pos);
                    if (!arena.getBlockState(pos).isAir()) {
                        arena.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                        if (arena instanceof ServerWorld sw) {
                            sw.spawnParticles(ParticleTypes.POOF, pos.getX() + 0.5, pos.getY() + 0.5,
                                    pos.getZ() + 0.5, 6, 0.25, 0.25, 0.25, 0.01);
                        }
                    }
                }
            }

            // 2) 玩家踩过的平台方块：tntRunVanishTicks（0.2 秒）后消失。
            //    用碰撞箱水平投影覆盖到的所有方块（含踩到边缘时相邻的方块），避免边缘漏判
            if (this.tntRunLayout != null) {
                for (ServerPlayerEntity online : this.aliveOnlineInArena()) {
                    if (!online.isOnGround()) {
                        continue;
                    }
                    Box pbox = online.getBoundingBox();
                    int minX = (int) Math.floor(pbox.minX - 0.001);
                    int maxX = (int) Math.floor(pbox.maxX - 0.001);
                    int minZ = (int) Math.floor(pbox.minZ - 0.001);
                    int maxZ = (int) Math.floor(pbox.maxZ - 0.001);
                    int underY = (int) Math.floor(pbox.minY) - 1;
                    for (int bx = minX; bx <= maxX; bx++) {
                        for (int bz = minZ; bz <= maxZ; bz++) {
                            BlockPos pos = new BlockPos(bx, underY, bz);
                            if (this.tntRunLayout.isPlatformBlock(pos)) {
                                this.tntRunVanish.putIfAbsent(pos, this.ticks + Math.max(1, cfg.tntRunVanishTicks));
                            }
                        }
                    }
                }
            }
        }

        // 3) 掉出底层平台 → 淘汰
        double deathY = ArenaTemplate.PLATFORM_Y - 8;
        for (ServerPlayerEntity online : this.aliveOnlineInArena()) {
            if (online.getY() < deathY) {
                this.eliminate(online, EliminationCause.VOID);
            }
        }

        // 4) 地面随机刷新火焰弹/TNT 掉落物（纯物品实体，不触发方块消失）
        if (++this.tntRunDropTimer >= Math.max(10, cfg.tntRunDropIntervalTicks)) {
            this.tntRunDropTimer = 0;
            this.spawnTntRunDrops();
        }

        // 5) 清除竞技场内不带道具 tag 的掉落物（主要是 TNT/火焰弹炸掉的羊毛等方块掉落）：
        //    防止玩家捡起羊毛重新搭方块（TNT 跑酷为生存模式可放方块）。只保留带 TNT_RUN_ITEM_TAG 的道具。
        if (arena != null && this.tntRunLayout != null) {
            int half = this.tntRunLayout.maxRadius + 8;
            BlockPos c = this.tntRunLayout.mapCenter;
            Box box = new Box(c.getX() - half, arena.getBottomY(), c.getZ() - half,
                    c.getX() + half, arena.getTopY(), c.getZ() + half);
            for (ItemEntity entity : arena.getEntitiesByClass(ItemEntity.class, box,
                    e -> !isTntRunItem(e.getStack()))) {
                entity.discard();
            }
        }
    }

    /** TNT 跑酷地面刷新掉落物：在仍有方块的最上层随机放火焰弹/TNT（带道具 tag，不被清理）。 */
    private void spawnTntRunDrops() {
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null || this.tntRunLayout == null) {
            return;
        }
        int half = this.tntRunLayout.halfSize;
        int cx = this.tntRunLayout.mapCenter.getX();
        int cz = this.tntRunLayout.mapCenter.getZ();
        for (int i = 0; i < 2; i++) {
            int x = cx + this.random.nextInt(half * 2 + 1) - half;
            int z = cz + this.random.nextInt(half * 2 + 1) - half;
            for (int layer = this.tntRunLayout.layerYs.size() - 1; layer >= 0; layer--) {
                int y = this.tntRunLayout.layerYs.get(layer);
                if (!arena.getBlockState(new BlockPos(x, y, z)).isAir()) {
                    Item item = this.random.nextBoolean() ? Items.FIRE_CHARGE : Items.TNT;
                    ItemStack stack = new ItemStack(item);
                    NbtCompound nbt = new NbtCompound();
                    nbt.putString(TNT_RUN_ITEM_TAG, "1");
                    stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
                    ItemEntity entity = new ItemEntity(arena, x + 0.5, y + 1.0, z + 0.5, stack);
                    entity.setVelocity(0, 0.1, 0);
                    arena.spawnEntity(entity);
                    break;
                }
            }
        }
    }

    /** 是否为 TNT 跑酷道具掉落物（带 pvp.tntrun_item tag）。 */
    public static boolean isTntRunItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        NbtComponent nbt = stack.get(DataComponentTypes.CUSTOM_DATA);
        return nbt != null && nbt.copyNbt().contains(TNT_RUN_ITEM_TAG);
    }

    /** TNT 跑酷地图布局（方块消失判定/破坏保护用），非 TNT 跑酷模式返回 null。 */
    public TntRunLayout tntRunLayout() {
        return this.tntRunLayout;
    }

    // ---------- 战桥 (Bridge) ----------

    /** 战桥每帧逻辑：延迟重生、箭矢回复、进球/坠落判定、回合间歇倒计时。 */
    private void tickBridge() {
        // ALLOW_DEATH 拦截后延迟到下一 tick 重生（避免事件处理中传送）
        for (UUID uuid : List.copyOf(this.bridgePendingRespawns)) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(uuid);
            if (online != null) {
                this.bridgeRespawn(online);
            } else {
                this.bridgePendingRespawns.remove(uuid);
            }
        }

        // 箭矢回复：每隔配置秒数，为箭数不足的玩家补 1 支
        if (++this.bridgeArrowRegenTicks >= PvPConfig.INSTANCE.bridgeArrowRegenSeconds * 20) {
            this.bridgeArrowRegenTicks = 0;
            this.refillBridgeArrows();
        }

        // 进球/坠落判定
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena != null && this.bridgeLayout != null) {
            for (ServerPlayerEntity player : this.players) {
                if (this.eliminated.contains(player.getUuid())) {
                    continue;
                }
                ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
                if (online == null || online.getWorld() != arena) {
                    continue;
                }
                this.checkBridgeGoalAndFall(online);
            }
        }

        // 进球后的回合间歇倒计时
        if (this.bridgeRoundTicks > 0) {
            this.bridgeRoundTicks--;
            if (this.bridgeRoundTicks % 20 == 0) {
                this.broadcastTitle("回合继续：" + ((this.bridgeRoundTicks + 19) / 20) + "s");
            }
            if (this.bridgeRoundTicks <= 0) {
                this.releaseBridgeRound();
            }
        }
    }

    /** 进球与坠落判定：掉进敌方球门得分、自家球门只重生、掉出地图重生。 */
    private void checkBridgeGoalAndFall(ServerPlayerEntity player) {
        if (this.bridgeLayout == null) {
            return;
        }
        double px = player.getX();
        double pz = player.getZ();
        double y = player.getY();

        // 回合间歇内不判进球（玩家在笼位等放出），但坠落仍处理
        if (this.bridgeRoundTicks <= 0 && y < ArenaTemplate.PLATFORM_Y - 1) {
            for (BridgeLayout.BridgeBase base : this.bridgeLayout.bases()) {
                if (base.goalContains(px, pz)) {
                    MatchTeam goalTeam = this.teams.get(base.teamIndex());
                    MatchTeam scorerTeam = this.teamOf(player);
                    if (scorerTeam == goalTeam) {
                        this.bridgeRespawn(player); // 自家球门不算分，仅重生
                    } else {
                        this.onBridgeGoal(scorerTeam, goalTeam, player);
                    }
                    return;
                }
            }
        }

        // 掉出地图（虚空且不在方块上）→ 立即重生
        if (y < ArenaTemplate.PLATFORM_Y - 10 && !player.isOnGround()) {
            this.bridgeRespawn(player);
        }
    }

    /** 一次进球：得分 → 广播比分 → 达到目标则获胜，否则开启回合间歇。 */
    private void onBridgeGoal(MatchTeam scorerTeam, MatchTeam goalTeam, ServerPlayerEntity player) {
        scorerTeam.addScore();
        this.broadcast(Messages.gold("§e" + player.getGameProfile().getName() + "§r 进球！ " + this.scoreLine()));
        if (scorerTeam.getScore() >= PvPConfig.INSTANCE.bridgeWinScore) {
            this.finishMatch(scorerTeam);
            return;
        }
        this.startBridgeRound();
    }

    /** 进球后：全员回各自笼位补装备、无敌，开始短倒计时。 */
    private void startBridgeRound() {
        this.bridgeRoundTicks = PvPConfig.INSTANCE.countdownSeconds * 20;
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }
        for (ServerPlayerEntity player : this.players) {
            if (this.eliminated.contains(player.getUuid())) {
                continue;
            }
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null) {
                continue;
            }
            this.teleportToBridgeSpawn(online);
            this.applyBridgeGear(online);
            online.setInvulnerable(true);
        }
        this.broadcastTitleBig("§e回合暂停", "§f" + PvPConfig.INSTANCE.countdownSeconds + " 秒后继续");
    }

    /** 回合间歇结束：解除所有存活玩家无敌。 */
    private void releaseBridgeRound() {
        this.bridgeRoundTicks = 0;
        for (ServerPlayerEntity player : this.players) {
            if (this.eliminated.contains(player.getUuid())) {
                continue;
            }
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online != null) {
                online.setInvulnerable(false);
            }
        }
        this.broadcast(Messages.gold("开始！"));
        this.broadcastTitle("开始！");
    }

    /** 战桥重生：传回己方笼位 + 补满队伍色装备（回合间歇内保持无敌）。 */
    public void bridgeRespawn(ServerPlayerEntity player) {
        if (this.state != MatchState.ACTIVE) {
            return;
        }
        this.bridgePendingRespawns.remove(player.getUuid());
        this.teleportToBridgeSpawn(player);
        this.applyBridgeGear(player);
        player.setInvulnerable(this.bridgeRoundTicks > 0);
    }

    /** ALLOW_DEATH 拦截回调：只登记，等下一 tick 重生（避免死亡判定途中传送）。 */
    public void onBridgeDeath(ServerPlayerEntity player) {
        // 立即回血/清火/清坠落：避免血量 0 同步到客户端短暂弹出原生死亡界面（战桥有概率触发）
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.fallDistance = 0;
        if (this.state != MatchState.ACTIVE) {
            return; // 非 ACTIVE（倒计时/庆祝）只保证不死，不排队重生
        }
        this.bridgePendingRespawns.add(player.getUuid());
    }

    /** 传送到玩家所属队伍的笼位（面朝地图中心）。 */
    private void teleportToBridgeSpawn(ServerPlayerEntity player) {
        if (this.bridgeLayout == null) {
            return;
        }
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }
        int teamIndex = this.teamIndex(player);
        if (teamIndex < 0 || teamIndex >= this.bridgeLayout.bases().size()) {
            return;
        }
        BlockPos spawn = this.bridgeLayout.bases().get(teamIndex).spawn();
        float yaw = this.faceCenter(spawn);
        player.teleport(arena, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, yaw, 0);
    }

    /** 给玩家发放所属队伍颜色的战桥装备（铁剑/弓/镐/陶瓦/金苹果/染甲）。 */
    private void applyBridgeGear(ServerPlayerEntity player) {
        MatchTeam team = this.teamOf(player);
        BridgeGear.apply(player, team != null ? team.getColor() : Formatting.WHITE);
    }

    /** 玩家所属队伍索引（-1 表示未找到）。 */
    private int teamIndex(ServerPlayerEntity player) {
        for (int i = 0; i < this.teams.size(); i++) {
            if (this.teams.get(i).contains(player.getUuid())) {
                return i;
            }
        }
        return -1;
    }

    /** 玩家所属队伍。 */
    private MatchTeam teamOf(ServerPlayerEntity player) {
        int idx = this.teamIndex(player);
        return idx >= 0 ? this.teams.get(idx) : null;
    }

    /** 超时结算：比分最高者胜，并列则平局。 */
    private MatchTeam bridgeTimeoutWinner() {
        MatchTeam best = null;
        int bestScore = -1;
        for (MatchTeam team : this.teams) {
            if (team.getScore() > bestScore) {
                bestScore = team.getScore();
                best = team;
            } else if (team.getScore() == bestScore) {
                best = null;
            }
        }
        return best;
    }

    /** 为箭数不足的玩家补 1 支箭（弓最多保留 1 支可再用）。 */
    private void refillBridgeArrows() {
        for (ServerPlayerEntity player : this.players) {
            if (this.eliminated.contains(player.getUuid())) {
                continue;
            }
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null) {
                continue;
            }
            var inventory = online.getInventory();
            int arrows = 0;
            for (ItemStack stack : inventory.main) {
                if (stack.isOf(Items.ARROW)) {
                    arrows += stack.getCount();
                }
            }
            if (arrows >= 1) {
                continue;
            }
            ItemStack arrow = new ItemStack(Items.ARROW, 1);
            if (!inventory.insertStack(arrow)) {
                online.getWorld().spawnEntity(new ItemEntity(online.getWorld(),
                        online.getX(), online.getY(), online.getZ(), arrow));
            }
        }
    }

    /** 当前比分展示："红队 3  vs  蓝队 2"。 */
    private String scoreLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < this.teams.size(); i++) {
            if (i > 0) {
                sb.append(" §7vs ");
            }
            MatchTeam t = this.teams.get(i);
            sb.append(t.getColor()).append(t.getName()).append(" §f").append(t.getScore());
        }
        return sb.toString();
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
            this.dropEliminatedLoot(online); // 身上物品爆落在地（供其他玩家拾取），再转幽灵
            this.makeGhost(online);
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
        Set<UUID> winners = this.computeWinners();
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

    /** 结算胜者集合：心跳水立方为第一名到达者，其余模式为胜利队伍存活成员。 */
    private Set<UUID> computeWinners() {
        Set<UUID> winners = new HashSet<>();
        if (this.type == MatchType.HEARTBEAT) {
            ServerPlayerEntity best = this.bestHeartbeatPlayer();
            if (best != null) {
                winners.add(best.getUuid());
            }
            return winners;
        }
        if (this.winnerTeam != null) {
            for (ServerPlayerEntity player : this.winnerTeam.getAlivePlayers()) {
                winners.add(player.getUuid());
            }
        }
        return winners;
    }

    /** 庆祝结束后的实际结算：恢复、战绩、清场、释放。 */
    private void finalizeMatch() {
        Set<UUID> winners = this.computeWinners();
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
        // 幸运之柱一击必杀等全局标记在对局结束时清空，防止残留到下一场（否则下一场开局就全程生效）
        PvPMod.oneHitKillActive = false;
        try {
            int mapMaxRadius;
            if (this.skywarsLayout != null) {
                mapMaxRadius = this.skywarsLayout.maxRadius();
            } else if (this.bridgeLayout != null) {
                mapMaxRadius = this.bridgeLayout.maxRadius();
            } else if (this.luckyPillarLayout != null) {
                mapMaxRadius = this.luckyPillarLayout.maxRadius();
            } else if (this.tntRunLayout != null) {
                mapMaxRadius = this.tntRunLayout.maxRadius;
            } else if (this.heartbeatLayout != null) {
                mapMaxRadius = this.heartbeatLayout.maxRadius();
            } else if (this.hotPotatoLayout != null) {
                mapMaxRadius = this.hotPotatoLayout.maxRadius;
            } else {
                mapMaxRadius = 0;
            }
            this.manager.getArenaManager().clearArena(this.regionIndex, this.template, mapMaxRadius);
        } catch (Exception e) {
            LOGGER.error("[PvP] 清理竞技场出错", e);
        }
        this.manager.cleanupMatch(this);
        LOGGER.info("[PvP] 比赛 #{} 已结束并清理", this.id);
    }

    /** 把玩家背包/护甲/副手物品以掉落物形式丢在原地（死后装备可被其他玩家拾取）。 */
    private void dropEliminatedLoot(ServerPlayerEntity player) {
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

    /**
     * 将玩家转为"幽灵"：旁观者模式（完全隐身，其他玩家看不到）+ 空物品栏 + 无敌 + 可自由飞行，
     * 无法与对局任何交互。注意 vanilla 的 setInvisible 只是半透明（身体仍隐约可见），
     * 只有旁观者模式才能真正做到完全隐身。
     */
    public void makeGhost(ServerPlayerEntity player) {
        player.changeGameMode(GameMode.SPECTATOR);
        player.getInventory().clear();
        player.setInvulnerable(true);
        player.setHealth(20f);
        player.setNoGravity(true);
        player.clearStatusEffects();
        player.setInvisible(true); // 冗余保险（旁观者天然隐身）
        player.getAbilities().allowFlying = true;
        player.getAbilities().flying = true;
        player.sendAbilitiesUpdate();
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena != null) {
            BlockPos center = this.template.getCenter(this.regionIndex);
            player.teleport(arena, center.getX(), center.getY() + ArenaTemplate.WALL_HEIGHT + 5, center.getZ(), 0, 0);
        }
        player.currentScreenHandler.sendContentUpdates();
    }

    /** 幽灵掉出虚空时传送回观战台（sweepArenaWorld 兜底）。 */
    public void rescueGhost(ServerPlayerEntity player) {
        this.makeGhost(player);
    }

    /** 该玩家是否已在本场被淘汰（死亡幽灵，禁止一切交互）。 */
    public boolean isEliminated(UUID uuid) {
        return this.eliminated.contains(uuid);
    }

    /** 战桥地图布局（方块破坏保护用），非战桥模式返回 null。 */
    public BridgeLayout bridgeLayout() {
        return this.bridgeLayout;
    }

    /** 幸运之柱地图布局（方块破坏保护用），非幸运之柱模式返回 null。 */
    public LuckyPillarLayout luckyPillarLayout() {
        return this.luckyPillarLayout;
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

    /** 玩家主动离开竞技场回主城：幽灵走旁观离开流程；活跃玩家视为弃权淘汰并恢复赛前状态。 */
    public void leaveMatch(ServerPlayerEntity player) {
        if (this.eliminated.contains(player.getUuid())) {
            this.spectatorLeave(player, false);
            return;
        }
        this.eliminate(player, EliminationCause.FORFEIT);
        // 还原赛前状态（转幽灵后还原为正常玩家）、隐藏侧边栏、标记已提前离场
        InventorySnapshot snapshot = this.snapshots.get(player.getUuid());
        if (snapshot != null) {
            snapshot.restore(player);
        } else {
            this.manager.teleportToOverworldSpawn(player);
        }
        this.leftEarly.add(player.getUuid());
        if (player.networkHandler != null) {
            player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, null));
        }
    }

    /** 旁观者提前离场：requeue=true 立即重排当前模式，false 回主城。 */
    public void spectatorLeave(ServerPlayerEntity player, boolean requeue) {
        if (!this.leftEarly.add(player.getUuid())) {
            return;
        }
        // 离开本场回到主城：隐藏侧边栏
        if (player.networkHandler != null) {
            player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, null));
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

    /**
     * 1.8 模式：切换剑格挡状态（格挡时减速）。
     * 右键按住时客户端每 ~4 tick 重触发一次（UseItemCallback），每次都刷新倒计时；
     * 松开右键后倒计时走完即自动退出格挡，避免"中缓慢 II 效果"永久残留。
     */
    public void setBlocking(ServerPlayerEntity player, boolean block) {
        if (block) {
            this.blocking.add(player.getUuid());
            this.blockingRefresh.put(player.getUuid(), BLOCK_REFRESH_TICKS);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 200, 1));
        } else {
            this.blocking.remove(player.getUuid());
            this.blockingRefresh.remove(player.getUuid());
            player.removeStatusEffect(StatusEffects.SLOWNESS);
        }
    }

    /** 剑格挡刷新窗口（tick）：右键按住约每 4 tick 重触发，松开后约 0.5s 内退出格挡。 */
    private static final int BLOCK_REFRESH_TICKS = 10;

    /** 每帧维护 1.8 格挡：不拿剑则取消；格挡倒计时走完（松开右键）则退出；否则保持减速。 */
    private void tickLegacyBlocking() {
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null) {
                continue;
            }
            boolean isBlocking = this.blocking.contains(player.getUuid());
            if (!isBlocking) {
                continue;
            }
            boolean holdingSword = online.getMainHandStack().getItem() instanceof SwordItem;
            if (!holdingSword) {
                this.setBlocking(online, false);
                continue;
            }
            Integer remaining = this.blockingRefresh.get(player.getUuid());
            if (remaining == null || remaining <= 0) {
                this.setBlocking(online, false); // 松开右键：刷新倒计时耗尽
                continue;
            }
            this.blockingRefresh.put(player.getUuid(), remaining - 1);
            online.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 200, 1));
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

        // 幸运之柱：开局倒计时玩家不能移动（可旋转视角）——把偏离出生点的玩家拉回
        if (this.type == MatchType.LUCKY_PILLAR || this.type == MatchType.HEARTBEAT) {
            this.lockPlayersToSpawn();
        }

        if (this.countdownTicks > 0) {
            if (this.countdownTicks % 20 == 0) {
                int seconds = this.countdownTicks / 20;
                this.broadcastTitle(String.valueOf(seconds));
            }
            this.countdownTicks--;
        } else {
            this.state = MatchState.ACTIVE;
            if (this.type == MatchType.LUCKY_PILLAR) {
                // 物品：开赛立即发一轮，之后每隔 interval 秒发；事件仍从 interval 秒后开始
                this.luckyPillarItemTicks = 1;
                this.luckyPillarEventTicks = PvPConfig.INSTANCE.luckyPillarEventIntervalSeconds * 20;
            }
            if (this.type == MatchType.HOT_POTATO) {
                // 开赛立即随机给一名玩家发放烫手山芋
                ServerPlayerEntity first = this.pickRandomAlive();
                if (first != null) {
                    this.giveHotPotato(first);
                }
            }
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

    /** 幸运之柱 / 心跳水立方开局倒计时：把玩家锁在出生点（不能移动，但允许旋转视角——保留 yaw/pitch）。 */
    private void lockPlayersToSpawn() {
        ArenaWorld arena = this.manager.getArenaManager().getWorld();
        if (arena == null) {
            return;
        }
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null || online.getWorld() != arena) {
                continue;
            }
            BlockPos spawn = this.spawns.get(player.getUuid());
            if (spawn == null) {
                continue;
            }
            double tx = spawn.getX() + 0.5;
            double tz = spawn.getZ() + 0.5;
            double dx = online.getX() - tx;
            double dz = online.getZ() - tz;
            if (dx * dx + dz * dz > 0.02 || online.getY() < spawn.getY() - 0.1) {
                online.teleport(arena, tx, spawn.getY(), tz, online.getYaw(), online.getPitch());
                online.setVelocity(Vec3d.ZERO);
                online.velocityDirty = true;
            }
        }
    }

    private void setupPlayers() {
        this.manager.getArenaManager().buildArena(this.regionIndex, this.template, this.skywarsSeed,
                this.players.size(), this.type, this.players);
        ArenaWorld arena = this.manager.getArenaManager().getWorld();

        boolean skywars = this.type == MatchType.SKYWARS;
        boolean bridge = this.type.isBridge();
        boolean luckyPillar = this.type == MatchType.LUCKY_PILLAR;
        boolean tntRun = this.type == MatchType.TNT_RUN;
        boolean heartbeat = this.type == MatchType.HEARTBEAT;
        boolean hotPotato = this.type == MatchType.HOT_POTATO;
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online == null) {
                continue;
            }
            BlockPos spawn = this.spawns.get(player.getUuid());
            float yaw = this.faceCenter(spawn);
            online.teleport(arena, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, yaw, 0);
            if (bridge) {
                // 战桥：队伍色装备 + 生存模式（可放/拆方块）
                this.applyBridgeGear(online);
            } else if (skywars) {
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
            } else if (luckyPillar) {
                // 幸运之柱：无套件，生存模式空手开局，等随机物品发放
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
            } else if (tntRun) {
                // TNT 跑酷：无套件，生存模式空手开局，靠地面刷新火焰弹/TNT 掉落物
                online.getInventory().clear();
                online.setHealth(online.getMaxHealth());
                online.getHungerManager().setFoodLevel(20);
                online.getHungerManager().setSaturationLevel(5f);
                online.setAbsorptionAmount(0);
                online.setFireTicks(0);
                online.fallDistance = 0;
                online.clearStatusEffects();
                online.changeGameMode(GameMode.SURVIVAL);
                // 给饱和效果：跑步/跳跃不掉饥饿
                online.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, -1, 0, false, false, false));
                online.currentScreenHandler.sendContentUpdates();
            } else if (heartbeat || hotPotato) {
                // 心跳水立方 / 烫手山芋：无套件，冒险模式空手开局（专注玩法本身，不能放/拆方块）
                online.getInventory().clear();
                online.setHealth(online.getMaxHealth());
                online.getHungerManager().setFoodLevel(20);
                online.getHungerManager().setSaturationLevel(5f);
                online.setAbsorptionAmount(0);
                online.setFireTicks(0);
                online.fallDistance = 0;
                online.clearStatusEffects();
                online.changeGameMode(GameMode.ADVENTURE);
                online.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, -1, 0, false, false, false));
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
        if (bridge) {
            this.broadcast(Messages.info("战桥开始！冲进对方球门得分，先得 "
                    + PvPConfig.INSTANCE.bridgeWinScore + " 分获胜！（1.8 低版本战斗）"));
        } else if (skywars) {
            String themeName = this.skywarsTheme != null ? this.skywarsTheme.getDisplayName() : "主世界";
            this.broadcast(Messages.info("空岛战争开始！主题：§e" + themeName + "§r。搜刮空岛，成为最后幸存者！（1.8 低版本战斗）"));
        } else if (luckyPillar) {
            this.broadcast(Messages.info("幸运之柱开始！空手站在柱顶，每 §e"
                    + PvPConfig.INSTANCE.luckyPillarItemIntervalSeconds + "§r 秒获得随机物品，还会触发随机事件！最后的幸存者获胜！（1.8 低版本战斗）"));
        } else if (tntRun) {
            this.broadcast(Messages.info("TNT 跑酷开始！踩过的方块 §e0.2 秒§r 后掉落，掉出底层即淘汰；地面会刷火焰弹/TNT，捡起来砸人/炸人，最后的幸存者获胜！"));
        } else if (heartbeat) {
            this.broadcast(Messages.info("心跳水立方开始！从出发台中央洞口跳下，穿过每层红色地板上的洞落水过关，失误回塔顶重试；时间结束完成关卡数最多者获胜！"));
        } else if (hotPotato) {
            this.broadcast(Messages.info("烫手山芋开始！左键点击其他玩家传递山芋，持有时间到会爆炸！最后的幸存者获胜！"));
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
        if (this.type == MatchType.HEARTBEAT) {
            // 心跳水立方：全员通关全部关卡 → 结算（超时由 timeoutWinner 兜底）
            return this.heartbeatFinished.size() >= this.players.size() ? this.teams.get(0) : null;
        }
        if (this.type.isLastManStanding()) {
            List<ServerPlayerEntity> alive = this.teams.get(0).getAlivePlayers();
            return alive.size() <= 1 ? this.teams.get(0) : null;
        }
        if (this.type.isBridge()) {
            // 战桥：先得 bridgeWinScore 分的队伍获胜（目标分由进球逻辑判定）
            for (MatchTeam team : this.teams) {
                if (team.getScore() >= PvPConfig.INSTANCE.bridgeWinScore) {
                    return team;
                }
            }
            return null;
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
        if (this.type == MatchType.HEARTBEAT) {
            // 心跳水立方：按到达顺序广播完整排名
            this.announceHeartbeatResult();
            return;
        }
        if (this.type.isBridge()) {
            // 战桥：比分最高者胜，并列平局
            MatchTeam winningTeam = null;
            int best = -1;
            for (MatchTeam team : this.teams) {
                if (team.getScore() > best) {
                    best = team.getScore();
                    winningTeam = team;
                } else if (team.getScore() == best) {
                    winningTeam = null;
                }
            }
            if (winningTeam != null) {
                MutableText msg = Text.literal(winningTeam.getName()).formatted(winningTeam.getColor())
                        .append(Text.literal(" 获胜！(" + this.scoreLine() + ")").formatted(Formatting.GOLD));
                this.broadcast(Messages.prefix(msg));
            } else {
                this.broadcast(Messages.warn("本局平局"));
            }
            return;
        }
        if (this.type.isLastManStanding()) {
            ServerPlayerEntity winner = this.getWinnerPlayer(winners);
            if (winner != null) {
                this.broadcast(Messages.gold("§6" + winner.getGameProfile().getName() + "§r 在"
                        + this.type.getDisplayName() + "中获胜！"));
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
            // 组队模式（2v2 等）关闭友伤；FFA/空岛战争/幸运之柱全员同一队，必须保留互伤
            sbTeam.setFriendlyFireAllowed(this.type.isLastManStanding());
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

        // 清掉上一帧的行（分数存于全局计分板，仅本场重绘）
        for (String line : this.infoLines) {
            scoreboard.removeScore(ScoreHolder.fromName(line), objective);
        }
        this.infoLines.clear();

        int score = 15;
        this.setInfoLine(scoreboard, objective, this.modeLine(), score--);
        this.setInfoLine(scoreboard, objective, this.timeLine(), score--);
        this.setInfoLine(scoreboard, objective, "§8------------------------", score--);

        if (this.type == MatchType.HEARTBEAT) {
            // 心跳水立方：已通关人数 + 领先者 + 各玩家关卡进度
            HeartbeatLayout layout = this.heartbeatLayout;
            int finished = this.heartbeatFinished.size();
            this.setInfoLine(scoreboard, objective, "§b已通关 §f" + finished + "§7/§f" + this.players.size(), score--);
            ServerPlayerEntity leader = this.bestHeartbeatPlayer();
            if (leader != null && layout != null) {
                String ln = leader.getGameProfile().getName();
                int lp = this.heartbeatProgress.getOrDefault(leader.getUuid(), 0);
                this.setInfoLine(scoreboard, objective, "§e领先 §f" + ln + " §7("
                        + Math.min(lp + 1, layout.levelCount) + "/" + layout.levelCount + " 关)", score--);
            }
            this.setInfoLine(scoreboard, objective, "§8------------------------", score--);
            for (ServerPlayerEntity player : this.players) {
                if (score < 0) {
                    break;
                }
                ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
                String name = online != null ? online.getGameProfile().getName() : player.getGameProfile().getName();
                if (this.heartbeatFinished.contains(player.getUuid())) {
                    this.setInfoLine(scoreboard, objective, " §a★ §f" + name + " §7(通关)", score--);
                } else if (layout != null) {
                    int p = this.heartbeatProgress.getOrDefault(player.getUuid(), 0);
                    this.setInfoLine(scoreboard, objective, " §a● §f" + name + " §7("
                            + Math.min(p + 1, layout.levelCount) + "/" + layout.levelCount + ")", score--);
                } else {
                    this.setInfoLine(scoreboard, objective, " §a● §f" + name, score--);
                }
            }
        } else if (this.type.isLastManStanding()) {
            // FFA/空岛战争/幸运之柱/TNT 跑酷/烫手山芋：存活数 + 存活玩家列表
            int alive = this.teams.isEmpty() ? 0 : this.teams.get(0).aliveCount();
            this.setInfoLine(scoreboard, objective, "§b存活 §f" + alive + "§7/§f" + this.players.size(), score--);
            if (this.type == MatchType.SKYWARS) {
                this.setInfoLine(scoreboard, objective, this.skywarsShrinkLine(), score--);
                String themeName = this.skywarsTheme != null ? this.skywarsTheme.getDisplayName() : "主世界";
                this.setInfoLine(scoreboard, objective, "§7主题: §e" + themeName, score--);
            }
            if (this.type == MatchType.LUCKY_PILLAR) {
                int itemSec = Math.max(0, (this.luckyPillarItemTicks + 19) / 20);
                this.setInfoLine(scoreboard, objective, "§e下次物品 §f" + itemSec + "s", score--);
                if (PvPConfig.INSTANCE.luckyPillarEvents) {
                    int evSec = Math.max(0, (this.luckyPillarEventTicks + 19) / 20);
                    this.setInfoLine(scoreboard, objective, "§d下次事件 §f" + evSec + "s", score--);
                }
            }
            if (this.type == MatchType.HOT_POTATO) {
                ServerPlayerEntity holder = this.onlineHotPotatoHolder();
                String holderName = holder != null ? holder.getGameProfile().getName() : "无";
                this.setInfoLine(scoreboard, objective, "§6山芋持有者 §f" + holderName, score--);
                int left = Math.max(0, (PvPConfig.INSTANCE.hotPotatoExplodeSeconds * 20
                        - this.hotPotatoTicks + 19) / 20);
                this.setInfoLine(scoreboard, objective, "§c爆炸倒计时 §f" + left + "s", score--);
            }
            this.setInfoLine(scoreboard, objective, "§8------------------------", score--);
            for (ServerPlayerEntity player : this.players) {
                if (score < 0) {
                    break;
                }
                if (this.eliminated.contains(player.getUuid())) {
                    continue;
                }
                ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
                if (online == null) {
                    continue;
                }
                this.setInfoLine(scoreboard, objective, " §a● §f" + online.getGameProfile().getName(), score--);
            }
        } else {
            // 组队模式：逐队显示队伍与成员（队友/对手，颜色区分）
            if (this.type.isBridge()) {
                this.setInfoLine(scoreboard, objective,
                        "§7目标：先到 §e" + PvPConfig.INSTANCE.bridgeWinScore + "§7 分获胜", score--);
                this.setInfoLine(scoreboard, objective, "§8------------------------", score--);
            }
            for (MatchTeam team : this.teams) {
                if (score < 0) {
                    break;
                }
                String teamLine = this.type.isBridge()
                        ? team.getColor() + "● " + team.getName() + " §7(" + team.getScore() + "分)"
                        : team.getColor() + "● " + team.getName() + " §7(" + team.aliveCount() + "/" + team.getPlayers().size() + ")";
                this.setInfoLine(scoreboard, objective, teamLine, score--);
                for (ServerPlayerEntity player : team.getPlayers()) {
                    if (score < 0) {
                        break;
                    }
                    ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
                    String name = online != null ? online.getGameProfile().getName() : player.getGameProfile().getName();
                    if (team.isAlive(player)) {
                        this.setInfoLine(scoreboard, objective, " " + team.getColor() + "● §f" + name, score--);
                    } else {
                        this.setInfoLine(scoreboard, objective, " §7✝ §f" + name, score--);
                    }
                }
                this.setInfoLine(scoreboard, objective, "§8------------------------", score--);
            }
        }

        // 只给本场参战玩家显示侧边栏（回主城后自然隐藏）
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online != null && online.networkHandler != null) {
                online.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective));
            }
        }
    }

    /** 模式行：按模式着色。 */
    private String modeLine() {
        String color = switch (this.type) {
            case DUEL_1V1 -> "§a";
            case DUEL_2V2 -> "§e";
            case FFA -> "§d";
            case SUMO -> "§b";
            case PVP_1_8 -> "§c";
            case SKYWARS -> "§6";
            case BRIDGE_1V1 -> "§3";
            case BRIDGE_2V2 -> "§b";
            case BRIDGE_1V1V1V1 -> "§5";
            case BRIDGE_TEAM -> "§e";
            case LUCKY_PILLAR -> "§d";
            case TNT_RUN -> "§4";
            case HEARTBEAT -> "§b";
            case HOT_POTATO -> "§c";
        };
        return "模式: " + color + this.type.getDisplayName();
    }

    /** 计时行：倒计时 / 已进行时间 / 结算中。 */
    private String timeLine() {
        return switch (this.state) {
            case COUNTDOWN -> "§e开局倒计时 §c" + ((this.countdownTicks + 19) / 20) + "s";
            case ACTIVE -> "§a已进行 §f" + formatTime(Math.max(0, (this.ticks - this.initialCountdownTicks) / 20));
            case CELEBRATING -> "§6结算中...";
            default -> "§7准备中...";
        };
    }

    /** 空岛战争缩圈行：未开始显示剩余时间，进行中显示当前安全半径。 */
    private String skywarsShrinkLine() {
        if (this.skywarsLayout == null) {
            return "§7缩圈: -";
        }
        PvPConfig cfg = PvPConfig.INSTANCE;
        int elapsed = Math.max(0, (this.ticks - this.initialCountdownTicks) / 20);
        int startSec = cfg.skywarsShrinkStartSeconds;
        if (elapsed < startSec) {
            return "§c缩圈 §7" + formatTime(startSec - elapsed) + " 后";
        }
        int stage = (elapsed - startSec) / cfg.skywarsShrinkIntervalSeconds + 1;
        int keep = this.skywarsLayout.maxRadius() - stage * cfg.skywarsShrinkBlocksPerStage;
        if (keep < this.skywarsMinShrinkRadius()) {
            keep = this.skywarsMinShrinkRadius();
        }
        return "§c缩圈中 §e半径 " + keep;
    }

    /** 缩圈最小安全半径：不低于中间主岛半径+2，避免缩圈拆掉主岛。 */
    private int skywarsMinShrinkRadius() {
        return Math.max(PvPConfig.INSTANCE.skywarsShrinkMinRadius,
                PvPConfig.INSTANCE.skywarsMiddleRadius + 2);
    }

    private static String formatTime(int seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
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
        // 本场玩家回到主城后隐藏侧边栏（按玩家发送空显示）
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online != null && online.networkHandler != null) {
                online.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, null));
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

    /** 屏幕中央大字（Title 系统）提示：主标题 + 可选副标题。 */
    private void broadcastTitleBig(String title, String subtitle) {
        for (ServerPlayerEntity player : this.players) {
            ServerPlayerEntity online = this.manager.getOnlinePlayer(player.getUuid());
            if (online != null && online.networkHandler != null) {
                online.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 40, 10));
                if (subtitle != null) {
                    online.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(subtitle)));
                }
                online.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title)));
            }
        }
    }

    /** 倒计时/简短提示：屏幕中央大字（不再是动作栏小字）。 */
    private void broadcastTitle(String text) {
        this.broadcastTitleBig("§6§l" + text, null);
    }
}
