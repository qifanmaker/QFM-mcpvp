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
import net.minecraft.world.explosion.ExplosionBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

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
        float h = Math.max(0.0f, PvPConfig.INSTANCE.fireballKnockbackHorizontal);
        float v = Math.max(0.0f, PvPConfig.INSTANCE.fireballKnockbackVertical);
        // 用原版 modifier=1 先让爆炸施加基础击退（不放大威力/伤害），再从 affectedPlayers
        // 读到已施加的击退向量，把水平×h、竖直×v 的增量补加给玩家，实现横纵分开倍率
        Explosion explosion = world.createExplosion(entity, world.getDamageSources().explosion(entity, self.getOwner()),
                new ExplosionBehavior(), x, y, z, power, createFire, effectiveType);
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
