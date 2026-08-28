package com.example.pvp.mixin;

import com.example.pvp.PvPMod;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchState;
import com.example.pvp.match.MatchType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TNT 跑酷：右键羽毛触发跳跃的兜底——直挂 Item.use（服务端处理物品使用的必经路径），
 * 确保无论 UseItemCallback 事件是否派发，只要物品被使用就触发。
 */
@Mixin(Item.class)
public abstract class ItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void pvp$featherJump(World world, PlayerEntity user, Hand hand,
                                 CallbackInfoReturnable<TypedActionResult<ItemStack>> cir) {
        Item self = (Item) (Object) this;
        if (self != Items.FEATHER || !(user instanceof ServerPlayerEntity sp)) {
            return;
        }
        MatchManager manager = PvPMod.MATCH;
        if (manager == null || manager.isEliminated(sp.getUuid())) {
            return;
        }
        Match match = manager.getMatchFor(sp);
        if (match != null && match.getType() == MatchType.TNT_RUN && match.getState() == MatchState.ACTIVE) {
            match.tntRunFeatherJump(sp);
            cir.setReturnValue(TypedActionResult.success(sp.getStackInHand(hand)));
        }
    }
}
