package com.example.pvp.mixin;

import com.example.pvp.match.MatchManager;
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
 * PvP 钓鱼竿：命中时把目标向施法者面前击退一段距离，
 * 并取消原版"把目标拉向自己"的行为（仅在对局中生效）。
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
        if (matchManager == null || !matchManager.isInMatch(caster.getUuid())) {
            return;
        }
        // 朝施法者面向的方向击退
        double yawRad = Math.toRadians(caster.getYaw());
        victim.takeKnockback(1.2F, -Math.sin(yawRad), Math.cos(yawRad));
    }

    /** 取消原版把目标拉向自己的行为，只保留命中击退。 */
    @Inject(method = "pullHookedEntity", at = @At("HEAD"), cancellable = true)
    private void pvp$noRodPull(Entity entity, CallbackInfo ci) {
        FishingBobberEntity bobber = (FishingBobberEntity) (Object) this;
        PlayerEntity owner = bobber.getPlayerOwner();
        if (owner instanceof ServerPlayerEntity caster) {
            MatchManager matchManager = MatchManager.get();
            if (matchManager != null && matchManager.isInMatch(caster.getUuid())) {
                ci.cancel();
            }
        }
    }
}
