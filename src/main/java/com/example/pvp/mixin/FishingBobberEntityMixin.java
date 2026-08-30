package com.example.pvp.mixin;

import com.example.pvp.match.Match;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PvP 钓鱼竿：命中时把目标向远离施法者的方向击退，并取消原版"拉向自己"；
 * 仅在对局中生效，且幸运之柱例外——保留原版钓鱼拉回，不做命中击退。
 */
@Mixin(FishingBobberEntity.class)
public abstract class FishingBobberEntityMixin {
    /** 浮标勾住实体时（新的勾住）立即击退一次。 */
    @Inject(method = "updateHookedEntityId", at = @At("HEAD"))
    private void pvp$rodKnockback(Entity entity, CallbackInfo ci) {
        FishingBobberEntity bobber = (FishingBobberEntity) (Object) this;
        if (entity == null || bobber.getHookedEntity() == entity) {
            return; // 已勾住的实体，避免每 tick 重复击退
        }
        PlayerEntity owner = bobber.getPlayerOwner();
        if (!(owner instanceof ServerPlayerEntity caster)
                || !(entity instanceof LivingEntity victim)
                || victim == caster) {
            return;
        }
        MatchManager matchManager = MatchManager.get();
        if (matchManager == null) {
            return;
        }
        Match match = matchManager.getMatchFor(caster.getUuid());
        if (match == null || match.getType() == MatchType.LUCKY_PILLAR) {
            return; // 幸运之柱：保留原版钓鱼拉回，不做命中击退
        }
        // 朝远离施法者的方向击退（takeKnockback 方向约定相反会把人往身后拉，
        // 这里直接施加速度，参照粘液球击退）
        double dx = victim.getX() - caster.getX();
        double dz = victim.getZ() - caster.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.01) {
            return;
        }
        victim.setVelocity(
                victim.getVelocity().x + dx / d * 1.2,
                victim.getVelocity().y + 0.3,
                victim.getVelocity().z + dz / d * 1.2);
        victim.velocityDirty = true;
    }

    /** 取消原版把目标拉向自己的行为，只保留命中击退。 */
    @Inject(method = "pullHookedEntity", at = @At("HEAD"), cancellable = true)
    private void pvp$noRodPull(Entity entity, CallbackInfo ci) {
        FishingBobberEntity bobber = (FishingBobberEntity) (Object) this;
        PlayerEntity owner = bobber.getPlayerOwner();
        if (owner instanceof ServerPlayerEntity caster) {
            MatchManager matchManager = MatchManager.get();
            if (matchManager != null) {
                Match match = matchManager.getMatchFor(caster.getUuid());
                // 幸运之柱保留原生拉回；其他模式取消拉回（用命中击退代替）
                if (match != null && match.getType() != MatchType.LUCKY_PILLAR) {
                    ci.cancel();
                }
            }
        }
    }
}
