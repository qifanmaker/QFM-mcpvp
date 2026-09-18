package com.example.pvp.practice;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.text.Messages;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 单人练习会话：在一个独占的竞技场区域里做「从出发点抵达目标台」的计时挑战。
 *
 * <p>两种练习共用同一套流程，只是补给不同：搭路给 64 个方块，珍珠给 16 颗末影珍珠。
 * 失败（掉出场地）自动重置，成功记录用时并 2 秒后自动重开。
 *
 * <p><b>场地保护</b>：练习玩家不在任何 {@code Match} 里，所以必须注册为竞技场访客
 * （{@link ArenaWorldManager#addVisitor}），否则 {@code MatchManager.sweepArenaWorld}
 * 会在下一 tick 把玩家当"游离玩家"直接传回主城 —— 这是"进不去练习场地"的根因。
 */
public final class PracticeSession {
    /** 平台方块顶面的 Y（实体站在 Y+1）。 */
    private static final int GROUND_Y = ArenaTemplate.PLATFORM_Y;
    /** 出发点/目标台边长（5×5）。 */
    private static final int PAD_SIZE = 5;
    private static final int PAD_HALF = PAD_SIZE / 2;
    /** 出发点中心在区域内的局部坐标（避开区域边界）。 */
    private static final int LOCAL_START_X = 10;
    private static final int LOCAL_START_Z = 10;
    /** 掉到这个高度以下算失败（场地下方留 8 格缓冲）。 */
    private static final int FALL_Y = GROUND_Y - 8;
    /** 珍珠两次投掷之间的冷却（tick）。 */
    private static final int PEARL_COOLDOWN_TICKS = 40;
    /** 完成/失败后到自动重开的等待（tick）。 */
    private static final int RESET_DELAY_TICKS = 40;
    /** 计时中每多少 tick 刷一次动作栏计时。 */
    private static final int TIMER_HUD_INTERVAL = 5;
    /** 向 ArenaWorldManager 续期的间隔与时长（秒）。 */
    private static final long VISITOR_REFRESH_TICKS = 20L * 20L;
    private static final int VISITOR_SECONDS = 3600;

    private final UUID uuid;
    private final PracticeType type;
    private final int regionIndex;

    /** 出发点与目标台的地面中心（Y = GROUND_Y）。 */
    private final BlockPos startPad;
    private final BlockPos targetPad;
    /** 需要保护的场地方块（出发点 + 目标台），不允许被拆。 */
    private final Set<BlockPos> protectedBlocks = new HashSet<>();

    // ---- 计时 ----
    private boolean running;
    private boolean finished;
    private int runTicks;
    private int bestTicks = -1;
    private int lastHudTick = -1;

    // ---- 珍珠 ----
    private int pearlCount;
    private int pearlCooldown;

    // ---- 生命周期 ----
    private int resetTicks;
    private long lastVisitorRefresh = Long.MIN_VALUE;

    public PracticeSession(UUID uuid, PracticeType type, int regionIndex) {
        this.uuid = uuid;
        this.type = type;
        this.regionIndex = regionIndex;

        BlockPos origin = new BlockPos(regionIndex * ArenaTemplate.REGION_SPACING,
                ArenaTemplate.PLATFORM_Y, 0);
        this.startPad = origin.add(LOCAL_START_X, 0, LOCAL_START_Z);
        this.targetPad = this.startPad.add(type.getGap(), 0, 0);
        this.pearlCount = type == PracticeType.ENDER_PEARL ? type.getSupply() : 0;
    }

    // ---------- 场地 ----------

    /** 铺出发点与目标台。必须在传送玩家之前调用。 */
    public void buildArena() {
        ArenaWorld arena = arena();
        if (arena == null) {
            return;
        }

        protectedBlocks.clear();
        placePad(arena, startPad);
        placePad(arena, targetPad);
    }

    private void placePad(ArenaWorld arena, BlockPos center) {
        for (int dx = -PAD_HALF; dx <= PAD_HALF; dx++) {
            for (int dz = -PAD_HALF; dz <= PAD_HALF; dz++) {
                BlockPos pos = center.add(dx, 0, dz);
                arena.setBlockState(pos, Blocks.OBSIDIAN.getDefaultState(), 3);
                protectedBlocks.add(pos);
            }
        }
    }

    /** 清掉本练习区域内的方块与实体（范围按补给量收敛，不做全场扫描）。 */
    public void clearArena() {
        ArenaWorld arena = arena();
        if (arena == null) {
            return;
        }

        int minX = startPad.getX() - PAD_HALF - 6;
        int maxX = targetPad.getX() + PAD_HALF + 6;
        int minZ = Math.min(startPad.getZ(), targetPad.getZ()) - PAD_HALF - 16;
        int maxZ = Math.max(startPad.getZ(), targetPad.getZ()) + PAD_HALF + 16;
        int minY = FALL_Y - 4;
        int maxY = GROUND_Y + 96; // 只有 64 个方块，搭不了更高

        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    if (!arena.getBlockState(pos).isAir()) {
                        arena.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                    }
                }
            }
        }

        Box box = new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        for (Entity entity : arena.getEntitiesByClass(Entity.class, box,
                e -> !(e instanceof ServerPlayerEntity))) {
            entity.discard();
        }
    }

    // ---------- 流程 ----------

    /** 传送玩家入场并发放补给。 */
    public void start(ServerPlayerEntity player) {
        if (arena() == null) {
            player.sendMessage(Messages.error("竞技场未就绪，无法进入练习"), false);
            return;
        }

        registerVisitor(player, true);
        resetRun(player);

        player.sendMessage(Messages.gold("§6已进入 §e" + type.getDisplayName() + " §6练习"), false);
        player.sendMessage(Messages.info("§7从出发点铺到 §f" + targetPad.getX() + "§7 格外的目标台，全程计时"), false);
        player.sendMessage(Messages.info("§7手持 §c退出练习 §7右键即可离开"), false);
    }

    /** 每 tick 调用：续访客期、跑计时、检测掉落、处理自动重开。 */
    public void tick() {
        if (arena() == null) {
            return;
        }
        ServerPlayerEntity player = getPlayer();
        if (player == null) {
            return;
        }

        registerVisitor(player, false);

        if (pearlCooldown > 0) {
            pearlCooldown--;
        }

        // 掉出场地 → 判定失败并重开
        if (resetTicks <= 0 && player.getY() < FALL_Y) {
            fail(player, "§c掉下去了！");
            return;
        }

        // 到点自动重开
        if (resetTicks > 0) {
            resetTicks--;
            if (resetTicks == 0) {
                resetRun(player);
            }
            return;
        }

        updateRunState(player);
    }

    private void updateRunState(ServerPlayerEntity player) {
        if (finished) {
            return;
        }
        if (!running && !onPad(player, startPad)) {
            startRun(player);
        }
        if (running && onPad(player, targetPad)) {
            completeRun(player);
        }
        if (running) {
            runTicks++;
        }
    }

    /** 开始计时（离开出发点，或第一次投出珍珠）。 */
    private void startRun(ServerPlayerEntity player) {
        if (running || finished) {
            return;
        }
        running = true;
        runTicks = 0;
        lastHudTick = -1;
        player.sendMessage(Messages.info("§a计时开始！"), true);
    }

    private void completeRun(ServerPlayerEntity player) {
        finished = true;
        running = false;

        double seconds = runTicks / 20.0;
        boolean newBest = bestTicks < 0 || runTicks < bestTicks;
        if (newBest) {
            bestTicks = runTicks;
        }

        double bestSeconds = bestTicks / 20.0;
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 40, 10));
        player.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal(newBest ? "§a§l新纪录！" : "§e§l抵达目标")));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(
                Text.literal(String.format("§f用时 §e%.2f§f 秒  §7| 最佳 §a%.2f§7 秒", seconds, bestSeconds))));
        player.sendMessage(Messages.gold(String.format(
                "§6本轮用时 §e%.2f §6秒，最佳 §a%.2f §6秒", seconds, bestSeconds)), false);

        ServerWorld world = arena();
        if (world != null) {
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP,
                    SoundCategory.PLAYERS, 1.0f, newBest ? 1.2f : 1.0f);
        }
        scheduleReset();
    }

    private void fail(ServerPlayerEntity player, String reason) {
        running = false;
        player.sendMessage(Messages.warn(reason + " §7正在重置…"), true);
        scheduleReset();
    }

    private void scheduleReset() {
        if (resetTicks <= 0) {
            resetTicks = RESET_DELAY_TICKS;
        }
    }

    /** 重置一轮：传回出发点、清状态、发补给、清零计时。 */
    private void resetRun(ServerPlayerEntity player) {
        ArenaWorld arena = arena();
        if (arena == null) {
            return;
        }

        running = false;
        finished = false;
        runTicks = 0;
        lastHudTick = -1;
        resetTicks = 0;
        pearlCooldown = 0;
        pearlCount = type == PracticeType.ENDER_PEARL ? type.getSupply() : 0;

        // 先落到出发点正上方再补状态，避免在下落途中被打断
        player.teleport(arena, startPad.getX() + 0.5, GROUND_Y + 1.0, startPad.getZ() + 0.5, -90f, 0f);
        player.stopRiding();
        player.setVelocity(0, 0, 0);
        player.velocityDirty = true;
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.fallDistance = 0;
        player.clearStatusEffects();
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5f);
        player.changeGameMode(type == PracticeType.BRIDGE ? GameMode.SURVIVAL : GameMode.ADVENTURE);

        giveLoadout(player);
        player.currentScreenHandler.sendContentUpdates();
    }

    private void giveLoadout(ServerPlayerEntity player) {
        player.getInventory().clear();
        player.getInventory().setStack(0, supplyStack());
        player.getInventory().setStack(8, PracticeManager.createExitItem());
        player.getInventory().selectedSlot = 0;
    }

    private ItemStack supplyStack() {
        return type == PracticeType.BRIDGE
                ? new ItemStack(Items.WHITE_WOOL, type.getSupply())
                : new ItemStack(Items.ENDER_PEARL, type.getSupply());
    }

    // ---------- 珍珠 ----------

    /** 玩家是否还能投珍珠（不可投时给出提示）。 */
    public boolean canThrowPearl(ServerPlayerEntity player) {
        if (type != PracticeType.ENDER_PEARL) {
            return false;
        }
        if (pearlCount <= 0) {
            player.sendMessage(Messages.warn("珍珠已用完，正在重置…"), true);
            fail(player, "§c珍珠用完了！");
            return false;
        }
        if (pearlCooldown > 0) {
            player.sendMessage(Messages.warn(String.format("§e冷却中… %.1f 秒",
                    pearlCooldown / 20.0)), true);
            return false;
        }
        return true;
    }

    /** 记一次成功投掷：扣珍珠、起冷却、首次投掷开始计时。 */
    public void onPearlThrown(ServerPlayerEntity player) {
        if (type != PracticeType.ENDER_PEARL) {
            return;
        }
        pearlCount--;
        pearlCooldown = PEARL_COOLDOWN_TICKS;
        startRun(player);
        player.sendMessage(Messages.info("§7珍珠剩余 §f" + pearlCount), true);
    }

    // ---------- 场地保护 ----------

    /** 该方块是否属于练习场地（不可破坏）。 */
    public boolean isProtected(BlockPos pos) {
        return protectedBlocks.contains(pos);
    }

    // ---------- 退出 ----------

    /** 退出练习：清场、释放区域、送回主城。 */
    public void exit(ServerPlayerEntity player) {
        clearArena();
        PracticeManager.get().releaseRegion(regionIndex);

        ArenaWorldManager manager = ArenaWorldManager.getOrNull();
        if (manager != null) {
            manager.removeVisitor(uuid);
        }

        MinecraftServer server = PvPMod.SERVER;
        if (server != null && player != null) {
            ServerWorld overworld = server.getOverworld();
            BlockPos spawn = overworld.getSpawnPos();
            player.teleport(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    overworld.getSpawnAngle(), 0f);
            player.changeGameMode(GameMode.ADVENTURE);
            player.setHealth(player.getMaxHealth());
            player.setFireTicks(0);
            player.fallDistance = 0;
            player.clearStatusEffects();
            player.getHungerManager().setFoodLevel(20);
            player.getInventory().clear();
            player.currentScreenHandler.sendContentUpdates();
            player.sendMessage(Messages.info("§6已退出练习模式"), false);
        }
    }

    // ---------- 辅助 ----------

    /**
     * 续期/注册竞技场访客资格。没有这一步，{@code sweepArenaWorld} 会把玩家传回主城。
     *
     * @param force 立刻注册（入场时用），否则按间隔节流续期
     */
    private void registerVisitor(ServerPlayerEntity player, boolean force) {
        ArenaWorldManager manager = ArenaWorldManager.getOrNull();
        if (manager == null) {
            return;
        }
        MinecraftServer server = PvPMod.SERVER;
        long now = server == null ? 0L : server.getTicks();
        if (!force && now - lastVisitorRefresh < VISITOR_REFRESH_TICKS) {
            return;
        }
        lastVisitorRefresh = now;
        manager.addVisitor(player, VISITOR_SECONDS);
    }

    /** 玩家是否站在某个台面上。 */
    private boolean onPad(ServerPlayerEntity player, BlockPos padCenter) {
        double dx = player.getX() - (padCenter.getX() + 0.5);
        double dz = player.getZ() - (padCenter.getZ() + 0.5);
        if (Math.abs(dx) > PAD_HALF + 0.5 || Math.abs(dz) > PAD_HALF + 0.5) {
            return false;
        }
        return Math.abs(player.getY() - (GROUND_Y + 1.0)) < 2.0;
    }

    /** 供 Manager 每 tick 调用的计时 HUD（动作栏实时显示用时）。 */
    public void tickTimerHud(long serverTicks) {
        if (!running || finished) {
            return;
        }
        if (serverTicks - lastHudTick < TIMER_HUD_INTERVAL) {
            return;
        }
        ServerPlayerEntity player = getPlayer();
        if (player == null) {
            return;
        }
        lastHudTick = (int) serverTicks;
        player.sendMessage(Text.literal(String.format("§e%.2f §7秒", runTicks / 20.0)), true);
    }

    private ServerPlayerEntity getPlayer() {
        MinecraftServer server = PvPMod.SERVER;
        if (server == null) {
            return null;
        }
        return server.getPlayerManager().getPlayer(uuid);
    }

    private ArenaWorld arena() {
        ArenaWorldManager manager = ArenaWorldManager.getOrNull();
        return manager == null ? null : manager.getWorld();
    }

    // ---------- Getters ----------

    public UUID getUuid() {
        return uuid;
    }

    public PracticeType getType() {
        return type;
    }

    public int getRegionIndex() {
        return regionIndex;
    }

    public int getBestTicks() {
        return bestTicks;
    }

    public int getPearlCount() {
        return pearlCount;
    }
}
