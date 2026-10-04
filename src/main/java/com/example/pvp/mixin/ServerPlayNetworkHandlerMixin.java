package com.example.pvp.mixin;

import com.example.pvp.PvPMod;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 亦可赛艇：把"骑乘时的输入包"转发给氮气逻辑，实现<b>按空格喷氮气</b>。
 *
 * <p>为什么需要这个触发方式：船是被骑乘的载具，按住 W 前进时鼠标右键的物品使用会被客户端吞掉
 * —— 驾驶时准星常常压在冰面或船身上，右键会先去做方块/实体交互就结束了，物品使用包根本不发。
 * 而骑乘时客户端每 tick 都会发 {@code PlayerInputC2SPacket}（前进/跳跃/潜行），
 * 它不经过准星判定、按住 W 也照发；船又用不到跳跃键，所以空格是最稳的触发键。
 *
 * <p>注入在 TAIL：原版 {@code updateInput} 先照常执行（车辆物理还依赖它），我们只旁听一份。
 *
 * <p>注：血量标签拦出站包的那段注入不在这里 —— {@code sendPacket} 声明在父类
 * {@code ServerCommonNetworkHandler} 上，而 Mixin 不会去父类找 {@code @Inject} 目标，
 * 所以单独放在 {@link ServerCommonNetworkHandlerMixin}。
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Inject(method = "onPlayerInput", at = @At("TAIL"))
    private void pvp$forwardRiderInput(PlayerInputC2SPacket packet, CallbackInfo ci) {
        ServerPlayerEntity player = ((ServerPlayNetworkHandler) (Object) this).player;
        if (player != null) {
            PvPMod.onRiderInput(player, packet.isJumping());
        }
    }
}
