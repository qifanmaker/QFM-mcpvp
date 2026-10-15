package com.example.pvp.mixin;

import com.example.pvp.match.MatchManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 雪球命中实体：沿雪球飞行方向给目标击退（有攻击效果但不掉血），对局内生效。
 * 直接施加速度（takeKnockback 的方向约定相反），参照粘液球击退实现。
 */
@Mixin(SnowballEntity.class)
public abstract class SnowballEntityMixin {
    // 原版 onEntityHit 会先调用 damage(0)，在 HEAD 修改速度会被后续伤害流程覆盖，
    // 所以必须在原版命中处理完成后再施加击退。
    @Inject(method = "onEntityHit", at = @At("TAIL"))
    private void pvp$snowballKnockback(EntityHitResult entityHitResult, CallbackInfo ci) {
        SnowballEntity snowball = (SnowballEntity) (Object) this;
        Entity target = entityHitResult.getEntity();
        Entity owner = snowball.getOwner();
        if (!(target instanceof LivingEntity victim)
                || !(owner instanceof ServerPlayerEntity caster)
                || victim == caster) {
            return;
        }
        MatchManager matchManager = MatchManager.get();
        if (matchManager == null) {
            return;
        }
        var match = matchManager.getMatchFor(caster.getUuid());
        if (match == null) {
            return;
        }
        // 沿雪球飞行方向击退（水平 1.2，竖直 0.3）
        Vec3d vel = snowball.getVelocity();
        double len = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (len < 0.001) {
            return;
        }
        victim.setVelocity(
                victim.getVelocity().x + vel.x / len * 1.2,
                victim.getVelocity().y + 0.3,
                victim.getVelocity().z + vel.z / len * 1.2);
        victim.velocityDirty = true;
    }
}
