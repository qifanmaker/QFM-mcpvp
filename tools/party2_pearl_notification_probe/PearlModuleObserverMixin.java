package com.example.pvp.validation.notification.mixin;
import com.example.pvp.arena.party.PearlHurlRound;
import com.example.pvp.validation.notification.NativePearlNotificationProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=PearlHurlRound.class,remap=false)
public abstract class PearlModuleObserverMixin {
    @Inject(method="pearlLanded",at=@At("HEAD"),remap=false)
    private void p2notify$observed(CallbackInfo ci){NativePearlNotificationProbe.moduleCallback();}
}
