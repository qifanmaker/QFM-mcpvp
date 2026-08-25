package com.example.pvp.mixin.registry;

import com.example.pvp.util.RemoveFromRegistry;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import net.minecraft.registry.MutableRegistry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.SimpleRegistry;
import net.minecraft.registry.entry.RegistryEntryInfo;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

@Mixin(SimpleRegistry.class)
public abstract class SimpleRegistryMixin<T> implements RemoveFromRegistry<T>, MutableRegistry<T> {
    @Unique
    private static final Logger pvp$LOGGER = LogUtils.getLogger();

    @Shadow
    @Final
    private Map<T, RegistryEntry.Reference<T>> valueToEntry;
    @Shadow
    @Final
    private Map<Identifier, RegistryEntry.Reference<T>> idToEntry;
    @Shadow
    @Final
    private Map<RegistryKey<T>, RegistryEntry.Reference<T>> keyToEntry;
    @Shadow
    @Final
    private Map<RegistryKey<T>, RegistryEntryInfo> keyToEntryInfo;
    @Shadow
    @Final
    private ObjectList<RegistryEntry.Reference<T>> rawIdToEntry;
    @Shadow
    @Final
    private Reference2IntMap<T> entryToRawId;
    @Shadow
    private boolean frozen;

    @Override
    public boolean pvp$remove(T entry) {
        RegistryEntry.Reference<T> registryEntry = this.valueToEntry.get(entry);
        int rawId = this.entryToRawId.removeInt(entry);
        if (rawId == -1) {
            return false;
        }

        try {
            this.keyToEntry.remove(registryEntry.registryKey());
            this.idToEntry.remove(registryEntry.registryKey().getValue());
            this.valueToEntry.remove(entry);
            this.rawIdToEntry.set(rawId, null);
            this.keyToEntryInfo.remove(registryEntry.registryKey());

            return true;
        } catch (Throwable e) {
            pvp$LOGGER.error("Could not remove entry", e);
            return false;
        }
    }

    @Override
    public boolean pvp$remove(Identifier key) {
        RegistryEntry.Reference<T> entry = this.idToEntry.get(key);
        return entry != null && entry.hasKeyAndValue() && this.pvp$remove(entry.value());
    }

    @Override
    public void pvp$setFrozen(boolean value) {
        this.frozen = value;
    }

    @Override
    public boolean pvp$isFrozen() {
        return this.frozen;
    }
}
