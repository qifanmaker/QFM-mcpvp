package com.example.pvp.mixin;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaWorldManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 原包默认randomTickSpeed=0；需要生长的模块在自己的时序中调用有界原生采样。 */
@Mixin(ServerChunkManager.class)
public abstract class PartyRandomTickMixin {
    @WrapOperation(method = "tickChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;tickChunk(Lnet/minecraft/world/chunk/WorldChunk;I)V"))
    private void pvp$scopedRandomTickSpeed(ServerWorld world, WorldChunk chunk, int speed, Operation<Void> original) {
        boolean party = world.getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY && PvPMod.MATCH != null
                && PvPMod.MATCH.partySceneSessionAt(chunk.getPos().getCenterAtY(0)) != null;
        original.call(world, chunk, party ? 0 : speed);
    }
}
