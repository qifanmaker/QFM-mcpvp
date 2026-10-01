package com.example.pvp.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.AffineTransformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 展示实体的配置 setter 在 1.21.1 里全是 private（只给 NBT 加载用），
 * 应用代码没有别的入口，所以用 {@code @Invoker} 暴露出来。
 *
 * <p>注意：调用方必须强转成**本接口**，绝不能强转 mixin 类本体——
 * Mixin 禁止应用代码直接加载 mixin 类，会抛 {@code IllegalClassLoadError}。
 */
@Mixin(DisplayEntity.class)
public interface DisplayEntityInvoker {
    /** 朝向模式：CENTER = 永远正对观察者（原版玩家铭牌就是这个效果）。 */
    @Invoker("setBillboardMode")
    void pvp$setBillboardMode(DisplayEntity.BillboardMode mode);

    /** 位姿。缩放 1.0 时文字大小与原版玩家铭牌完全一致。 */
    @Invoker("setTransformation")
    void pvp$setTransformation(AffineTransformation transformation);

    /** 位置更新的客户端插值时长（tick）；0 表示收到位置包后立即定位，避免跟随延迟。 */
    @Invoker("setTeleportDuration")
    void pvp$setTeleportDuration(int ticks);
}
