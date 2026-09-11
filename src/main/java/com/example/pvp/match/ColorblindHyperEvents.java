package com.example.pvp.match;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.text.Messages;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 色盲派对的 Hyper 模式灾难事件（对齐 Hypixel Pixel Party 的 5 种）。
 *
 * <p>每回合最多触发一个，只在 {@link ColorblindPartySession#phase() COUNTDOWN/GAP} 期间运行，
 * 回合结束由 {@link #cleanup()} 统一收尾（实体 discard + 地板层以上方块抹掉）。
 */
public final class ColorblindHyperEvents {

    public enum Kind {
        ANVIL_RAIN("铁砧雨"),
        BLIZZARD("暴雪"),
        COLOR_STAMPEDE("色块冲撞"),
        ROLLING_COLORS("滚动色块"),
        TNT_RAIN("TNT 雨");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return this.displayName;
        }
    }

    /** 铁砧雨：每隔多少 tick 落一颗。 */
    private static final int ANVIL_INTERVAL = 8;
    /** TNT 雨：每隔多少 tick 射一颗。 */
    private static final int TNT_INTERVAL = 12;
    /** 滚动色块：每隔多少 tick 滚一格。 */
    private static final int ROLL_INTERVAL = 8;
    /** 色块冲撞：彩牛几 tick 后爆炸。 */
    private static final int COW_FUSE = 60;
    /** 暴雪覆盖比例。 */
    private static final double BLIZZARD_COVERAGE = 0.25;

    private final ColorblindPartySession session;
    private final Random random;

    private Kind kind;
    private int ticks;
    private int nextAction;

    private final List<FallingBlockEntity> falling = new ArrayList<>();
    private final List<CowEntity> cows = new ArrayList<>();
    private final List<Entity> spawned = new ArrayList<>();

    public ColorblindHyperEvents(ColorblindPartySession session, Random random) {
        this.session = session;
        this.random = random;
    }

    public Kind current() {
        return this.kind;
    }

    public boolean active() {
        return this.kind != null;
    }

    /** 随机抽一个事件并立即触发。 */
    public void beginRandom() {
        Kind[] all = Kind.values();
        this.begin(all[this.random.nextInt(all.length)]);
    }

    public void begin(Kind kind) {
        this.cleanup();
        this.kind = kind;
        this.ticks = 0;
        this.nextAction = 0;
        ArenaWorld arena = this.session.arena();
        if (arena == null) {
            this.kind = null;
            return;
        }
        if (kind == Kind.BLIZZARD) {
            this.startBlizzard(arena);
        } else if (kind == Kind.COLOR_STAMPEDE) {
            this.startStampede(arena);
        }
        this.session.match().broadcastToMatch(
                Messages.warn("§c⚠ 灾难事件：§e" + kind.displayName() + "§r！"));
    }

    public void tick() {
        if (this.kind == null) {
            return;
        }
        ArenaWorld arena = this.session.arena();
        if (arena == null) {
            return;
        }
        this.ticks++;
        switch (this.kind) {
            case ANVIL_RAIN -> this.tickAnvilRain(arena);
            case TNT_RAIN -> this.tickTntRain(arena);
            case ROLLING_COLORS -> this.tickRollingColors(arena);
            case COLOR_STAMPEDE -> this.tickStampede(arena);
            case BLIZZARD -> {
                // 一次性铺雪，无需逐 tick 处理
            }
        }
        this.tickFalling(arena);
    }

    /** 回合结束：清掉本回合事件留下的实体。方块残留由 {@code ColorblindMapGenerator.clearAboveFloor} 处理。 */
    public void cleanup() {
        for (Entity entity : this.spawned) {
            if (!entity.isRemoved()) {
                entity.discard();
            }
        }
        this.spawned.clear();
        this.falling.clear();
        this.cows.clear();
        this.kind = null;
    }

    // ---------- 铁砧雨 ----------

    private void tickAnvilRain(ArenaWorld arena) {
        if (--this.nextAction > 0) {
            return;
        }
        this.nextAction = ANVIL_INTERVAL;
        var center = this.session.floor().center();
        int dx = this.random.nextInt(this.session.floor().size());
        int dz = this.random.nextInt(this.session.floor().size());
        BlockPos drop = new BlockPos(this.session.floor().origin().getX() + dx,
                ArenaTemplate.PLATFORM_Y + 26,
                this.session.floor().origin().getZ() + dz);
        FallingBlockEntity fb = FallingBlockEntity.spawnFromBlock(arena, drop, Blocks.ANVIL.getDefaultState());
        fb.setHurtEntities(0.0f, 0); // 落地伤害由本类自己结算，避免和原版重复
        this.falling.add(fb);
        this.spawned.add(fb);
    }

    // ---------- TNT 雨 ----------

    private void tickTntRain(ArenaWorld arena) {
        if (--this.nextAction > 0) {
            return;
        }
        this.nextAction = TNT_INTERVAL;
        var center = this.session.floor().center();
        BlockPos from = new BlockPos(center.getX(), ArenaTemplate.PLATFORM_Y + 8, center.getZ());
        FallingBlockEntity fb = FallingBlockEntity.spawnFromBlock(arena, from, Blocks.TNT.getDefaultState());
        // 从中心朝外抛，落点散布到整片地板
        double angle = this.random.nextDouble() * Math.PI * 2;
        double speed = 0.35 + this.random.nextDouble() * 0.35;
        fb.setVelocity(Math.cos(angle) * speed, 0.15, Math.sin(angle) * speed);
        fb.velocityDirty = true;
        this.falling.add(fb);
        this.spawned.add(fb);
    }

    // ---------- 滚动色块 ----------

    private void tickRollingColors(ArenaWorld arena) {
        if (--this.nextAction > 0) {
            return;
        }
        this.nextAction = ROLL_INTERVAL;
        this.session.floor().rotateStrips(arena, 4);
    }

    // ---------- 色块冲撞 ----------

    private void startStampede(ArenaWorld arena) {
        var center = this.session.floor().center();
        for (int i = 0; i < 6; i++) {
            CowEntity cow = new CowEntity(EntityType.COW, arena);
            double angle = i * Math.PI * 2 / 6;
            cow.refreshPositionAndAngles(center.getX() + 0.5, ArenaTemplate.PLATFORM_Y + 1,
                    center.getZ() + 0.5, (float) Math.toDegrees(angle), 0);
            cow.setVelocity(Math.cos(angle) * 0.4, 0.2, Math.sin(angle) * 0.4);
            cow.velocityDirty = true;
            cow.setInvulnerable(true);
            cow.setSilent(true);
            arena.spawnEntity(cow);
            this.cows.add(cow);
            this.spawned.add(cow);
        }
    }

    private void tickStampede(ArenaWorld arena) {
        if (this.ticks < COW_FUSE) {
            return;
        }
        for (CowEntity cow : this.cows) {
            if (cow.isRemoved()) {
                continue;
            }
            this.session.repaintAround(cow.getX(), cow.getZ(), 3, this.session.randomRoundColor());
            arena.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, cow.getX(), cow.getY() + 0.5, cow.getZ(),
                    1, 0, 0, 0, 0);
            cow.discard();
        }
        this.cows.clear();
    }

    // ---------- 暴雪 ----------

    private void startBlizzard(ArenaWorld arena) {
        int size = this.session.floor().size();
        BlockPos origin = this.session.floor().origin();
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                if (this.random.nextDouble() >= BLIZZARD_COVERAGE) {
                    continue;
                }
                BlockPos pos = origin.add(dx, 1, dz);
                if (arena.getBlockState(pos).isAir()) {
                    arena.setBlockState(pos, Blocks.SNOW.getDefaultState(), 3);
                }
            }
        }
    }

    // ---------- 落地结算 ----------

    private void tickFalling(ArenaWorld arena) {
        for (int i = this.falling.size() - 1; i >= 0; i--) {
            FallingBlockEntity fb = this.falling.get(i);
            if (fb.isRemoved()) {
                this.falling.remove(i);
                continue;
            }
            if (fb.getY() > ArenaTemplate.PLATFORM_Y + 1.2) {
                continue;
            }
            this.falling.remove(i);
            if (this.kind == Kind.TNT_RAIN) {
                this.session.repaintAround(fb.getX(), fb.getZ(), 2, this.session.randomRoundColor());
                arena.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, fb.getX(), fb.getY(), fb.getZ(), 1, 0, 0, 0, 0);
                this.playAt(arena, fb.getX(), fb.getY(), fb.getZ(),
                        SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.0F, 1.2F);
            } else {
                this.crushNearby(arena, fb);
                arena.spawnParticles(ParticleTypes.CRIT, fb.getX(), fb.getY(), fb.getZ(), 12, 0.4, 0.2, 0.4, 0.1);
                this.playAt(arena, fb.getX(), fb.getY(), fb.getZ(), SoundEvents.BLOCK_ANVIL_LAND, 1.0F, 1.0F);
            }
            fb.discard();
        }
    }

    /** 铁砧砸中站在落点附近的玩家（本模式无护甲，满血 20 点即死）。 */
    private void crushNearby(ArenaWorld arena, FallingBlockEntity fb) {
        for (ServerPlayerEntity player : this.session.match().onlineParticipants()) {
            if (this.session.match().isEliminated(player.getUuid()) || player.getWorld() != arena) {
                continue;
            }
            double dx = player.getX() - fb.getX();
            double dz = player.getZ() - fb.getZ();
            if (dx * dx + dz * dz <= 1.5 * 1.5 && Math.abs(player.getY() - fb.getY()) < 3) {
                player.damage(player.getDamageSources().fallingAnvil(fb), 20.0F);
            }
        }
    }

    private void playAt(ArenaWorld arena, double x, double y, double z,
                        net.minecraft.sound.SoundEvent sound, float volume, float pitch) {
        for (ServerPlayerEntity player : this.session.match().onlineParticipants()) {
            if (player.getWorld() == arena) {
                player.playSoundToPlayer(sound, SoundCategory.PLAYERS, volume, pitch);
            }
        }
    }
}
