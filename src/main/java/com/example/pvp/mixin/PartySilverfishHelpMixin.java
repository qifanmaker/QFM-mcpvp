package com.example.pvp.mixin;

import com.example.pvp.arena.party.PartySourceMobs;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.entity.mob.SilverfishEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** 原图关闭破坏时蠹虫会将虫蚀石还原，而不是打破方块并释放更多生物；不更改世界规则。 */
@Mixin(targets = "net.minecraft.entity.mob.SilverfishEntity$CallForHelpGoal")
public abstract class PartySilverfishHelpMixin {
    @Shadow @Final private SilverfishEntity silverfish;

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/GameRules;getBoolean(Lnet/minecraft/world/GameRules$Key;)Z"))
    private boolean pvp$sourceMobGriefing(boolean original) {
        return original && !PartySourceMobs.isSourceMob(this.silverfish);
    }
}
