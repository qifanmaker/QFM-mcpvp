package com.example.pvp.arena.party;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.mob.ZombieVillagerEntity;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.SilverfishEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 原地图以普通生物充当打靶目标，并全局禁用生物掉落。共享服务器不能为此修改难度或掉落规则，
 * 所以只创建保持原版运动、伤害及物种行为的本场子类，允许和平难度存活且不产出物品/经验。
 * 属性、装备、AI 和移除时机由各小游戏按原源码设置；原有世界里的同类生物完全不受影响。
 */
public final class PartySourceMobs {
    private static final Set<Entity> SOURCES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Entity> NO_COLLISION = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Entity, MobEntity> CLOUD_CREATORS = new WeakHashMap<>();

    private PartySourceMobs() { }

    public static boolean isSourceMob(Entity entity) { return SOURCES.contains(entity); }

    /** 苦力怕原生云没有 Owner；单独记录精确创建者，保留原生伤害归属。 */
    public static void createdCloud(AreaEffectCloudEntity cloud, MobEntity creator) {
        if (!isSourceMob(creator)) throw new IllegalArgumentException("Not a Party 2 source mob");
        CLOUD_CREATORS.put(cloud, creator);
    }

    public static MobEntity cloudCreator(Entity cloud) { return CLOUD_CREATORS.get(cloud); }

    /** 原 WAM no_collision 队伍仅作用于该游戏目标，Monster Shoot 保留普通生物碰撞。 */
    public static void noCollision(MobEntity entity) {
        if (!isSourceMob(entity)) throw new IllegalArgumentException("Not a Party 2 source mob");
        NO_COLLISION.add(entity);
    }

    public static MobEntity create(EntityType<?> type, ServerWorld world) {
        MobEntity mob;
        if (type == EntityType.ZOMBIE) mob = new ZombieEntity(EntityType.ZOMBIE, world) {
            @Override public boolean isPushable() { return !NO_COLLISION.contains(this) && super.isPushable(); }
            @Override public void pushAwayFrom(Entity entity) { if (!NO_COLLISION.contains(this)) super.pushAwayFrom(entity); }
            @Override protected boolean isDisallowedInPeaceful() { return false; }
            @Override protected int getXpToDrop() { return 0; }
            @Override protected void dropLoot(DamageSource source, boolean causedByPlayer) { }
            @Override protected void dropEquipment(ServerWorld sourceWorld, DamageSource source, boolean causedByPlayer) { }
        };
        else if (type == EntityType.ZOMBIE_VILLAGER) mob = new ZombieVillagerEntity(EntityType.ZOMBIE_VILLAGER, world) {
            @Override public boolean isPushable() { return !NO_COLLISION.contains(this) && super.isPushable(); }
            @Override public void pushAwayFrom(Entity entity) { if (!NO_COLLISION.contains(this)) super.pushAwayFrom(entity); }
            @Override protected boolean isDisallowedInPeaceful() { return false; }
            @Override protected int getXpToDrop() { return 0; }
            @Override protected void dropLoot(DamageSource source, boolean causedByPlayer) { }
            @Override protected void dropEquipment(ServerWorld sourceWorld, DamageSource source, boolean causedByPlayer) { }
        };
        else if (type == EntityType.WITHER_SKELETON) mob = new WitherSkeletonEntity(EntityType.WITHER_SKELETON, world) {
            @Override public boolean isPushable() { return !NO_COLLISION.contains(this) && super.isPushable(); }
            @Override public void pushAwayFrom(Entity entity) { if (!NO_COLLISION.contains(this)) super.pushAwayFrom(entity); }
            @Override protected boolean isDisallowedInPeaceful() { return false; }
            @Override protected int getXpToDrop() { return 0; }
            @Override protected void dropLoot(DamageSource source, boolean causedByPlayer) { }
            @Override protected void dropEquipment(ServerWorld sourceWorld, DamageSource source, boolean causedByPlayer) { }
        };
        else if (type == EntityType.ENDERMAN) mob = new EndermanEntity(EntityType.ENDERMAN, world) {
            @Override public boolean isPushable() { return !NO_COLLISION.contains(this) && super.isPushable(); }
            @Override public void pushAwayFrom(Entity entity) { if (!NO_COLLISION.contains(this)) super.pushAwayFrom(entity); }
            @Override protected boolean isDisallowedInPeaceful() { return false; }
            @Override protected int getXpToDrop() { return 0; }
            @Override protected void dropLoot(DamageSource source, boolean causedByPlayer) { }
            @Override protected void dropEquipment(ServerWorld sourceWorld, DamageSource source, boolean causedByPlayer) { }
        };
        else if (type == EntityType.BLAZE) mob = new BlazeEntity(EntityType.BLAZE, world) {
            @Override public boolean isPushable() { return !NO_COLLISION.contains(this) && super.isPushable(); }
            @Override public void pushAwayFrom(Entity entity) { if (!NO_COLLISION.contains(this)) super.pushAwayFrom(entity); }
            @Override protected boolean isDisallowedInPeaceful() { return false; }
            @Override protected int getXpToDrop() { return 0; }
            @Override protected void dropLoot(DamageSource source, boolean causedByPlayer) { }
            @Override protected void dropEquipment(ServerWorld sourceWorld, DamageSource source, boolean causedByPlayer) { }
        };
        else if (type == EntityType.ZOMBIFIED_PIGLIN) mob = new ZombifiedPiglinEntity(EntityType.ZOMBIFIED_PIGLIN, world) {
            @Override public boolean isPushable() { return !NO_COLLISION.contains(this) && super.isPushable(); }
            @Override public void pushAwayFrom(Entity entity) { if (!NO_COLLISION.contains(this)) super.pushAwayFrom(entity); }
            @Override protected boolean isDisallowedInPeaceful() { return false; }
            @Override protected int getXpToDrop() { return 0; }
            @Override protected void dropLoot(DamageSource source, boolean causedByPlayer) { }
            @Override protected void dropEquipment(ServerWorld sourceWorld, DamageSource source, boolean causedByPlayer) { }
        };
        else if (type == EntityType.CREEPER) mob = new CreeperEntity(EntityType.CREEPER, world) {
            @Override public boolean isPushable() { return !NO_COLLISION.contains(this) && super.isPushable(); }
            @Override public void pushAwayFrom(Entity entity) { if (!NO_COLLISION.contains(this)) super.pushAwayFrom(entity); }
            @Override protected boolean isDisallowedInPeaceful() { return false; }
            @Override protected int getXpToDrop() { return 0; }
            @Override protected void dropLoot(DamageSource source, boolean causedByPlayer) { }
            @Override protected void dropEquipment(ServerWorld sourceWorld, DamageSource source, boolean causedByPlayer) { }
        };
        else if (type == EntityType.SILVERFISH) mob = new SilverfishEntity(EntityType.SILVERFISH, world) {
            @Override public boolean isPushable() { return !NO_COLLISION.contains(this) && super.isPushable(); }
            @Override public void pushAwayFrom(Entity entity) { if (!NO_COLLISION.contains(this)) super.pushAwayFrom(entity); }
            @Override protected boolean isDisallowedInPeaceful() { return false; }
            @Override protected int getXpToDrop() { return 0; }
            @Override protected void dropLoot(DamageSource source, boolean causedByPlayer) { }
            @Override protected void dropEquipment(ServerWorld sourceWorld, DamageSource source, boolean causedByPlayer) { }
        };
        else if (type == EntityType.SKELETON) mob = new SkeletonEntity(EntityType.SKELETON, world) {
            @Override public boolean isPushable() { return !NO_COLLISION.contains(this) && super.isPushable(); }
            @Override public void pushAwayFrom(Entity entity) { if (!NO_COLLISION.contains(this)) super.pushAwayFrom(entity); }
            @Override protected boolean isDisallowedInPeaceful() { return false; }
            @Override protected int getXpToDrop() { return 0; }
            @Override protected void dropLoot(DamageSource source, boolean causedByPlayer) { }
            @Override protected void dropEquipment(ServerWorld sourceWorld, DamageSource source, boolean causedByPlayer) { }
        };
        else if (type == EntityType.CHICKEN) mob = new ChickenEntity(EntityType.CHICKEN, world) {
            @Override public boolean isPushable() { return !NO_COLLISION.contains(this) && super.isPushable(); }
            @Override public void pushAwayFrom(Entity entity) { if (!NO_COLLISION.contains(this)) super.pushAwayFrom(entity); }
            @Override protected boolean isDisallowedInPeaceful() { return false; }
            @Override protected int getXpToDrop() { return 0; }
            @Override protected void dropLoot(DamageSource source, boolean causedByPlayer) { }
            @Override protected void dropEquipment(ServerWorld sourceWorld, DamageSource source, boolean causedByPlayer) { }
        };
        else throw new IllegalArgumentException("Unsupported Party 2 target type: " + type);
        // 原地图固定普通难度，不会出现困难难度援军；不允许未登记生物泄入共享世界。
        var reinforcements = mob.getAttributeInstance(EntityAttributes.ZOMBIE_SPAWN_REINFORCEMENTS);
        if (reinforcements != null) reinforcements.setBaseValue(0);
        SOURCES.add(mob);
        return mob;
    }
}
