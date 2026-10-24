package com.example.pvp.kit;

import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;

/** 服务端主动切换快捷栏后，显式同步客户端实际手持的槽位。 */
public final class HotbarSync {
    private HotbarSync() { }

    public static void sendSelection(ServerPlayerEntity player) {
        // sendContentUpdates 只同步物品，不同步 selectedSlot。漏发此包会让客户端拿着
        // 平台/击退球，服务端却按第 0 格的剑处理使用和攻击。即使服务端槽位未变也要发。
        player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(player.getInventory().selectedSlot));
    }
}
