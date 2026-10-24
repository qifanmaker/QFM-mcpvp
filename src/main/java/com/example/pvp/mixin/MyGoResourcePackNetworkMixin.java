package com.example.pvp.mixin;

import com.example.pvp.mygo.MyGoResourcePack;
import net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MyGO 必须等待真实资源重载成功；原版回调只处理强制包拒绝，不保存每个模式的准备状态。 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class MyGoResourcePackNetworkMixin {
    @Shadow @Final protected MinecraftServer server;

    @Inject(method = "onResourcePackStatus", at = @At("TAIL"))
    private void pvp$mygoPackStatus(ResourcePackStatusC2SPacket packet, CallbackInfo ci) {
        if (server.isStopped()) return;
        if ((Object) this instanceof ServerPlayNetworkHandler handler) {
            server.execute(() -> {
                if (!server.isStopped()) MyGoResourcePack.onStatus(handler.player, packet);
            });
        }
    }
}
