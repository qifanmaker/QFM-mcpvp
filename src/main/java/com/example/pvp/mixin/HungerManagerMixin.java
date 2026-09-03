package com.example.pvp.mixin;

import com.example.pvp.PvPMod;
import com.example.pvp.match.Match;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 起床战争禁用自然生命回复：跳过 HungerManager.update（含满饥饿回血与挨饿扣血）。
 * 饥饿值由对局内的饱和效果维持满格，不会因跳过 update 而挨饿。
 */
@Mixin(HungerManager.class)
public abstract class HungerManagerMixin {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void pvp$bedwarsNoNaturalRegen(PlayerEntity player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayerEntity sp) || PvPMod.MATCH == null) {
            return;
        }
        Match match = PvPMod.MATCH.getMatchFor(sp);
        if (match != null && match.getType().isBedWars()) {
            ci.cancel();
        }
    }
}
