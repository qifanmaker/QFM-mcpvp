package com.example.pvp.mixin;

import com.example.pvp.mygo.MyGoCommands;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实服测试发现和平难度仍会在 HungerManager 之外回血，导致虚拟 HP 对应心数每秒闪跳。
 * MyGO 的 HP 完全由会话管理，因此同时拦截原版 heal；其他模式保留原有治疗行为。
 */
@Mixin(LivingEntity.class)
public abstract class MyGoHealthMixin {
    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    private void pvp$myGoNoHealing(float amount, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayerEntity player && MyGoCommands.weaponSession(player) != null) {
            ci.cancel();
        }
    }
}
