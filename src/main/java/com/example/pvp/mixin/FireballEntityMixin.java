package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.config.PvPConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

/**
 * 竞技场内玩家发射的恶魂火焰弹：爆炸威力直接使用 FireballEntity 自身的 power，
 * 只对爆炸计算出的玩家击退向量分别调整水平和垂直分量。非竞技场或非玩家发射保持原版行为。
 */
@Mixin(FireballEntity.class)
public abstract class FireballEntityMixin {

    @Redirect(method = "onCollision", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;createExplosion(Lnet/minecraft/entity/Entity;DDDFZLnet/minecraft/world/World$ExplosionSourceType;)Lnet/minecraft/world/explosion/Explosion;"))
    private Explosion pvp$strongerKnockback(World world, Entity entity, double x, double y, double z,
                                            float power, boolean createFire, World.ExplosionSourceType sourceType,
                                            HitResult hitResult) {
        FireballEntity self = (FireballEntity) (Object) this;
        if (world.getRegistryKey() != ArenaWorldManager.ARENA_WORLD_KEY
                || !(self.getOwner() instanceof ServerPlayerEntity)) {
            return world.createExplosion(entity, x, y, z, power, createFire, sourceType);
        }
        float h = Math.max(0.0f, PvPConfig.INSTANCE.fireballKnockbackHorizontal);
        float v = Math.max(0.0f, PvPConfig.INSTANCE.fireballKnockbackVertical);
        // 让火焰弹自己的 power 直接决定原版爆炸威力、伤害和破坏范围；击退调节只处理
        // 原版已经算出的玩家速度，避免用 TNT 式 ExplosionBehavior 改写爆炸本身。
        Explosion explosion = world.createExplosion(entity, x, y, z, power, createFire, sourceType);
        if (h != 1.0f || v != 1.0f) {
            for (Map.Entry<PlayerEntity, Vec3d> entry : explosion.getAffectedPlayers().entrySet()) {
                PlayerEntity player = entry.getKey();
                Vec3d base = entry.getValue();
                double extraX = base.x * h - base.x;
                double extraY = base.y * v - base.y;
                double extraZ = base.z * h - base.z;
                player.addVelocity(extraX, extraY, extraZ);
            }
        }
        // 仅投掷者本人：被自己的火球弹飞后免疫第一次摔落伤害
        if (self.getOwner() instanceof ServerPlayerEntity owner) {
            com.example.pvp.PvPMod.fireballNoFallOnce.add(owner.getUuid());
        }
        return explosion;
    }
}
