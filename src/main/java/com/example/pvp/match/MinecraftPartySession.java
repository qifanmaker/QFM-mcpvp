package com.example.pvp.match;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.MapBuildQueue;
import com.example.pvp.arena.party.PartyMapBlueprint;
import com.example.pvp.arena.party.PartyMinigame;
import com.example.pvp.arena.party.PartyRound;
import com.example.pvp.arena.party.PartyRoundContext;
import com.example.pvp.arena.party.PartyGameSelector;
import com.example.pvp.arena.party.DropperRound;
import com.example.pvp.arena.party.RainbowRunRound;
import com.example.pvp.arena.party.RunnerRound;
import com.example.pvp.arena.party.MemoryLaneRound;
import com.example.pvp.arena.party.PartyCombatRound;
import com.example.pvp.arena.party.PartyTournamentScores;
import com.example.pvp.arena.party.CaveParkourRound;
import com.example.pvp.arena.party.PlatformsRound;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Minecraft Party 2 原生小游戏与本服五轮积分赛。
 * 原地图要求 1.21.10 且使用全服选择器，所以将规则改为仅作用于本场玩家的 Java 状态机，
 * 并使用从原图提取的静态场景与原版生成规则。按本服要求连续玩五个不重复项目，
 * 每轮先按各自规则排名，再转换为累计积分，避免把击杀数、下落深度和持肉时间直接相加。
 * 原版 Quick Play 是单小游戏；五轮累计与每轮 10 秒投票属于本服适配。
 *
 * <p>规则依据：spleef{,2,3,_tick}.mcfunction、tile_capture{,_rules,_start,_tick}.mcfunction、
 * rlgl{,_rules,_start,_tick,_finish}.mcfunction、redlight{,2}.mcfunction、endrlgl.mcfunction。
 * 自查重点：离场永久失去资格；淘汰只进入本场观战；并列名次保留；所有地形/投射物精确清理；
 * 原图的 gamerule、scoreboard、team 和 schedule 均不复制到共享服务器。
 */
public final class MinecraftPartySession implements PortedGameSession {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SELECT_TICKS = 200;
    private static final int INTRO_TICKS = 160;
    private static final int RESULT_TICKS = 120;
    private static final int MAP_RADIUS = 168;
    private static final Block[] PLAYER_COLORS = {
            Blocks.BLUE_CONCRETE, Blocks.RED_CONCRETE, Blocks.LIME_CONCRETE, Blocks.YELLOW_CONCRETE,
            Blocks.CYAN_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.BROWN_CONCRETE, Blocks.GREEN_CONCRETE,
            Blocks.PURPLE_CONCRETE, Blocks.GRAY_CONCRETE
    };

    private enum Phase { SELECT, BUILD, INTRO, PLAY, ROUND_RESULTS, CLEANUP, FINAL_RESULTS, FINISHED }

    private final Match match;
    private final BlockPos center;
    private final BlockPos lobby;
    private final Random random;
    private final PartyTournamentScores tournamentScores;
    private final Set<PartyMinigame> playedGames = EnumSet.noneOf(PartyMinigame.class);
    private final Map<UUID, Integer> slots = new LinkedHashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Set<UUID> left = new HashSet<>();
    private final Set<UUID> out = new HashSet<>();
    private final Set<UUID> completed = new HashSet<>();
    private final Map<UUID, PartyMinigame> votes = new HashMap<>();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Vec3d> lastSafe = new HashMap<>();
    private final Map<UUID, Vec3d> tileRollback = new HashMap<>();
    private final Map<UUID, Vec3d> redPositions = new HashMap<>();
    private final Map<UUID, Integer> maxProgress = new HashMap<>();
    private final Map<UUID, Double> originalJumpStrength = new HashMap<>();
    private final Map<Long, UUID> tileOwners = new HashMap<>();
    private final Map<UUID, PersistentProjectileEntity> arrows = new HashMap<>();
    // 等待平台跨轮保留；只有本轮登记的格子进入分帧清场，不能把大厅一并拆掉。
    private final LongOpenHashSet roundBlocks = new LongOpenHashSet();
    private final LongOpenHashSet lobbyBlocks = new LongOpenHashSet();
    private final List<BlockPos> lobbySpawns = new ArrayList<>();
    private final Map<UUID, Vec3d> gameSpawns = new HashMap<>();
    private final ServerBossBar bossBar = new ServerBossBar(Text.literal("Party 2 · 选择小游戏"),
            BossBar.Color.BLUE, BossBar.Style.PROGRESS);

    private Phase phase = Phase.SELECT;
    private PartyMinigame game;
    private PartyRound round;
    private boolean roundClosed;
    private Set<UUID> roundPlayers = Set.of();
    private final PartyRoundContext roundContext = new RoundContext();
    private MapBuildQueue buildQueue;
    private BlockPos sourceOffset = BlockPos.ORIGIN;
    private int phaseTicks;
    private int elapsed;
    private int gameTimeLimit;
    private int eliminatedCount;
    private int initialGamePlayers;
    private int tileFloorY;
    private int totalTiles;
    private boolean largeSpleef;
    private boolean redLight;
    private int lightTicks;
    private int redGraceTicks;
    private boolean ended;
    private Set<UUID> finalWinners = Set.of();
    private List<PartyTournamentScores.Result> finalStandings = List.of();

    public MinecraftPartySession(Match match, ArenaTemplate template, int regionIndex, int seed) {
        this.match = match;
        this.center = template.getCenter(regionIndex);
        // 观战/选择平台高于原版小人数 Spleef 的整个模板，避免与其上层地形重叠。
        // 新增 Dropper 占满世界高度；等待平台移到区域角落，避免铺图覆盖仍在等候的玩家。
        this.lobby = this.center.add(144, 54, 144);
        this.random = new Random(seed * 0x9E3779B97F4A7C15L + 31);
        int count = Math.max(1, match.players().size());
        for (int i = 0; i < match.players().size(); i++) {
            ServerPlayerEntity player = match.players().get(i);
            this.slots.put(player.getUuid(), i);
            this.names.put(player.getUuid(), player.getName().getString());
            this.scores.put(player.getUuid(), 0);
            double angle = i * Math.PI * 2 / count;
            this.lobbySpawns.add(this.lobby.add((int) Math.round(Math.cos(angle) * 3), 0,
                    (int) Math.round(Math.sin(angle) * 3)));
        }
        this.tournamentScores = new PartyTournamentScores(List.copyOf(this.slots.keySet()));
    }

    @Override
    public List<BlockPos> spawnPositions() {
        return List.copyOf(this.lobbySpawns);
    }

    @Override
    public void stageMap() {
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                this.putLobby(this.lobby.add(x, -1, z), Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState());
                if (Math.abs(x) == 6 || Math.abs(z) == 6) {
                    this.putLobby(this.lobby.add(x, 0, z), Blocks.OAK_FENCE.getDefaultState());
                }
            }
        }
    }

    @Override
    public void start() {
        this.beginSelection();
    }

    private void beginSelection() {
        this.resetRoundState();
        this.phase = Phase.SELECT;
        this.phaseTicks = SELECT_TICKS;
        this.bossBar.setColor(BossBar.Color.BLUE);
        this.bossBar.setPercent(1);
        List<PartyMinigame> choices = this.selectionChoices();
        for (ServerPlayerEntity player : this.participants()) {
            this.prepare(player, GameMode.ADVENTURE);
            this.teleport(player, this.lobbySpawns.get(this.slots.get(player.getUuid())).toCenterPos());
            this.bossBar.addPlayer(player);
            for (int i = 0; i < Math.min(8, choices.size()); i++) {
                PartyMinigame selection = choices.get(i);
                player.getInventory().setStack(i, named(selection.selectionItem().getDefaultStack(), "§e右键选择 " + selection.title()));
            }
            player.getInventory().setStack(8, named(new ItemStack(Items.COMPASS), "§e右键打开全部小游戏投票"));
            player.currentScreenHandler.sendContentUpdates();
            PartyGameSelector.open(player, this);
        }
        this.say("§6Party 2 · 第 " + this.roundNumber() + "/" + PartyTournamentScores.ROUNDS
                + " 轮§7：从剩余 " + choices.size() + " 个项目中投票。\n§e每轮按开局人数 − 名次 + 1 积分，并列同分；五轮累计分决定最终排名。\n§e10 秒后取最高票，同票随机，已玩项目不会再次出现。");
        this.title("§6第 " + this.roundNumber() + "/" + PartyTournamentScores.ROUNDS + " 轮 · 选择小游戏",
                "§f点击菜单投票，右键指南针重新打开");
    }

    @Override
    public void tick() {
        if (this.ended || this.match.getState() != MatchState.ACTIVE) {
            return;
        }
        // 只保留本场仍参赛者。临时在线实体引用从 Match 获取，避免复活后操作旧 player 对象。
        for (UUID uuid : this.slots.keySet()) {
            if (!this.left.contains(uuid) && (!this.match.contains(uuid) || this.match.onlinePlayer(uuid) == null)) {
                this.left.add(uuid);
                this.votes.remove(uuid);
            }
        }
        for (ServerPlayerEntity observer : this.participants()) {
            if (this.phase == Phase.PLAY && this.out.contains(observer.getUuid()) && (this.outOfRegion(observer)
                    || Math.abs(observer.getY() - this.center.getY()) > 75)) {
                this.teleport(observer, this.center.up(12).toCenterPos());
            }
        }
        if (this.participants().size() < 2 && !this.hasFinalResult()) {
            // 全员离场仍由 MatchManager 取消且不记战绩；剩一人只结清已经开打的这一轮。
            if (this.participants().isEmpty()) return;
            if (this.phase == Phase.PLAY) this.finishRound();
            this.finishTournament();
            return;
        }
        switch (this.phase) {
            case SELECT -> {
                this.keepInLobby();
                this.bossBar.setName(Text.literal("§e第 " + this.roundNumber() + "/" + PartyTournamentScores.ROUNDS
                        + " 轮投票 · " + seconds(this.phaseTicks) + " 秒"));
                this.bossBar.setPercent(Math.max(0, this.phaseTicks / (float) SELECT_TICKS));
                if (--this.phaseTicks <= 0) {
                    this.selectGame();
                }
            }
            case BUILD -> {
                this.keepInLobby();
                this.bossBar.setName(Text.literal("§e载入原版场景 · " + (int) (this.buildQueue.progress() * 100) + "%"));
                this.bossBar.setPercent((float) this.buildQueue.progress());
                if (this.buildQueue.flush(4)) {
                    this.buildQueue = null;
                    this.beginIntro();
                }
            }
            case INTRO -> {
                this.lockAtStarts();
                this.bossBar.setName(Text.literal("§e" + this.game.title() + " · " + seconds(this.phaseTicks) + " 秒后开始"));
                if (--this.phaseTicks <= 0) {
                    this.beginGame();
                }
            }
            case PLAY -> {
                this.elapsed++;
                if (this.round != null) {
                    this.round.tick(this.roundContext, this.elapsed);
                } else switch (this.game) {
                    case SPLEEF -> this.tickSpleef();
                    case TILE_CAPTURE -> this.tickTileCapture();
                    case RED_LIGHT_GREEN_LIGHT -> this.tickTrafficLights();
                    default -> throw new IllegalStateException("Missing Party round: " + this.game);
                }
                if (this.phase == Phase.PLAY && this.gameTimeLimit > 0 && this.elapsed >= this.gameTimeLimit) {
                    this.finishRound();
                }
                if (this.phase == Phase.PLAY && this.elapsed % 20 == 0) {
                    for (ServerPlayerEntity player : this.playingPlayers()) {
                        player.getHungerManager().setFoodLevel(20);
                        player.getHungerManager().setSaturationLevel(20);
                    }
                    this.updateProgress();
                }
            }
            case ROUND_RESULTS -> {
                this.keepInLobby();
                if (--this.phaseTicks <= 0) this.beginRoundCleanup();
            }
            case CLEANUP -> {
                this.keepInLobby();
                this.bossBar.setName(Text.literal("§e清理第 " + this.roundNumber() + " 轮场景 · "
                        + (int) (this.buildQueue.progress() * 100) + "%"));
                this.bossBar.setPercent((float) this.buildQueue.progress());
                if (this.buildQueue.flush(4)) {
                    this.buildQueue = null;
                    this.roundBlocks.clear();
                    if (this.tournamentScores.roundsPlayed() >= PartyTournamentScores.ROUNDS) this.finishTournament();
                    else this.beginSelection();
                }
            }
            case FINAL_RESULTS -> {
                this.keepInLobby();
                if (--this.phaseTicks <= 0) {
                    this.phase = Phase.FINISHED;
                    this.match.finishMatch(this.match.firstTeam());
                }
            }
            case FINISHED -> { }
        }
    }

    private void selectGame() {
        EnumMap<PartyMinigame, Integer> counts = new EnumMap<>(PartyMinigame.class);
        for (PartyMinigame choice : this.selectionChoices()) {
            counts.put(choice, 0);
        }
        if (counts.isEmpty()) {
            this.finishTournament();
            return;
        }
        for (Map.Entry<UUID, PartyMinigame> vote : this.votes.entrySet()) {
            if (this.isParticipant(vote.getKey()) && counts.containsKey(vote.getValue())) {
                counts.merge(vote.getValue(), 1, Integer::sum);
            }
        }
        int maximum = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<PartyMinigame> tied = counts.entrySet().stream().filter(entry -> entry.getValue() == maximum)
                .map(Map.Entry::getKey).toList();
        this.game = tied.get(this.random.nextInt(tied.size()));
        this.playedGames.add(this.game);
        for (ServerPlayerEntity player : this.participants()) {
            PartyGameSelector.close(player);
            player.getInventory().clear();
            player.currentScreenHandler.sendContentUpdates();
        }
        // 投票物品不能留到铺图时继续丢出，也不能作为掉落物被下一轮捡走。
        this.clearDroppedItems();
        this.round = switch (this.game) {
            case DROPPER -> new DropperRound();
            case RAINBOW_RUN -> new RainbowRunRound();
            case RUNNER -> new RunnerRound();
            case MEMORY_LANE -> new MemoryLaneRound();
            case MEAT_SLAP, ONE_SHOT_WONDER -> new PartyCombatRound(this.game);
            case CAVE_PARKOUR -> new CaveParkourRound();
            case PLATFORMS -> new PlatformsRound();
            default -> null;
        };
        try {
            this.queueGameMap();
            this.phase = Phase.BUILD;
            this.say("§a第 " + this.roundNumber() + "/" + PartyTournamentScores.ROUNDS + " 轮选择：§f"
                    + this.game.title() + " §7（原版编号 " + this.game.sourceId() + "）");
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("[PvP] Party 2 场景加载失败: {}", this.game, exception);
            this.match.cancelMatch("Party 2 场景加载失败，请联系管理员查看日志");
        }
    }

    private void queueGameMap() throws IOException {
        this.roundPlayers = Set.copyOf(this.eligiblePlayers());
        this.initialGamePlayers = this.roundPlayers.size();
        if (this.round != null) {
            this.buildQueue = new MapBuildQueue(this.arena(), this.center, MAP_RADIUS);
            this.round.stage(this.roundContext);
            for (ServerPlayerEntity player : this.participants()) {
                if (!this.gameSpawns.containsKey(player.getUuid())) throw new IOException("Party module omitted a spawn");
            }
            this.buildQueue.close();
            return;
        }
        String geometry;
        BlockPos sourceAnchor;
        int radius;
        switch (this.game) {
            case SPLEEF -> {
                this.largeSpleef = this.initialGamePlayers >= 5;
                geometry = this.largeSpleef ? "spleef_large" : "spleef_small";
                // map1 来源模板比运行区向西 30 格；map2 模板到运行区平移 (416,11,677)。
                sourceAnchor = this.largeSpleef ? new BlockPos(-245, 11, -316) : new BlockPos(-384, -9, -155);
                radius = this.largeSpleef ? 10 : 4;
                this.gameTimeLimit = 0; // 原版 Spleef 没有倒计时，以最后存活者结算。
            }
            case TILE_CAPTURE -> {
                int variant = this.initialGamePlayers >= 5 ? 3 : this.initialGamePlayers <= 3 ? 0 : this.random.nextInt(3);
                String[] variants = {"small", "medium", "large", "crowd"};
                int[] floors = {5, -9, -23, -36};
                int[] limits = {1100, 1400, 1800, 1400};
                int[] captureTargets = {625, 841, 1089, 973};
                this.tileFloorY = floors[variant];
                // 按原 tile_capture_tick2 的目标数结算；它不等于重置的白羊毛矩形面积。
                this.totalTiles = captureTargets[variant];
                geometry = "tile_capture_" + variants[variant];
                sourceAnchor = new BlockPos(-124, this.tileFloorY + 1, -140);
                radius = this.initialGamePlayers <= 3 ? 9 : 12;
                this.gameTimeLimit = this.initialGamePlayers <= 3 ? 2000 : limits[variant];
            }
            case RED_LIGHT_GREEN_LIGHT -> {
                geometry = "red_light_green_light";
                sourceAnchor = new BlockPos(-503, 26, 16);
                radius = 0;
                this.gameTimeLimit = 1800;
            }
            default -> throw new IllegalStateException("Unsupported Party 2 game");
        }
        this.sourceOffset = this.center.subtract(sourceAnchor);
        this.buildQueue = new MapBuildQueue(this.arena(), this.center, MAP_RADIUS);
        PartyMapBlueprint blueprint = PartyMapBlueprint.load(geometry);
        if (this.game == PartyMinigame.TILE_CAPTURE) {
            // Tile 原图裁剪在 x=-140/-141/-143 等边界截开装饰水槽，不能允许其向外流动；
            // 流体冻结由局部运行时守卫负责。清场还须覆盖模板原有的空气格，回收动态落块。
            // 最大 Tile 包围盒为 41×9×41=15129 格；不把 40 万格的 Spleef 模板膨胀为全体积清场。
            if (blueprint.volume() > 32_768L) {
                throw new IOException("Tile Capture cleanup volume exceeds its bounded map budget: " + blueprint.volume());
            }
            blueprint.forEachPosition(this.sourceOffset, pos -> this.roundBlocks.add(pos.asLong()));
        }
        blueprint.forEachTranslated(this.sourceOffset, (pos, state) -> {
            this.roundBlocks.add(pos.asLong());
            this.buildQueue.stage(pos, state);
        });
        if (this.largeSpleef && this.game == PartyMinigame.SPLEEF) {
            // spleef2 / spleef3 移除开局困住玩家的屏障。模板坐标等于原运行坐标 x-30。
            this.clearStagedBarriers(new BlockPos(-259, 12, -328), new BlockPos(-231, 13, -302));
            this.clearStagedBarriers(new BlockPos(-254, 11, -325), new BlockPos(-237, 12, -308));
        }
        this.buildQueue.close();
        List<ServerPlayerEntity> players = this.participants();
        for (int i = 0; i < players.size(); i++) {
            double angle = i * Math.PI * 2 / players.size();
            Vec3d spawn;
            if (this.game == PartyMinigame.RED_LIGHT_GREEN_LIGHT) {
                // 原起点 9 格宽；10 人按两排摆放，避免初始碰撞把玩家推过起跑线。
                spawn = this.source(-507 + i % 9 + 0.5, 26, 16.5 + i / 9);
            } else {
                spawn = this.source(sourceAnchor.getX() + Math.cos(angle) * radius + 0.5,
                        sourceAnchor.getY(), sourceAnchor.getZ() + Math.sin(angle) * radius + 0.5);
            }
            this.gameSpawns.put(players.get(i).getUuid(), spawn);
        }
    }

    private void clearStagedBarriers(BlockPos minimum, BlockPos maximum) {
        for (BlockPos source : BlockPos.iterate(minimum, maximum)) {
            BlockPos pos = source.add(this.sourceOffset);
            BlockState state = this.buildQueue.peek(pos);
            if (state != null && state.isOf(Blocks.BARRIER)) {
                this.buildQueue.stage(pos, Blocks.AIR.getDefaultState());
            }
        }
    }

    private void beginIntro() {
        this.phase = Phase.INTRO;
        this.phaseTicks = INTRO_TICKS;
        this.bossBar.setPercent(1);
        this.say("§6规则：" + this.game.title());
        for (String rule : this.game.rules()) {
            this.say("§f• " + rule);
        }
        for (ServerPlayerEntity player : this.participants()) {
            this.prepare(player, GameMode.ADVENTURE);
            this.teleport(player, this.gameSpawns.get(player.getUuid()));
        }
        this.title("§6" + this.game.title(), "§f阅读聊天规则，8 秒后开始");
    }

    private void beginGame() {
        this.phase = Phase.PLAY;
        this.elapsed = 0;
        for (ServerPlayerEntity player : this.participants()) {
            this.prepare(player, this.round != null ? this.round.gameMode() : this.game == PartyMinigame.SPLEEF ? GameMode.SURVIVAL : GameMode.ADVENTURE);
            this.lastSafe.put(player.getUuid(), player.getPos());
            this.tileRollback.put(player.getUuid(), player.getPos());
            if (this.game == PartyMinigame.SPLEEF) {
                this.scores.put(player.getUuid(), this.initialGamePlayers);
                ItemStack shovel = named(new ItemStack(Items.IRON_SHOVEL), "§f掘战铁锹");
                shovel.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(false));
                var enchantments = player.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
                enchantments.getEntry(Enchantments.EFFICIENCY).ifPresent(entry -> shovel.addEnchantment(entry, 3));
                ItemStack bow = new ItemStack(Items.BOW);
                bow.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(false));
                enchantments.getEntry(Enchantments.INFINITY).ifPresent(entry -> bow.addEnchantment(entry, 1));
                enchantments.getEntry(Enchantments.POWER).ifPresent(entry -> bow.addEnchantment(entry, 5));
                player.getInventory().setStack(0, shovel);
                player.getInventory().setStack(1, bow);
                player.getInventory().setStack(2, new ItemStack(Items.ARROW));
            } else if (this.game == PartyMinigame.TILE_CAPTURE) {
                var jump = player.getAttributeInstance(EntityAttributes.GENERIC_JUMP_STRENGTH);
                if (jump != null) {
                    this.originalJumpStrength.put(player.getUuid(), jump.getBaseValue());
                    jump.setBaseValue(0);
                }
                player.getInventory().setStack(0, named(new ItemStack(Items.CARROT_ON_A_STICK), "§e右键返回最近安全位置"));
                this.scores.put(player.getUuid(), 0);
            } else {
                this.scores.put(player.getUuid(), 0);
                this.maxProgress.put(player.getUuid(), 0);
            }
            player.currentScreenHandler.sendContentUpdates();
        }
        if (this.round != null) this.round.start(this.roundContext);
        this.title("§a开始！", this.game.title());
        if (this.game == PartyMinigame.RED_LIGHT_GREEN_LIGHT) {
            this.setLight(false);
        }
        this.updateProgress();
    }

    private void tickSpleef() {
        // 原版同 tick 坠落者也有相同生存时间；先收集整批，再一并结算，避免 UUID 顺序决定胜负。
        List<ServerPlayerEntity> falling = new ArrayList<>();
        for (ServerPlayerEntity player : this.playingPlayers()) {
            BlockState below = this.arena().getBlockState(player.getBlockPos().down());
            if (this.outOfRegion(player) || (this.largeSpleef && below.isOf(Blocks.BLACK_CONCRETE))
                    || player.getY() < this.center.getY() - (this.largeSpleef ? 13 : 27)) {
                falling.add(player);
            }
        }
        int placementScore = this.eliminatedCount + 1;
        for (ServerPlayerEntity player : falling) {
            this.scores.put(player.getUuid(), placementScore);
            this.eliminatedCount++;
            this.spectate(player, "§c你已坠落，正在观战本场掘战。");
        }
        this.tickSpleefArrows();
        if (this.playingPlayers().size() <= 1) {
            this.finishRound();
        }
    }

    private void tickSpleefArrows() {
        Box local = new Box(this.center).expand(165, 80, 165);
        for (PersistentProjectileEntity arrow : this.arena().getEntitiesByClass(PersistentProjectileEntity.class, local, this::ownsEntity)) {
            this.arrows.put(arrow.getUuid(), arrow);
        }
        var iterator = this.arrows.values().iterator();
        while (iterator.hasNext()) {
            PersistentProjectileEntity arrow = iterator.next();
            if (arrow.isRemoved()) {
                iterator.remove();
                continue;
            }
            if (arrow.age > 400 || this.outOfRegion(arrow)) {
                arrow.discard();
                iterator.remove();
                continue;
            }
            // 1.21.1 的 onBlockHit 设置公开 shake=7；无需反射 inGround 或序列化 NBT。
            if (arrow.shake > 0) {
                Vec3d hit = arrow.getPos();
                for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(hit.add(-0.5, -0.5, -0.5)),
                        BlockPos.ofFloored(hit.add(0.5, 0.5, 0.5)))) {
                    if (this.roundBlocks.contains(pos.asLong()) && this.arena().getBlockState(pos).isOf(Blocks.SNOW_BLOCK)) {
                        this.put(pos, Blocks.AIR.getDefaultState());
                    }
                }
                arrow.discard();
                iterator.remove();
            }
        }
    }

    private void tickTileCapture() {
        for (ServerPlayerEntity player : this.playingPlayers()) {
            UUID uuid = player.getUuid();
            Vec3d safe = this.lastSafe.get(uuid);
            if (this.outOfRegion(player) || player.getY() < this.center.getY() - 0.1
                    || player.getY() > this.center.getY() + 0.2 || !this.tilePathAllowed(uuid, safe, player.getPos())) {
                this.teleport(player, this.tileRollback.get(uuid));
                this.lastSafe.put(uuid, player.getPos());
                continue;
            }
            BlockPos below = BlockPos.ofFloored(player.getX(), this.center.getY() - 1, player.getZ());
            UUID owner = this.tileOwners.get(below.asLong());
            if (owner != null && !owner.equals(uuid)) {
                this.teleport(player, this.tileRollback.get(uuid));
                this.lastSafe.put(uuid, player.getPos());
                continue;
            }
            if (owner == null && this.arena().getBlockState(below).isOf(Blocks.WHITE_WOOL)) {
                this.tileOwners.put(below.asLong(), uuid);
                this.put(below, PLAYER_COLORS[this.slots.get(uuid) % PLAYER_COLORS.length].getDefaultState());
                this.scores.merge(uuid, 1, Integer::sum);
            } else if (owner == null) {
                this.teleport(player, this.tileRollback.get(uuid));
                this.lastSafe.put(uuid, player.getPos());
                continue;
            }
            this.lastSafe.put(uuid, player.getPos());
            // 原版 tile_capture_tick2 每 15 tick 保存越界回退点；回位棒另用最近的己方格位置。
            if (this.elapsed % 15 == 0) {
                this.tileRollback.put(uuid, player.getPos());
            }
        }
        if (this.totalTiles > 0 && this.tileOwners.size() >= this.totalTiles) {
            this.finishRound();
        }
    }

    /** 检查每个经过的格子和相邻对角，阻止跨过他人领地后直接占领远端白格。 */
    private boolean tilePathAllowed(UUID uuid, Vec3d from, Vec3d to) {
        if (from == null) {
            return false;
        }
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) * 8));
        if (steps > 80) {
            return false;
        }
        for (int i = 1; i <= steps; i++) {
            Vec3d point = from.lerp(to, i / (double) steps);
            BlockPos pos = BlockPos.ofFloored(point.x, this.center.getY() - 1, point.z);
            UUID owner = this.tileOwners.get(pos.asLong());
            if (owner != null && !owner.equals(uuid)) {
                return false;
            }
            int dx = Double.compare(to.x, from.x);
            int dz = Double.compare(to.z, from.z);
            if (dx != 0 && dz != 0) {
                UUID sideA = this.tileOwners.get(pos.add(-dx, 0, 0).asLong());
                UUID sideB = this.tileOwners.get(pos.add(0, 0, -dz).asLong());
                if (sideA != null && sideA.equals(sideB) && !sideA.equals(uuid)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void tickTrafficLights() {
        if (--this.lightTicks <= 0) {
            this.setLight(!this.redLight);
        }
        if (this.redLight && this.redGraceTicks > 0 && --this.redGraceTicks == 0) {
            for (ServerPlayerEntity player : this.playingPlayers()) {
                this.redPositions.put(player.getUuid(), player.getPos());
            }
        }
        List<ServerPlayerEntity> finishedThisTick = new ArrayList<>();
        for (ServerPlayerEntity player : this.playingPlayers()) {
            UUID uuid = player.getUuid();
            if (this.completed.contains(uuid)) {
                continue;
            }
            Vec3d locked = this.redPositions.get(uuid);
            if (this.outOfRegion(player) || player.getY() < this.center.getY() - 2
                    || (this.redLight && this.redGraceTicks == 0 && locked != null && locked.squaredDistanceTo(player.getPos()) > 0.0001)) {
                this.teleport(player, this.gameSpawns.get(uuid));
                this.redPositions.put(uuid, player.getPos());
                player.sendMessage(Text.literal("§c你动了，回到起点！"), true);
            }
            int currentProgress = Math.max(0, Math.min(145, (int) Math.floor(16.5 + this.sourceOffset.getZ() - player.getZ())));
            this.maxProgress.merge(uuid, currentProgress, Math::max);
            this.scores.put(uuid, this.maxProgress.get(uuid) * 16 + currentProgress);
            if (player.getZ() <= -128.5 + this.sourceOffset.getZ()
                    && this.arena().getBlockState(player.getBlockPos().down()).isOf(Blocks.WHITE_CONCRETE)) {
                finishedThisTick.add(player);
            }
        }
        int finishScore = (this.initialGamePlayers - this.completed.size()) * 100_000 + 145 * 17;
        for (ServerPlayerEntity player : finishedThisTick) {
            this.completed.add(player.getUuid());
            this.scores.put(player.getUuid(), finishScore);
            this.say("§a" + player.getName().getString() + " 已到达终点！");
            this.spectate(player, "§a你已完赛，正在观看其他玩家。");
        }
        if (this.playingPlayers().size() <= 1) {
            this.finishRound();
        }
    }

    private void setLight(boolean red) {
        this.redLight = red;
        this.lightTicks = 40 + this.random.nextInt(8) * 10;
        this.redGraceTicks = red ? 19 : 0; // 原版普通难度 redlight.mcfunction 的 19 tick 反应窗口。
        this.redPositions.clear();
        this.say(red ? "§c§l红灯！停下！" : "§a§l绿灯！前进！");
        this.title(red ? "§c§l红灯" : "§a§l绿灯", red ? "§f立即停下，移动会回到起点" : "§f向前跑到终点");
        this.bossBar.setColor(red ? BossBar.Color.RED : BossBar.Color.GREEN);
        for (ServerPlayerEntity player : this.participants()) {
            player.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.PLAYERS, 0.7f, red ? 2 : 0.7f);
        }
    }

    private void finishRound() {
        if (this.phase != Phase.PLAY || this.ended) return;
        // 先关掉 PLAY 防止模块 finish 再次请求结束，保证本轮最多计分一次。
        this.phase = Phase.ROUND_RESULTS;
        if (this.round != null) this.round.finish(this.roundContext);
        Map<UUID, Integer> values = new LinkedHashMap<>();
        for (UUID player : this.roundPlayers) {
            if (this.isParticipant(player)) values.put(player, this.scores.getOrDefault(player, 0));
        }
        if (values.isEmpty()) return; // 全员退出由父 Match 取消，不制造一轮空成绩。
        List<PartyTournamentScores.Result> ranking = this.tournamentScores.recordRound(values);
        this.phaseTicks = RESULT_TICKS;
        this.say("§6§l第 " + this.tournamentScores.roundsPlayed() + "/" + PartyTournamentScores.ROUNDS
                + " 轮排名 · " + this.game.title());
        for (PartyTournamentScores.Result result : ranking) {
            this.say("§e第 " + result.place() + " 名 §f" + this.names.get(result.player()) + " §7"
                    + this.scoreDescription(result.player()) + " §a本轮 +" + result.awarded()
                    + "｜累计 " + result.total());
        }
        // finish 只保证模块分数已结清；Dropper/Rainbow 的属性恢复在 close 中。
        this.closeRoundResources();
        this.returnToLobby();
        this.bossBar.setName(Text.literal("§6第 " + this.tournamentScores.roundsPlayed() + "/"
                + PartyTournamentScores.ROUNDS + " 轮结束 · 已计入累计积分"));
        this.bossBar.setPercent(1);
        this.title("§6第 " + this.tournamentScores.roundsPlayed() + "/" + PartyTournamentScores.ROUNDS + " 轮结束",
                "§f本轮积分与累计分已发至聊天");
    }

    private void beginRoundCleanup() {
        this.closeRoundResources();
        this.returnToLobby();
        this.phase = Phase.CLEANUP;
        this.buildQueue = new MapBuildQueue(this.arena(), this.center, MAP_RADIUS);
        // 先 close 模块，让 Runner 最后落地的方块也登记进 roundBlocks，再确定待清集合。
        var iterator = this.roundBlocks.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            if (!this.lobbyBlocks.contains(packed)) {
                this.buildQueue.stage(BlockPos.fromLong(packed), Blocks.AIR.getDefaultState());
            }
        }
        this.buildQueue.close();
    }

    private void finishTournament() {
        if (this.hasFinalResult() || this.ended) return;
        Set<UUID> eligible = this.eligiblePlayers();
        if (eligible.isEmpty()) return;
        // 只有最终榜冻结后 Match 才将离场视为普通退出；前四轮结果页仍属于未结束比赛。
        this.finalStandings = this.tournamentScores.standings(eligible);
        this.finalWinners = this.tournamentScores.leaders(eligible);
        this.phase = Phase.FINAL_RESULTS;
        this.phaseTicks = RESULT_TICKS;
        this.buildQueue = null;
        this.closeRoundResources();
        this.returnToLobby();
        this.say("§6§lParty 2 五轮总排名");
        if (this.tournamentScores.roundsPlayed() < PartyTournamentScores.ROUNDS) {
            this.say("§7剩余参赛人数不足，按已完成的 " + this.tournamentScores.roundsPlayed() + " 轮提前结算。");
        }
        for (PartyTournamentScores.Result result : this.finalStandings) {
            this.say("§e第 " + result.place() + " 名 §f" + this.names.get(result.player())
                    + " §a累计 " + result.total() + " 分");
        }
        this.bossBar.setName(Text.literal("§6Party 2 五轮总排名 · 随后结算并返回大厅"));
        this.bossBar.setPercent(1);
        this.title("§6Party 2 五轮赛结束", "§f按累计积分排名，随后返回大厅");
    }

    private void closeRoundResources() {
        try {
            if (!this.roundClosed) {
                if (this.round != null) this.round.close(this.roundContext);
                this.roundClosed = true;
            }
        } finally {
            this.discardArrows();
            this.clearDroppedItems();
            for (ServerPlayerEntity player : this.participants()) this.restoreJump(player);
        }
    }

    private void clearDroppedItems() {
        ArenaWorld world = this.arena();
        if (world == null) return;
        // 单小游戏以前由 Match 最终清场回收掉落；五轮中途也要清理，但只限本区域物品实体。
        Box bounds = new Box(this.center.getX() - MAP_RADIUS, world.getBottomY(), this.center.getZ() - MAP_RADIUS,
                this.center.getX() + MAP_RADIUS, world.getTopY(), this.center.getZ() + MAP_RADIUS);
        for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, bounds, entity -> true)) item.discard();
    }

    private void returnToLobby() {
        for (ServerPlayerEntity player : this.participants()) {
            PartyGameSelector.close(player);
            this.prepare(player, GameMode.ADVENTURE);
            this.teleport(player, this.lobbySpawns.get(this.slots.get(player.getUuid())).toCenterPos());
        }
    }

    private void resetRoundState() {
        // 必须先用旧坐标收尾，再清空原三款内置玩法与独立模块的本轮状态。
        this.closeRoundResources();
        this.round = null;
        this.roundClosed = false;
        this.game = null;
        this.roundPlayers = Set.of();
        this.buildQueue = null;
        this.sourceOffset = BlockPos.ORIGIN;
        this.out.clear();
        this.completed.clear();
        this.votes.clear();
        this.scores.clear();
        this.gameSpawns.clear();
        this.lastSafe.clear();
        this.tileRollback.clear();
        this.redPositions.clear();
        this.maxProgress.clear();
        this.originalJumpStrength.clear();
        this.tileOwners.clear();
        this.elapsed = 0;
        this.gameTimeLimit = 0;
        this.eliminatedCount = 0;
        this.initialGamePlayers = 0;
        this.tileFloorY = 0;
        this.totalTiles = 0;
        this.largeSpleef = false;
        this.redLight = false;
        this.lightTicks = 0;
        this.redGraceTicks = 0;
    }

    private int roundNumber() {
        int played = this.tournamentScores.roundsPlayed();
        return this.phase == Phase.ROUND_RESULTS || this.phase == Phase.CLEANUP
                ? Math.max(1, played) : Math.min(PartyTournamentScores.ROUNDS, played + 1);
    }

    private Set<UUID> eligiblePlayers() {
        Set<UUID> eligible = new HashSet<>();
        for (UUID uuid : this.slots.keySet()) if (this.isParticipant(uuid)) eligible.add(uuid);
        return eligible;
    }

    @Override
    public ActionResult useItem(ServerPlayerEntity player, Hand hand) {
        if (!this.isParticipant(player.getUuid())) {
            return ActionResult.PASS;
        }
        ItemStack held = player.getStackInHand(hand);
        if (this.phase == Phase.SELECT) {
            if (held.isOf(Items.COMPASS)) {
                PartyGameSelector.open(player, this);
                return ActionResult.SUCCESS;
            }
            for (PartyMinigame choice : this.selectionChoices()) {
                if (held.isOf(choice.selectionItem())) {
                    this.vote(player, choice);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.FAIL;
        }
        if (this.phase == Phase.PLAY && this.round != null && !this.out.contains(player.getUuid())) {
            return this.round.useItem(this.roundContext, player, hand);
        }
        if (this.phase == Phase.PLAY && this.game == PartyMinigame.TILE_CAPTURE && !this.out.contains(player.getUuid())
                && held.isOf(Items.CARROT_ON_A_STICK)) {
            // tc_reset_to_spawn 指向每 tick 更新的 player_N_respawn，并不是开局出生点。
            this.teleport(player, this.lastSafe.get(player.getUuid()));
            return ActionResult.SUCCESS;
        }
        return this.phase == Phase.PLAY && this.game == PartyMinigame.SPLEEF ? ActionResult.PASS : ActionResult.FAIL;
    }

    @Override
    public ActionResult useBlock(ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        // 场景里的按钮、门、容器不能触发原地图已移除的红石或储物逻辑。
        if (this.phase == Phase.PLAY && this.round != null && this.isParticipant(player.getUuid())
                && !this.out.contains(player.getUuid())) {
            return this.round.useBlock(this.roundContext, player, hand, hit);
        }
        ActionResult itemResult = this.useItem(player, hand);
        return itemResult == ActionResult.PASS ? ActionResult.FAIL : itemResult;
    }

    @Override
    public boolean canBreak(ServerPlayerEntity player, BlockPos pos) {
        if (this.round != null) return this.phase == Phase.PLAY && this.isParticipant(player.getUuid())
                && !this.out.contains(player.getUuid()) && this.round.canBreak(this.roundContext, player, pos);
        if (this.phase != Phase.PLAY || this.game != PartyMinigame.SPLEEF || !this.isParticipant(player.getUuid())
                || this.out.contains(player.getUuid()) || !player.getMainHandStack().isOf(Items.IRON_SHOVEL)
                || !this.roundBlocks.contains(pos.asLong()) || player.getEyePos().squaredDistanceTo(pos.toCenterPos()) > 36) {
            return false;
        }
        BlockState state = this.arena().getBlockState(pos);
        return state.isOf(Blocks.SNOW_BLOCK) || state.isOf(Blocks.SNOW) || state.isOf(Blocks.RED_SAND)
                || state.isOf(Blocks.DIRT) || state.isOf(Blocks.COARSE_DIRT);
    }

    @Override
    public ActionResult attackBlock(ServerPlayerEntity player, BlockPos pos) {
        if (this.round != null) return this.phase == Phase.PLAY && this.isParticipant(player.getUuid())
                && !this.out.contains(player.getUuid()) ? this.round.attackBlock(this.roundContext, player, pos) : ActionResult.FAIL;
        if (!this.canBreak(player, pos)) {
            return ActionResult.FAIL;
        }
        // 效率 III 铁锹在原图可快速挖掘；服务端直接移除同一合法格，避免掉落物泄入其他模式。
        this.put(pos, Blocks.AIR.getDefaultState());
        return ActionResult.SUCCESS;
    }

    @Override
    public boolean ownsEntity(Entity entity) {
        if (this.round != null) return this.round.ownsEntity(entity);
        return this.game == PartyMinigame.SPLEEF && entity instanceof PersistentProjectileEntity projectile
                && projectile.getOwner() != null && this.roundPlayers.contains(projectile.getOwner().getUuid())
                && this.isParticipant(projectile.getOwner().getUuid())
                && !this.outOfRegion(projectile);
    }

    @Override
    public void onDeath(ServerPlayerEntity player) {
        if (!this.isParticipant(player.getUuid()) || this.hasFinalResult() || this.ended) {
            return;
        }
        if (this.phase != Phase.PLAY) {
            this.prepare(player, GameMode.ADVENTURE);
            this.teleport(player, this.phase == Phase.INTRO ? this.gameSpawns.get(player.getUuid()) : this.lobby.toCenterPos());
            return;
        }
        if (this.round != null) {
            this.round.death(this.roundContext, player);
            return;
        }
        if (!this.out.contains(player.getUuid())) {
            if (this.game == PartyMinigame.SPLEEF) {
                this.scores.put(player.getUuid(), ++this.eliminatedCount);
            }
            this.spectate(player, "§c你已出局，正在观看本场比赛。");
        }
    }

    @Override
    public void onLeave(ServerPlayerEntity player) {
        if (this.round != null && !this.roundClosed && !this.left.contains(player.getUuid())) {
            this.round.leave(this.roundContext, player);
        }
        PartyGameSelector.close(player);
        this.left.add(player.getUuid());
        this.votes.remove(player.getUuid());
        this.bossBar.removePlayer(player);
        this.restoreJump(player);
        // 已获得的累计积分保留；退赛失去后续参赛资格，最终榜公布后的赢家集合不再改写。
    }

    @Override
    public void onEnd() {
        if (this.ended) {
            return;
        }
        this.ended = true;
        this.closeRoundResources();
        for (ServerPlayerEntity player : this.participants()) PartyGameSelector.close(player);
        this.buildQueue = null;
        this.bossBar.clearPlayers();
        // 保留累计榜 / finalWinners 供 Match 唯一一次最终结算保存战绩。
    }

    @Override
    public void clearArena() {
        this.buildQueue = null;
        if (this.arena() != null) {
            var iterator = this.roundBlocks.iterator();
            while (iterator.hasNext()) {
                this.arena().setBlockState(BlockPos.fromLong(iterator.nextLong()), Blocks.AIR.getDefaultState(), 3);
            }
            iterator = this.lobbyBlocks.iterator();
            while (iterator.hasNext()) {
                this.arena().setBlockState(BlockPos.fromLong(iterator.nextLong()), Blocks.AIR.getDefaultState(), 3);
            }
        }
        this.roundBlocks.clear();
        this.lobbyBlocks.clear();
    }

    @Override
    public Set<UUID> winners() {
        // 仅 finishTournament 冻结累计分赢家；单轮赢家不能触发整场战绩。
        return this.finalWinners;
    }

    @Override
    public boolean hasFinalResult() {
        return this.phase == Phase.FINAL_RESULTS || this.phase == Phase.FINISHED;
    }

    @Override
    public List<String> scoreboardLines() {
        List<String> lines = new ArrayList<>();
        lines.add("§6Party 2 · 五轮积分赛");
        lines.add(this.hasFinalResult() ? "§f已完成 " + this.tournamentScores.roundsPlayed() + "/" + PartyTournamentScores.ROUNDS + " 轮"
                : "§f第 " + this.roundNumber() + "/" + PartyTournamentScores.ROUNDS + " 轮 · 不重复项目");
        if (this.game != null && !this.hasFinalResult()) lines.add("§b" + this.game.title());
        lines.add(switch (this.phase) {
            case SELECT -> "§e投票剩余 §f" + seconds(this.phaseTicks) + "s";
            case BUILD -> "§e原版场景加载中";
            case INTRO -> "§e准备 §f" + seconds(this.phaseTicks) + "s";
            case PLAY -> this.gameTimeLimit > 0 ? "§e剩余 §f" + seconds(this.gameTimeLimit - this.elapsed) + "s" : "§e最后存活者获胜";
            case ROUND_RESULTS -> "§6本轮积分已计入总分";
            case CLEANUP -> "§e本轮场景清理中";
            case FINAL_RESULTS, FINISHED -> "§6最终累计排名已公布";
        });
        List<PartyTournamentScores.Result> ranking = this.hasFinalResult() ? this.finalStandings
                : this.tournamentScores.standings(this.eligiblePlayers());
        for (PartyTournamentScores.Result result : ranking.stream().limit(8).toList()) {
            String progress = this.phase == Phase.PLAY ? " §7(" + this.scoreDescription(result.player()) + ")" : "";
            lines.add("§f" + this.names.get(result.player()) + " §e累计 " + result.total() + progress);
        }
        return lines;
    }

    private String scoreDescription(UUID uuid) {
        if (this.round != null) return this.round.scoreDescription(this.roundContext, uuid);
        if (this.game == PartyMinigame.RED_LIGHT_GREEN_LIGHT) {
            return this.completed.contains(uuid) ? "已完赛" : this.maxProgress.getOrDefault(uuid, 0) + " / 145 格";
        }
        if (this.game == PartyMinigame.SPLEEF) {
            return this.out.contains(uuid) ? "已出局" : "存活";
        }
        return this.scores.getOrDefault(uuid, 0) + (this.game == PartyMinigame.TILE_CAPTURE ? " 格" : "");
    }

    private void spectate(ServerPlayerEntity player, String message) {
        this.out.add(player.getUuid());
        player.getInventory().clear();
        player.setHealth(player.getMaxHealth());
        player.changeGameMode(GameMode.SPECTATOR);
        this.teleport(player, this.center.up(12).toCenterPos());
        player.sendMessage(Text.literal(message), false);
    }

    private void lockAtStarts() {
        for (ServerPlayerEntity player : this.participants()) {
            Vec3d spawn = this.gameSpawns.get(player.getUuid());
            if (spawn != null && player.getPos().squaredDistanceTo(spawn) > 0.01) {
                this.teleport(player, spawn);
            }
        }
    }

    private void keepInLobby() {
        for (ServerPlayerEntity player : this.participants()) {
            if (player.getY() < this.lobby.getY() - 1 || player.getPos().squaredDistanceTo(this.lobby.toCenterPos()) > 150) {
                this.teleport(player, this.lobbySpawns.get(this.slots.get(player.getUuid())).toCenterPos());
            }
        }
    }

    private void updateProgress() {
        if (this.round != null && this.round.status(this.roundContext) != null) {
            this.bossBar.setName(Text.literal(this.round.status(this.roundContext)));
            this.bossBar.setPercent(this.gameTimeLimit <= 0 ? 1 : Math.max(0, 1 - this.elapsed / (float) this.gameTimeLimit));
            return;
        }
        this.bossBar.setName(Text.literal(this.game.title() + (this.gameTimeLimit > 0
                ? " · " + seconds(this.gameTimeLimit - this.elapsed) + " 秒" : " · 剩余 " + this.playingPlayers().size() + " 人")));
        this.bossBar.setPercent(this.gameTimeLimit <= 0 ? 1 : Math.max(0, 1 - this.elapsed / (float) this.gameTimeLimit));
    }

    private void prepare(ServerPlayerEntity player, GameMode mode) {
        player.getInventory().clear();
        player.clearStatusEffects();
        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(20);
        player.changeGameMode(mode);
        player.setFireTicks(0);
        player.fallDistance = 0;
    }

    private void restoreJump(ServerPlayerEntity player) {
        Double original = this.originalJumpStrength.remove(player.getUuid());
        if (original != null) {
            var attribute = player.getAttributeInstance(EntityAttributes.GENERIC_JUMP_STRENGTH);
            if (attribute != null) {
                attribute.setBaseValue(original);
            }
        }
    }

    private void discardArrows() {
        if (this.arena() != null) {
            for (PersistentProjectileEntity projectile : this.arena().getEntitiesByClass(PersistentProjectileEntity.class,
                    new Box(this.center).expand(165, 100, 165), this::ownsEntity)) {
                projectile.discard();
            }
        }
        this.arrows.values().forEach(Entity::discard);
        this.arrows.clear();
    }

    private boolean isParticipant(UUID uuid) {
        return this.slots.containsKey(uuid) && !this.left.contains(uuid) && this.match.owns(uuid) && this.match.onlinePlayer(uuid) != null;
    }

    private List<ServerPlayerEntity> participants() {
        List<ServerPlayerEntity> result = new ArrayList<>();
        for (UUID uuid : this.slots.keySet()) {
            if (this.isParticipant(uuid)) {
                result.add(this.match.onlinePlayer(uuid));
            }
        }
        return result;
    }

    private List<ServerPlayerEntity> playingPlayers() {
        return this.participants().stream().filter(player -> !this.out.contains(player.getUuid())).toList();
    }

    private ArenaWorld arena() { return this.match.arenaWorld(); }

    private Vec3d source(double x, double y, double z) {
        return new Vec3d(x + this.sourceOffset.getX(), y + this.sourceOffset.getY(), z + this.sourceOffset.getZ());
    }

    private boolean outOfRegion(Entity entity) {
        return entity.getWorld() != this.arena() || Math.abs(entity.getX() - this.center.getX()) > MAP_RADIUS - 2
                || Math.abs(entity.getZ() - this.center.getZ()) > MAP_RADIUS - 2;
    }

    private void teleport(ServerPlayerEntity player, Vec3d target) {
        if (target == null || this.arena() == null) {
            return;
        }
        player.teleport(this.arena(), target.x, target.y, target.z,
                this.game == PartyMinigame.RED_LIGHT_GREEN_LIGHT ? 180 : player.getYaw(), player.getPitch());
        player.setVelocity(Vec3d.ZERO);
        player.velocityDirty = true;
        player.fallDistance = 0;
    }

    private void put(BlockPos pos, BlockState state) {
        this.roundBlocks.add(pos.asLong());
        this.arena().setBlockState(pos, state, 3);
    }

    private void putLobby(BlockPos pos, BlockState state) {
        this.lobbyBlocks.add(pos.asLong());
        this.arena().setBlockState(pos, state, 3);
    }

    private void say(String message) {
        for (ServerPlayerEntity player : this.participants()) {
            player.sendMessage(Text.literal(message), false);
        }
    }

    private void title(String title, String subtitle) {
        for (ServerPlayerEntity player : this.participants()) {
            player.networkHandler.sendPacket(new TitleFadeS2CPacket(0, 35, 5));
            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(subtitle)));
            player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title)));
        }
    }

    @Override
    public void onDeath(ServerPlayerEntity player, DamageSource source) {
        if (this.phase == Phase.PLAY && this.round != null && this.isParticipant(player.getUuid())
                && !this.out.contains(player.getUuid()) && !this.ended) {
            this.round.death(this.roundContext, player, source);
        } else this.onDeath(player);
    }

    @Override
    public void onEntityLoad(Entity entity) {
        if (this.phase == Phase.PLAY && this.round != null) this.round.entityLoaded(this.roundContext, entity);
    }

    public boolean allowsNaturalRegeneration() {
        return this.phase != Phase.PLAY || this.round == null || this.round.allowsNaturalRegeneration();
    }

    public boolean isChoosing(ServerPlayerEntity player) {
        return this.phase == Phase.SELECT && !this.ended && this.isParticipant(player.getUuid());
    }

    public PartyMinigame voteOf(UUID player) { return this.votes.get(player); }

    /** 菜单、快捷栏与服务器投票校验共用剩余列表，旧物品不能重选已经玩过的项目。 */
    public List<PartyMinigame> selectionChoices() {
        List<PartyMinigame> choices = new ArrayList<>();
        int count = this.eligiblePlayers().size();
        for (PartyMinigame choice : PartyMinigame.values()) {
            if (!this.playedGames.contains(choice) && choice.supportsPlayers(count)) choices.add(choice);
        }
        return List.copyOf(choices);
    }

    public void vote(ServerPlayerEntity player, PartyMinigame choice) {
        if (!this.isChoosing(player) || !this.selectionChoices().contains(choice)) return;
        this.votes.put(player.getUuid(), choice);
        player.sendMessage(Text.literal("§a已选择：" + choice.title()), true);
    }

    @Override
    public boolean allowsDamage(LivingEntity victim, DamageSource source) {
        return this.phase == Phase.PLAY && this.round != null && victim instanceof ServerPlayerEntity player
                && this.isParticipant(player.getUuid()) && !this.out.contains(player.getUuid())
                && !this.completed.contains(player.getUuid()) && this.round.allowsDamage(this.roundContext, victim, source);
    }

    @Override
    public void onDamage(LivingEntity victim, DamageSource source, float taken) {
        if (this.phase == Phase.PLAY && this.round != null) this.round.damaged(this.roundContext, victim, source, taken);
    }

    private final class RoundContext implements PartyRoundContext {
        public ArenaWorld world() { return arena(); }
        public BlockPos center() { return center; }
        public Random random() { return random; }
        public List<ServerPlayerEntity> players() { return participants(); }
        public List<ServerPlayerEntity> alive() { return playingPlayers().stream().filter(p -> !completed.contains(p.getUuid())).toList(); }
        public Set<UUID> playerIds() { return roundPlayers; }
        public int initialPlayerCount() { return initialGamePlayers; }
        public int slot(UUID player) { return slots.getOrDefault(player, -1); }
        public boolean isOut(UUID player) { return out.contains(player) || left.contains(player) || completed.contains(player); }
        public void sourceAnchor(BlockPos anchor) { sourceOffset = center.subtract(anchor); }
        public Vec3d source(double x, double y, double z) { return MinecraftPartySession.this.source(x, y, z); }
        public void geometry(String name, BlockPos translation) throws IOException {
            PartyMapBlueprint.load(name).forEachTranslated(translation, this::stage);
        }
        private void validate(BlockPos pos) {
            if (Math.abs(pos.getX() - center.getX()) > MAP_RADIUS || Math.abs(pos.getZ() - center.getZ()) > MAP_RADIUS
                    || pos.getY() < arena().getBottomY() || pos.getY() >= arena().getTopY()) {
                throw new IllegalArgumentException("Party module wrote outside its region: " + pos);
            }
        }
        public void stage(BlockPos pos, BlockState state) {
            validate(pos);
            roundBlocks.add(pos.asLong());
            buildQueue.stage(pos, state);
        }
        public void set(BlockPos pos, BlockState state) {
            validate(pos);
            put(pos, state);
        }
        public void spawn(UUID player, Vec3d position) { gameSpawns.put(player, position); }
        public void teleport(ServerPlayerEntity player, Vec3d pos) { MinecraftPartySession.this.teleport(player, pos); }
        public void timeLimit(int ticks) { gameTimeLimit = ticks; }
        public int elapsed() { return elapsed; }
        public int timeLimit() { return gameTimeLimit; }
        public int score(UUID player) { return scores.getOrDefault(player, 0); }
        public void score(UUID player, int score) { scores.put(player, score); }
        public void eliminate(ServerPlayerEntity player, String message) { spectate(player, message); }
        public void completed(UUID player) { completed.add(player); }
        public void finish() { finishRound(); }
        public void announce(String message) { say(message); }
        public void title(String title, String subtitle) { MinecraftPartySession.this.title(title, subtitle); }
    }

    private static ItemStack named(ItemStack stack, String name) {
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    private static int seconds(int ticks) { return Math.max(0, (ticks + 19) / 20); }
}
