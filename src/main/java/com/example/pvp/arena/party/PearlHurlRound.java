package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.EndGatewayBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版426 Pearl Hurl：整座末地岛/三条原路线、原生珍珠飞行与LIGHT格安全点和门终点。
 * 修改原因：原全服End命令/临时实体/属性计分不能运行于共享大厅，缩小平台或模拟珍珠会改难度。
 * 自查：保留938098格整岛、30条生成指令的空气/筛选/克隆顺序、43箭头和56条CP半径/前置范围；
 * 5..10人按原player_line交替站位，初始mp安全点独立于live位置，INTRO同样更新LIGHT安全点。
 * 非零LIGHT先存精确安全位置，再按原行序更新CP；开局11tick首珠、25tick补珠独立于原生20冷却。
 * 源3600tick/正常N−1，原生珍珠实体/碰撞/命中传送，局内伤害免疫和珍珠清理不改全服规则。
 * 原gateway远端出口必须由共享有界原生门户适配接入；不能把原生门户终点替换成按钮或缩小路线。
 */
public final class PearlHurlRound implements PartyRound {
    private static final BlockPos ANCHOR=new BlockPos(38,39,-24);
    private static final int[] FINISH_CP={23,21,19};
    private static final Identifier GATEWAY_ADVANCEMENT=Identifier.ofVanilla("end/enter_end_gateway");
    record Spawn(double x,double y,double z) { }
    /** Source player_line local offsets alternate right/left; they are not seat+X. */
    static Spawn launchSpawn(int players,int seat){
        if(players<=4)return new Spawn(64.5+seat,39,-79.5);
        int alternating=seat%2==1?(seat+1)/2:-seat/2;
        return new Spawn(66+(players%2==0?-.5:0)+alternating,39,-79);
    }
    /** mp5..10 intentionally share the fourth initial marker until nonzeroLIGHT updates it. */
    static Spawn initialSafe(int seat){return new Spawn(64.5+Math.min(seat,3),39,-79.5);}
    record Checkpoint(int pathMask,double x,double y,double z,double radius,int minimum,int maximum,int value) { }
    private record Operation(String type,BlockPos a,BlockPos b,BlockPos destination,BlockState state,Block replace,int pathMask) { }
    private static final class Hurler {
        Vec3d safe;
        int checkpoint,lastCheckpoint=-1,reload=14,usedBefore,landingTick=Integer.MIN_VALUE;
        Vec3d landing;
        Set<String> originalGatewayCriteria=Set.of();
        Hurler(Vec3d safe){this.safe=safe;}
    }
    private final Map<UUID,Hurler> hurlers=new LinkedHashMap<>();
    private final Map<UUID,EnderPearlEntity> pearls=new LinkedHashMap<>();
    private final Map<Long,JsonObject> signs=new LinkedHashMap<>();
    private final List<Checkpoint> checkpoints=new ArrayList<>();
    private PartyChunkTickets sceneTickets,projectileTickets;
    private final Set<UUID> activeOwners=new HashSet<>();
    private JsonObject rules;
    private ServerWorld world;
    private AdvancementEntry gatewayAdvancement;
    private int path,finishers;
    private boolean checkpointUsed,closed;

    static int nextCheckpoint(int path,int current,double x,double y,double z,List<Checkpoint> clauses) {
        for(Checkpoint clause:clauses)if((clause.pathMask()&(1<<path))!=0&&current>=clause.minimum()&&current<=clause.maximum()) {
            double dx=x-clause.x(),dy=y-clause.y(),dz=z-clause.z();
            if(dx*dx+dy*dy+dz*dz<=clause.radius()*clause.radius())current=clause.value();
        }
        return current;
    }
    static int rawScore(int players,int checkpoint,int last,List<Integer> times,boolean used) {
        return players*checkpoint+(used?(int)times.stream().filter(t->t>last).count():3);
    }
    static int nextReload(int counter,boolean threw){return threw&&counter==26?1:counter<=25?counter+1:26;}
    private static int paths(JsonArray paths){int mask=0;for(JsonElement p:paths)mask|=1<<p.getAsInt();return mask;}
    static List<Checkpoint> clauses(JsonArray encoded) {
        List<Checkpoint> result=new ArrayList<>();for(JsonElement value:encoded) {
            JsonObject row=value.getAsJsonObject();JsonArray p=row.getAsJsonArray("position");
            result.add(new Checkpoint(paths(row.getAsJsonArray("paths")),p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble(),row.get("radius").getAsDouble(),row.get("previousMin").getAsInt(),row.get("previousMax").getAsInt(),row.get("checkpoint").getAsInt()));
        }return result;
    }
    @Override public boolean tracksTerrainChanges(){return true;}
    @Override public PartyArenaBounds arenaBounds(){return new PartyArenaBounds(-1536,1536,-1536,1536);}
    @Override public void stage(PartyRoundContext context)throws IOException {
        context.sourceAnchor(ANCHOR);context.timeLimit(3600);this.world=context.world();this.sceneTickets=new PartyChunkTickets(this.world);this.projectileTickets=new PartyChunkTickets(this.world);this.path=context.random().nextInt(3)+1;
        try(var input=PearlHurlRound.class.getResourceAsStream("/pvp/party/pearl_hurl_rules.json")) {
            if(input==null)throw new IOException("Missing Pearl Hurl source rules");
            this.rules=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
        this.checkpoints.addAll(clauses(this.rules.getAsJsonArray("checkpoints")));
        for(JsonElement value:this.rules.getAsJsonArray("signs")){JsonObject row=value.getAsJsonObject();this.signs.put(position(row.getAsJsonArray("position")).asLong(),row);}
        List<Operation> operations=new ArrayList<>();LongOpenHashSet needed=new LongOpenHashSet();
        for(JsonElement value:this.rules.getAsJsonArray("generation")) {
            Operation op=operation(value.getAsJsonObject());if((op.pathMask()&(1<<this.path))==0)continue;operations.add(op);
            for(BlockPos p:BlockPos.iterate(low(op.a(),op.b()),high(op.a(),op.b())))needed.add(p.asLong());
            if(op.destination()!=null) {
                BlockPos size=high(op.a(),op.b()).subtract(low(op.a(),op.b()));
                for(BlockPos p:BlockPos.iterate(op.destination(),op.destination().add(size)))needed.add(p.asLong());
            }
        }
        // Only small generation windows are retained, avoiding a second boxed million-cell scene.
        Map<Long,BlockState> scene=new HashMap<>();
        PartyMapBlueprint.load("pearl_hurl").forEachTranslated(BlockPos.ORIGIN,(p,state)->{if(needed.contains(p.asLong()))scene.put(p.asLong(),state);});
        // ArenaWorld is intentionally not saved. Static scene tickets keep the
        // original far islands intact until native pearls actually reach them.
        this.sceneTickets.retain(context.sourceBlock(-64,0,-140),context.sourceBlock(140,106,95));
        context.geometry("pearl_hurl");
        for(JsonElement value:this.rules.getAsJsonArray("remoteGeometry")) {
            JsonObject region=value.getAsJsonObject();
            if(!region.getAsJsonArray("missingSourceChunks").isEmpty())throw new IOException("Unresolved original Pearl Hurl remote chunks: "+region.get("name"));
            BlockPos a=position(region.getAsJsonObject("bounds").getAsJsonArray("min")),b=position(region.getAsJsonObject("bounds").getAsJsonArray("max"));
            this.sceneTickets.retain(context.sourceBlock(a.getX(),a.getY(),a.getZ()),context.sourceBlock(b.getX(),b.getY(),b.getZ()));
            context.geometry(region.get("name").getAsString());
        }
        if(this.path==3&&!this.rules.getAsJsonArray("unresolvedGateways").isEmpty())throw new IOException("Original-version native Pearl Hurl gateway resolution is required before path3 runtime");
        for(Operation op:operations)this.generate(scene,op);
        for(long packed:needed) {
            BlockPos p=BlockPos.fromLong(packed);context.stage(context.sourceBlock(p.getX(),p.getY(),p.getZ()),scene.getOrDefault(packed,Blocks.AIR.getDefaultState()));
        }
        int count=context.players().size();for(ServerPlayerEntity player:context.players()) {
            int seat=context.slot(player.getUuid());Spawn launch=launchSpawn(count,seat),safe=initialSafe(seat);
            this.hurlers.put(player.getUuid(),new Hurler(context.source(safe.x(),safe.y(),safe.z())));this.activeOwners.add(player.getUuid());
            context.spawn(player.getUuid(),context.source(launch.x(),launch.y(),launch.z()));
            if(count>=5){player.setYaw(180);player.setPitch(0);}
        }
    }
    /** playingPearlHurl=1 also runs source light safety after the introduction's player_spawn hold. */
    @Override public void introTick(PartyRoundContext context,int elapsed){
        for(ServerPlayerEntity p:context.alive()){
            Hurler state=this.hurlers.get(p.getUuid());if(state==null)continue;BlockState feet=this.world.getBlockState(p.getBlockPos());
            if(feet.isOf(Blocks.AIR)||feet.isOf(Blocks.LIGHT)&&feet.get(Properties.LEVEL_15)==0)context.teleport(p,state.safe);
            feet=this.world.getBlockState(p.getBlockPos());if(feet.isOf(Blocks.LIGHT)&&feet.get(Properties.LEVEL_15)!=0)state.safe=p.getPos();
        }
    }
    private void generate(Map<Long,BlockState> scene,Operation op) {
        BlockPos low=low(op.a(),op.b()),high=high(op.a(),op.b());
        if(op.type().equals("clone")) {
            Map<Long,BlockState> copy=new HashMap<>();Map<Long,JsonObject> signCopy=new HashMap<>();Set<Long> targets=new HashSet<>();
            for(BlockPos source:BlockPos.iterate(low,high)) {
                long target=op.destination().add(source.subtract(low)).asLong();targets.add(target);copy.put(target,scene.getOrDefault(source.asLong(),Blocks.AIR.getDefaultState()));
                JsonObject sign=this.signs.get(source.asLong());if(sign!=null)signCopy.put(target,sign);
            }
            scene.putAll(copy);targets.forEach(this.signs::remove);this.signs.putAll(signCopy);
        }else for(BlockPos p:BlockPos.iterate(low,high)) {
            BlockState previous=scene.getOrDefault(p.asLong(),Blocks.AIR.getDefaultState());
            if(op.replace()==null||previous.isOf(op.replace())){scene.put(p.asLong(),op.state());this.signs.remove(p.asLong());}
        }
    }
    @Override public void start(PartyRoundContext context) {
        this.gatewayAdvancement=this.world.getServer().getAdvancementLoader().get(GATEWAY_ADVANCEMENT);
        for(var entry:this.signs.entrySet()) {
            BlockPos p=BlockPos.fromLong(entry.getKey()),target=context.sourceBlock(p.getX(),p.getY(),p.getZ());
            if(!(this.world.getBlockEntity(target)instanceof SignBlockEntity sign))continue;
            for(String face:List.of("front","back")) {
                JsonObject text=entry.getValue().getAsJsonObject(face);Text[] lines=new Text[4];JsonArray messages=text.getAsJsonArray("messages");
                for(int i=0;i<4;i++)lines[i]=Text.literal(messages.get(i).getAsString());
                sign.setText(new SignText(lines,lines,DyeColor.byName(text.get("color").getAsString(),DyeColor.BLACK),text.get("glowing").getAsBoolean()),face.equals("front"));
            }
            sign.setWaxed(entry.getValue().get("waxed").getAsBoolean());sign.markDirty();this.world.getChunkManager().markForUpdate(target);
        }
        List<JsonElement> allGateways=new ArrayList<>(this.rules.getAsJsonArray("gateways").asList());allGateways.addAll(this.rules.getAsJsonArray("remoteGateways").asList());
        for(JsonElement value:allGateways) {
            JsonObject row=value.getAsJsonObject();BlockPos p=position(row.getAsJsonArray("position"));
            if(this.world.getBlockEntity(context.sourceBlock(p.getX(),p.getY(),p.getZ()))instanceof EndGatewayBlockEntity gateway) {
                NbtCompound nbt=new NbtCompound();nbt.putLong("Age",row.get("age").getAsLong());gateway.readComponentlessNbt(nbt,this.world.getRegistryManager());
                if(!row.get("exit").isJsonNull()){BlockPos exit=position(row.getAsJsonArray("exit"));gateway.setExitPortalPos(context.sourceBlock(exit.getX(),exit.getY(),exit.getZ()),row.get("exact").getAsBoolean());}
                gateway.markDirty();
            }
        }
        for(ServerPlayerEntity player:context.players()) {
            Hurler state=this.hurlers.get(player.getUuid());state.usedBefore=player.getStatHandler().getStat(Stats.USED.getOrCreateStat(Items.ENDER_PEARL));
            if(this.gatewayAdvancement!=null){state.originalGatewayCriteria=Set.copyOf(this.gatewayCriteria(player));this.clearGateway(player);}
            context.score(player.getUuid(),3);
        }
        this.effects(context);context.announce("§a末影投掷 · 路线 "+this.path+"/3：跟随原图箭头，用末影珍珠抵达各个平台，最后穿过折跃门。\n§e珍珠每25tick补充一颗；掉入空气会返回最近的安全光源格。限时180秒，完赛人数达到总人数−1时结算。");
    }
    @Override public void tick(PartyRoundContext context,int elapsed) {
        if(this.closed)return;this.effects(context);
        for(ServerPlayerEntity player:List.copyOf(context.alive())) {
            Hurler state=this.hurlers.get(player.getUuid());if(state==null)continue;
            Vec3d origin=context.source(0,0,0),source=player.getPos().subtract(origin);
            boolean distant=source.lengthSquared()>=500*500;
            boolean gateway=distant||this.gatewayAdvancement!=null&&player.getAdvancementTracker().getProgress(this.gatewayAdvancement).isDone();
            BlockState feet=this.world.getBlockState(player.getBlockPos());
            // Source functions run before world/entity ticks. Read-only phase evidence
            // showed the END hook otherwise rolls a real pearl landing back in its
            // collision tick, before the client can settle on the original slab.
            boolean landingFrame=feet.isOf(Blocks.AIR)&&state.landingTick==this.world.getServer().getTicks()
                    &&state.landing!=null&&player.getPos().squaredDistanceTo(state.landing)<1.0E-9;
            if(!gateway&&!landingFrame&&(feet.isOf(Blocks.AIR)||feet.isOf(Blocks.LIGHT)&&feet.get(Properties.LEVEL_15)==0))context.teleport(player,state.safe);
            feet=this.world.getBlockState(player.getBlockPos());boolean safe=feet.isOf(Blocks.LIGHT)&&feet.get(Properties.LEVEL_15)!=0;
            if(!gateway&&safe)state.safe=player.getPos();
            if(gateway) {
                if(distant&&state.checkpoint==FINISH_CP[this.path-1]) {
                    state.checkpoint++;state.lastCheckpoint=elapsed;this.checkpointUsed=true;this.finishers++;this.activeOwners.remove(player.getUuid());this.clearGateway(player);
                    context.completed(player.getUuid());context.eliminate(player,"§a完成末影珍珠赛程，正在观看其他玩家。");context.teleport(player,context.source(65.5,39,-81.5));context.announce("§a"+player.getName().getString()+" 完成了末影投掷！");continue;
                }
                context.teleport(player,context.source(30.5,17,-28.5));
            }
            this.clearGateway(player);
            feet=this.world.getBlockState(player.getBlockPos());
            if(feet.isOf(Blocks.LIGHT)&&feet.get(Properties.LEVEL_15)!=0) {
                Vec3d point=player.getPos().subtract(origin);int next=nextCheckpoint(this.path,state.checkpoint,point.x,point.y,point.z,this.checkpoints);
                if(next>state.checkpoint){state.checkpoint=next;state.lastCheckpoint=elapsed;this.checkpointUsed=true;player.sendMessage(Text.literal("§a检查点 "+next+"/"+FINISH_CP[this.path-1]),false);}
            }
            this.restock(player,state);
        }
        // Source returns distant spectators in the same tick as gateway completion.
        for(ServerPlayerEntity player:context.players())if(context.isOut(player.getUuid())&&player.getPos().subtract(context.source(0,0,0)).lengthSquared()>=200*200)context.teleport(player,context.source(65.5,39,-81.5));
        this.projectiles(context);this.updateScores(context);
        if(this.finishers>=context.initialPlayerCount()-1||context.alive().size()<=1)context.finish();
    }
    /** Routed only after an owned native pearl actually teleports its live owner. */
    public void pearlLanded(PartyRoundContext context,ServerPlayerEntity player){
        Hurler state=this.hurlers.get(player.getUuid());if(this.closed||state==null||context.isOut(player.getUuid()))return;
        state.landingTick=this.world.getServer().getTicks();state.landing=player.getPos();
    }
    private void restock(ServerPlayerEntity player,Hurler state) {
        int used=player.getStatHandler().getStat(Stats.USED.getOrCreateStat(Items.ENDER_PEARL));boolean threw=used>state.usedBefore;state.usedBefore=used;
        state.reload=nextReload(state.reload,threw);boolean dirty=false;
        if(state.reload==25||state.reload==26&&player.getInventory().count(Items.ENDER_PEARL)==0){player.getInventory().insertStack(new ItemStack(Items.ENDER_PEARL));dirty=true;}
        boolean any=false,one=false;for(int i=0;i<player.getInventory().size();i++) {ItemStack stack=player.getInventory().getStack(i);if(stack.isOf(Items.ENDER_PEARL)){any=true;one|=stack.getCount()==1;}}
        if(any&&!one){player.getInventory().remove(stack->stack.isOf(Items.ENDER_PEARL),1,player.playerScreenHandler.getCraftingInput());dirty=true;}
        if(dirty){player.currentScreenHandler.sendContentUpdates();player.playerScreenHandler.onContentChanged(player.getInventory());}
    }
    private void effects(PartyRoundContext context) {
        for(ServerPlayerEntity p:context.alive()) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION,20,0,false,false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,20,99,false,false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE,20,99,false,false));
        }
    }
    private void projectiles(PartyRoundContext context) {
        this.pearls.values().removeIf(Entity::isRemoved);
        Set<ChunkPos> ticking=new HashSet<>();
        for(EnderPearlEntity pearl:this.pearls.values()) {
            BlockPos p=pearl.getBlockPos();int source17=context.sourceBlock(0,17,0).getY(),source1=context.sourceBlock(0,1,0).getY();
            boolean soulsand=this.world.getBlockState(new BlockPos(p.getX(),source17,p.getZ())).isOf(Blocks.SOUL_SAND);
            if(soulsand||this.world.getBlockState(new BlockPos(p.getX(),source1,p.getZ())).isOf(Blocks.SOUL_SAND))pearl.setGlowing(true);
            BlockState above=this.world.getBlockState(p.up());
            if(this.path==3&&soulsand&&above.isOf(Blocks.PURPUR_SLAB)&&above.get(Properties.SLAB_TYPE)==net.minecraft.block.enums.SlabType.BOTTOM)pearl.setPosition(pearl.getX(),pearl.getY()+1.5,pearl.getZ());
            int cx=p.getX()>>4,cz=p.getZ()>>4;for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)ticking.add(new ChunkPos(cx+dx,cz+dz));
        }
        this.projectileTickets.update(ticking);
        // Dropped pearls are removed at native entity load, including the distant
        // return islands; avoid scanning a3072x3072 scene every tick.
    }
    private void updateScores(PartyRoundContext context) {
        List<Integer> times=this.hurlers.values().stream().map(s->s.lastCheckpoint).toList();
        this.hurlers.forEach((uuid,state)->context.score(uuid,rawScore(context.initialPlayerCount(),state.checkpoint,state.lastCheckpoint,times,this.checkpointUsed)));
    }
    private List<String> gatewayCriteria(ServerPlayerEntity player){List<String> criteria=new ArrayList<>();player.getAdvancementTracker().getProgress(this.gatewayAdvancement).getObtainedCriteria().forEach(criteria::add);return criteria;}
    private void clearGateway(ServerPlayerEntity player) {
        if(this.gatewayAdvancement==null)return;
        for(String criterion:this.gatewayCriteria(player))player.getAdvancementTracker().revokeCriterion(this.gatewayAdvancement,criterion);
    }
    @Override public boolean allowsDamage(PartyRoundContext c,LivingEntity v,DamageSource s){return !s.isOf(DamageTypes.FALL)&&!(s.getAttacker()instanceof ServerPlayerEntity);}
    @Override public ActionResult useItem(PartyRoundContext c,ServerPlayerEntity p,Hand hand){return !c.isOut(p.getUuid())&&p.getStackInHand(hand).isOf(Items.ENDER_PEARL)?ActionResult.PASS:ActionResult.FAIL;}
    /** Aiming at an actual platform must still reach the native pearl/block-use path. */
    @Override public ActionResult useBlock(PartyRoundContext c,ServerPlayerEntity p,Hand hand,net.minecraft.util.hit.BlockHitResult hit){return this.useItem(c,p,hand);}
    @Override public void entityLoaded(PartyRoundContext c,Entity e){if(e instanceof ItemEntity item&&this.ownsEntity(item)){item.discard();return;}if(e instanceof EnderPearlEntity pearl&&pearl.getOwner()instanceof ServerPlayerEntity p&&!this.closed&&!c.isOut(p.getUuid())&&c.playerIds().contains(p.getUuid())&&e.getWorld()==this.world)this.pearls.put(pearl.getUuid(),pearl);}
    // A leaver may already be throwing pearls in another arena match; old score records confer no ownership.
    @Override public boolean ownsEntity(Entity e){
        if(this.closed||e.getWorld()!=this.world)return false;
        if(e instanceof EnderPearlEntity pearl)return this.pearls.containsKey(e.getUuid())||pearl.getOwner()!=null&&this.activeOwners.contains(pearl.getOwner().getUuid());
        return e instanceof ItemEntity item&&item.getStack().isOf(Items.ENDER_PEARL)&&item.getOwner()!=null&&this.activeOwners.contains(item.getOwner().getUuid());
    }
    @Override public void death(PartyRoundContext c,ServerPlayerEntity p) {
        for(EnderPearlEntity pearl:this.pearls.values())if(pearl.getOwner()==p)pearl.discard();
        p.setHealth(p.getMaxHealth());p.setFireTicks(0);p.fallDistance=0;p.timeUntilRegen=0;p.hurtTime=0;p.setAttacker(null);c.teleport(p,c.source(-20.5,19,-28.5));
    }
    @Override public void leave(PartyRoundContext c,ServerPlayerEntity p){
        this.activeOwners.remove(p.getUuid());
        for(EnderPearlEntity pearl:this.pearls.values())if(pearl.getOwner()==p)pearl.discard();
        this.restoreGateway(p);p.removeStatusEffect(StatusEffects.SATURATION);p.removeStatusEffect(StatusEffects.WEAKNESS);p.removeStatusEffect(StatusEffects.RESISTANCE);
    }
    private void restoreGateway(ServerPlayerEntity p){this.clearGateway(p);Hurler s=this.hurlers.get(p.getUuid());if(s!=null&&this.gatewayAdvancement!=null)for(String criterion:s.originalGatewayCriteria)p.getAdvancementTracker().grantCriterion(this.gatewayAdvancement,criterion);}
    @Override public void finish(PartyRoundContext c){this.updateScores(c);}
    @Override public String scoreDescription(PartyRoundContext c,UUID p){Hurler s=this.hurlers.get(p);return(s==null?0:Math.min(s.checkpoint,FINISH_CP[this.path-1]))+"/"+FINISH_CP[this.path-1]+" 检查点";}
    @Override public String status(PartyRoundContext c){return "路线 "+this.path+"/3";}
    @Override public void close(PartyRoundContext c) {
        if(this.closed)return;this.closed=true;this.activeOwners.clear();this.pearls.values().forEach(Entity::discard);this.pearls.clear();
        for(ServerPlayerEntity p:c.players()) {
            this.restoreGateway(p);
            p.getInventory().clear();p.removeStatusEffect(StatusEffects.SATURATION);p.removeStatusEffect(StatusEffects.WEAKNESS);p.removeStatusEffect(StatusEffects.RESISTANCE);
        }
        if(this.projectileTickets!=null)this.projectileTickets.close();if(this.sceneTickets!=null)this.sceneTickets.close();
    }
    private static BlockPos position(JsonArray a){return new BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt());}
    private static BlockPos low(BlockPos a,BlockPos b){return new BlockPos(Math.min(a.getX(),b.getX()),Math.min(a.getY(),b.getY()),Math.min(a.getZ(),b.getZ()));}
    private static BlockPos high(BlockPos a,BlockPos b){return new BlockPos(Math.max(a.getX(),b.getX()),Math.max(a.getY(),b.getY()),Math.max(a.getZ(),b.getZ()));}
    private static Operation operation(JsonObject row)throws IOException {
        BlockPos a=position(row.getAsJsonArray(row.get("type").getAsString().equals("setblock")?"position":"min"));
        return new Operation(row.get("type").getAsString(),a,row.has("max")?position(row.getAsJsonArray("max")):a,row.has("destination")?position(row.getAsJsonArray("destination")):null,row.has("state")?state(row.get("state").getAsString()):null,row.has("replace")&&!row.get("replace").isJsonNull()?state(row.get("replace").getAsString()).getBlock():null,paths(row.getAsJsonArray("paths")));
    }
    private static BlockState state(String encoded)throws IOException {
        int bracket=encoded.indexOf('[');String id=bracket<0?encoded:encoded.substring(0,bracket);Identifier identifier=Identifier.of(id);
        if(!Registries.BLOCK.containsId(identifier))throw new IOException("Unknown Pearl Hurl block "+id);Block block=Registries.BLOCK.get(identifier);BlockState result=block.getDefaultState();
        if(bracket>=0)for(String value:encoded.substring(bracket+1,encoded.length()-1).split(",")){String[] pair=value.split("=");Property<?> property=block.getStateManager().getProperty(pair[0]);if(property==null)throw new IOException("Unknown Pearl property "+value);result=property(result,property,pair[1]);}return result;
    }
    private static <T extends Comparable<T>>BlockState property(BlockState state,Property<T> key,String value)throws IOException{return state.with(key,key.parse(value).orElseThrow(()->new IOException("Invalid Pearl property "+value)));}
}
