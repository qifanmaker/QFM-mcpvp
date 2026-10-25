package com.example.pvp.mixin;

import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** 末影珍珠落点伤害减半，保留其它来源的摔落伤害。 */
@Mixin(EnderPearlEntity.class)
public abstract class EnderPearlEntityMixin {
    // 1.21.1 的 onCollision 以 Entity 类型保存 owner，字节码因此调用 Entity.damage；
    // 旧 LivingEntity 目标匹配不到调用点，会让服务端启动时注入失败。
    @ModifyArg(
            method = "onCollision",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;damage(Lnet/minecraft/entity/damage/DamageSource;F)Z"),
            index = 1)
    private float pvp$halvePearlFallDamage(float amount) {
        return amount * 0.5F;
    }
}
