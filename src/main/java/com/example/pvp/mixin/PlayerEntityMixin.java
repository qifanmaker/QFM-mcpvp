package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchType;
import net.minecraft.entity.Entity;
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

    /** 1.8 模式：无攻击冷却——攻击进度始终为满，满伤害、满击退，支持疯狂点按。 */
    @Inject(method = "getAttackCooldownProgress", at = @At("HEAD"), cancellable = true)
    private void pvp$legacyNoCooldown(float basePeriod, CallbackInfoReturnable<Float> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        MatchManager matchManager = MatchManager.get();
        if (matchManager != null && self instanceof ServerPlayerEntity sp) {
            Match match = matchManager.getMatchFor(sp);
            if (match != null && match.getType() == MatchType.PVP_1_8) {
                cir.setReturnValue(1.0F);
            }
        }
    }

    /** 1.8 模式：攻击即解除格挡（block-hit 手感）。 */
    @Inject(method = "attack", at = @At("HEAD"))
    private void pvp$legacyClearBlocking(Entity target, CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        MatchManager matchManager = MatchManager.get();
        if (matchManager != null && self instanceof ServerPlayerEntity sp) {
            Match match = matchManager.getMatchFor(sp);
            if (match != null && match.getType() == MatchType.PVP_1_8) {
                match.setBlocking(sp, false);
            }
        }
    }
}
