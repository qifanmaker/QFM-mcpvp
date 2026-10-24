package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorldManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 竞技场内玩家发射的恶魂火焰弹：爆炸威力直接使用 FireballEntity 自身的 power，
 * 投掷者保留一次落地保护；击退倍率由 ExplosionMixin 在广播前统一写入。
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
        Explosion explosion = world.createExplosion(entity, x, y, z, power, createFire, sourceType);
        // 仅投掷者本人：被自己的火球弹飞后免疫第一次摔落伤害
        if (self.getOwner() instanceof ServerPlayerEntity owner) {
            com.example.pvp.PvPMod.fireballNoFallOnce.add(owner.getUuid());
        }
        return explosion;
    }
}
