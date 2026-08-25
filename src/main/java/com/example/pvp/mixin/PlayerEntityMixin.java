package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorldManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 允许在竞技场内（冒险模式下）放置岩浆/水桶：
 * 冒险模式会通过 {@link PlayerEntity#canPlaceOn} 检查物品 can_place_on 标签，
 * 普通水桶没有该标签因此无法放置。竞技场内对岩浆/水桶放行。
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "canPlaceOn(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;Lnet/minecraft/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void pvp$allowBucketsInArena(BlockPos pos, Direction side, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (self.getWorld().getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY
                && (stack.isOf(Items.LAVA_BUCKET) || stack.isOf(Items.WATER_BUCKET))) {
            cir.setReturnValue(true);
        }
    }
}
