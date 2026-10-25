package com.example.pvp.mixin;

import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** 末影珍珠落点伤害减半，保留其它来源的摔落伤害。 */
@Mixin(EnderPearlEntity.class)
public abstract class EnderPearlEntityMixin {
    @ModifyArg(
            method = "onCollision",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/LivingEntity;damage(Lnet/minecraft/entity/damage/DamageSource;F)Z"),
            index = 1)
    private float pvp$halvePearlFallDamage(float amount) {
        return amount * 0.5F;
    }
}
