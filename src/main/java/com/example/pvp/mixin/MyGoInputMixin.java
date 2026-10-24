package com.example.pvp.mixin;

import com.example.pvp.mygo.MyGoCommands;
import com.example.pvp.mygo.MyGoInputGuard;
import com.example.pvp.mygo.MyGoSession;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.network.NetworkThreadUtils;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MyGO 将左键挥手及 F/Q 输入交给武器状态机；其他模式继续使用原版输入。 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class MyGoInputMixin {
    @Unique
    private final MyGoInputGuard pvp$myGoInput = new MyGoInputGuard();
    @Unique
    private MyGoSession pvp$predictionSession;

    // 修复原因：右键 use 的 SUCCESS 会向本人广播摆手；1.21.1 原版客户端收到后
    // 调用 ClientPlayerEntity.swingHand，再回发 HandSwingC2S，误走下面的左键开火入口。
    // 预审：只重定向两个 use 处理器的 (Hand, boolean) 调用；MyGO 仅通知其他玩家，
    // 其他模式保留原参数。真实左键的 onHandSwing 不设时间屏蔽，快速开镜后仍可立即射击。
    @Redirect(method = {"onPlayerInteractItem", "onPlayerInteractBlock"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerPlayerEntity;swingHand(Lnet/minecraft/util/Hand;Z)V"))
    private void pvp$myGoUseAnimation(ServerPlayerEntity actor, Hand hand, boolean includeSelf) {
        actor.swingHand(hand, includeSelf && MyGoCommands.session(actor) == null);
    }

    // 空挥、点方块、攻击实体都会发送挥手包；统一在此触发，避免一击打出两组子弹。
    // TAIL 已经过原版的 forceMainThread，不能在 Netty 线程直接修改会话。
    @Inject(method = "onHandSwing", at = @At("TAIL"))
    private void pvp$myGoFire(HandSwingC2SPacket packet, CallbackInfo ci) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        MyGoSession session = MyGoCommands.session(handler.player);
        if (session == null || session != this.pvp$predictionSession) this.pvp$clearPrediction();
        if (session == null) return;
        if (!this.pvp$myGoInput.consumePredictedSwing(packet.getHand() == Hand.MAIN_HAND))
            session.onAttackInput(handler.player, packet.getHand());
    }

    // 预审：只处理原版源码明确会 shouldSwingHand 的主手 block.onUse；潜行会跳过
    // 方块交互，铁门/铁活板门返回 PASS。按钮不挂令牌：客户端 powerOn 会先改本地
    // powered，而服务端 MyGO 会拦截改动；不同步时无法保证它一定会摆手，不能冒险吞左键。
    @Inject(method = "onPlayerInteractBlock", at = @At("HEAD"))
    private void pvp$myGoPredictBlockSwing(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        NetworkThreadUtils.forceMainThread(packet, handler, handler.player.getServerWorld());
        boolean predicts = false;
        MyGoSession session = MyGoCommands.session(handler.player);
        if (session != null && packet.getHand() == Hand.MAIN_HAND
                && !handler.player.shouldCancelInteraction() && !handler.player.getMainHandStack().isEmpty()) {
            BlockState state = handler.player.getServerWorld().getBlockState(packet.getBlockHitResult().getBlockPos());
            var block = state.getBlock();
            predicts = block instanceof DoorBlock door && door.getBlockSetType().canOpenByHand()
                    || block instanceof TrapdoorBlock && !state.isOf(Blocks.IRON_TRAPDOOR)
                    || block instanceof FenceGateBlock || block instanceof LeverBlock;
        }
        this.pvp$myGoInput.blockUse(predicts);
        this.pvp$predictionSession = predicts ? session : null;
    }

    // block-use 后继续发 item-use 说明客户端没有接受该方块；实体攻击/交互也标志
    // 下一次明确输入。清除令牌可保留切换目标后紧随的真实左键，而无需延迟射击。
    @Inject(method = "onPlayerInteractItem", at = @At("HEAD"))
    private void pvp$myGoItemInput(PlayerInteractItemC2SPacket packet, CallbackInfo ci) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        NetworkThreadUtils.forceMainThread(packet, handler, handler.player.getServerWorld());
        this.pvp$clearPrediction();
    }

    @Inject(method = "onPlayerInteractEntity", at = @At("HEAD"))
    private void pvp$myGoEntityInput(PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        NetworkThreadUtils.forceMainThread(packet, handler, handler.player.getServerWorld());
        this.pvp$clearPrediction();
    }

    @Inject(method = "onUpdateSelectedSlot", at = @At("TAIL"))
    private void pvp$myGoSlot(UpdateSelectedSlotC2SPacket packet, CallbackInfo ci) {
        this.pvp$clearPrediction();
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        MyGoSession session = MyGoCommands.session(handler.player);
        if (session != null) session.onSelectedSlotChanged(handler.player);
    }

    @Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
    private void pvp$myGoAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        NetworkThreadUtils.forceMainThread(packet, handler, handler.player.getServerWorld());
        this.pvp$clearPrediction();
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

    @Unique
    private void pvp$clearPrediction() {
        this.pvp$myGoInput.clear();
        this.pvp$predictionSession = null;
    }
}
