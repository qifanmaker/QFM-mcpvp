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

    /** 1.8 战斗模式（1.8 经典PvP / 空岛战争）：无攻击冷却——攻击进度始终为满，满伤害、满击退。 */
    @Inject(method = "getAttackCooldownProgress", at = @At("HEAD"), cancellable = true)
    private void pvp$legacyNoCooldown(float basePeriod, CallbackInfoReturnable<Float> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        MatchManager matchManager = MatchManager.get();
        if (matchManager != null && self instanceof ServerPlayerEntity sp) {
            Match match = matchManager.getMatchFor(sp);
            if (matchManager.isLegacyCombat(match)) {
                cir.setReturnValue(1.0F);
            }
        }
    }

    /** 1.8 战斗模式：攻击即解除格挡（block-hit 手感）。 */
    @Inject(method = "attack", at = @At("HEAD"))
    private void pvp$legacyClearBlocking(Entity target, CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        MatchManager matchManager = MatchManager.get();
        if (matchManager != null && self instanceof ServerPlayerEntity sp) {
            Match match = matchManager.getMatchFor(sp);
            if (matchManager.isLegacyCombat(match)) {
                match.setBlocking(sp, false);
            }
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
        if (target instanceof LivingEntity victim && victim != self) {
            double dx = victim.getX() - self.getX(); // 指向目标（远离自己）
            double dz = victim.getZ() - self.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 0.01) {
                dx = 0.0;
                dz = 0.01;
                d = 0.01;
            }
            // 直接施加速度：takeKnockback 的方向约定相反（传"指向目标"会把人往攻击者方向拉），这里显式推离
            // 空岛战争击退球：水平 1.5 → 1.0、竖直 0.3 → 0.2（*2/3）；其他模式（幸运之柱随机掉落）保持原强度
            boolean skywars = match.getType() == MatchType.SKYWARS;
            double strength = skywars ? 1.0 : 2.0;
            double up = skywars ? 0.2 : 0.4;
            victim.setVelocity(
                    victim.getVelocity().x + dx / d * strength,
                    victim.getVelocity().y + up,
                    victim.getVelocity().z + dz / d * strength);
            victim.velocityDirty = true;
        }
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
