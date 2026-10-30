package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.match.PortedGameEvents;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.EnderPearlItem;
import net.minecraft.item.Item;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * PvP 竞技场内末影珍珠无冷却；移植玩法及竞技场外保留原版 1 秒冷却。
 * 练习模式下：由 PvPMod 的 UseItemCallback 限制投掷次数与冷却。
 */
@Mixin(EnderPearlItem.class)
public abstract class EnderPearlItemMixin {
    @Redirect(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/ItemCooldownManager;set(Lnet/minecraft/item/Item;I)V"))
    private void pvp$noPearlCooldownInArena(ItemCooldownManager manager, Item item, int ticks,
                                            World world, PlayerEntity user, Hand hand) {
        if (world.getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY && !PortedGameEvents.usesNativeItemRules(user)) {
            return; // 竞技场内不设置冷却
        }
        manager.set(item, ticks);
    }
}
