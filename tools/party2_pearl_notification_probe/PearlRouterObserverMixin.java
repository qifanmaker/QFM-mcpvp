package com.example.pvp.validation.notification.mixin;
import com.example.pvp.match.PortedGameEvents;
import com.example.pvp.validation.notification.NativePearlNotificationProbe;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=PortedGameEvents.class,remap=false)
public abstract class PearlRouterObserverMixin {
    @Inject(method="pearlLanded",at=@At("RETURN"),remap=false)
    private static void p2notify$observed(EnderPearlEntity pearl,ServerPlayerEntity player,CallbackInfoReturnable<Boolean> cir){NativePearlNotificationProbe.routed("global",pearl,player,cir.getReturnValue());}
}
