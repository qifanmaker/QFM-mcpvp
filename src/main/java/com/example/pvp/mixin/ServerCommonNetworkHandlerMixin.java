package com.example.pvp.mixin;

import com.example.pvp.hud.HealthTagManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 血量标签方案 A：<b>不把标签实体发给它服务的那位玩家自己</b>，这样第一人称不会有一块贴脸的名字/
 * 血量挡住视野（详见 {@code HealthTagManager} 类注释）。其他人的标签、以及本人标签发给别人，都不受影响。
 *
 * <p><b>为什么注入在 {@link ServerCommonNetworkHandler} 而不是 {@code ServerPlayNetworkHandler}</b>：
 * {@code sendPacket/send} 是声明在这个父类上的，而 Mixin 不会去父类里找注入目标 ——
 * 把这段挂在子类上会直接报
 * {@code InvalidInjectionException: could not find any targets matching 'sendPacket'}，
 * 而且因为 {@code pvp.mixins.json} 是 {@code required + defaultRequire=1}，
 * 后果是整个 mod 初始化失败、服务器起不来（实测）。挂到声明类上才是对的。
 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {
    // 在统一发送出口过滤，覆盖普通发送和带回调的发送，并允许重建混合 Bundle。
    @WrapMethod(method = "send")
    private void pvp$hideOwnHealthTag(Packet<?> packet, PacketCallbacks callbacks, Operation<Void> original) {
        // 同一个父类还被登录/配置阶段的 handler 继承，只有 play 阶段才有 player 可用
        if ((Object) this instanceof ServerPlayNetworkHandler playHandler) {
            ServerPlayerEntity player = playHandler.player;
            packet = HealthTagManager.filterOwnTagPacket(packet, player);
            if (packet == null) return;
        }
        original.call(packet, callbacks);
    }
}
