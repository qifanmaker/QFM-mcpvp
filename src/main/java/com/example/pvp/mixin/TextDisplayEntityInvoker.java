package com.example.pvp.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 文字展示实体的文本 / 显示标志位 setter 都是 private，见 {@link DisplayEntityInvoker}。
 *
 * <p>背景色（{@code setBackground}）不在这里暴露：默认值 0x40000000 已经和原版玩家铭牌的
 * 底纹一模一样，不用改。默认只有「阴影」是关的（flags = 0），而原版铭牌是带阴影的，
 * 所以 {@link #pvp$setDisplayFlags} 用来补上 {@code SHADOW_FLAG}。
 *
 * <p>调用方强转本**接口**，不要强转 mixin 类本体。
 */
@Mixin(DisplayEntity.TextDisplayEntity.class)
public interface TextDisplayEntityInvoker {
    @Invoker("setText")
    void pvp$setText(Text text);

    /** 标志位：{@code SHADOW_FLAG} / {@code SEE_THROUGH_FLAG} / 对齐标志的组合。 */
    @Invoker("setDisplayFlags")
    void pvp$setDisplayFlags(byte flags);
}
