package com.example.pvp.practice;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.kit.HotbarSync;
import com.example.pvp.kit.Kit;
import com.example.pvp.kit.KitApplicator;
import com.example.pvp.match.MatchType;
import com.example.pvp.text.Messages;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;
import net.minecraft.text.Text;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/**
 * 人机对练需要完整的决斗生命周期：原版死亡会弹死亡界面，且把机器人当访客死亡处理。
 * 因此在练习会话内统一处理倒计时、致死结算和重开，场地/出生点/套件沿用正式 1v1。
 * 不创建 Match，不写入真人战绩或占用正式比赛名额。
 */
public final class PvpPracticeDuel {
    private enum Phase { COUNTDOWN, ACTIVE, INTERMISSION, CLOSED }
    private final UUID owner;
    private final int region;
    private final BotDifficulty difficulty;
    private final Kit kit;
    private final ArenaTemplate template;
    private final List<BlockPos> spawns;
    private PracticeBot bot;
    private Phase phase = Phase.CLOSED;
    private int phaseTicks;
    private int elapsed;
    private int wins;
    private int losses;
    private int round;
    private UUID roundId = UUID.randomUUID();

    PvpPracticeDuel(UUID owner, int region, BotDifficulty difficulty, Kit kit) {
        this.owner = owner;
        this.region = region;
        this.difficulty = java.util.Objects.requireNonNull(difficulty);
        this.kit = java.util.Objects.requireNonNull(kit);
        this.template = PvPMod.MATCH.createTemplate(MatchType.DUEL_1V1);
        this.spawns = template.computeSpawns(region, 2);
    }

    private ArenaWorld world() {
        return ArenaWorldManager.getOrNull().getWorld();
    }

    void buildArena() {
        ArenaWorldManager.getOrNull().buildArena(region, template, 0, 2, MatchType.DUEL_1V1, List.of());
    }

    void start(ServerPlayerEntity player) {
        player.sendMessage(Messages.gold("已进入 PvP 人机对练 · " + difficulty.getDisplayName()
                + " · " + kit.getDisplayName()), false);
        player.sendMessage(Messages.info("双方同装备、原版伤害，胜负后自动重开，不计战绩。用 /pvp practice leave 或 /hub 退出。"), false);
        resetRound(player);
    }

    private void resetRound(ServerPlayerEntity player) {
        clearRoundEntities();
        removeBot();
        roundId = UUID.randomUUID();
        // 水/岩浆是铁套套件的正常工具；每轮清掉流体再重铺，防止上轮地形影响公平开局。
        clearBlocks();
        buildArena();
        BlockPos spawn = spawns.getFirst();
        player.stopRiding();
        player.stopUsingItem();
        player.teleport(world(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, -90, 0);
        KitApplicator.apply(player, kit);
        player.changeGameMode(GameMode.ADVENTURE);
        player.setVelocity(0, 0, 0);
        player.velocityModified = true;
        player.setInvisible(false);
        player.setNoGravity(false);
        player.getAbilities().flying = false;
        player.getAbilities().allowFlying = false;
        player.sendAbilitiesUpdate();
        // PvP 使用完整套件物品栏；玩家通过命令退出，不放置占用战斗槽位的退出工具。
        player.currentScreenHandler.sendContentUpdates();
        HotbarSync.sendSelection(player);
        bot = new PracticeBot(PvPMod.SERVER, world(), player, difficulty, kit);
        bot.spawn(spawns.get(1), 90);
        ArenaWorldManager.getOrNull().addVisitor(bot.getEntity(), 3600);
        phase = Phase.COUNTDOWN;
        // 新生成的 ServerPlayer 有原版 60 tick 入场保护，倒计时至少覆盖它。
        phaseTicks = Math.max(60, PvPConfig.INSTANCE.countdownSeconds * 20);
        elapsed = 0;
        round++;
    }

    void tick(ServerPlayerEntity player) {
        if (phase == Phase.CLOSED) return;
        if (bot == null || bot.getEntity().isRemoved()) {
            finishRound(player, null, "人机异常离场，本轮重开");
        }
        if (phase == Phase.COUNTDOWN) {
            holdAtSpawn(player, spawns.getFirst(), -90);
            if (phaseTicks % 20 == 0) title(player, "§e" + phaseTicks / 20, "§7第 " + round + " 回合 · " + difficulty.getDisplayName());
            bot.tick(false);
            if (--phaseTicks <= 0) {
                phase = Phase.ACTIVE;
                title(player, "§a开始！", "§f" + kit.getDisplayName());
            }
        } else if (phase == Phase.INTERMISSION) {
            if (bot != null) bot.tick(false);
            if (--phaseTicks <= 0) resetRound(player);
        } else if (phase == Phase.ACTIVE) {
            if (!inside(player)) finishRound(player, false, "离开场地");
            else if (!inside(bot.getEntity())) finishRound(player, true, "人机离开场地");
            else if (++elapsed >= Math.max(20, PvPConfig.INSTANCE.matchTimeoutSeconds * 20)) {
                finishRound(player, null, "本轮超时");
            } else {
                bot.tick(true);
                if (elapsed % 400 == 0) ArenaWorldManager.getOrNull().addVisitor(bot.getEntity(), 3600);
            }
        }
        if (PvPMod.SERVER.getTicks() % 5 == 0) {
            String state = switch (phase) {
                case COUNTDOWN -> "§e准备中";
                case INTERMISSION -> "§e即将重开";
                case ACTIVE -> "§c人机 " + String.format(java.util.Locale.ROOT, "%.1f", bot.getEntity().getHealth())
                        + "❤ §7· " + bot.getStateLabel();
                case CLOSED -> "";
            };
            player.sendMessage(Text.literal("§6" + difficulty.getDisplayName() + " §7| §a" + wins
                    + " 胜 §c" + losses + " 负 §7| " + state), true);
        }
    }

    private void holdAtSpawn(ServerPlayerEntity player, BlockPos spawn, float yaw) {
        if (player.squaredDistanceTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5) > 0.04) {
            player.teleport(world(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, yaw, 0);
        }
        player.setVelocity(0, 0, 0);
        player.fallDistance = 0;
    }

    private boolean inside(ServerPlayerEntity entity) {
        BlockPos origin = template.getRegionOrigin(region);
        return entity.getWorld() == world() && entity.getY() >= ArenaTemplate.PLATFORM_Y - 8
                && entity.getY() <= ArenaTemplate.PLATFORM_Y + 32
                && entity.getX() >= origin.getX() && entity.getX() < origin.getX() + template.getSize()
                && entity.getZ() >= origin.getZ() && entity.getZ() < origin.getZ() + template.getSize();
    }

    /** ALLOW_DEATH 在原版死亡前调用；先封锁伤害再回血，避免同 tick 重复计分。 */
    public void onDeath(ServerPlayerEntity victim) {
        ServerPlayerEntity player = PvPMod.SERVER.getPlayerManager().getPlayer(owner);
        if (player != null) finishRound(player, !owner.equals(victim.getUuid()), "生命耗尽");
        victim.setHealth(victim.getMaxHealth());
        victim.setFireTicks(0);
        victim.fallDistance = 0;
    }

    private void finishRound(ServerPlayerEntity player, Boolean won, String reason) {
        if (phase != Phase.ACTIVE && phase != Phase.COUNTDOWN) return;
        phase = Phase.INTERMISSION;
        phaseTicks = 60;
        if (won != null) {
            if (won) wins++;
            else losses++;
        }
        player.stopUsingItem();
        player.setFireTicks(0);
        player.clearStatusEffects();
        player.setHealth(player.getMaxHealth());
        holdAtSpawn(player, spawns.getFirst(), -90);
        if (bot != null) bot.getEntity().stopUsingItem();
        title(player, won == null ? "§e本轮结束" : won ? "§a胜利！" : "§c落败", "§7" + reason + " · 3 秒后重开");
        player.sendMessage(Messages.info("第 " + round + " 回合：" + (won == null ? "平局" : won ? "胜利" : "落败")
                + "，" + wins + " 胜 " + losses + " 负；3 秒后重开"), false);
    }

    public boolean isActive() { return phase == Phase.ACTIVE; }
    public boolean contains(UUID uuid) { return owner.equals(uuid) || bot != null && bot.getEntity().getUuid().equals(uuid); }
    public ServerPlayerEntity getBotEntity() { return bot == null ? null : bot.getEntity(); }
    public BotDifficulty getDifficulty() { return difficulty; }
    public Kit getKit() { return kit; }
    public String projectileRoundTag() { return PracticeManager.PROJECTILE_ROUND_PREFIX + roundId; }

    private void removeBot() {
        if (bot == null) return;
        ArenaWorldManager.getOrNull().removeVisitor(bot.getEntity().getUuid());
        bot.discard();
        bot = null;
    }

    private Box bounds() {
        BlockPos origin = template.getRegionOrigin(region);
        return new Box(origin.getX() - 8, world().getBottomY(), -8,
                origin.getX() + template.getSize() + 8, world().getTopY(), template.getSize() + 8);
    }

    private void clearRoundEntities() {
        Box bounds = bounds();
        List<Entity> retired = new ArrayList<>();
        for (Entity entity : world().iterateEntities()) {
            // 抛射物可能飞出场地；同时按归属回收，不能让上轮箭在下轮或邻场命中。
            boolean owned = entity instanceof net.minecraft.entity.projectile.ProjectileEntity projectile
                    && projectile.getOwner() != null && contains(projectile.getOwner().getUuid());
            if (!(entity instanceof ServerPlayerEntity) && (owned || bounds.contains(entity.getPos()))) retired.add(entity);
        }
        // discard 会同步移除世界实体索引；先收集再删除，避免遍历途中漏掉上轮抛射物。
        retired.forEach(Entity::discard);
    }

    private void clearBlocks() {
        BlockPos origin = template.getRegionOrigin(region);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = -8; x < template.getSize() + 8; x++) {
            for (int z = -8; z < template.getSize() + 8; z++) {
                // 桶中流体可沿围墙外侧流到世界底部，清到最低层才能完全回收本轮场地。
                for (int y = world().getBottomY(); y <= ArenaTemplate.PLATFORM_Y + 32; y++) {
                    pos.set(origin.getX() + x, y, origin.getZ() + z);
                    if (!world().getBlockState(pos).isAir()) world().setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                }
            }
        }
    }

    void clearArena() {
        phase = Phase.CLOSED;
        clearRoundEntities();
        removeBot();
        clearBlocks();
    }

    private static void title(ServerPlayerEntity player, String title, String subtitle) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(2, 30, 5));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(subtitle)));
        player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title)));
    }
}
