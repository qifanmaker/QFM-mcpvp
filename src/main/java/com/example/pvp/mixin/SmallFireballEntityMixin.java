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
        // 所以往脚底扔时（爆炸在脚下）会被向上弹起——即火焰弹跳，机制与旁人完全一致
        for (PlayerEntity player : fireball.getWorld().getPlayers()) {
            double dx = player.getX() - x;
            double dy = (player.getY() + player.getHeight() / 2.0) - y; // 用玩家身体中心
            double dz = player.getZ() - z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > 25.0) { // 半径 5 格内
                continue;
            }
            double dist = Math.sqrt(distSq);
            if (dist < 0.01) {
                dx = 0.0;
                dy = 1.0;
                dz = 0.0;
                dist = 1.0;
            }
            double strength = 1.8 * Math.max(0.3, 1.0 - dist / 6.0); // 距离越近越强
            player.setVelocity(
                    player.getVelocity().x + dx / dist * strength,
                    player.getVelocity().y + dy / dist * strength,
                    player.getVelocity().z + dz / dist * strength);
            player.velocityDirty = true;
        }
    }
}
