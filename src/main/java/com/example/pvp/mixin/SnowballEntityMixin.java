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
 * 通过原版击退入口计算速度，并显式同步给受击玩家本人。
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
        if (!com.example.pvp.match.PortedGameEvents.allowsExtraKnockback(caster, victim)) return;
        // 水平击退按用户设定翻倍，垂直击退降为原来的三分之一。
        Vec3d vel = snowball.getVelocity();
        double len = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (len < 0.001) {
            return;
        }
        // takeKnockback 的方向参数是“推力来源”，所以传入飞行方向的反向。
        victim.takeKnockback(0.36, -vel.x / len, -vel.z / len);
        victim.addVelocity(0.0, 0.1, 0.0);
        snowball.getWorld().sendEntityStatus(victim, (byte) 2);
        // takeKnockback 只设置 velocityDirty；零伤害雪球不会替我们设置本人同步标记。
        victim.velocityModified = true;
    }
}
