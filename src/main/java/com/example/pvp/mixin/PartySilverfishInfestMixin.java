package com.example.pvp.mixin;

import com.example.pvp.arena.party.PartySourceMobs;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.entity.ai.goal.WanderAroundGoal;
import net.minecraft.entity.mob.PathAwareEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 原图 mobGriefing=false：保留打靶蠹虫的原生游走与攻击，只关闭它的钻石头行为。 */
@Mixin(targets = "net.minecraft.entity.mob.SilverfishEntity$WanderAndInfestGoal")
public abstract class PartySilverfishInfestMixin extends WanderAroundGoal {
    protected PartySilverfishInfestMixin(PathAwareEntity mob, double speed, int chance) { super(mob, speed, chance); }

    @ModifyExpressionValue(method = "canStart", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/GameRules;getBoolean(Lnet/minecraft/world/GameRules$Key;)Z"))
    private boolean pvp$sourceMobGriefing(boolean original) {
        return original && !PartySourceMobs.isSourceMob(this.mob);
    }
}
