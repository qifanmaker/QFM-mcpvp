package com.example.pvp.validation.mixin;
import com.example.pvp.validation.PearlPhaseProbe;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=EnderPearlEntity.class,remap=false)
public abstract class PearlCollisionProbeMixin {
    @Unique private Vec3d beforeOwner;
    @Inject(method="onCollision",at=@At("HEAD"),remap=false)
    private void before(HitResult hit,CallbackInfo ci){var p=((EnderPearlEntity)(Object)this).getOwner();this.beforeOwner=p==null?null:p.getPos();}
    @Inject(method="onCollision",at=@At("TAIL"),remap=false)
    private void phase(HitResult hit,CallbackInfo ci){PearlPhaseProbe.collided((EnderPearlEntity)(Object)this,this.beforeOwner);}
}
