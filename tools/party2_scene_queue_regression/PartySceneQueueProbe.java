package com.example.pvp.validation;

import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.MapBuildQueue;
import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Disposable native integration fixture; never compiled into shipped output. */
public final class PartySceneQueueProbe implements ModInitializer {
    private final ArrayList<Map<String,Object>> checks=new ArrayList<>();
    private ArenaWorld world;
    private int elapsed;
    private boolean running,finished;
    private final BlockPos floating=new BlockPos(18,90,20),ordinary=new BlockPos(22,90,20),lamp=new BlockPos(72,100,24);

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            try {this.setup(server);this.running=true;}
            catch(Throwable error){this.finish(server,error);}
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(!this.running||this.finished)return;
            if(++this.elapsed==20) {
                try {this.afterTicks();this.finish(server,null);}
                catch(Throwable error){this.finish(server,error);}
            }
        });
    }
    private void check(String name,boolean pass,Object actual) {
        this.checks.add(Map.of("name",name,"pass",pass,"actual",actual));
        if(!pass)throw new IllegalStateException(name+": "+actual);
    }
    private int diamonds(BlockPos point) {
        return this.world.getEntitiesByClass(ItemEntity.class,new Box(point).expand(3),item->item.getStack().isOf(Items.DIAMOND))
                .stream().mapToInt(item->item.getStack().getCount()).sum();
    }
    private void setup(MinecraftServer server) {
        this.world=ArenaWorldManager.get(server).createWorld();
        this.check("The fixtures run in the actual registered ArenaWorld",server.getWorld(ArenaWorldManager.ARENA_WORLD_KEY)==this.world,this.world.getRegistryKey().getValue().toString());
        for(BlockPos pos:new BlockPos[]{this.floating,new BlockPos(40,90,20),new BlockPos(56,90,20),this.lamp,new BlockPos(102,100,100),new BlockPos(256,-64,256)})
            this.world.setChunkForced(pos.getX()>>4,pos.getZ()>>4,true);

        MapBuildQueue mixed=new MapBuildQueue(this.world,new BlockPos(98,98,98),new BlockPos(106,106,106));
        mixed.stageSnapshotRun(new BlockPos(100,100,100),Blocks.STONE.getDefaultState(),Direction.Axis.X,5);
        mixed.stageSnapshotRun(new BlockPos(102,98,100),Blocks.GOLD_BLOCK.getDefaultState(),Direction.Axis.Y,5);
        mixed.stageSnapshotRun(new BlockPos(102,100,98),Blocks.REDSTONE_BLOCK.getDefaultState(),Direction.Axis.Z,5);
        this.check("Mixed XYZ run overlay returns the last intersecting run",mixed.peek(new BlockPos(102,100,100)).isOf(Blocks.REDSTONE_BLOCK),mixed.totalStaged());
        mixed.stage(new BlockPos(102,100,100),Blocks.DIAMOND_BLOCK.getDefaultState());
        this.check("An ordinary point staged after indexed runs wins the overlay",mixed.peek(new BlockPos(102,100,100)).isOf(Blocks.DIAMOND_BLOCK),mixed.totalStaged());
        this.check("Actual writes count overlaps and the final ordinary point",mixed.totalStaged()==16,mixed.totalStaged());
        mixed.close();this.check("A short mixed queue flushes fully",mixed.flush(0),mixed.placedCount());
        this.check("All mixed XYZ legs retain exact native material and point override",
                this.world.getBlockState(new BlockPos(100,100,100)).isOf(Blocks.STONE)
                        &&this.world.getBlockState(new BlockPos(102,98,100)).isOf(Blocks.GOLD_BLOCK)
                        &&this.world.getBlockState(new BlockPos(102,100,98)).isOf(Blocks.REDSTONE_BLOCK)
                        &&this.world.getBlockState(new BlockPos(102,100,100)).isOf(Blocks.DIAMOND_BLOCK),mixed.placedCount());
        this.check("Placed count records all16 writes rather than13 unique positions",mixed.placedCount()==16,mixed.placedCount());

        MapBuildQueue vertical=new MapBuildQueue(this.world,new BlockPos(256,-64,256),new BlockPos(256,319,256));
        vertical.stageSnapshotRun(new BlockPos(256,-64,256),Blocks.STONE.getDefaultState(),Direction.Axis.Y,384);vertical.close();
        boolean first=vertical.flush(0);
        this.check("A zero-budget single384-cell Y run yields after exactly256 writes",!first&&vertical.placedCount()==256,vertical.placedCount());
        this.check("The partial run stops at its exact cursor offset",this.world.getBlockState(new BlockPos(256,191,256)).isOf(Blocks.STONE)&&this.world.getBlockState(new BlockPos(256,192,256)).isAir(),this.world.getTopY(Heightmap.Type.WORLD_SURFACE,256,256));
        boolean second=vertical.flush(0);int stones=0;for(int y=-64;y<=319;y++)if(this.world.getBlockState(new BlockPos(256,y,256)).isOf(Blocks.STONE))stones++;
        this.check("The next flush resumes the same Y run without skips or duplicate accounting",second&&vertical.placedCount()==384&&stones==384,Map.of("placed",vertical.placedCount(),"stones",stones));
        for(Heightmap.Type type:new Heightmap.Type[]{Heightmap.Type.MOTION_BLOCKING,Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,Heightmap.Type.OCEAN_FLOOR,Heightmap.Type.WORLD_SURFACE})
            this.check("Snapshot import maintains native heightmap "+type,this.world.getTopY(type,256,256)==320,this.world.getTopY(type,256,256));

        this.world.setBlockStateSnapshotDirect(this.floating.down(6),Blocks.STONE.getDefaultState());
        this.world.setBlockStateSnapshotDirect(this.ordinary.down(6),Blocks.STONE.getDefaultState());
        this.world.setBlockStateSnapshotDirect(this.floating,Blocks.SAND.getDefaultState());
        this.world.setBlockStateDirect(this.ordinary,Blocks.SAND.getDefaultState());
        this.check("Snapshot scope is closed immediately after each write",!this.world.isSnapshotImporting(this.floating,Blocks.SAND.getDefaultState()),"closed");

        BlockPos quiet=new BlockPos(40,90,20);
        this.world.setBlockStateSnapshotDirect(quiet,Blocks.CHEST.getDefaultState());
        this.check("Snapshot import still creates native chest block entities",this.world.getBlockEntity(quiet) instanceof ChestBlockEntity,"chest");
        ((ChestBlockEntity)this.world.getBlockEntity(quiet)).setStack(0,new ItemStack(Items.DIAMOND,7));
        this.world.setBlockStateSnapshotDirect(quiet,Blocks.AIR.getDefaultState());
        this.check("Snapshot chest-to-air removes the previous native block entity",this.world.getBlockEntity(quiet)==null,"removed");
        this.check("Snapshot removal does not scatter the seven stored diamonds",this.diamonds(quiet)==0,this.diamonds(quiet));
        this.world.setBlockStateSnapshotDirect(quiet,Blocks.CHEST.getDefaultState());
        this.world.setBlockStateSnapshotDirect(quiet,Blocks.OAK_SIGN.getDefaultState());
        this.check("Snapshot replacement validates and creates a different native BE type",this.world.getBlockEntity(quiet) instanceof SignBlockEntity,"sign");
        this.world.setBlockStateSnapshotDirect(quiet,Blocks.AIR.getDefaultState());

        BlockPos normal=new BlockPos(56,90,20);
        this.world.setBlockStateDirect(normal,Blocks.CHEST.getDefaultState());
        ((ChestBlockEntity)this.world.getBlockEntity(normal)).setStack(0,new ItemStack(Items.DIAMOND,7));
        this.world.setBlockStateDirect(normal,Blocks.AIR.getDefaultState());
        this.check("Ordinary direct chest removal still scatters all seven diamonds",this.diamonds(normal)==7,this.diamonds(normal));
        this.world.setBlockStateSnapshotDirect(this.lamp,Blocks.GLOWSTONE.getDefaultState());
    }
    private void afterTicks() {
        this.check("Unsupported imported sand remains exactly at its saved position after20 native ticks",this.world.getBlockState(this.floating).isOf(Blocks.SAND),this.world.getBlockState(this.floating).toString());
        this.check("Ordinary direct sand still performs native scheduled falling",this.world.getBlockState(this.ordinary).isAir()&&this.world.getBlockState(this.ordinary.down(5)).isOf(Blocks.SAND),Map.of("start",this.world.getBlockState(this.ordinary).toString(),"landing",this.world.getBlockState(this.ordinary.down(5)).toString()));
        this.check("Snapshot placement maintains native emitted block light",this.world.getLightLevel(LightType.BLOCK,this.lamp)==15,this.world.getLightLevel(LightType.BLOCK,this.lamp));
        this.check("Native block light propagates into adjacent air",this.world.getLightLevel(LightType.BLOCK,this.lamp.east())>=14,this.world.getLightLevel(LightType.BLOCK,this.lamp.east()));
    }
    private void finish(MinecraftServer server,Throwable error) {
        if(this.finished)return;this.finished=true;
        Map<String,Object> report=new LinkedHashMap<>();report.put("ok",error==null);report.put("checks",this.checks);report.put("nativeTicks",this.elapsed);
        if(error!=null){report.put("error",error.toString());error.printStackTrace();}
        try{Files.writeString(Path.of("party-scene-queue-report.json"),new Gson().toJson(report)+"\n");}
        catch(Exception failure){throw new IllegalStateException(failure);}
        server.stop(false);
    }
}
