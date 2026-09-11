package com.example.pvp.match;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.colorblind.ColorblindFloor;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.text.Messages;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.thrown.EggEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 色盲派对的加成信标与 14 种加成（对齐 Hypixel Pixel Party 的 power-up 列表）。
 *
 * <p>每回合开始时有概率在地板上刷几个信标方块，左键打碎随机获得一种加成 —— 有正有负。
 * 部分加成持续整个回合（酸雨/彩色足印/魔毯/饥饿清零/南瓜头），回合结束统一由 {@link #cleanup()} 收尾。
 */
public final class ColorblindPowerUps {

    public enum Kind {
        ACID_RAIN("酸雨", false),
        COLOR_COW("彩虹牛", true),
        COLOR_TRAIL("彩色足印", true),
        CROWD("人群", false),
        CURSED_PUMPKIN("诅咒南瓜", false),
        ENDER_PEARL("末影珍珠", true),
        JUMP_BOOST("跳跃提升", true),
        LEAP_FEATHER("飞跃羽毛", true),
        MAGIC_CARPET("魔毯", true),
        NO_HUNGER("饥饿清零", false),
        PAINT_EGG("颜料蛋", true),
        GLASS_BOXES("随机玻璃罩", false),
        RANDOM_TELEPORT("随机传送", true),
        SPEED_BOOST("速度提升", true);

        private final String displayName;
        private final boolean positive;

        Kind(String displayName, boolean positive) {
            this.displayName = displayName;
            this.positive = positive;
        }

        public String displayName() {
            return this.displayName;
        }

        public boolean positive() {
            return this.positive;
        }
    }

    /** 持续整回合的加成。 */
    private record Active(Kind kind, UUID owner, int ticks) {
    }

    private final ColorblindPartySession session;
    private final Random random;
    private final List<Active> actives = new ArrayList<>();
    private final List<Entity> spawned = new ArrayList<>();
    private final List<EggEntity> eggs = new ArrayList<>();
    private final Set<UUID> pumpkinWearers = new HashSet<>();
    private final Set<Integer> trackedEggIds = new HashSet<>();
    /** 本回合有没有人拿到过颜料蛋（没有就不必每 tick 扫蛋）。 */
    private boolean paintEggActive;

    private CowEntity colorCow;
    private int colorCowFuse;
    private int acidTicks;

    public ColorblindPowerUps(ColorblindPartySession session, Random random) {
        this.session = session;
        this.random = random;
    }

    // ---------- 信标 ----------

    /** 回合开始时按概率刷信标（左键打碎获得随机加成）。 */
    public void spawnBeacons(ArenaWorld arena) {
        if (this.random.nextInt(100) >= PvPConfig.INSTANCE.colorblindBeaconChance) {
            return;
        }
        ColorblindFloor floor = this.session.floor();
        int count = Math.max(1, PvPConfig.INSTANCE.colorblindBeaconCount);
        for (int i = 0; i < count; i++) {
            int dx = this.random.nextInt(floor.size());
            int dz = this.random.nextInt(floor.size());
            BlockPos pos = floor.origin().add(dx, 1, dz);
            if (arena.getBlockState(pos).isAir()) {
                arena.setBlockState(pos, Blocks.BEACON.getDefaultState(), 3);
            }
        }
    }

    /** 打碎信标：发一个随机加成。 */
    public void grantRandom(ServerPlayerEntity player) {
        Kind[] all = Kind.values();
        this.grant(player, all[this.random.nextInt(all.length)]);
    }

    public void grant(ServerPlayerEntity player, Kind kind) {
        ArenaWorld arena = this.session.arena();
        if (arena == null) {
            return;
        }
        switch (kind) {
            case ACID_RAIN -> {
                this.actives.add(new Active(kind, player.getUuid(), 0));
                this.acidTicks = 0;
            }
            case COLOR_TRAIL -> this.actives.add(new Active(kind, player.getUuid(), 0));
            case MAGIC_CARPET -> this.actives.add(new Active(kind, player.getUuid(), 0));
            case NO_HUNGER -> this.actives.add(new Active(kind, player.getUuid(), 0));
            case COLOR_COW -> this.spawnColorCow(arena, player);
            case CROWD -> this.spawnCrowd(arena, player);
            case CURSED_PUMPKIN -> {
                player.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
                this.pumpkinWearers.add(player.getUuid());
            }
            case ENDER_PEARL -> this.giveItem(player, new ItemStack(Items.ENDER_PEARL, 1));
            case PAINT_EGG -> {
                this.giveItem(player, new ItemStack(Items.EGG, 3));
                this.paintEggActive = true;
            }
            case JUMP_BOOST -> player.addStatusEffect(
                    new StatusEffectInstance(StatusEffects.JUMP_BOOST, 600, 1, false, false, true));
            case SPEED_BOOST -> player.addStatusEffect(
                    new StatusEffectInstance(StatusEffects.SPEED, 600, 1, false, false, true));
            case LEAP_FEATHER -> {
                Vec3d look = player.getRotationVec(1.0F);
                player.setVelocity(look.x * 1.4, 0.9, look.z * 1.4);
                player.velocityDirty = true;
            }
            case GLASS_BOXES -> this.placeGlassBox(arena);
            case RANDOM_TELEPORT -> this.teleportRandom(arena, player);
        }
        this.session.match().broadcastToMatch(Messages.info("§e" + player.getGameProfile().getName()
                + "§r 打碎了信标 —— " + (kind.positive() ? "§a" : "§c") + kind.displayName() + "§r！"));
    }

    // ---------- 逐 tick ----------

    public void tick() {
        ArenaWorld arena = this.session.arena();
        if (arena == null) {
            return;
        }
        if (this.paintEggActive) {
            this.trackEggs(arena);
        }
        if (this.colorCow != null) {
            this.tickColorCow(arena);
        }
        for (Active active : this.actives) {
            ServerPlayerEntity owner = this.online(active.owner());
            switch (active.kind()) {
                case ACID_RAIN -> {
                    if (++this.acidTicks % 10 == 0) {
                        this.randomRepaint(arena, 2);
                    }
                }
                case COLOR_TRAIL -> {
                    if (owner != null) {
                        this.session.repaintAround(owner.getX(), owner.getZ(), 0, this.session.randomRoundColor());
                    }
                }
                case MAGIC_CARPET -> {
                    if (owner != null) {
                        this.placeCarpet(arena, owner);
                    }
                }
                case NO_HUNGER -> {
                    if (owner != null) {
                        owner.getHungerManager().setFoodLevel(0);
                        owner.getHungerManager().setSaturationLevel(0f);
                    }
                }
                default -> {
                }
            }
        }
    }

    /** 回合结束：撤掉持续效果、清掉召唤物。 */
    public void cleanup() {
        for (Entity entity : this.spawned) {
            if (!entity.isRemoved()) {
                entity.discard();
            }
        }
        this.spawned.clear();
        this.eggs.clear();
        this.trackedEggIds.clear();
        this.colorCow = null;
        this.colorCowFuse = 0;
        this.paintEggActive = false;
        this.actives.clear();
        for (UUID uuid : this.pumpkinWearers) {
            ServerPlayerEntity player = this.online(uuid);
            if (player != null) {
                player.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
            }
        }
        this.pumpkinWearers.clear();
    }

    // ---------- 各种加成的实现 ----------

    private void spawnColorCow(ArenaWorld arena, ServerPlayerEntity player) {
        CowEntity cow = new CowEntity(EntityType.COW, arena);
        cow.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), 0, 0);
        cow.setInvulnerable(true);
        cow.setSilent(true);
        arena.spawnEntity(cow);
        this.spawned.add(cow);
        this.colorCow = cow;
        this.colorCowFuse = 60;
    }

    private void tickColorCow(ArenaWorld arena) {
        if (this.colorCow.isRemoved() || --this.colorCowFuse <= 0) {
            if (!this.colorCow.isRemoved()) {
                this.session.repaintAround(this.colorCow.getX(), this.colorCow.getZ(), 3,
                        this.session.randomRoundColor());
                arena.spawnParticles(ParticleTypes.EXPLOSION_EMITTER,
                        this.colorCow.getX(), this.colorCow.getY() + 0.5, this.colorCow.getZ(), 1, 0, 0, 0, 0);
                this.colorCow.discard();
            }
            this.colorCow = null;
        }
    }

    private void spawnCrowd(ArenaWorld arena, ServerPlayerEntity player) {
        for (int i = 0; i < 8; i++) {
            VillagerEntity villager = new VillagerEntity(EntityType.VILLAGER, arena);
            double angle = i * Math.PI * 2 / 8;
            villager.refreshPositionAndAngles(player.getX() + Math.cos(angle) * 2,
                    player.getY(), player.getZ() + Math.sin(angle) * 2, 0, 0);
            villager.setInvulnerable(true);
            villager.setSilent(true);
            arena.spawnEntity(villager);
            this.spawned.add(villager);
        }
    }

    /** 随机 3×3 玻璃罩（两层高的空心方框），罩住一片地板制造视野干扰。 */
    private void placeGlassBox(ArenaWorld arena) {
        ColorblindFloor floor = this.session.floor();
        int cx = this.random.nextInt(Math.max(1, floor.size() - 3)) + 1;
        int cz = this.random.nextInt(Math.max(1, floor.size() - 3)) + 1;
        BlockPos origin = floor.origin();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // 中心留空，别把人闷死
                }
                for (int dy = 1; dy <= 2; dy++) {
                    BlockPos pos = origin.add(cx + dx, dy, cz + dz);
                    if (arena.getBlockState(pos).isAir()) {
                        arena.setBlockState(pos, Blocks.GLASS.getDefaultState(), 3);
                    }
                }
            }
        }
    }

    private void teleportRandom(ArenaWorld arena, ServerPlayerEntity player) {
        ColorblindFloor floor = this.session.floor();
        BlockPos target = floor.origin().add(this.random.nextInt(floor.size()), 1,
                this.random.nextInt(floor.size()));
        player.teleport(arena, target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                player.getYaw(), player.getPitch());
    }

    /** 魔毯：在玩家脚下铺一层 3×3 方块，整回合跟着走（防止掉下去）。 */
    private void placeCarpet(ArenaWorld arena, ServerPlayerEntity owner) {
        int color = this.session.randomRoundColor();
        BlockPos base = owner.getBlockPos().down();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = base.add(dx, 0, dz);
                if (arena.getBlockState(pos).isAir()) {
                    arena.setBlockState(pos, ColorblindFloor.state(color), 3);
                }
            }
        }
        arena.spawnParticles(new DustParticleEffect(new org.joml.Vector3f(1f, 1f, 1f), 0.6F),
                owner.getX(), owner.getY() - 0.2, owner.getZ(), 2, 0.3, 0, 0.3, 0);
    }

    private void randomRepaint(ArenaWorld arena, int radius) {
        ColorblindFloor floor = this.session.floor();
        BlockPos pos = floor.origin().add(this.random.nextInt(floor.size()), 0,
                this.random.nextInt(floor.size()));
        this.session.repaintAround(pos.getX(), pos.getZ(), radius, this.session.randomRoundColor());
    }

    // ---------- 颜料蛋：蛋落地后把落点染成"本回合目标色" ----------

    /** 蛋是原版扔出去的，这里每 tick 把场上新出现的蛋收进来跟踪（只在有人拿到颜料蛋时扫）。 */
    private void trackEggs(ArenaWorld arena) {
        ColorblindFloor floor = this.session.floor();
        BlockPos origin = floor.origin();
        int span = floor.size() + 8;
        net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
                origin.getX() - 4, ArenaTemplate.PLATFORM_Y - 40, origin.getZ() - 4,
                origin.getX() + span, ArenaTemplate.PLATFORM_Y + 48, origin.getZ() + span);
        for (EggEntity egg : arena.getEntitiesByClass(EggEntity.class, box, e -> !e.isRemoved())) {
            if (this.trackedEggIds.add(egg.getId())) {
                this.eggs.add(egg);
            }
        }
        this.tickEggs(arena);
    }

    private void tickEggs(ArenaWorld arena) {
        Iterator<EggEntity> it = this.eggs.iterator();
        while (it.hasNext()) {
            EggEntity egg = it.next();
            if (!egg.isRemoved()) {
                continue;
            }
            it.remove();
            this.trackedEggIds.remove(egg.getId());
            // 蛋碎的地方染成"本回合正确答案"的颜色 —— 给玩家临时造一块安全地
            this.session.repaintAround(egg.getX(), egg.getZ(), 2, this.session.answerColor());
            arena.spawnParticles(ParticleTypes.POOF, egg.getX(), egg.getY(), egg.getZ(), 8, 0.3, 0.3, 0.3, 0.02);
        }
    }

    // ---------- 小工具 ----------

    private void giveItem(ServerPlayerEntity player, ItemStack stack) {
        if (!player.getInventory().insertStack(stack)) {
            player.dropItem(stack, false);
        }
        player.currentScreenHandler.sendContentUpdates();
    }

    private ServerPlayerEntity online(UUID uuid) {
        for (ServerPlayerEntity player : this.session.match().onlineParticipants()) {
            if (player.getUuid().equals(uuid)) {
                return player;
            }
        }
        return null;
    }

}
