package com.example.pvp.match;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.colorblind.ColorblindFloor;
import com.example.pvp.arena.colorblind.ColorblindMapGenerator;
import com.example.pvp.arena.colorblind.ColorblindPalette;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.text.Messages;
import com.mojang.logging.LogUtils;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 色盲派对（对照 Hypixel Pixel Party）的对局运行时。
 *
 * <p>每回合：重建一整片随机配色的地板 → 用<b>标题</b>给出目标颜色 → 倒计时 → 非目标色方块全部消失 →
 * 站错的人掉进虚空淘汰。撑满回合数或最后一人存活即胜。
 *
 * <p><b>本模式的核心改动（不同于 Hypixel）</b>：目标颜色不通过快捷栏方块给出，而是用屏幕中央的
 * 大字标题 —— 标题<b>写的是一个颜色名，却被渲染成另一种颜色</b>，正确答案是<b>文字的颜色</b>
 * （Stroop 效应）。所以标题文字用 {@link ColorblindPalette.Entry#rgb()} 精确上色，
 * 而不是用 §色码。
 */
public final class ColorblindPartySession {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每回合重建地板后留出的"看清地板"时间。 */
    private static final int BUILD_TICKS = 40;
    /** 方块消失后留给玩家坠落/结算的时间。 */
    private static final int GAP_TICKS = 30;
    /** 掉到这个高度以下就算掉出场地。 */
    private static final int VOID_MARGIN = 12;

    public enum Phase {
        /** 重建地板，等待下一回合。 */
        BUILD,
        /** 已公布目标色，倒计时中。 */
        COUNTDOWN,
        /** 方块已消失，等待坠落淘汰结算。 */
        GAP
    }

    private final Match match;
    private final Random random;
    private final int maxRounds;

    private ColorblindFloor floor;
    private List<BlockPos> spawnList = List.of();

    private Phase phase = Phase.BUILD;
    private int phaseTicks = BUILD_TICKS;
    private int round;
    private int[] roundColors = new int[0];
    private int answerColor;
    private int wordColor;
    /** 本回合 Hyper 灾难事件结束时需要清理的地板层以上残留（雪、玻璃罩、飞毯等）。 */
    private boolean hyper;

    public ColorblindPartySession(Match match, ArenaTemplate template, int regionIndex, int seed) {
        this.match = match;
        this.random = new Random(seed * 0x9E3779B97F4A7C15L + 17);
        this.maxRounds = Math.max(1, PvPConfig.INSTANCE.colorblindRounds);
        this.hyper = "hyper".equalsIgnoreCase(PvPConfig.INSTANCE.colorblindForceMode);
        this.floor = ColorblindMapGenerator.createFloor(regionIndex);
    }

    // ---------- 对外接口（Match 调用） ----------

    public int round() {
        return this.round;
    }

    public int maxRounds() {
        return this.maxRounds;
    }

    public boolean hyper() {
        return this.hyper;
    }

    public Phase phase() {
        return this.phase;
    }

    /** 地板边长（记分板/命令用）。 */
    public int floorSize() {
        return this.floor.size();
    }

    /** 本局所有玩家的出生点（地板上均布一圈）。 */
    public List<BlockPos> spawns() {
        return this.spawnList;
    }

    /**
     * 倒计时阶段调用：先把第 1 回合的地板铺出来，否则玩家在开赛前的等待期会直接掉进虚空。
     * {@code playerCount} 同时决定出生点分布。
     */
    public void prepare(int playerCount) {
        BlockPos center = this.floor.center();
        int radius = Math.max(3, this.floor.size() / 6);
        List<BlockPos> spawns = new ArrayList<>();
        for (int i = 0; i < Math.max(1, playerCount); i++) {
            double angle = i * 2.0 * Math.PI / Math.max(1, playerCount);
            spawns.add(new BlockPos(
                    center.getX() + (int) Math.round(Math.cos(angle) * radius),
                    ArenaTemplate.PLATFORM_Y + 1,
                    center.getZ() + (int) Math.round(Math.sin(angle) * radius)));
        }
        this.spawnList = List.copyOf(spawns);

        ArenaWorld arena = this.arena();
        if (arena == null) {
            LOGGER.warn("[PvP] 色盲派对：竞技场世界未就绪，地板未铺设");
            return;
        }
        this.round = 1;
        this.rollFloor(arena); // 只铺地板，不提前播报"第 1 回合准备中"
    }

    /**
     * ACTIVE 开始：重摇第 1 回合地板并给 2 秒准备，然后才公布目标色。
     * （倒计时阶段铺的那版地板只是防止玩家掉虚空，这里刷新一次更干净；
     * 也顺便避免"开始！"的开赛大字把目标色标题立刻顶掉。）
     */
    public void start() {
        ArenaWorld arena = this.arena();
        if (arena == null) {
            this.enterCountdown();
            return;
        }
        this.round = 1;
        this.enterBuild(arena);
    }

    /** 每 tick 调用（仅在 ACTIVE 期间）。 */
    public void tick() {
        if (this.match.getState() != MatchState.ACTIVE) {
            return;
        }
        this.checkVoidFalls();
        switch (this.phase) {
            case BUILD -> this.tickBuild();
            case COUNTDOWN -> this.tickCountdownPhase();
            case GAP -> this.tickGap();
        }
    }

    // ---------- 回合状态机 ----------

    private void tickBuild() {
        if (--this.phaseTicks > 0) {
            return;
        }
        this.enterCountdown();
    }

    private void tickCountdownPhase() {
        if (this.phaseTicks % 10 == 0) {
            this.sendActionBarHint();
        }
        if (this.phaseTicks % 20 == 0) {
            this.playToAlive(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.7F, 1.4F);
        }
        if (--this.phaseTicks > 0) {
            return;
        }
        this.enterGap();
    }

    private void tickGap() {
        if (--this.phaseTicks > 0) {
            return;
        }
        if (this.round >= this.maxRounds) {
            this.endMatch("§d第 §e" + this.maxRounds + "§r 回合结束 —— 场上剩下的玩家全部获胜！");
            return;
        }
        this.round++;
        ArenaWorld arena = this.arena();
        if (arena == null) {
            return;
        }
        this.enterBuild(arena);
    }

    /** 摇出本回合配色并铺满地板（同时定好目标色，但要等到 COUNTDOWN 才公布）。 */
    private void rollFloor(ArenaWorld arena) {
        this.roundColors = this.pickRoundColors();
        this.floor.fill(this.random, this.roundColors);
        this.floor.placeAll(arena);
        this.pickAnswerAndWord();
    }

    /** 进入"重建地板"阶段：铺好下一回合的地板并给玩家 2 秒看清。 */
    private void enterBuild(ArenaWorld arena) {
        this.phase = Phase.BUILD;
        this.phaseTicks = BUILD_TICKS;
        this.rollFloor(arena);
        this.match.broadcastToMatch(Messages.info("第 §e" + this.round + "§r / " + this.maxRounds + " 回合准备中…"));
    }

    private void enterCountdown() {
        this.phase = Phase.COUNTDOWN;
        this.phaseTicks = this.roundTicks(this.round);
        this.broadcastStroopTitle();
    }

    private void enterGap() {
        this.phase = Phase.GAP;
        this.phaseTicks = GAP_TICKS;
        ArenaWorld arena = this.arena();
        if (arena != null) {
            this.floor.vanishExcept(arena, this.answerColor);
        }
        this.playToAlive(SoundEvents.BLOCK_GLASS_BREAK, 1.0F, 0.7F);
    }

    /** Hypixel 的逐回合时限表（tick），再乘配置的缩放系数。 */
    private int roundTicks(int round) {
        int base;
        if (round <= 3) {
            base = 90;
        } else if (round <= 5) {
            base = 85;
        } else if (round <= 7) {
            base = 80;
        } else if (round <= 9) {
            base = 75;
        } else if (round <= 11) {
            base = 70;
        } else if (round <= 13) {
            base = 65;
        } else if (round <= 15) {
            base = 60;
        } else if (round <= 17) {
            base = 50;
        } else if (round <= 19) {
            base = 40;
        } else if (round <= 21) {
            base = 30;
        } else if (round <= 23) {
            base = 20;
        } else {
            base = 10;
        }
        double scale = PvPConfig.INSTANCE.colorblindTimeScale;
        return Math.max(10, (int) Math.round(base * Math.max(0.1, scale)));
    }

    // ---------- 颜色与提示 ----------

    /** 每回合从 16 色里随机抽 N 种铺地板（默认 8 种）。 */
    private int[] pickRoundColors() {
        int total = ColorblindPalette.size();
        int want = Math.max(2, Math.min(PvPConfig.INSTANCE.colorblindColorsPerRound, total));
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            pool.add(i);
        }
        Collections.shuffle(pool, this.random);
        int[] out = new int[want];
        for (int i = 0; i < want; i++) {
            out[i] = pool.get(i);
        }
        return out;
    }

    /**
     * 选"正确答案"与"文字内容"：
     * <ul>
     *   <li>{@code answerColor} = 标题文字的渲染色，也就是玩家要找的地板颜色；</li>
     *   <li>{@code wordColor} = 标题写出来的颜色名，<b>必须 ≠ answerColor</b>，
     *       而且也从本回合地板上真实存在的颜色里挑，让干扰最大化（文字写着场上另一个颜色的名字）。</li>
     * </ul>
     */
    private void pickAnswerAndWord() {
        List<Integer> answerable = new ArrayList<>();
        for (int color : this.roundColors) {
            if (ColorblindPalette.get(color).answerable()) {
                answerable.add(color);
            }
        }
        if (answerable.isEmpty()) {
            // 本回合抽到的颜色都不能当答案（例如含纯黑）；退而求其次允许纯黑
            for (int color : this.roundColors) {
                answerable.add(color);
            }
        }
        this.answerColor = answerable.get(this.random.nextInt(answerable.size()));

        List<Integer> words = new ArrayList<>();
        for (int color : this.roundColors) {
            if (color != this.answerColor) {
                words.add(color);
            }
        }
        if (words.isEmpty()) {
            words.add(this.answerColor); // 极端兜底：本回合只有一种颜色时文字与颜色必然一致
        }
        this.wordColor = words.get(this.random.nextInt(words.size()));
    }

    /** 标题 = 颜色名（含义）× 另一种颜色的渲染色（答案）。 */
    private MutableText stroopText() {
        ColorblindPalette.Entry word = ColorblindPalette.get(this.wordColor);
        ColorblindPalette.Entry answer = ColorblindPalette.get(this.answerColor);
        return Text.literal(word.name())
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(answer.rgb())).withBold(true));
    }

    private void broadcastStroopTitle() {
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            if (this.match.isEliminated(player.getUuid()) || player.networkHandler == null) {
                continue;
            }
            player.networkHandler.sendPacket(new TitleFadeS2CPacket(2, 30, 6));
            player.networkHandler.sendPacket(new SubtitleS2CPacket(
                    Text.literal("§7站到 §f文字的颜色§7 上，别看它写的是什么！")));
            player.networkHandler.sendPacket(new TitleS2CPacket(this.stroopText()));
        }
    }

    /** 回合内周期性重复提示，替代 Hypixel 常驻的快捷栏方块。 */
    private void sendActionBarHint() {
        MutableText hint = this.stroopText();
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            if (this.match.isEliminated(player.getUuid())) {
                continue;
            }
            player.sendMessage(hint, true);
        }
    }

    // ---------- 淘汰与胜负 ----------

    /** 掉出场地即淘汰（地板层下方 12 格）。 */
    private void checkVoidFalls() {
        ArenaWorld arena = this.arena();
        if (arena == null) {
            return;
        }
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            if (this.match.isEliminated(player.getUuid()) || player.getWorld() != arena) {
                continue;
            }
            if (player.getY() < ArenaTemplate.PLATFORM_Y - VOID_MARGIN) {
                this.match.eliminate(player, EliminationCause.VOID);
            }
        }
    }

    private void endMatch(String message) {
        this.match.broadcastToMatch(Messages.gold(message));
        this.match.finishMatch(this.match.firstTeam());
    }

    // ---------- 小工具 ----------

    private ArenaWorld arena() {
        return this.match.arenaWorld();
    }

    private void playToAlive(net.minecraft.sound.SoundEvent sound, float volume, float pitch) {
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            if (!this.match.isEliminated(player.getUuid())) {
                player.playSoundToPlayer(sound, SoundCategory.PLAYERS, volume, pitch);
            }
        }
    }
}
