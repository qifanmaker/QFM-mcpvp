package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorldManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 竞技场内的火焰弹（空岛战争可抛）：落地/命中时把附近玩家弹开。
 */
@Mixin(SmallFireballEntity.class)
public abstract class SmallFireballEntityMixin {
    @Inject(method = "onCollision", at = @At("HEAD"))
    private void pvp$fireballKnockback(HitResult hitResult, CallbackInfo ci) {
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
        for (PlayerEntity player : fireball.getWorld().getPlayers()) {
            if (player == owner) {
                continue;
            }
            if (player.squaredDistanceTo(x, y, z) > 25.0) { // 半径 5 格内
                continue;
            }
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            if (dx * dx + dz * dz < 0.01) {
                dx = 0.0;
                dz = 0.01;
            }
            player.takeKnockback(1.8, dx, dz);
        }
    }
}
