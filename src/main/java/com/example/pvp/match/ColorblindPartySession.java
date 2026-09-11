package com.example.pvp.match;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.colorblind.ColorblindFloor;
import com.example.pvp.arena.colorblind.ColorblindMapGenerator;
import com.example.pvp.arena.colorblind.ColorblindPalette;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.text.Messages;
import com.mojang.logging.LogUtils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

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
        /** 开局 Normal/Hyper 投票。 */
        VOTE,
        /** 重建地板，等待下一回合。 */
        BUILD,
        /** 已公布目标色，倒计时中。 */
        COUNTDOWN,
        /** 方块已消失，等待坠落淘汰结算。 */
        GAP
    }

    /** 投票道具名（PvPMod 用它识别右击的是不是投票纸）。 */
    public static final String VOTE_ITEM_MARK = "模式投票";

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
    /** 本局是否开启 Hyper 灾难事件（开局投票决定，或被 colorblindForceMode 强制）。 */
    private boolean hyper;
    /** 开局投票：UUID → 是否投 Hyper。 */
    private final Map<UUID, Boolean> votes = new HashMap<>();
    private final ColorblindHyperEvents events;
    private final ColorblindPowerUps powerUps;

    public ColorblindPartySession(Match match, ArenaTemplate template, int regionIndex, int seed) {
        this.match = match;
        this.random = new Random(seed * 0x9E3779B97F4A7C15L + 17);
        this.maxRounds = Math.max(1, PvPConfig.INSTANCE.colorblindRounds);
        this.hyper = "hyper".equalsIgnoreCase(PvPConfig.INSTANCE.colorblindForceMode);
        this.floor = ColorblindMapGenerator.createFloor(regionIndex);
        this.events = new ColorblindHyperEvents(this, this.random);
        this.powerUps = new ColorblindPowerUps(this, this.random);
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
        this.round = 1;
        if (arena == null) {
            this.enterCountdown();
            return;
        }
        this.rollFloor(arena); // 先铺地板，投票期间玩家得站得住
        String forced = PvPConfig.INSTANCE.colorblindForceMode;
        if ("normal".equalsIgnoreCase(forced)) {
            this.hyper = false;
            this.enterBuild(arena);
        } else if ("hyper".equalsIgnoreCase(forced)) {
            this.hyper = true;
            this.enterBuild(arena);
        } else {
            this.enterVote();
        }
    }

    /** 每 tick 调用（仅在 ACTIVE 期间）。 */
    public void tick() {
        if (this.match.getState() != MatchState.ACTIVE) {
            return;
        }
        this.checkVoidFalls();
        switch (this.phase) {
            case VOTE -> this.tickVote();
            case BUILD -> this.tickBuild();
            case COUNTDOWN -> this.tickCountdownPhase();
            case GAP -> this.tickGap();
        }
        if (this.phase == Phase.COUNTDOWN || this.phase == Phase.GAP) {
            this.events.tick();
            this.powerUps.tick();
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

    // ---------- 开局 Normal/Hyper 投票 ----------

    /** 是否为投票阶段（PvPMod 判定右击投票纸是否有效）。 */
    public boolean voting() {
        return this.phase == Phase.VOTE && this.match.getState() == MatchState.ACTIVE;
    }

    private void enterVote() {
        this.phase = Phase.VOTE;
        this.phaseTicks = Math.max(20, PvPConfig.INSTANCE.colorblindVoteSeconds * 20);
        this.votes.clear();
        this.refreshVoteItems();
        this.match.broadcastToMatch(Messages.info("§d开局投票§r：右击手里的纸切换 §aNormal§7 / §cHyper§r"
                + "（" + PvPConfig.INSTANCE.colorblindVoteSeconds + " 秒后按多数决定，平票为 Normal）"));
        this.refreshVoteActionBar();
    }

    private void tickVote() {
        if (this.phaseTicks % 10 == 0) {
            this.refreshVoteActionBar();
        }
        if (--this.phaseTicks > 0) {
            return;
        }
        int forHyper = 0;
        int forNormal = 0;
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            Boolean vote = this.votes.get(player.getUuid());
            if (vote == null) {
                continue;
            }
            if (vote) {
                forHyper++;
            } else {
                forNormal++;
            }
        }
        this.hyper = forHyper > forNormal;
        this.clearVoteItems();
        this.match.broadcastToMatch(Messages.gold("§d本局模式：§r"
                + (this.hyper ? "§cHyper §7(每回合可能触发灾难事件)" : "§aNormal §7(无灾难事件)")));
        ArenaWorld arena = this.arena();
        if (arena != null) {
            this.enterBuild(arena);
        }
    }

    /** 右击投票纸：在 Normal / Hyper 之间切换（首次点击投 Hyper）。 */
    public void castVote(ServerPlayerEntity player) {
        if (!this.voting()) {
            return;
        }
        Boolean current = this.votes.get(player.getUuid());
        this.votes.put(player.getUuid(), current == null || !current);
        this.refreshVoteItems();
        this.refreshVoteActionBar();
    }

    private void refreshVoteItems() {
        String name = "§d" + VOTE_ITEM_MARK + " §7(右键切换)";
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            Boolean vote = this.votes.get(player.getUuid());
            String state = vote == null ? "§7未投票" : (vote ? "§cHyper" : "§aNormal");
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name + " §8| 当前: " + state));
            player.getInventory().setStack(8, stack);
            player.currentScreenHandler.sendContentUpdates();
        }
    }

    private void clearVoteItems() {
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            for (int slot = 0; slot < player.getInventory().size(); slot++) {
                ItemStack stack = player.getInventory().getStack(slot);
                if (stack.isOf(Items.PAPER) && stack.getName().getString().contains(VOTE_ITEM_MARK)) {
                    player.getInventory().setStack(slot, ItemStack.EMPTY);
                }
            }
            player.currentScreenHandler.sendContentUpdates();
        }
    }

    private void refreshVoteActionBar() {
        int forHyper = 0;
        int forNormal = 0;
        int pending = 0;
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            Boolean vote = this.votes.get(player.getUuid());
            if (vote == null) {
                pending++;
            } else if (vote) {
                forHyper++;
            } else {
                forNormal++;
            }
        }
        Text bar = Text.literal("§aNormal " + forNormal + "§7 | §cHyper " + forHyper
                + (pending > 0 ? "§7 | 未投 " + pending : "") + "   §8" + ((this.phaseTicks + 19) / 20) + "s");
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            player.sendMessage(bar, true);
        }
    }

    /** 摇出本回合配色并铺满地板（同时定好目标色，但要等到 COUNTDOWN 才公布）。 */
    private void rollFloor(ArenaWorld arena) {
        this.roundColors = this.pickRoundColors();
        if (this.round == 1) {
            this.fillLogoPattern(); // 第 1 回合铺同心方环图案（对齐 Hypixel「首回合固定图案」）
        } else {
            this.floor.fill(this.random, this.roundColors);
        }
        this.floor.placeAll(arena);
        this.pickAnswerAndWord();
    }

    /** 首回合图案：一圈套一圈的同心方环，每环 3 格宽，按本回合配色轮换。 */
    private void fillLogoPattern() {
        int size = this.floor.size();
        int n = this.roundColors.length;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int ring = Math.min(Math.min(dx, dz), Math.min(size - 1 - dx, size - 1 - dz));
                this.floor.set(dx, dz, this.roundColors[(ring / 3) % n]);
            }
        }
    }

    /** 进入"重建地板"阶段：清掉上一回合的灾难残留，铺好新地板并给玩家 2 秒看清。 */
    private void enterBuild(ArenaWorld arena) {
        this.phase = Phase.BUILD;
        this.phaseTicks = BUILD_TICKS;
        this.events.cleanup();
        this.powerUps.cleanup();
        ColorblindMapGenerator.clearAboveFloor(arena, this.floor.origin(), this.floor.size());
        this.rollFloor(arena);
        this.match.broadcastToMatch(Messages.info("第 §e" + this.round + "§r / " + this.maxRounds + " 回合准备中…"));
    }

    private void enterCountdown() {
        this.phase = Phase.COUNTDOWN;
        this.phaseTicks = this.roundTicks(this.round);
        // Hyper：第 1 回合永远是普通局，之后按配置概率触发灾难事件
        if (this.hyper && this.round >= 2
                && this.random.nextInt(100) < PvPConfig.INSTANCE.colorblindHyperEventChance) {
            this.events.beginRandom();
        }
        ArenaWorld arena = this.arena();
        if (arena != null) {
            this.powerUps.spawnBeacons(arena);
        }
        this.broadcastStroopTitle();
    }

    private void enterGap() {
        this.phase = Phase.GAP;
        this.phaseTicks = GAP_TICKS;
        ArenaWorld arena = this.arena();
        if (arena != null) {
            this.floor.vanishExcept(arena, this.answerColor);
            // 拆掉地板层以上的东西（信标/铁砧/玻璃罩）：否则玩家能站在这些悬空方块上躲过本回合。
            // 只清上面 2 层 —— 魔毯补的是地板层本身，得留着（那正是它的作用）。
            ColorblindMapGenerator.clearAboveFloor(arena, this.floor.origin(), this.floor.size(), 2);
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
                this.playToAlive(SoundEvents.BLOCK_GLASS_BREAK, 0.9F, 0.5F);
                this.match.eliminate(player, EliminationCause.VOID);
            }
        }
    }

    private void endMatch(String message) {
        this.match.broadcastToMatch(Messages.gold(message));
        this.match.finishMatch(this.match.firstTeam());
    }

    // ---------- 小工具 ----------

    // ---------- 收尾 ----------

    /**
     * 对局结束（由 {@link Match#finishMatch} 调用）：撤掉灾难/加成残留，
     * 把整块地板重绘成 "GAME OVER"（对齐 Hypixel 结算时的地板字样）。
     */
    public void onMatchEnd() {
        this.events.cleanup();
        this.powerUps.cleanup();
        ArenaWorld arena = this.arena();
        if (arena == null) {
            return;
        }
        ColorblindMapGenerator.clearAboveFloor(arena, this.floor.origin(), this.floor.size());
        this.drawGameOver(arena);
        this.playToAlive(SoundEvents.BLOCK_GLASS_BREAK, 1.0F, 0.6F);
    }

    /** 4×5 点阵字形，'#' 为笔画。只需要 "GAME OVER" 用到的 7 个字母。 */
    private static final java.util.Map<Character, String[]> GLYPHS = java.util.Map.of(
            'G', new String[]{"####", "#...", "#.##", "#..#", "####"},
            'A', new String[]{".##.", "#..#", "####", "#..#", "#..#"},
            'M', new String[]{"#..#", "####", "####", "#..#", "#..#"},
            'E', new String[]{"####", "#...", "###.", "#...", "####"},
            'O', new String[]{".##.", "#..#", "#..#", "#..#", ".##."},
            'V', new String[]{"#..#", "#..#", "#..#", "#..#", ".##."},
            'R', new String[]{"###.", "#..#", "###.", "#.#.", "#..#"});

    private void drawGameOver(ArenaWorld arena) {
        final String text = "GAME OVER";
        final int glyphW = 4;
        final int glyphH = 5;
        final int gap = 1;
        int size = this.floor.size();
        int totalW = text.length() * (glyphW + gap) - gap;
        int originX = Math.max(0, (size - totalW) / 2);
        int originZ = Math.max(0, (size - glyphH) / 2);

        int bg = ColorblindPalette.indexOf(net.minecraft.block.Blocks.BLACK_CONCRETE);
        int fg = ColorblindPalette.indexOf(net.minecraft.block.Blocks.WHITE_CONCRETE);
        if (bg < 0) {
            bg = 0;
        }
        if (fg < 0) {
            fg = 0;
        }
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                this.floor.set(dx, dz, bg);
            }
        }
        for (int i = 0; i < text.length(); i++) {
            String[] glyph = GLYPHS.get(text.charAt(i));
            if (glyph == null) {
                continue;
            }
            for (int row = 0; row < glyphH; row++) {
                for (int col = 0; col < glyphW; col++) {
                    if (glyph[row].charAt(col) != '#') {
                        continue;
                    }
                    int x = originX + i * (glyphW + gap) + col;
                    int z = originZ + row;
                    if (this.floor.inBounds(x, z)) {
                        this.floor.set(x, z, fg);
                    }
                }
            }
        }
        this.floor.placeAll(arena);
    }

    // ---------- 供 ColorblindHyperEvents / ColorblindPowerUps 使用（同包） ----------

    ArenaWorld arena() {
        return this.match.arenaWorld();
    }

    Match match() {
        return this.match;
    }

    ColorblindFloor floor() {
        return this.floor;
    }

    ColorblindPowerUps powerUps() {
        return this.powerUps;
    }

    /** 本回合的正确答案色号（颜料蛋把落点染成这个颜色 = 临时安全地）。 */
    int answerColor() {
        return this.answerColor;
    }

    /** 从本回合上场的颜色里随机取一个（灾难事件重涂用）。 */
    int randomRoundColor() {
        return this.roundColors[this.random.nextInt(this.roundColors.length)];
    }

    /** 把世界坐标附近的一片地板重涂成指定颜色（灾难事件/加成用）。 */
    void repaintAround(double worldX, double worldZ, int radius, int color) {
        ArenaWorld arena = this.arena();
        if (arena == null) {
            return;
        }
        int[] local = new int[2];
        if (!this.floor.toLocal((int) Math.floor(worldX), (int) Math.floor(worldZ), local)) {
            return;
        }
        this.floor.repaint(arena, local[0], local[1], radius, color);
    }

    private void playToAlive(net.minecraft.sound.SoundEvent sound, float volume, float pitch) {
        for (ServerPlayerEntity player : this.match.onlineParticipants()) {
            if (!this.match.isEliminated(player.getUuid())) {
                player.playSoundToPlayer(sound, SoundCategory.PLAYERS, volume, pitch);
            }
        }
    }
}
