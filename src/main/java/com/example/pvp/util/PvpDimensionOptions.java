package com.example.pvp.util;

import net.minecraft.world.dimension.DimensionOptions;

import java.util.function.Predicate;

/**
 * 通过 Mixin 附加到 {@link DimensionOptions} 上的运行时标志，
 * 用于标记竞技场维度是否应被写入存档（level.dat / 区块文件）。
 * 参考 NucleoidMC/fantasy 的实现。
 */
public interface PvpDimensionOptions {
    Predicate<DimensionOptions> SAVE_PREDICATE = e -> ((PvpDimensionOptions) (Object) e).pvp$getSave();
    Predicate<DimensionOptions> SAVE_PROPERTIES_PREDICATE = e -> ((PvpDimensionOptions) (Object) e).pvp$getSaveProperties();

    void pvp$setSave(boolean value);

    boolean pvp$getSave();

    void pvp$setSaveProperties(boolean value);

    boolean pvp$getSaveProperties();
}
