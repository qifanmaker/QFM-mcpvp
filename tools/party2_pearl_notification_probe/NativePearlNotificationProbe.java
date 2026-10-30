package com.example.pvp.validation.notification;

import com.example.pvp.match.MinecraftPartySession;
import com.example.pvp.match.PortedGameEvents;
import com.example.pvp.validation.notification.mixin.PearlCollisionInvoker;
import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

/** Snapshot-only native collision/return controls; no replacement production notification. */
public final class NativePearlNotificationProbe implements ModInitializer {
    private static final Gson JSON=new Gson();
    private static final List<Map<String,Object>> ROUTES=new ArrayList<>();
    private static final Map<UUID,MinecraftPartySession> SESSIONS=new LinkedHashMap<>();
    private static String mode="none";
    private static UUID owner;
    private static int moduleCallbacks;
    public static boolean cancel(ServerPlayerEntity p){return p.getUuid().equals(owner)&&(mode.equals("null")||mode.equals("spoof"));}
    public static boolean spoof(){return mode.equals("spoof");}
    public static void afterDamage(Entity target){if(target instanceof ServerPlayerEntity p&&p.getUuid().equals(owner)&&mode.equals("tailshift"))p.setPosition(p.getPos().add(.5,0,0));}
    public static void moduleCallback(){moduleCallbacks++;}
    public static void routed(String layer,EnderPearlEntity pearl,ServerPlayerEntity p,boolean accepted){
        Map<String,Object> row=new LinkedHashMap<>();row.put("layer",layer);row.put("accepted",accepted);row.put("owner",p.getName().getString());row.put("pearl",pearl.getUuid().toString());row.put("tick",p.getServer().getTicks());ROUTES.add(row);
        if(layer.equals("session")&&PortedGameEvents.matchOf(p)!=null&&PortedGameEvents.matchOf(p).portedSession()instanceof MinecraftPartySession s)SESSIONS.put(p.getUuid(),s);
    }
    @Override public void onInitialize(){CommandRegistrationCallback.EVENT.register((dispatcher,registries,environment)->{
        var command=literal("p2notify").requires(s->s.hasPermissionLevel(4));
        for(String test:List.of("ordinary","zero","null","spoof","tailshift","crossworld","oldsession"))command.then(literal(test).then(argument("player",EntityArgumentType.player()).executes(c->run(EntityArgumentType.getPlayer(c,"player"),test))));
        dispatcher.register(command);
    });}
    private static List<Double> vector(Vec3d p){return List.of(p.x,p.y,p.z);}
    private static int run(ServerPlayerEntity player,String test){
        ROUTES.clear();moduleCallbacks=0;mode=test;owner=player.getUuid();Vec3d before=player.getPos();ServerWorld beforeWorld=player.getServerWorld();
        Map<String,Object> row=new LinkedHashMap<>();row.put("case",test);row.put("owner",player.getName().getString());row.put("tick",player.getServer().getTicks());row.put("before",vector(before));row.put("beforeWorld",beforeWorld.getRegistryKey().getValue().toString());row.put("fixture","Invoke real native EnderPearlEntity.onCollision; null/spoof/tailshift are explicitly controlled test-only negative fixtures, not normal gameplay.");
        try{
            ServerWorld world=test.equals("crossworld")?player.getServer().getOverworld():beforeWorld;
            EnderPearlEntity pearl=new EnderPearlEntity(world,player);Vec3d target=test.equals("zero")?before:before.add(.25,.125,0);pearl.setPosition(target);pearl.setNoGravity(true);
            if(test.equals("oldsession")){
                MinecraftPartySession session=SESSIONS.get(player.getUuid());row.put("hasRememberedSession",session!=null);row.put("directRouterAccepted",session!=null&&session.onPearlLanded(pearl,player));
            }else{
                world.spawnEntity(pearl);((PearlCollisionInvoker)pearl).p2notify$collide(new BlockHitResult(target,Direction.UP,BlockPos.ofFloored(target).down(),false));
            }
            row.put("target",vector(target));row.put("targetWorld",world.getRegistryKey().getValue().toString());row.put("after",vector(player.getPos()));row.put("afterWorld",player.getServerWorld().getRegistryKey().getValue().toString());row.put("removed",pearl.isRemoved());row.put("nativeOwnerDisplaced",before.squaredDistanceTo(player.getPos())>1.0E-9);row.put("ownerAtNativeTarget",player.getServerWorld()==world&&player.getPos().squaredDistanceTo(target)<1.0E-9);row.put("moduleCallbacks",moduleCallbacks);row.put("routes",new ArrayList<>(ROUTES));pearl.discard();
            System.out.println("P2_NOTIFY_RESULT "+JSON.toJson(row));return 1;
        }catch(Throwable error){row.put("error",error.toString());System.out.println("P2_NOTIFY_RESULT "+JSON.toJson(row));throw new RuntimeException(error);}
        finally{mode="none";owner=null;}
    }
}
