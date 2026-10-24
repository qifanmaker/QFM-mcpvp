package com.example.pvp.mixin;

import com.example.pvp.match.Match;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * PvP 钓鱼竿：命中时把目标向远离施法者的方向击退，并取消原版"拉向自己"；
 * 仅在对局中生效，且幸运之柱例外——保留原版钓鱼拉回，不做命中击退。
 */
@Mixin(FishingBobberEntity.class)
public abstract class FishingBobberEntityMixin {
    @Unique private boolean pvp$knockbackHook;

    /**
     * 低版本钓鱼竿在鱼钩命中时击退；等待 pullHookedEntity 会拖到收杆才触发。
     * 零伤害命中不经过正常受伤同步，必须显式设置 velocityModified。
     */
    @Inject(method = "onEntityHit", at = @At("TAIL"))
    private void pvp$rodKnockback(EntityHitResult hit, CallbackInfo ci) {
        FishingBobberEntity bobber = (FishingBobberEntity) (Object) this;
        PlayerEntity owner = bobber.getPlayerOwner();
        if (!(owner instanceof ServerPlayerEntity caster)
                || !(hit.getEntity() instanceof LivingEntity victim)
                || victim == caster) {
            return;
        }
        MatchManager matchManager = MatchManager.get();
        if (matchManager == null) {
            return;
        }
        Match match = matchManager.getMatchFor(caster.getUuid());
        if (match == null || match.getType() == MatchType.LUCKY_PILLAR) {
            return; // 幸运之柱：保留原版钓鱼拉回，不做命中击退
        }
        if (!com.example.pvp.match.PortedGameEvents.allowsExtraKnockback(caster, victim)) return;
        // 朝远离施法者的方向击退。takeKnockback 的方向参数是“推力来源”，
        // 所以传入远离施法者方向的反向，并由原版处理抗击退。
        double dx = victim.getX() - caster.getX();
        double dz = victim.getZ() - caster.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.01) {
            return;
        }
        // 与雪球保持同档，避免命中一次就把平地目标推开数格。
        victim.takeKnockback(0.18, -dx / d, -dz / d);
        victim.addVelocity(0.0, 0.3, 0.0);
        victim.velocityModified = true;
        this.pvp$knockbackHook = true;
    }

    /**
     * 命中击退后收杆只回收鱼钩，保留原版钩中实体的 5 点耐久消耗。
     * 必须跳过整个拉取分支：原版状态包 31 还会让未装模组的客户端自行执行拉回。
     */
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void pvp$retractWithoutPull(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        FishingBobberEntity bobber = (FishingBobberEntity) (Object) this;
        if (!bobber.getWorld().isClient && this.pvp$knockbackHook) {
            bobber.discard();
            cir.setReturnValue(5);
        }
    }
}
