package com.example.pvp.mixin.registry;

import com.example.pvp.util.PvpDimensionOptions;
import net.minecraft.world.dimension.DimensionOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(DimensionOptions.class)
public class DimensionOptionsMixin implements PvpDimensionOptions {
    @Unique
    private boolean pvp$save = true;
    @Unique
    private boolean pvp$saveProperties = true;

    @Override
    public void pvp$setSave(boolean value) {
        this.pvp$save = value;
    }

    @Override
    public boolean pvp$getSave() {
        return this.pvp$save;
    }

    @Override
    public void pvp$setSaveProperties(boolean value) {
        this.pvp$saveProperties = value;
    }

    @Override
    public boolean pvp$getSaveProperties() {
        return this.pvp$saveProperties;
    }
}
