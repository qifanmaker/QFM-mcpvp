package com.example.pvp.mixin;

import com.example.pvp.PvPMod;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchState;
import com.example.pvp.match.MatchType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 1.8 模式：剑格挡时受到的伤害减半（模拟 1.8.9 的剑格挡）。
 * 幸运之柱"一击必杀"事件：开启时对应 ACTIVE 幸运之柱对局的玩家所有伤害直接致死。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @ModifyArg(
            method = "damage",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;applyDamage(Lnet/minecraft/entity/damage/DamageSource;F)V"),
            index = 1
    )
    private float pvp$blockDamageReduction(float amount) {
        LivingEntity self = (LivingEntity) (Object) this;
        // 幸运之柱一击必杀：限定在 ACTIVE 幸运之柱对局内生效，避免误伤其他同时进行中的比赛
        if (PvPMod.oneHitKillActive && self instanceof ServerPlayerEntity player) {
            Match match = PvPMod.MATCH == null ? null : PvPMod.MATCH.getMatchFor(player);
            if (match != null && match.getType() == MatchType.LUCKY_PILLAR && match.getState() == MatchState.ACTIVE) {
                return 9999.0F;
            }
        }
        if (amount > 0 && self instanceof ServerPlayerEntity player) {
            MatchManager matchManager = MatchManager.get();
            if (matchManager != null && matchManager.isLegacyBlocking(player)) {
                return amount * 0.5F;
            }
        }
        return amount;
    }
}
