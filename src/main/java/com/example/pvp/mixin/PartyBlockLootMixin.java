package com.example.pvp.mixin;

import com.example.pvp.match.PortedGameEvents;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.block.Block;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Supplier;

/**
 * 源图默认禁用方块掉落，Tedious Tasks 等采集回合单独开启。
 * 在原生掉落实例生成处覆盖开关，保留真实采集、掉落位置、速度、统计与经验计算；
 * 其他区域仍调用原实现，不修改共享世界的 doTileDrops。
 */
@Mixin(Block.class)
public abstract class PartyBlockLootMixin {
    @WrapOperation(method = {
            "dropStack(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/item/ItemStack;)V",
            "dropStack(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;Lnet/minecraft/item/ItemStack;)V"
    }, at = @At(value = "INVOKE", target = "Lnet/minecraft/block/Block;dropStack(Lnet/minecraft/world/World;Ljava/util/function/Supplier;Lnet/minecraft/item/ItemStack;)V"))
    private static void pvp$scopedBlockDrops(World world, Supplier<ItemEntity> supplier, ItemStack stack,
                                            Operation<Void> original, @Local(argsOnly = true) BlockPos pos) {
        Boolean allowed = PortedGameEvents.blockDropOverride(world, pos);
        if (allowed == null) {
            original.call(world, supplier, stack);
        } else if (allowed && !world.isClient && !stack.isEmpty()) {
            ItemEntity entity = supplier.get();
            entity.setToDefaultPickupDelay();
            world.spawnEntity(entity);
        }
    }

    @Redirect(method = "dropExperience", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/GameRules;getBoolean(Lnet/minecraft/world/GameRules$Key;)Z"))
    private boolean pvp$scopedBlockExperience(GameRules rules, GameRules.Key<GameRules.BooleanRule> key,
                                              ServerWorld world, BlockPos pos, int size) {
        Boolean allowed = PortedGameEvents.blockDropOverride(world, pos);
        return allowed == null ? rules.getBoolean(key) : allowed;
    }
}
