package com.example.pvp.mixin;

import net.minecraft.entity.TntEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 主世界（主城）TNT 爆炸不破坏方块、也不误伤玩家——改成无害的爆炸（保留声音/粒子）。
 * 竞技场内的 TNT（可抛/炸岛）保持原版破坏。
 */
@Mixin(TntEntity.class)
public abstract class TntEntityMixin {
    @ModifyArg(
            method = "explode",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;createExplosion("
                    + "Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/damage/DamageSource;"
                    + "Lnet/minecraft/world/explosion/ExplosionBehavior;DDDFZ"
                    + "Lnet/minecraft/world/World$ExplosionSourceType;)"
                    + "Lnet/minecraft/world/explosion/Explosion;"),
            index = 8
    )
    private World.ExplosionSourceType pvp$noTntGriefInOverworld(World.ExplosionSourceType sourceType) {
        TntEntity tnt = (TntEntity) (Object) this;
        if (tnt.getWorld().getRegistryKey() == World.OVERWORLD) {
            return World.ExplosionSourceType.NONE; // 主城 TNT 无害爆炸，不破坏方块
        }
        return sourceType;
    }
}
