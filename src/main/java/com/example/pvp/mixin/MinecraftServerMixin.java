package com.example.pvp.mixin;

import com.example.pvp.util.SafeIterator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.Iterator;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Redirect(
            method = "tickWorlds",
            at = @At(value = "INVOKE", target = "Ljava/lang/Iterable;iterator()Ljava/util/Iterator;", ordinal = 0),
            require = 0
    )
    private Iterator<ServerWorld> pvp$copyBeforeTicking(Iterable<ServerWorld> instance) {
        return new SafeIterator<>((Collection<ServerWorld>) instance);
    }
}
