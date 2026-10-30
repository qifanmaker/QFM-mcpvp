package com.example.pvp.mixin;

import com.example.pvp.arena.party.PartySourceMobs;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 在 ENTITY_LOAD 之前记录天然云的实际创建者，避免猜最近目标或更改原本为空的 Owner。 */
@Mixin(CreeperEntity.class)
public abstract class PartyCreeperCloudMixin {
    @Redirect(method = "spawnEffectsCloud", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;spawnEntity(Lnet/minecraft/entity/Entity;)Z"))
    private boolean pvp$registerSourceCloud(World world, Entity entity) {
        CreeperEntity creator = (CreeperEntity) (Object) this;
        if (entity instanceof AreaEffectCloudEntity cloud && PartySourceMobs.isSourceMob(creator)) {
            PartySourceMobs.createdCloud(cloud, creator);
        }
        return world.spawnEntity(entity);
    }
}
