package com.example.pvp.mixin;

import com.example.pvp.hud.HealthTagManager;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 血量标签方案 A：<b>不把标签实体发给它服务的那位玩家自己</b>，这样第一人称不会有一块贴脸的名字/
 * 血量挡住视野（详见 {@code HealthTagManager} 类注释）。其他人的标签、以及本人标签发给别人，都不受影响。
 *
 * <p><b>为什么注入在 {@link ServerCommonNetworkHandler} 而不是 {@code ServerPlayNetworkHandler}</b>：
 * {@code sendPacket} 是声明在这个父类上的，而 Mixin 不会去父类里找 {@code @Inject} 的目标 ——
 * 把这段挂在子类上会直接报
 * {@code InvalidInjectionException: could not find any targets matching 'sendPacket'}，
 * 而且因为 {@code pvp.mixins.json} 是 {@code required + defaultRequire=1}，
 * 后果是整个 mod 初始化失败、服务器起不来（实测）。挂到声明类上才是对的。
 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {
    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void pvp$hideOwnHealthTag(Packet<?> packet, CallbackInfo ci) {
        // 同一个父类还被登录/配置阶段的 handler 继承，只有 play 阶段才有 player 可用
        if ((Object) this instanceof ServerPlayNetworkHandler playHandler) {
            ServerPlayerEntity player = playHandler.player;
            if (player != null && HealthTagManager.shouldHideOwnTag(packet, player)) {
                ci.cancel();
            }
        }
    }
}
