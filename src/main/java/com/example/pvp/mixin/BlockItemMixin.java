package com.example.pvp.mixin;

import com.example.pvp.match.Match;
import com.example.pvp.match.MatchState;
import com.example.pvp.match.PortedGameEvents;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 用原版计算后的真实放置位置保护移植场地；不改变现有连点器交互计数。 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void pvp$protectPortedMap(ItemPlacementContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (!(context.getPlayer() instanceof ServerPlayerEntity player)) return;
        if (!PortedGameEvents.canAccessPosition(player, context.getWorld(), context.getBlockPos())) {
            cir.setReturnValue(ActionResult.FAIL);
            return;
        }
        Match match = PortedGameEvents.matchOf(player);
        if (match != null && match.portedSession() != null
                && (match.getState() != MatchState.ACTIVE || match.isEliminated(player.getUuid())
                || !match.portedSession().canPlace(player, context.getBlockPos()))) {
            cir.setReturnValue(ActionResult.FAIL);
        }
    }

    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;",
            at = @At("RETURN"))
    private void pvp$recordPortedPlacement(ItemPlacementContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (!(context.getPlayer() instanceof ServerPlayerEntity player) || !cir.getReturnValue().isAccepted()) return;
        Match match = PortedGameEvents.matchOf(player);
        if (match != null && match.portedSession() != null
                && !context.getWorld().getBlockState(context.getBlockPos()).isAir()) {
            match.portedSession().onPlaced(player, context.getBlockPos());
        }
    }
}
