package com.example.pvp.validation.notification.mixin;
import com.example.pvp.validation.notification.NativePearlNotificationProbe;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(value=EnderPearlEntity.class,remap=false)
public abstract class PearlTailControlMixin {
    @WrapOperation(method="onCollision",at=@At(value="INVOKE",target="Lnet/minecraft/entity/Entity;damage(Lnet/minecraft/entity/damage/DamageSource;F)Z",remap=false),remap=false)
    private boolean p2notify$afterDamage(Entity target,DamageSource source,float amount,Operation<Boolean> original){boolean result=original.call(target,source,amount);NativePearlNotificationProbe.afterDamage(target);return result;}
}
