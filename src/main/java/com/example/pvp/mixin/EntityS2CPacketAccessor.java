package com.example.pvp.mixin;

import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link EntityS2CPacket} 的实体 id 是 {@code protected final int id}，没有公开 getter，
 * 而血量标签需要在 {@code ServerPlayNetworkHandler.sendPacket} 上按 id 拦包
 * （见 {@code HealthTagManager#shouldHideOwnTag}），所以用 {@code @Accessor} 暴露出来。
 *
 * <p>只读，不改原版行为。用法与项目的 {@code DisplayEntityInvoker} 一致：
 * 调用方强转本<b>接口</b>，不要强转 mixin 类本体（Mixin 禁止应用代码直接加载 mixin 类）。
 */
@Mixin(EntityS2CPacket.class)
public interface EntityS2CPacketAccessor {
    @Accessor("id")
    int pvp$getEntityId();
}
