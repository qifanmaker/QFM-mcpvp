package com.example.pvp.mixin;

import com.example.pvp.mygo.MyGoCommands;
import com.example.pvp.mygo.MyGoSession;
import net.minecraft.network.NetworkThreadUtils;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MyGO 把原版 F/Q 输入交给服务端武器状态机，防止真实副手交换和无授权掉落。 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class MyGoInputMixin {
    @Inject(method = "onUpdateSelectedSlot", at = @At("TAIL"))
    private void pvp$myGoSlot(UpdateSelectedSlotC2SPacket packet, CallbackInfo ci) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        MyGoSession session = MyGoCommands.session(handler.player);
        if (session != null) session.onSelectedSlotChanged(handler.player);
    }

    @Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
    private void pvp$myGoAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        NetworkThreadUtils.forceMainThread(packet, handler, handler.player.getServerWorld());
        MyGoSession session = MyGoCommands.session(handler.player);
        if (session == null) return;
        switch (packet.getAction()) {
            case SWAP_ITEM_WITH_OFFHAND -> {
                session.reload(handler.player);
                ci.cancel();
            }
            case DROP_ITEM, DROP_ALL_ITEMS -> {
                session.drop(handler.player);
                ci.cancel();
            }
            default -> { }
        }
    }
}
