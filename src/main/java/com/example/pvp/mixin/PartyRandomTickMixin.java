package com.example.pvp.mixin;

import com.example.pvp.match.PortedGameEvents;
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
        // Warm/idle resident maps have no Match, but their saved crops and
        // decorative terrain must remain unchanged until a round leases them.
        boolean party = world instanceof com.example.pvp.arena.ArenaWorld arena
                && arena.residentMaps().intersectsChunk(chunk.getPos().x, chunk.getPos().z)
                || PortedGameEvents.isPartyScenePosition(world, chunk.getPos().getCenterAtY(0));
        original.call(world, chunk, party ? 0 : speed);
    }
}
