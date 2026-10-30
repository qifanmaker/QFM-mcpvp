package com.example.pvp.validation.notification.mixin;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(value=EnderPearlEntity.class,remap=false)
public interface PearlCollisionInvoker {
    @Invoker(value="onCollision",remap=false) void p2notify$collide(HitResult hit);
}
