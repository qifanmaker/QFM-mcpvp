package com.example.pvp.mixin;

import com.example.pvp.mygo.MyGoCommands;
import com.example.pvp.mygo.MyGoSession;
import net.minecraft.network.NetworkThreadUtils;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MyGO 将左键挥手及 F/Q 输入交给武器状态机；其他模式继续使用原版输入。 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class MyGoInputMixin {
    // 空挥、点方块、攻击实体都会发送挥手包；统一在此触发，避免一击打出两组子弹。
    // TAIL 已经过原版的 forceMainThread，不能在 Netty 线程直接修改会话。
    @Inject(method = "onHandSwing", at = @At("TAIL"))
    private void pvp$myGoFire(HandSwingC2SPacket packet, CallbackInfo ci) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        MyGoSession session = MyGoCommands.session(handler.player);
        if (session != null) session.onAttackInput(handler.player, packet.getHand());
    }

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
