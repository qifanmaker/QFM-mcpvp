package com.example.pvp.mixin;

import com.example.pvp.match.MatchManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 1.8 模式：剑格挡时受到的伤害减半（模拟 1.8.9 的剑格挡）。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @ModifyArg(
            method = "damage",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;applyDamage(Lnet/minecraft/entity/damage/DamageSource;F)V"),
            index = 1
    )
    private float pvp$blockDamageReduction(float amount) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (amount > 0 && self instanceof ServerPlayerEntity player) {
            MatchManager matchManager = MatchManager.get();
            if (matchManager != null && matchManager.isLegacyBlocking(player)) {
                return amount * 0.5F;
            }
        }
        return amount;
    }
}
