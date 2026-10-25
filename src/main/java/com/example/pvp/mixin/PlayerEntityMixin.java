package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

/**
 * 允许在竞技场内（冒险模式下）放置/舀取岩浆与水：
 * 冒险模式会通过 {@link PlayerEntity#canPlaceOn} 检查物品 can_place_on 标签，
 * 普通水桶/空桶没有该标签因此无法放置或舀取。竞技场内对岩浆桶、水桶、空桶放行。
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "canPlaceOn(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;Lnet/minecraft/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void pvp$allowBucketsInArena(BlockPos pos, Direction side, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (self.getWorld().getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY
                && (stack.isOf(Items.LAVA_BUCKET) || stack.isOf(Items.WATER_BUCKET) || stack.isOf(Items.BUCKET))) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 1.8 手感：无攻击冷却——攻击进度始终为满，满伤害、满击退。
     * 判定见 {@link MatchManager#usesLegacyCombat}：1.8 模式，或用的是「1.8 经典」套件。
     */
    @Inject(method = "getAttackCooldownProgress", at = @At("HEAD"), cancellable = true)
    private void pvp$legacyNoCooldown(float basePeriod, CallbackInfoReturnable<Float> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        MatchManager matchManager = MatchManager.get();
        if (matchManager != null && self instanceof ServerPlayerEntity sp
                && matchManager.usesLegacyCombat(sp)) {
            cir.setReturnValue(1.0F);
        }
    }

    /** 空岛无冷却必须配套旧版斧基础伤害，否则现代斧的慢攻速代价被完全抹掉。 */
    @ModifyExpressionValue(method = "attack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/PlayerEntity;getAttributeValue(Lnet/minecraft/registry/entry/RegistryEntry;)D", ordinal = 0))
    private double pvp$skywarsWeaponDamage(double original) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        MatchManager manager = MatchManager.get();
        if (self instanceof ServerPlayerEntity player && manager != null) {
            Match match = manager.getMatchFor(player);
            if (match != null && match.getType() == MatchType.SKYWARS) {
                return com.example.pvp.match.SkyWarsCombat.attackDamage(player, original);
            }
        }
        return original;
    }

    /** 记录攻击尝试，供连点器检测使用。 */
    @Inject(method = "attack", at = @At("HEAD"))
    private void pvp$recordAttack(Entity target, CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (self instanceof ServerPlayerEntity sp) {
            com.example.pvp.PvPMod.onAttackAttempt(sp);
        }
    }

    /** 粘液球（空岛战争/幸运之柱击退 III 武器）：左键攻击命中时把目标强力击退。 */
    @Inject(method = "attack", at = @At("HEAD"))
    private void pvp$slimeBallKnockback(Entity target, CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (!(self instanceof ServerPlayerEntity sp)) {
            return;
        }
        if (!self.getMainHandStack().isOf(Items.SLIME_BALL)) {
            return; // 只有手持粘液球攻击才有击退 III
        }
        MatchManager matchManager = MatchManager.get();
        if (matchManager == null) {
            return;
        }
        Match match = matchManager.getMatchFor(sp.getUuid());
        if (match == null) {
            return;
        }
        if (match.getType() == MatchType.SKYWARS) return; // 空岛在有效伤害之后处理，拒绝的高频攻击不额外击退。
        if (target instanceof LivingEntity victim && victim != self) {
            if (!com.example.pvp.match.PortedGameEvents.allowsExtraKnockback(sp, victim)) return;
            double dx = victim.getX() - self.getX(); // 指向目标（远离自己）
            double dz = victim.getZ() - self.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 0.01) {
                dx = 0.0;
                dz = 0.01;
                d = 0.01;
            }
            // 直接施加速度：takeKnockback 的方向约定相反（传"指向目标"会把人往攻击者方向拉），这里显式推离
            // 空岛战争击退球：水平 1.5 → 1.0(*2/3) → 0.6(*3/5)、竖直 0.3 → 0.2 → 0.12；其他模式（幸运之柱随机掉落）保持原强度
            boolean skywars = match.getType() == MatchType.SKYWARS;
            double strength = skywars ? 0.6 : 2.0;
            double up = skywars ? 0.12 : 0.4;
            victim.setVelocity(
                    victim.getVelocity().x + dx / d * strength,
                    victim.getVelocity().y + up,
                    victim.getVelocity().z + dz / d * strength);
            victim.velocityDirty = true;
        }
    }

    /** 空岛特殊球只在本次伤害被接受后施力，避免无敌帧内连点无限叠加额外击退。 */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "attack",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;damage(Lnet/minecraft/entity/damage/DamageSource;F)Z"))
    private boolean pvp$skywarsBallHit(Entity target, net.minecraft.entity.damage.DamageSource source, float damage,
                                      com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original) {
        boolean accepted = original.call(target, source, damage);
        if (!accepted || !((Object) this instanceof ServerPlayerEntity player)
                || !(target instanceof ServerPlayerEntity victim)
                || !com.example.pvp.match.SkyWarsItems.isKnockbackBall(player.getMainHandStack())) return accepted;
        MatchManager manager = MatchManager.get();
        Match match = manager == null ? null : manager.getMatchFor(player);
        if (match == null || match.getType() != MatchType.SKYWARS
                || match.getState() != com.example.pvp.match.MatchState.ACTIVE
                || match.isEliminated(player.getUuid()) || match.isEliminated(victim.getUuid())
                || manager.getMatchFor(victim) != match) return accepted;
        double dx = victim.getX() - player.getX();
        double dz = victim.getZ() - player.getZ();
        double length = Math.hypot(dx, dz);
        if (length > 0.001) {
            // 击退球：水平击退翻倍，垂直击退降为原来的三分之一；
            // original.call 已经成功造成伤害，因此保留原版受击动画与受击反馈。
            victim.addVelocity(dx / length * 1.2, 0.04, dz / length * 1.2);
            victim.velocityModified = true;
        }
        return accepted;
    }

    /**
     * 1.8 手感：关掉剑的<b>横扫攻击</b>（1.9+ 才有的群体伤害）。
     *
     * <p>横扫在原版里的判定是 {@code d < this.getMovementSpeed()}，而 {@code d} 恒为非负 ——
     * 让这个方法返回负值就能让条件恒假，且该调用在 {@code attack} 里只此一处、只用于这一条判断，
     * 改动范围严格限定在横扫分支，不影响伤害/击退/暴击。
     */
    @ModifyExpressionValue(method = "attack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/PlayerEntity;getMovementSpeed()F"))
    private float pvp$legacyNoSweep(float original) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        MatchManager matchManager = MatchManager.get();
        if (matchManager != null && self instanceof ServerPlayerEntity sp
                && matchManager.usesLegacyCombat(sp)) {
            return -1.0F;
        }
        return original;
    }

    /** 相扑 / 烫手山芋：跳过血量扣减（damage() 仍正常返回 true，击退照常生效，只不掉血）。 */
    @Inject(method = "applyDamage(Lnet/minecraft/entity/damage/DamageSource;F)V", at = @At("HEAD"), cancellable = true)
    private void pvp$noHealthLossForKnockbackOnly(net.minecraft.entity.damage.DamageSource source, float amount, CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        MatchManager matchManager = MatchManager.get();
        if (matchManager != null && self instanceof ServerPlayerEntity sp) {
            Match match = matchManager.getMatchFor(sp);
            if (match != null && (match.getType() == MatchType.SUMO || match.getType() == MatchType.HOT_POTATO)) {
                ci.cancel(); // 只掉血不掉血：相扑靠推出平台、烫手山芋靠山芋爆炸淘汰
            }
        }
    }
}
