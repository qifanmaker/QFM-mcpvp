package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorldManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 竞技场内的火焰弹（空岛战争/幸运之柱可抛）：落地/命中时播放爆炸特效并弹开附近 5 格玩家。
 * 直接命中身体时沿火焰弹飞行方向推（水平击退更直观，避免纯竖直上天）；被弹开的玩家获得
 * 2 秒抗性 II，减少被弹起后落地的摔落伤害。
 */
@Mixin(SmallFireballEntity.class)
public abstract class SmallFireballEntityMixin {
    @Inject(method = "onCollision", at = @At("HEAD"))
    private void pvp$fireballExplosion(HitResult hitResult, CallbackInfo ci) {
        SmallFireballEntity fireball = (SmallFireballEntity) (Object) this;
        if (fireball.getWorld().getRegistryKey() != ArenaWorldManager.ARENA_WORLD_KEY) {
            return;
        }
        Entity owner = fireball.getOwner();
        if (!(owner instanceof ServerPlayerEntity)) {
            return; // 仅玩家投掷的火焰弹生效
        }
        double x = fireball.getX();
        double y = fireball.getY();
        double z = fireball.getZ();

        // 爆炸特效（粒子 + 音效）：打中地面也有明显的爆炸反馈
        if (fireball.getWorld() instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0, 0, 0, 0);
            serverWorld.playSound(null, x, y, z, SoundEvents.ENTITY_GENERIC_EXPLODE,
                    SoundCategory.BLOCKS, 4.0F, (1.0F + serverWorld.random.nextFloat() * 0.2F) * 0.7F);
        }

        // 统一径向击退：爆炸点周围 5 格的所有玩家（含投掷者）都沿"爆炸→自身"方向被震开，
        // 横向与纵向同一 strength（衰减减少、无纵向封顶）。
        // 直接命中身体（距离≈0）时沿火焰弹飞行方向推——水平击退直观，且不会 0/0 NaN 或纯竖直上天。
        Vec3d flight = fireball.getVelocity();
        for (PlayerEntity player : fireball.getWorld().getPlayers()) {
            double dx = player.getX() - x;
            double dy = (player.getY() + player.getHeight() / 2.0) - y; // 用玩家身体中心
            double dz = player.getZ() - z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > 25.0) { // 半径 5 格内
                continue;
            }
            double dist = Math.sqrt(distSq);
            Vec3d dir;
            if (dist < 0.01) {
                // 直接命中：沿火焰弹飞行方向推（水平击退；往脚底扔时仍会因落地距离≈1.5 而上弹）
                dir = flight.lengthSquared() > 0.0001 ? flight.normalize() : new Vec3d(0, 1, 0);
            } else {
                dir = new Vec3d(dx, dy, dz).multiply(1.0 / dist);
            }
            double strength = 3.0 * Math.max(0.6, 1.0 - dist / 8.0); // 距离衰减减少，整体击退 x1.5
            player.setVelocity(player.getVelocity().add(dir.multiply(strength)));
            player.velocityDirty = true;
            // 触发后给 2 秒抗性 II，减少被弹起/推离后落地的摔落伤害
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 1));
        }
    }
}
