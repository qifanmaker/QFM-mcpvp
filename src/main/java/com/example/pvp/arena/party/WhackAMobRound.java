package com.example.pvp.arena.party;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版 402 Whack-A-Mob：相同的九洞序列同时发给各房间，使用原生斧头伤害与铁甲减伤。
 * 原数据包依赖全服物品随机池、kill 统计和全局生物规则；复制场景或按点击直接加分都会丢失
 * 蓄力、护甲、缩回、末批等待等规则，因此保留原生目标，仅将时序与统计限制到本场实体。
 * 自查：占洞是所有房间的存活实体共同决定；同 tick 同物种击杀按原统计存在条件只计一次；
 * 回缩 /kill 不带玩家伤害源，不能误记 minecraft.killed:type；退出者永久不能再得分。
 */
public final class WhackAMobRound implements PartyRound {
    private static final int DURATION = 1800;
    private static final int MOB_DELAY = 20;
    private static final int[] WEIGHTED_KINDS = {0, 0, 0, 0, 1, 2, 3, 4, 4, 5, 5};

    private enum Kind {
        ZOMBIE(EntityType.ZOMBIE, 10, 1, SoundEvents.ENTITY_ZOMBIE_DEATH),
        WITHER_SKELETON(EntityType.WITHER_SKELETON, 10, 3, SoundEvents.ENTITY_WITHER_SKELETON_DEATH),
        ARMORED_ZOMBIE(EntityType.ZOMBIE_VILLAGER, 10, 3, SoundEvents.ENTITY_ZOMBIE_VILLAGER_DEATH),
        ENDERMAN(EntityType.ENDERMAN, 9, 5, SoundEvents.ENTITY_ENDERMAN_DEATH),
        BLAZE(EntityType.BLAZE, 4, -1, SoundEvents.ENTITY_BLAZE_DEATH),
        PIGLIN(EntityType.ZOMBIFIED_PIGLIN, 10, 2, SoundEvents.ENTITY_ZOMBIFIED_PIGLIN_DEATH);

        final EntityType<?> type;
        final float health;
        final int points;
        final SoundEvent sound;
        Kind(EntityType<?> type, float health, int points, SoundEvent sound) {
            this.type = type; this.health = health; this.points = points; this.sound = sound;
        }
    }

    private static final class Target {
        final MobEntity mob;
        final Kind kind;
        final int hole;
        final Vec3d point;
        int life;
        boolean pointMarker = true;
        Target(MobEntity mob, Kind kind, int hole, Vec3d point) {
            this.mob = mob; this.kind = kind; this.hole = hole; this.point = point;
        }
    }

    private final Map<UUID, Target> targets = new LinkedHashMap<>();
    private final Map<UUID, Integer> playerLanes = new LinkedHashMap<>();
    private final Map<UUID, EnumSet<Kind>> pendingKills = new HashMap<>();
    private final Map<UUID, Integer> respawnProtection = new HashMap<>();
    private final Map<UUID, DisplayEntity.TextDisplayEntity> popups = new HashMap<>();
    private final Set<UUID> activePlayers = new HashSet<>();
    private ServerWorld world;
    private int offsetY;
    private int lanes;
    private int cycle;
    private int endermanCycle;
    private int lastHole = -1;
    private boolean finished;
    private Team collisionTeam;

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(new BlockPos(-217, 39, -468));
        context.geometry("whack_a_mob");
        context.timeLimit(DURATION);
        this.world = context.world();
        this.offsetY = context.sourceBlock(0, 0, 0).getY();
        this.lanes = Math.max(4, context.initialPlayerCount());
        int lane = 0;
        for (ServerPlayerEntity player : context.players()) {
            this.playerLanes.put(player.getUuid(), lane);
            this.activePlayers.add(player.getUuid());
            context.spawn(player.getUuid(), this.playerSpawn(context, lane++));
        }
    }

    @Override public void start(PartyRoundContext context) {
        // 原 no_collision 还会通知原版客户端停止碰撞预测。使用独立队伍，仅加入本场目标 UUID，
        // 不修改玩家或其他比赛的队伍；否则只有服务端不推挤时，客户端仍可能预测玩家被目标挤走。
        var scoreboard = this.world.getServer().getScoreboard();
        String teamName;
        do { teamName = "p2w_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
        while (scoreboard.getTeam(teamName) != null);
        this.collisionTeam = scoreboard.addTeam(teamName);
        this.collisionTeam.setCollisionRule(AbstractTeam.CollisionRule.NEVER);
        for (ServerPlayerEntity player : context.players()) {
            context.score(player.getUuid(), 0);
            player.removeStatusEffect(StatusEffects.WEAKNESS);
            this.equip(player);
        }
        context.announce("§a打怪达人：用蓄满力的下界合金斧击杀出现的怪物。"
                + "\n§7僵尸 +1、猪灵 +2、凋灵骷髅 / 铁甲僵尸 +3、末影人 +5；烈焰人 −1。"
                + "\n§e90 秒后停止出怪，最后一批退场后按积分排名。");
    }

    @Override public boolean handlesTimeLimit() { return true; }

    @Override public void tick(PartyRoundContext context, int elapsed) {
        if (this.finished) return;
        // 原 tick 先缓存 ypos，再上升、增加 life、缩回；后面的条件仍使用先前缓存的 ypos。
        for (Target target : new ArrayList<>(this.targets.values())) {
            MobEntity mob = target.mob;
            if (!mob.isAlive() || mob.isRemoved()) continue;
            // 原世界底部为 -64，原生虚空从源 Y<-128 开始伤害。场景竖直平移后仍按源高度判定，
            // 否则未击打的烈焰人下降到虚空所需时间会多出几十格，甚至永远等不到末批退场。
            if (mob.getY() - this.offsetY < -128) mob.damage(this.world.getDamageSources().outOfWorld(), 4);
            if (!mob.isAlive()) continue;
            int y = (int) Math.floor(mob.getY() - this.offsetY);
            if (y <= 37 && target.life == 0) this.move(mob, .05);
            if (y >= 38) target.life++;
            if (target.kind == Kind.ENDERMAN) target.life++;
            if (target.kind == Kind.WITHER_SKELETON && target.life >= 20 && target.life < 60) target.life = 60;
            if (target.life >= 60) this.move(mob, -.05);
            if (target.kind == Kind.ENDERMAN && target.life >= 60) mob.refreshPositionAndAngles(mob.getX(),
                    context.source(0, -64, 0).y, mob.getZ(), mob.getYaw(), 0);
            if (target.kind != Kind.BLAZE && target.life >= 60 && y <= 36
                    || target.kind == Kind.BLAZE && y >= 41) mob.kill();
        }

        this.endermanCycle++;
        boolean hasEnderman = this.targets.values().stream().anyMatch(target -> target.kind == Kind.ENDERMAN && target.mob.isAlive());
        if (this.endermanCycle == 20 && hasEnderman) {
            this.teleportEndermen(context, 30);
            for (ServerPlayerEntity player : context.players()) {
                player.playSoundToPlayer(SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.MASTER, 1, 1);
            }
        }
        if (this.endermanCycle >= 30 && hasEnderman) {
            this.teleportEndermen(context, 37);
            this.endermanCycle = 0;
        }

        if (++this.cycle >= MOB_DELAY && elapsed <= DURATION) this.spawnWave(context);
        this.updatePointMarkers(context);
        this.flushPoints(context);
        // 原得分 marker 独立于生物尸体；同列还有同类目标时，即使尸体消失也须保留 marker。
        this.targets.values().removeIf(target -> target.mob.isRemoved() && !target.pointMarker);
        this.popups.values().removeIf(text -> {
            if (text.isRemoved() || text.age >= 20) { text.discard(); return true; }
            return false;
        });
        if (elapsed % 20 == 0) {
            for (ServerPlayerEntity player : context.players()) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, 25, 99, false, false, false));
            }
        }
        if (elapsed >= DURATION && this.targets.values().stream().noneMatch(target -> target.mob.isAlive())) context.finish();
    }

    private void spawnWave(PartyRoundContext context) {
        Kind kind = Kind.values()[WEIGHTED_KINDS[context.random().nextInt(WEIGHTED_KINDS.length)]];
        List<Integer> available = new ArrayList<>();
        for (int hole = 0; hole < 9; hole++) {
            int position = hole;
            if (this.targets.values().stream().noneMatch(target -> target.hole == position && target.mob.isAlive())) available.add(hole);
        }
        // 原 mob_spot 分数没有先重置：九洞都占用时会重复使用上一次位置，不能改成跳过生成。
        if (!available.isEmpty()) this.lastHole = available.get(context.random().nextInt(available.size()));
        if (this.lastHole >= 0) for (int lane = 0; lane < this.lanes; lane++) this.spawn(context, kind, this.lastHole, lane);
        this.cycle = 0;
    }

    private void spawn(PartyRoundContext context, Kind kind, int hole, int lane) {
        int bank = lane / 5, row = lane % 5;
        double x = -233.5 + (hole / 3) * 2 + bank * 30;
        double z = -423.5 + (hole % 3) * 2 - row * 23;
        Vec3d position = context.source(x, kind == Kind.BLAZE ? 36 : 35, z);
        MobEntity mob = PartySourceMobs.create(kind.type, this.world);
        PartySourceMobs.noCollision(mob);
        mob.setNoGravity(true);
        mob.setAiDisabled(kind == Kind.ENDERMAN);
        mob.setSilent(true);
        mob.setPersistent();
        this.attribute(mob, EntityAttributes.GENERIC_MAX_HEALTH, kind.health);
        this.attribute(mob, EntityAttributes.GENERIC_FOLLOW_RANGE, 0);
        this.attribute(mob, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1);
        this.attribute(mob, EntityAttributes.GENERIC_MOVEMENT_SPEED, 0);
        this.attribute(mob, EntityAttributes.GENERIC_ATTACK_DAMAGE, 0);
        mob.setHealth(kind.health);
        if (kind == Kind.ARMORED_ZOMBIE) {
            mob.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            mob.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            mob.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            mob.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) mob.setEquipmentDropChance(slot, 0);
        float yaw = 0;
        ServerPlayerEntity nearest = this.nearest(context, position);
        if (nearest != null) yaw = (float) -Math.toDegrees(Math.atan2(nearest.getX() - position.x, nearest.getZ() - position.z));
        mob.refreshPositionAndAngles(position.x, position.y, position.z, yaw, 0);
        mob.setHeadYaw(yaw);
        mob.setBodyYaw(yaw);
        mob.addCommandTag("pvp_party_whack_target");
        if (this.collisionTeam != null) this.world.getServer().getScoreboard().addScoreHolderToTeam(mob.getUuidAsString(), this.collisionTeam);
        this.targets.put(mob.getUuid(), new Target(mob, kind, hole, context.source(x, 40.5, z)));
        this.world.spawnEntity(mob);
    }

    private void teleportEndermen(PartyRoundContext context, double y) {
        for (Target target : this.targets.values()) if (target.kind == Kind.ENDERMAN && target.mob.isAlive()) {
            MobEntity mob = target.mob;
            mob.refreshPositionAndAngles(mob.getX(), context.source(0, y, 0).y, mob.getZ(), mob.getYaw(), 0);
        }
    }

    private void move(MobEntity mob, double y) {
        mob.refreshPositionAndAngles(mob.getX(), mob.getY() + y, mob.getZ(), mob.getYaw(), mob.getPitch());
    }

    private void updatePointMarkers(PartyRoundContext context) {
        for (Target marker : this.targets.values()) {
            if (!marker.pointMarker) continue;
            // 原 marker 的 dx=0,dz=0,dy=-40 会同时看到重叠的同物种；须全部离开这一列才显示。
            Box column = new Box(marker.point.x, marker.point.y - 30, marker.point.z,
                    marker.point.x + 1, marker.point.y + 11, marker.point.z + 1);
            boolean present = this.targets.values().stream().anyMatch(target -> target.kind == marker.kind && target.mob.isAlive()
                    && target.mob.getBoundingBox().intersects(column));
            if (present) continue;
            marker.pointMarker = false;
            ServerPlayerEntity player = this.nearest(context, marker.point);
            if (player == null || !this.pendingKills.getOrDefault(player.getUuid(), EnumSet.noneOf(Kind.class)).contains(marker.kind)) continue;
            DisplayEntity.TextDisplayEntity popup = new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, this.world);
            popup.setText(Text.literal((marker.kind.points < 0 ? "§c" : "§a+") + marker.kind.points + " 分"));
            popup.setBillboardMode(DisplayEntity.BillboardMode.CENTER);
            popup.refreshPositionAndAngles(marker.point.x, marker.point.y, marker.point.z, 0, 0);
            this.popups.put(popup.getUuid(), popup);
            this.world.spawnEntity(popup);
        }
    }

    private ServerPlayerEntity nearest(PartyRoundContext context, Vec3d point) {
        ServerPlayerEntity nearest = null;
        double distance = Double.MAX_VALUE;
        for (ServerPlayerEntity player : context.players()) {
            double current = player.squaredDistanceTo(point);
            if (current < distance) { nearest = player; distance = current; }
        }
        return nearest;
    }

    private void flushPoints(PartyRoundContext context) {
        for (ServerPlayerEntity player : context.players()) {
            for (Kind kind : this.pendingKills.getOrDefault(player.getUuid(), EnumSet.noneOf(Kind.class))) {
                context.addScore(player.getUuid(), kind.points);
                player.playSoundToPlayer(kind.sound, SoundCategory.MASTER, 1, 1);
                player.sendMessage(Text.literal((kind.points < 0 ? "§c" : "§a+") + kind.points + " 分 · 当前 " + context.score(player.getUuid())), true);
            }
        }
        this.pendingKills.clear();
    }

    @Override public void mobDied(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        Target target = this.targets.get(victim.getUuid());
        if (this.finished || target == null || !(source.getAttacker() instanceof ServerPlayerEntity player)
                || !this.activePlayers.contains(player.getUuid()) || context.isOut(player.getUuid())) return;
        this.pendingKills.computeIfAbsent(player.getUuid(), ignored -> EnumSet.noneOf(Kind.class)).add(target.kind);
    }

    @Override public boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        // 原公共开场会清除抗性，WAM 只补饱食度；玩家仍可被环境伤害或 /kill 触发重生。
        // pvp=false 由独立战斗开关控制，不能用“拒绝所有玩家伤害”替代这一规则。
        if (victim instanceof ServerPlayerEntity player) {
            if (this.finished || player.getWorld() != this.world || !this.activePlayers.contains(player.getUuid())
                    || context.isOut(player.getUuid()) || source.getAttacker() instanceof ServerPlayerEntity) return false;
            return context.elapsed() >= this.respawnProtection.getOrDefault(player.getUuid(), 0)
                    || source.isOf(DamageTypes.FALL) || source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY);
        }
        Target target = this.targets.get(victim.getUuid());
        if (this.finished || target == null || victim.getWorld() != this.world || !victim.isAlive()) return false;
        if (source.getAttacker() == null) return true;
        return source.getAttacker() instanceof ServerPlayerEntity player && player.getWorld() == this.world
                && this.activePlayers.contains(player.getUuid()) && !context.isOut(player.getUuid());
    }

    @Override public boolean ownsEntity(Entity entity) {
        return entity.getWorld() == this.world && (this.targets.containsKey(entity.getUuid()) || this.popups.containsKey(entity.getUuid()));
    }

    @Override public void death(PartyRoundContext context, ServerPlayerEntity player) {
        Integer lane = this.playerLanes.get(player.getUuid());
        if (lane == null || context.isOut(player.getUuid())) return;
        player.setHealth(player.getMaxHealth());
        player.clearStatusEffects();
        player.setFireTicks(0);
        player.fallDistance = 0;
        player.timeUntilRegen = 0;
        player.hurtTime = 0;
        player.setAttacker(null);
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5);
        // 实体复用替代原生重生，恢复原生新实体的 60 tick 保护；摔落和绕过无敌的伤害仍生效。
        this.respawnProtection.put(player.getUuid(), context.elapsed() + 60);
        context.teleport(player, this.playerSpawn(context, lane));
        this.equip(player);
    }

    @Override public void leave(PartyRoundContext context, ServerPlayerEntity player) {
        this.activePlayers.remove(player.getUuid());
        this.pendingKills.remove(player.getUuid());
        this.respawnProtection.remove(player.getUuid());
        player.removeStatusEffect(StatusEffects.SATURATION);
    }

    @Override public void finish(PartyRoundContext context) {
        this.flushPoints(context);
        this.finished = true;
    }

    @Override public void close(PartyRoundContext context) {
        this.finished = true;
        this.targets.values().forEach(target -> target.mob.discard());
        this.targets.clear();
        this.popups.values().forEach(Entity::discard);
        this.popups.clear();
        this.pendingKills.clear();
        this.respawnProtection.clear();
        this.activePlayers.clear();
        if (this.collisionTeam != null) {
            var scoreboard = this.world.getServer().getScoreboard();
            if (scoreboard.getTeam(this.collisionTeam.getName()) == this.collisionTeam) scoreboard.removeTeam(this.collisionTeam);
            this.collisionTeam = null;
        }
        context.players().forEach(player -> player.removeStatusEffect(StatusEffects.SATURATION));
    }

    @Override public String status(PartyRoundContext context) {
        return context.elapsed() >= DURATION ? "打怪达人 · 等待最后一批怪物退场"
                : "打怪达人 · 剩余 " + Math.max(0, (DURATION - context.elapsed() + 19) / 20) + " 秒";
    }

    @Override public String scoreDescription(PartyRoundContext context, UUID player) { return context.score(player) + " 分"; }

    private Vec3d playerSpawn(PartyRoundContext context, int lane) {
        return context.source(-231.5 + lane / 5 * 30, 39, -421.5 - lane % 5 * 23);
    }

    private void equip(ServerPlayerEntity player) {
        ItemStack axe = new ItemStack(Items.NETHERITE_AXE);
        axe.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§d怪物重锤"));
        axe.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(false));
        player.getInventory().setStack(0, axe);
        player.currentScreenHandler.sendContentUpdates();
    }

    private void attribute(MobEntity mob, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> type, double value) {
        var attribute = mob.getAttributeInstance(type);
        if (attribute != null) attribute.setBaseValue(value);
    }
}
