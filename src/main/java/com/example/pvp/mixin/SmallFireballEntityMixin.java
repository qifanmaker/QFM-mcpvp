package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorldManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 竞技场内的火焰弹（空岛战争可抛）：落地/命中时播放爆炸特效并弹开附近 5 格玩家。
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
        // 水平用 takeKnockback（可靠），垂直按方向加成——往脚底扔时爆炸在脚下 → 向上弹起（火焰弹跳）
        for (PlayerEntity player : fireball.getWorld().getPlayers()) {
            double dx = player.getX() - x;
            double dy = (player.getY() + player.getHeight() / 2.0) - y; // 用玩家身体中心
            double dz = player.getZ() - z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > 25.0) { // 半径 5 格内
                continue;
            }
            double dist = Math.sqrt(distSq);
            double strength = 2.4 * Math.max(0.45, 1.0 - dist / 7.0); // 距离越近越强
            // 水平击退（正上方 dx=dz≈0 时 takeKnockback 自动跳过）
            player.takeKnockback(strength, dx, dz);
            // 垂直分量：爆炸在脚下则向上（火焰弹跳，封顶约 1.5 ≈ 7 格高），在上方则向下压
            double vyAdd = dy / Math.max(0.1, dist) * strength;
            vyAdd = Math.min(vyAdd, 1.5);
            if (Math.abs(vyAdd) > 0.01) {
                player.setVelocity(player.getVelocity().x,
                        player.getVelocity().y + vyAdd, player.getVelocity().z);
                player.velocityDirty = true;
            }
        }
    }
}
