package com.example.pvp.mixin;

import com.example.pvp.match.Match;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PvP 钓鱼竿：命中时把目标向远离施法者的方向击退，并取消原版"拉向自己"；
 * 仅在对局中生效，且幸运之柱例外——保留原版钓鱼拉回，不做命中击退。
 */
@Mixin(FishingBobberEntity.class)
public abstract class FishingBobberEntityMixin {
    /**
     * 低版本钓鱼竿：收杆完成原版处理后再施加击退。
     * 原版 1.21 会在收杆时执行 pullHookedEntity，过早在 updateHookedEntityId 修改速度
     * 可能被后续实体同步覆盖，因此击退必须放在收杆流程之后。
     */
    @Inject(method = "pullHookedEntity", at = @At("TAIL"))
    private void pvp$rodKnockback(Entity entity, CallbackInfo ci) {
        FishingBobberEntity bobber = (FishingBobberEntity) (Object) this;
        PlayerEntity owner = bobber.getPlayerOwner();
        if (!(owner instanceof ServerPlayerEntity caster)
                || !(entity instanceof LivingEntity victim)
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
        // 朝远离施法者的方向击退。takeKnockback 的方向参数是“推力来源”，
        // 所以传入远离施法者方向的反向，并由原版负责速度同步和抗击退。
        double dx = victim.getX() - caster.getX();
        double dz = victim.getZ() - caster.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.01) {
            return;
        }
        victim.takeKnockback(1.2, -dx / d, -dz / d);
        victim.addVelocity(0.0, 0.3, 0.0);
    }
}
