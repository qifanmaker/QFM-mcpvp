package com.example.pvp.mixin;

import com.example.pvp.arena.party.PartySourceMobs;
import com.example.pvp.match.MatchState;
import com.example.pvp.match.PortedGameEvents;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** 怪物射击的天然云只影响本场玩家和本场实体；不改变普通药水云的任何行为。 */
@Mixin(AreaEffectCloudEntity.class)
public abstract class PartyEffectCloudMixin {
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;getNonSpectatingEntities(Ljava/lang/Class;Lnet/minecraft/util/math/Box;)Ljava/util/List;"))
    private List<LivingEntity> pvp$onlySourceMatch(List<LivingEntity> original) {
        AreaEffectCloudEntity cloud = (AreaEffectCloudEntity) (Object) this;
        if (PartySourceMobs.cloudCreator(cloud) == null) return original;
        var match = PortedGameEvents.matchOf(cloud);
        if (match == null || match.getState() != MatchState.ACTIVE) return List.of();
        return original.stream().filter(entity -> PortedGameEvents.matchOf(entity) == match).toList();
    }
}
