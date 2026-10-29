package com.example.pvp.arena.party;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版 403 Monster Shoot：只复制竞技场会丢失随机下落生物、鸡骑士和射箭计分，因此以本场
 * 原生实体重现 monster_shoot{,_start,_tick} 与 ms_spawn_monster，不执行全服选择器。
 * 自查：出生点25个，怪物权重5/3/2/2；5HP、原生AI及缓降/鸡骑士运动保持不变；
 * 同类同tick多次击杀按源统计只记一次；停止生成后等最后存活目标消失；只接受本场箭与环境伤害。
 * 所有实体、玩家属性、投射物和装备在退出/清场时回收，和平难度不需要修改全服难度。
 */
public final class MonsterShootRound implements PartyRound {
    private static final int DURATION = 1200;
    private static final int[][] STARTS = {{-84, -262}, {-72, -250}, {-60, -262}, {-72, -274}};
    private static final float[] YAWS = {-90, 180, 90, 0};
    private static final int[][] SPAWN_POINTS = {
            {-68,-266},{-70,-266},{-72,-266},{-74,-266},{-76,-266},
            {-69,-264},{-71,-264},{-73,-264},{-75,-264},{-77,-264},
            {-68,-262},{-70,-262},{-72,-262},{-74,-262},{-76,-262},
            {-69,-260},{-71,-260},{-73,-260},{-75,-260},{-77,-260},
            {-68,-258},{-70,-258},{-72,-258},{-74,-258},{-76,-258}
    };
    private enum Kind {
        ZOMBIE(EntityType.ZOMBIE, 1), CREEPER(EntityType.CREEPER, 2),
        SILVERFISH(EntityType.SILVERFISH, 5), SKELETON(EntityType.SKELETON, 3), CHICKEN(EntityType.CHICKEN, 1);
        final EntityType<?> type;
        final int points;
        Kind(EntityType<?> type, int points) { this.type = type; this.points = points; }
    }
    private record Target(MobEntity entity, Kind kind) { }
    private final PartyRoundPlayerState saved = new PartyRoundPlayerState();
    private final Map<UUID, Target> targets = new LinkedHashMap<>();
    private final Map<UUID, PersistentProjectileEntity> arrows = new HashMap<>();
    private final Map<UUID, AreaEffectCloudEntity> clouds = new HashMap<>();
    private final Map<UUID, EnumSet<Kind>> pendingPoints = new HashMap<>();
    private final Map<UUID, Vec3d> starts = new LinkedHashMap<>();
    private final Map<UUID, Float> startYaws = new HashMap<>();
    private final Set<UUID> participants = new HashSet<>();
    private ServerWorld world;
    private Box bounds;
    private Vec3d center;
    private Vec3d respawn;
    private int spawnDelay;
    private boolean started;
    private boolean finished;

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(new BlockPos(-72, 19, -262));
        context.geometry("monster_shoot");
        context.timeLimit(DURATION);
        this.world = context.world();
        this.bounds = new Box(context.source(-96, -12, -286), context.source(-48, 70, -238));
        this.center = context.center().toCenterPos();
        this.respawn = context.source(-85.5, 19, -262.5);
        int count = context.initialPlayerCount();
        this.spawnDelay = switch (count) { case 5 -> 17; case 6 -> 14; case 7 -> 12; case 8 -> 10; case 9 -> 9; case 10 -> 8; default -> 20; };
        List<ServerPlayerEntity> players = context.players();
        for (int seat = 0; seat < players.size(); seat++) {
            ServerPlayerEntity player = players.get(seat);
            Vec3d spawn;
            float yaw;
            if (count <= 4) {
                spawn = context.source(STARTS[seat][0] + .5, 19, STARTS[seat][1] + .5);
                yaw = YAWS[seat];
            } else {
                // player_circle / rotate_entity 使用整数360÷N，不是浮点均分；execute positioned 的整数XZ居中。
                int angle = seat * (360 / count);
                // ^ ^ ^1 使用 LookingPosArgument 的原生浮点三角表；与原函数一样连续走12个单位。
                Vec3d forward = new Vec3d(MathHelper.cos((angle + 90f) * (float) (Math.PI / 180)), 0,
                        MathHelper.sin((angle + 90f) * (float) (Math.PI / 180)));
                Vec3d circle = context.source(-71.5, 19, -262.5);
                spawn = circle;
                for (int step = 0; step < 12; step++) spawn = spawn.add(forward);
                yaw = MathHelper.wrapDegrees((float) (MathHelper.atan2(circle.z - spawn.z, circle.x - spawn.x) * 180 / Math.PI) - 90);
            }
            this.starts.put(player.getUuid(), spawn);
            this.startYaws.put(player.getUuid(), yaw);
            this.participants.add(player.getUuid());
            context.spawn(player.getUuid(), spawn);
        }
    }

    @Override public void start(PartyRoundContext context) {
        this.started = true;
        for (ServerPlayerEntity player : context.players()) {
            this.saved.attribute(player, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1);
            this.saved.resetHealth(player);
            this.giveEquipment(player);
            player.networkHandler.requestTeleport(player.getX(), player.getY(), player.getZ(),
                    this.startYaws.getOrDefault(player.getUuid(), 0f), 0);
            context.score(player.getUuid(), 0);
        }
        this.refreshEffects(context);
        context.announce("§a怪物射击：射落怪物得分，僵尸1、苦力怕2、骷髅3、蠹虫5分。"
                + "\n§e60秒后停止生成，处理完剩余目标后排名；箭具有原版一级穿透。");
    }

    @Override public boolean handlesTimeLimit() { return true; }

    @Override public void tick(PartyRoundContext context, int elapsed) {
        if (!this.started || this.finished) return;
        this.targets.values().removeIf(target -> target.entity().isRemoved());
        for (Target target : this.targets.values()) {
            MobEntity mob = target.entity();
            if (!mob.isAlive()) continue;
            if (!this.bounds.contains(mob.getPos())) { mob.discard(); continue; }
            if (target.kind() != Kind.ZOMBIE && target.kind() != Kind.CHICKEN) {
                // 原函数每tick补1秒；这也决定苦力怕爆炸后药水云复制的剩余效果时长。
                mob.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 20, 0, false, false));
            }
            if (mob.isOnGround() || (target.kind() == Kind.ZOMBIE && !this.nearKind(Kind.CHICKEN, mob, .4))
                    || (target.kind() == Kind.CHICKEN && !this.nearKind(Kind.ZOMBIE, mob, 1))) mob.kill();
        }
        for (ServerPlayerEntity player : context.players()) {
            if (!this.bounds.contains(player.getPos())) this.death(context, player);
            this.ensureWeapons(player);
        }
        this.refreshEffects(context);
        this.arrows.values().removeIf(arrow -> {
            if (arrow.isRemoved()) return true;
            if (!this.insideRegion(arrow)) { arrow.discard(); return true; }
            if (arrow.getPierceLevel() != 1) setPiercing(arrow);
            return false;
        });
        this.clouds.values().removeIf(cloud -> {
            if (!this.insideRegion(cloud)) cloud.discard();
            return cloud.isRemoved();
        });
        for (ItemEntity item : this.world.getEntitiesByClass(ItemEntity.class, this.bounds,
                entity -> entity.getStack().isOf(Items.BOW) || entity.getStack().isOf(Items.ARROW))) item.discard();
        if (elapsed <= DURATION && elapsed % this.spawnDelay == 0) this.spawnTarget(context);
        if (elapsed >= DURATION && this.targets.values().stream().noneMatch(target -> target.kind() != Kind.CHICKEN && target.entity().isAlive())) {
            // 原 tick 在最后目标消失时先 end，再移除各击杀统计并公布名次；该tick待结的击杀不进入分数。
            this.pendingPoints.clear();
            context.finish();
            return;
        }
        this.flushPoints(context);
    }

    private boolean nearKind(Kind kind, MobEntity mob, double distance) {
        double squared = distance * distance;
        return this.targets.values().stream().anyMatch(target -> target.kind() == kind && target.entity().isAlive()
                && target.entity().squaredDistanceTo(mob) <= squared);
    }

    private void spawnTarget(PartyRoundContext context) {
        int type = context.random().nextInt(12);
        Kind kind = type < 5 ? Kind.ZOMBIE : type < 8 ? Kind.CREEPER : type < 10 ? Kind.SILVERFISH : Kind.SKELETON;
        int[] spot = SPAWN_POINTS[context.random().nextInt(SPAWN_POINTS.length)];
        Vec3d position = context.source(spot[0] + .5, 55, spot[1] + .5);
        if (kind == Kind.ZOMBIE) {
            MobEntity chicken = this.create(Kind.CHICKEN, position);
            chicken.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(1);
            chicken.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, -1, 0, false, false));
            MobEntity zombie = this.create(Kind.ZOMBIE, position);
            zombie.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.STONE_BUTTON));
            zombie.startRiding(chicken, true);
            this.world.spawnEntity(chicken);
            this.world.spawnEntity(zombie);
        } else {
            MobEntity target = this.create(kind, position);
            if (kind == Kind.SKELETON) target.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.STONE_BUTTON));
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 20, 0, false, false));
            this.world.spawnEntity(target);
        }
    }

    private MobEntity create(Kind kind, Vec3d position) {
        MobEntity entity = PartySourceMobs.create(kind.type, this.world);
        entity.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        entity.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(5);
        entity.setHealth(5);
        entity.setSilent(true);
        entity.setPersistent();
        entity.setCanPickUpLoot(false);
        entity.addCommandTag("pvp_party_monster_shoot");
        entity.addCommandTag(kind == Kind.CHICKEN ? "pvp_party_ms_chicken" : "pvp_party_ms_monster");
        this.targets.put(entity.getUuid(), new Target(entity, kind));
        return entity;
    }

    @Override public boolean ownsEntity(Entity entity) {
        if (this.targets.containsKey(entity.getUuid()) || this.arrows.containsKey(entity.getUuid()) || this.clouds.containsKey(entity.getUuid())) return true;
        if (this.started && !this.finished && entity instanceof AreaEffectCloudEntity cloud && this.insideRegion(entity)) {
            MobEntity creator = PartySourceMobs.cloudCreator(cloud);
            if (creator != null && this.targets.containsKey(creator.getUuid())) return true;
        }
        return this.started && !this.finished && entity instanceof ArrowEntity arrow && arrow.getOwner() != null
                && this.participants.contains(arrow.getOwner().getUuid()) && this.insideRegion(entity);
    }

    private boolean insideRegion(Entity entity) {
        return entity.getWorld() == this.world && Math.abs(entity.getX() - this.center.x) <= 168
                && Math.abs(entity.getZ() - this.center.z) <= 168;
    }

    @Override public void entityLoaded(PartyRoundContext context, Entity entity) {
        if (entity instanceof AreaEffectCloudEntity cloud && this.ownsEntity(cloud)) {
            cloud.addCommandTag("pvp_party_ms_cloud");
            this.clouds.put(cloud.getUuid(), cloud);
        } else if (entity instanceof ArrowEntity arrow && this.ownsEntity(arrow)) {
            setPiercing(arrow);
            arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
            this.arrows.put(arrow.getUuid(), arrow);
        }
    }

    private static void setPiercing(PersistentProjectileEntity arrow) {
        // 1.21.1 的 setter 私有。与原 data merge 相同地保留所有其他原生箭字段，只改 PierceLevel。
        NbtCompound data = new NbtCompound();
        arrow.writeCustomDataToNbt(data);
        data.putByte("PierceLevel", (byte) 1);
        arrow.readCustomDataFromNbt(data);
    }

    @Override public boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        if (this.finished) return false;
        if (victim instanceof ServerPlayerEntity player) {
            // 原图用抗性99保护玩家，仍允许原生环境管线及绕过抗性的 /kill；玩家互击由源pvp=false阻止。
            return this.participants.contains(player.getUuid()) && context.players().contains(player)
                    && !(source.getAttacker() instanceof ServerPlayerEntity);
        }
        if (!this.targets.containsKey(victim.getUuid())) return false;
        if (source.getAttacker() instanceof ServerPlayerEntity player) {
            return this.participants.contains(player.getUuid()) && context.players().contains(player)
                    && source.getSource() instanceof ArrowEntity arrow && this.arrows.containsKey(arrow.getUuid());
        }
        return source.getAttacker() == null || this.ownsEntity(source.getAttacker());
    }

    @Override public void mobDied(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        Target target = this.targets.get(victim.getUuid());
        if (this.finished || target == null) return;
        // minecraft.killed:type 由 PlayerEntity.onKilledOther 更新，只认死亡伤害的直接玩家攻击者；
        // /kill 落地目标虽然有 getPrimeAdversary，也不会产生这种物种击杀统计。
        if (source.getAttacker() instanceof ServerPlayerEntity player && this.participants.contains(player.getUuid())
                && context.players().contains(player)) {
            this.pendingPoints.computeIfAbsent(player.getUuid(), ignored -> EnumSet.noneOf(Kind.class)).add(target.kind());
        }
    }

    private void flushPoints(PartyRoundContext context) {
        for (ServerPlayerEntity player : context.players()) {
            EnumSet<Kind> kinds = this.pendingPoints.remove(player.getUuid());
            if (kinds == null) continue;
            int points = kinds.stream().mapToInt(kind -> kind.points).sum();
            context.addScore(player.getUuid(), points);
            player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 1, 0);
            player.sendMessage(Text.literal("§a+" + points + " 分 · 累计 " + context.score(player.getUuid())), true);
        }
    }

    private void refreshEffects(PartyRoundContext context) {
        for (ServerPlayerEntity player : context.players()) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 20, 99, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20, 99, false, false));
        }
    }

    private void giveEquipment(ServerPlayerEntity player) {
        player.getInventory().clear();
        this.ensureWeapons(player);
        ItemStack head = new ItemStack(Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE);
        head.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
        player.getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.BINDING_CURSE)
                .ifPresent(entry -> head.addEnchantment(entry, 1));
        player.equipStack(EquipmentSlot.HEAD, head);
        player.currentScreenHandler.sendContentUpdates();
    }

    private void ensureWeapons(ServerPlayerEntity player) {
        if (player.getInventory().count(Items.BOW) == 0) {
            ItemStack bow = new ItemStack(Items.BOW);
            bow.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(false));
            var enchantments = player.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
            enchantments.getEntry(Enchantments.POWER).ifPresent(entry -> bow.addEnchantment(entry, 5));
            enchantments.getEntry(Enchantments.INFINITY).ifPresent(entry -> bow.addEnchantment(entry, 1));
            enchantments.getEntry(Enchantments.PIERCING).ifPresent(entry -> bow.addEnchantment(entry, 5));
            player.getInventory().insertStack(bow);
        }
        if (player.getInventory().count(Items.ARROW) == 0) player.getInventory().insertStack(new ItemStack(Items.ARROW));
    }

    @Override public ActionResult useItem(PartyRoundContext context, ServerPlayerEntity player, Hand hand) {
        return this.started && !this.finished && player.getStackInHand(hand).isOf(Items.BOW) ? ActionResult.PASS : ActionResult.FAIL;
    }
    @Override public ActionResult useBlock(PartyRoundContext context, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        return this.useItem(context, player, hand);
    }
    @Override public void death(PartyRoundContext context, ServerPlayerEntity player) {
        this.saved.resetHealth(player);
        context.teleport(player, this.respawn);
        this.giveEquipment(player);
        this.refreshEffects(context);
    }
    @Override public void leave(PartyRoundContext context, ServerPlayerEntity player) {
        this.participants.remove(player.getUuid());
        this.pendingPoints.remove(player.getUuid());
        this.restore(player);
    }
    @Override public void finish(PartyRoundContext context) { this.flushPoints(context); this.cleanup(context); }
    @Override public void close(PartyRoundContext context) { this.cleanup(context); }
    private void cleanup(PartyRoundContext context) {
        this.finished = true;
        this.targets.values().forEach(target -> target.entity().discard());
        this.targets.clear();
        this.arrows.values().forEach(Entity::discard);
        this.arrows.clear();
        this.clouds.values().forEach(Entity::discard);
        this.clouds.clear();
        this.pendingPoints.clear();
        this.participants.clear();
        context.players().forEach(this::restore);
    }
    private void restore(ServerPlayerEntity player) {
        this.saved.restore(player);
        player.removeStatusEffect(StatusEffects.WEAKNESS);
        player.removeStatusEffect(StatusEffects.RESISTANCE);
    }
    @Override public String scoreDescription(PartyRoundContext context, UUID player) { return context.score(player) + " 分"; }
    @Override public String status(PartyRoundContext context) {
        return context.elapsed() < DURATION ? "怪物射击 · " + ((DURATION - context.elapsed() + 19) / 20) + " 秒"
                : "时间结束 · 等待剩余目标 " + this.targets.values().stream().filter(target -> target.kind() != Kind.CHICKEN && target.entity().isAlive()).count();
    }
}
