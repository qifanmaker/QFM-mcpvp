package com.example.pvp.arena;

import com.example.pvp.mixin.MinecraftServerAccess;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.Util;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 运行时创建的竞技场世界：虚空地形，永不保存，每次服务器启动全新生成。
 */
public class ArenaWorld extends ServerWorld {

    public ArenaWorld(MinecraftServer server, RegistryKey<World> worldKey, ChunkGenerator generator) {
        super(
                server,
                Util.getMainWorkerExecutor(),
                ((MinecraftServerAccess) server).getSession(),
                new ArenaWorldProperties(server.getSaveProperties()),
                worldKey,
                new DimensionOptions(dimTypeEntry(server), generator),
                VoidWorldProgressListener.INSTANCE,
                false,
                BiomeAccess.hashSeed(0L),
                List.of(),
                false,
                null
        );
    }

    private static RegistryEntry<DimensionType> dimTypeEntry(MinecraftServer server) {
        return server.getRegistryManager()
                .get(RegistryKeys.DIMENSION_TYPE)
                .getEntry(DimensionTypes.OVERWORLD)
                .orElseThrow();
    }

    @Override
    public void save(@Nullable ProgressListener progressListener, boolean flush, boolean enabled) {
        // 竞技场世界从不写入磁盘
    }

    @Override
    public boolean isFlat() {
        return true;
    }
}
