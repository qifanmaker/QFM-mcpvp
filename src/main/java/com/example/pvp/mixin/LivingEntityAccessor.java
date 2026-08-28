package com.example.pvp.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link LivingEntity#jumping}（protected）——TNT 跑酷二段跳读取跳跃输入用。
 * 独立接口而非混进 LivingEntityMixin，避免应用代码直接引用 mixin 类导致类加载错误。
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("jumping")
    boolean pvp$isJumping();
}
