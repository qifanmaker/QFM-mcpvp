package com.example.pvp.mixin;

import com.example.pvp.match.MinecraftPartySession;
import com.example.pvp.match.PortedGameEvents;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tedious Tasks 要求亲自合成木斧，源规则 doLimitedCrafting=false。
 * 只对声明此规则的本场存活玩家忽略未发现配方限制，避免修改同服世界规则或提前赠送配方。
 * 仍使用原生配方匹配、材料消耗、产物与配方解锁，其他玩家沿用原有判断。
 */
@Mixin(CraftingScreenHandler.class)
public abstract class PartyCraftingMixin {
    @Redirect(method = "updateResult", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/inventory/CraftingResultInventory;shouldCraftRecipe(Lnet/minecraft/world/World;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/recipe/RecipeEntry;)Z"))
    private static boolean pvp$scopedRecipe(CraftingResultInventory result, World world,
                                           ServerPlayerEntity player, RecipeEntry<?> recipe) {
        var match = PortedGameEvents.matchOf(player);
        if (match != null && match.portedSession() instanceof MinecraftPartySession session
                && session.allowsUndiscoveredRecipes(player)) {
            result.setLastRecipe(recipe);
            return true;
        }
        return result.shouldCraftRecipe(world, player, recipe);
    }
}
