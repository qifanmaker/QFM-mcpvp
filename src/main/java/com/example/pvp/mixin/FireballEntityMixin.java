package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.config.PvPConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.world.explosion.ExplosionBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 竞技场内玩家发射的恶魂火焰弹：只放大爆炸击退（ExplosionBehavior.getKnockbackModifier），
 * 爆炸威力/伤害/破坏范围保持原版不变。非竞技场或非玩家发射保持原版行为。
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
        // 威力为 0 时禁用爆炸破坏方块（NONE 不炸方块、不伤害实体）
        World.ExplosionSourceType effectiveType = power <= 0.0f ? World.ExplosionSourceType.NONE : sourceType;
        ExplosionBehavior behavior = new ExplosionBehavior() {
            @Override
            public float getKnockbackModifier(Entity e) {
                return Math.max(0.0f, PvPConfig.INSTANCE.fireballKnockbackMultiplier);
            }
        };
        return world.createExplosion(entity, world.getDamageSources().explosion(entity, self.getOwner()),
                behavior, x, y, z, power, createFire, effectiveType);
    }
}
