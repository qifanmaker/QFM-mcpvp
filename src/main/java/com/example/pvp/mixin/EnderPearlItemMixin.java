package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorldManager;
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
 * 竞技场内末影珍珠无冷却（跳过原版 1 秒冷却的设置），竞技场外保持原版。
 */
@Mixin(EnderPearlItem.class)
public abstract class EnderPearlItemMixin {
    @Redirect(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/ItemCooldownManager;set(Lnet/minecraft/item/Item;I)V"))
    private void pvp$noPearlCooldownInArena(ItemCooldownManager manager, Item item, int ticks,
                                            World world, PlayerEntity user, Hand hand) {
        if (world.getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY) {
            return; // 竞技场内不设置冷却
        }
        manager.set(item, ticks);
    }
}
