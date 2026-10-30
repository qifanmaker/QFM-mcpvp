package com.example.pvp.validation;

import com.example.pvp.arena.party.PartyRoundContext;
import com.example.pvp.arena.party.PearlHurlRound;
import net.minecraft.util.math.Vec3d;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Snapshot-only phase logging; explicit phaseNotify opt-in invokes the production landing callback for provisional tests. */
public final class PearlPhaseProbe implements ModInitializer {
    private static final Map<UUID,Integer> COLLISIONS=new HashMap<>();
    private static final Map<UUID,PartyRoundContext> CONTEXTS=new HashMap<>();
    private static final Map<UUID,PearlHurlRound> ROUNDS=new HashMap<>();
    @Override public void onInitialize(){ServerEntityEvents.ENTITY_LOAD.register((e,w)->{if(e instanceof EnderPearlEntity p&&p.getOwner()instanceof ServerPlayerEntity owner&&owned(owner))record("spawn",p,owner,-1);});}
    private static boolean owned(ServerPlayerEntity p){return p.getName().getString().startsWith("MyGoCheck")&&p.getServerWorld().getRegistryKey().getValue().toString().equals("pvp:arena");}
    public static void collided(EnderPearlEntity pearl,Vec3d before){if(pearl.getOwner()instanceof ServerPlayerEntity p&&owned(p)){COLLISIONS.put(p.getUuid(),p.getServer().getTicks());record("collision_tail",pearl,p,-1);
        // Optional test-only adapter supplies the real native event while the
        // parent-owned shared router is awaiting integration. It invokes only
        // the production callback, after a verified real owner displacement.
        if(Boolean.getBoolean("party.pearl.phaseNotify")&&before!=null&&before.squaredDistanceTo(p.getPos())>1.0E-9){PartyRoundContext c=CONTEXTS.get(p.getUuid());PearlHurlRound r=ROUNDS.get(p.getUuid());if(c!=null&&r!=null&&!c.isOut(p.getUuid())){r.pearlLanded(c,p);record("native_notification_adapter",pearl,p,-1);}}
    }}
    public static void round(String phase,PearlHurlRound module,PartyRoundContext c,int elapsed){for(ServerPlayerEntity p:c.players()){CONTEXTS.put(p.getUuid(),c);ROUNDS.put(p.getUuid(),module);Integer at=COLLISIONS.get(p.getUuid());if(at!=null&&p.getServer().getTicks()-at<=2)record(phase,null,p,elapsed);}}
    private static void record(String phase,EnderPearlEntity pearl,ServerPlayerEntity p,int elapsed){
        JsonObject row=new JsonObject();row.addProperty("phase",phase);row.addProperty("serverTick",p.getServer().getTicks());row.addProperty("elapsed",elapsed);row.addProperty("owner",p.getName().getString());row.addProperty("ownerPos",p.getPos().toString());row.addProperty("feet",p.getServerWorld().getBlockState(p.getBlockPos()).toString());
        if(pearl!=null){row.addProperty("pearl",pearl.getUuid().toString());row.addProperty("pearlPos",pearl.getPos().toString());row.addProperty("motion",pearl.getVelocity().toString());row.addProperty("removed",pearl.isRemoved());}
        System.out.println("P2_PEARL_PHASE "+row);
    }
}
