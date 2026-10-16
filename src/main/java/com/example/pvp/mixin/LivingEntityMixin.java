package com.example.pvp.mixin;

import com.example.pvp.PvPMod;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchState;
import com.example.pvp.match.MatchType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 幸运之柱"一击必杀"事件：开启时对应 ACTIVE 幸运之柱对局的玩家所有伤害直接致死。
 * 烈焰弹：投掷者被自己的火球弹飞后免疫第一次摔落伤害。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @org.spongepowered.asm.mixin.injection.ModifyArg(
            method = "damage",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;applyDamage(Lnet/minecraft/entity/damage/DamageSource;F)V"),
            index = 1
    )
    private float pvp$oneHitKill(float amount) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (PvPMod.oneHitKillActive && self instanceof ServerPlayerEntity player) {
            Match match = PvPMod.MATCH == null ? null : PvPMod.MATCH.getMatchFor(player);
            if (match != null && match.getType() == MatchType.LUCKY_PILLAR && match.getState() == MatchState.ACTIVE) {
                return 9999.0F;
            }
        }
        return amount;
    }

    /** 烈焰弹：投掷者被自己的火球弹飞后，第一次落地不摔伤（一次性标记，落地消耗）。 */
    @Inject(method = "handleFallDamage", at = @At("HEAD"), cancellable = true)
    private void pvp$fireballNoFall(float fallDistance, float damageMultiplier, DamageSource source,
                                    CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayerEntity player && fallDistance > 2.0f
                && PvPMod.consumeFireballNoFall(player)) {
            cir.setReturnValue(false); // 免掉第一次摔落伤害
            return;
        }
        if (self instanceof ServerPlayerEntity player && fallDistance > 2.0f
                && PvPMod.MATCH != null) {
            com.example.pvp.match.Match match = PvPMod.MATCH.getMatchFor(player);
            if (match != null && match.consumeSkywarsStartFallProtection(player)) {
                cir.setReturnValue(false); // 空岛开局从玻璃笼子落下时免疫一次摔落伤害
            }
        }
    }
}
