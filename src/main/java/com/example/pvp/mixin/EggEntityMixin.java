package com.example.pvp.mixin;

import com.example.pvp.match.MatchManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.EggEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 鸡蛋在对局中沿飞行方向产生与雪球相同的击退和受击反馈。 */
@Mixin(EggEntity.class)
public abstract class EggEntityMixin {
    @Inject(method = "onEntityHit", at = @At("TAIL"))
    private void pvp$eggKnockback(EntityHitResult hit, CallbackInfo ci) {
        EggEntity egg = (EggEntity) (Object) this;
        Entity target = hit.getEntity();
        Entity owner = egg.getOwner();
        if (!(target instanceof LivingEntity victim)
                || !(owner instanceof ServerPlayerEntity caster)
                || victim == caster || MatchManager.get() == null
                || MatchManager.get().getMatchFor(caster.getUuid()) == null
                || !com.example.pvp.match.PortedGameEvents.allowsExtraKnockback(caster, victim)) {
            return;
        }
        Vec3d velocity = egg.getVelocity();
        double length = Math.hypot(velocity.x, velocity.z);
        if (length < 0.001) return;
        victim.takeKnockback(0.36, -velocity.x / length, -velocity.z / length);
        victim.addVelocity(0.0, 0.1, 0.0);
        egg.getWorld().sendEntityStatus(victim, (byte) 2);
        victim.velocityModified = true;
    }
}
