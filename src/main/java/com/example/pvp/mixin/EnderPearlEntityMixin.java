package com.example.pvp.mixin;

import com.example.pvp.match.PortedGameEvents;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.TeleportTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 末影珍珠落点伤害减半，保留其它来源的摔落伤害。 */
@Mixin(EnderPearlEntity.class)
public abstract class EnderPearlEntityMixin {
    @Unique private ServerPlayerEntity pvp$landedOwner;
    @Unique private TeleportTarget pvp$landingTarget;

    @Inject(method = "onCollision", at = @At("HEAD"))
    private void pvp$beginNativeLanding(HitResult hit, CallbackInfo ci) {
        this.pvp$landedOwner = null;
        this.pvp$landingTarget = null;
    }

    // A changed position is not proof of success and would miss successful zero-distance throws.
    // Observe the real return value, leave the target/physics untouched and call vanilla exactly once.
    @WrapOperation(method = "onCollision", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/entity/Entity;teleportTo(Lnet/minecraft/world/TeleportTarget;)Lnet/minecraft/entity/Entity;"))
    private Entity pvp$observeNativeLanding(Entity owner, TeleportTarget target, Operation<Entity> original) {
        Entity result = original.call(owner, target);
        EnderPearlEntity pearl = (EnderPearlEntity) (Object) this;
        if (result == owner && owner == pearl.getOwner() && result instanceof ServerPlayerEntity player
                && player.isAlive() && player.networkHandler.isConnectionOpen()
                && player.getWorld() == target.world() && target.world() == pearl.getWorld()
                && player.getPos().squaredDistanceTo(target.pos()) < 1.0E-9) {
            this.pvp$landedOwner = player;
            this.pvp$landingTarget = target;
        }
        return result;
    }

    @Inject(method = "onCollision", at = @At("TAIL"))
    private void pvp$finishNativeLanding(HitResult hit, CallbackInfo ci) {
        ServerPlayerEntity player = this.pvp$landedOwner;
        TeleportTarget target = this.pvp$landingTarget;
        this.pvp$landedOwner = null;
        this.pvp$landingTarget = null;
        EnderPearlEntity pearl = (EnderPearlEntity) (Object) this;
        if (player != null && target != null && player == pearl.getOwner() && player.isAlive()
                && player.networkHandler.isConnectionOpen() && player.getWorld() == target.world()
                && target.world() == pearl.getWorld() && player.getPos().squaredDistanceTo(target.pos()) < 1.0E-9) {
            PortedGameEvents.pearlLanded(pearl, player);
        }
    }

    // 1.21.1 的 onCollision 以 Entity 类型保存 owner，字节码因此调用 Entity.damage；
    // 旧 LivingEntity 目标匹配不到调用点，会让服务端启动时注入失败。
    @ModifyArg(
            method = "onCollision",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;damage(Lnet/minecraft/entity/damage/DamageSource;F)Z"),
            index = 1)
    private float pvp$halvePearlFallDamage(float amount) {
        // Tedious Tasks 的珍珠任务保留原版 5 点伤害；不改变普通 PvP 的既有减伤。
        EnderPearlEntity pearl = (EnderPearlEntity) (Object) this;
        if (PortedGameEvents.usesNativeItemRules(pearl.getOwner()) || PortedGameEvents.usesNativeItemRules(pearl)) return amount;
        return amount * 0.5F;
    }
}
