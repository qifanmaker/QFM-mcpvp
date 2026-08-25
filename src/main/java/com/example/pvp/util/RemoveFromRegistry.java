package com.example.pvp.util;

import net.minecraft.registry.SimpleRegistry;
import net.minecraft.util.Identifier;

/**
 * 通过 Mixin 附加到 {@link SimpleRegistry} 上的运行时注册表操作接口，
 * 用于解冻/冻结注册表以及在运行时移除条目。
 * 参考 NucleoidMC/fantasy 的实现。
 */
public interface RemoveFromRegistry<T> {
    @SuppressWarnings("unchecked")
    static <T> boolean remove(SimpleRegistry<T> registry, Identifier key) {
        return ((RemoveFromRegistry<T>) registry).pvp$remove(key);
    }

    @SuppressWarnings("unchecked")
    static <T> boolean remove(SimpleRegistry<T> registry, T value) {
        return ((RemoveFromRegistry<T>) registry).pvp$remove(value);
    }

    boolean pvp$remove(T value);

    boolean pvp$remove(Identifier key);

    void pvp$setFrozen(boolean value);

    boolean pvp$isFrozen();
}
