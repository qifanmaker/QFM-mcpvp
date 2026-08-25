package com.example.pvp.mixin.registry;

import com.example.pvp.util.PvpDimensionOptions;
import com.google.common.collect.Maps;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.level.WorldGenSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(WorldGenSettings.class)
public class WorldGenSettingsMixin {

    @ModifyArg(
            method = "encode(Lcom/mojang/serialization/DynamicOps;Lnet/minecraft/world/gen/GeneratorOptions;Lnet/minecraft/world/dimension/DimensionOptionsRegistryHolder;)Lcom/mojang/serialization/DataResult;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenSettings;<init>(Lnet/minecraft/world/gen/GeneratorOptions;Lnet/minecraft/world/dimension/DimensionOptionsRegistryHolder;)V"),
            index = 1
    )
    private static DimensionOptionsRegistryHolder pvp$wrapWorldGenSettings(DimensionOptionsRegistryHolder original) {
        var dimensions = original.dimensions();
        var saveDimensions = Maps.filterEntries(dimensions, entry -> PvpDimensionOptions.SAVE_PROPERTIES_PREDICATE.test(entry.getValue()));
        return new DimensionOptionsRegistryHolder(saveDimensions);
    }
}
