package com.example.pvp.validation.notification.mixin;
import com.example.pvp.match.MinecraftPartySession;
import com.example.pvp.validation.notification.NativePearlNotificationProbe;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=MinecraftPartySession.class,remap=false)
public abstract class PearlSessionObserverMixin {
    @Inject(method="onPearlLanded",at=@At("RETURN"),remap=false)
    private void p2notify$observed(EnderPearlEntity pearl,ServerPlayerEntity player,CallbackInfoReturnable<Boolean> cir){NativePearlNotificationProbe.routed("session",pearl,player,cir.getReturnValue());}
}
