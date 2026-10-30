package com.example.pvp.validation.notification.mixin;
import com.example.pvp.validation.notification.NativePearlNotificationProbe;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.TeleportTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=ServerPlayerEntity.class,remap=false)
public abstract class PearlTeleportControlMixin {
    @Inject(method="teleportTo",at=@At("HEAD"),cancellable=true,remap=false)
    private void p2notify$controlledReturn(TeleportTarget target,CallbackInfoReturnable<Entity> cir){
        ServerPlayerEntity player=(ServerPlayerEntity)(Object)this;
        if(NativePearlNotificationProbe.cancel(player))cir.setReturnValue(NativePearlNotificationProbe.spoof()?player:null);
    }
}
