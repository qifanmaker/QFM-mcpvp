package com.example.pvp.arena.party;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.util.math.Direction;
import java.util.EnumSet;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 原版423 Rocket Launch：完整2160格直线沙漠，真实鞘翅与一枚flight2烟花。
 * 修改原因：压缩赛道、虚拟助推、只取最高Z或N-1完赛均会改变原图物理和计分。
 * 自查：负Z向下取整、倒退先结算旧距离、红砂岩/light落地例外、横向原值回拉、
 * 2160终线当tick超出量与Y-196高度加分、同tick完成并列、全员完成且无全局时限。
 * 世界静态地形另行白名单提取，所有实体/装备/效果/积分仅作用于本场玩家。
 */
public final class RocketLaunchRound implements PartyRound {
    // Keep source Y unchanged: the framework center is101, not the launch Y138.
    static final BlockPos ANCHOR = new BlockPos(-62,101,120);
    private static final int[] LEATHER={5461244,16536660,5635156,16645204,43690,16733695,11141120,43520,11141290,170};
    private static final int[] FIREWORK={2173439,16727357,3211041,15335204,35980,16711935,8847360,43520,8913032,136};
    private static final class Native {
        static final Item[] ARMOR={Items.LEATHER_BOOTS,Items.LEATHER_LEGGINGS,Items.LEATHER_CHESTPLATE};
    }
    private static final class Flight {
        final UUID owner;
        final int seat;
        int distance;
        int cachedY=138;
        double markerZ=125;
        int finishedAt=-1;
        boolean left;
        Flight(UUID owner,int seat){this.owner=owner;this.seat=seat;}
    }
    private final Map<UUID,Flight> flights=new LinkedHashMap<>();
    private final Map<UUID,Entity> owned=new HashMap<>();
    private JsonObject rules;
    private ServerWorld world;
    private Vec3d launch;
    private BlockPos offset;
    private boolean started,closed;
    private int absoluteTick;

    static int distance(double sourceZ){return Math.max(0,117-MathHelper.floor(sourceZ));}
    static int terminalDistance(int distance,int sourceY){return distance+(distance>=2160?sourceY-196:0);}
    static int rawScore(int distance,int players,int finishedAt,List<Integer> others){
        return distance*players+(finishedAt<0?0:(int)others.stream().filter(t->t>finishedAt).count());
    }
    static boolean backward(int rawZ,double markerZ){return rawZ<=115&&MathHelper.floor(markerZ)-5<rawZ;}

    @Override public void stage(PartyRoundContext context)throws IOException {
        context.sourceAnchor(ANCHOR);context.timeLimit(0);
        this.world=context.world();
        try(var input=getClass().getResourceAsStream("/pvp/party/rocket_launch_rules.json")){
            if(input==null)throw new IOException("Missing audited RocketLaunch rules");
            try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){this.rules=JsonParser.parseReader(reader).getAsJsonObject();}
        }
        this.offset=context.sourceBlock(0,0,0);this.launch=context.source(-62,138,120);
        context.geometry("rocket_launch");
        int seat=0;
        for(ServerPlayerEntity player:context.players()){
            this.flights.put(player.getUuid(),new Flight(player.getUuid(),seat++));
            context.spawn(player.getUuid(),this.launch);
        }
    }

    @Override public boolean handlesTimeLimit(){return true;}
    @Override public boolean tracksTerrainChanges(){return true;}
    @Override public boolean locksIntroPosition(){return false;}
    @Override public boolean allowsIntroItemUse(){return true;}
    @Override public boolean allowsIntroCompletion(){return true;}
    @Override public boolean disableElytraMovementCheck(){return true;}
    @Override public PartyArenaBounds arenaBounds(){return new PartyArenaBounds(-200,200,-2250,200);}

    @Override public void introTick(PartyRoundContext context,int elapsed){
        if(this.closed)return;
        this.absoluteTick=elapsed;
        if(elapsed==0){
            this.restoreScene(context);
            for(ServerPlayerEntity player:context.players())this.equip(player,true);
            this.effects(context);
        }else this.sourceTick(context,elapsed,true);
    }

    @Override public void start(PartyRoundContext context){
        this.started=true;
        for(ServerPlayerEntity player:context.players()){
            Flight state=this.flights.get(player.getUuid());
            if(state.cachedY>=140)this.nativeTeleport(context,player,new Vec3d(player.getX(),this.offset.getY()+138,player.getZ()),Direction.Axis.Y);
            // Source start gives a fresh rocket only to adventure players and
            // does not reset markers, distance or pre-launch finish records.
            if(state.finishedAt>=0){
                if(state.cachedY>=141)player.getInventory().remove(stack->stack.isOf(Items.ELYTRA),-1,player.playerScreenHandler.getCraftingInput());
            }else this.equip(player,state.cachedY<141);
        }
        this.updateScores(context);
        this.effects(context);
        context.announce("§aRocket Launch：向北跳下平台，展开鞘翅，右键使用唯一一枚二级烟花。"
                +"\n§e落地即结束；距离越远越好，2160格终点的额外高度也会加分。"
                +"\n§7全员完成后按距离排名，同距离由较早完成者领先；没有总倒计时。");
    }

    private void restoreScene(PartyRoundContext context){
        // Preserve the actual underground dungeon/mineshaft scene. Import only
        // reviewed loot keys/seeds and vanilla spawner parameters, never raw BE NBT.
        for(var encoded:this.rules.getAsJsonArray("blockEntities")){
            JsonObject data=encoded.getAsJsonObject();var p=data.getAsJsonArray("pos");
            BlockPos pos=context.sourceBlock(p.get(0).getAsInt(),p.get(1).getAsInt(),p.get(2).getAsInt());
            if(data.get("type").getAsString().equals("chest")&&context.world().getBlockEntity(pos) instanceof LootableContainerBlockEntity chest){
                if(!data.get("lootTable").isJsonNull()){
                    String loot=data.get("lootTable").getAsString();
                    if(!loot.equals("minecraft:chests/simple_dungeon"))throw new IllegalStateException("Unreviewed RocketLaunch chest");
                    chest.setLootTable(RegistryKey.of(RegistryKeys.LOOT_TABLE,Identifier.of(loot)));chest.setLootTableSeed(data.get("lootSeed").getAsLong());chest.markDirty();
                }
            }else if(data.get("type").getAsString().equals("spawner")&&context.world().getBlockEntity(pos) instanceof MobSpawnerBlockEntity spawner){
                String type=data.get("entityType").getAsString();
                if(!List.of("minecraft:zombie","minecraft:skeleton","minecraft:spider","minecraft:cave_spider").contains(type))throw new IllegalStateException("Unreviewed RocketLaunch spawner");
                NbtCompound safe=new NbtCompound(),spawn=new NbtCompound(),entity=new NbtCompound();entity.putString("id",type);spawn.put("entity",entity);safe.put("SpawnData",spawn);
                for(var field:data.getAsJsonObject("fields").entrySet())safe.putShort(field.getKey(),field.getValue().getAsShort());
                spawner.getLogic().readNbt(context.world(),pos,safe);spawner.markDirty();context.world().updateListeners(pos,spawner.getCachedState(),spawner.getCachedState(),Block.NOTIFY_LISTENERS);
            }
        }
        for(var encoded:this.rules.getAsJsonArray("entities")){
            JsonObject data=encoded.getAsJsonObject();var p=data.getAsJsonArray("pos");Vec3d pos=context.source(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble());
            ChestMinecartEntity cart=new ChestMinecartEntity(context.world(),pos.x,pos.y,pos.z);var rotation=data.getAsJsonArray("rotation");cart.setYaw(rotation.get(0).getAsFloat());cart.setPitch(rotation.get(1).getAsFloat());
            String loot=data.get("lootTable").getAsString();if(!loot.equals("minecraft:chests/abandoned_mineshaft"))throw new IllegalStateException("Unreviewed RocketLaunch cart");
            cart.setLootTable(RegistryKey.of(RegistryKeys.LOOT_TABLE,Identifier.of(loot)));cart.setLootTableSeed(data.get("lootSeed").getAsLong());cart.addCommandTag("party_rocket_launch_cart");
            this.owned.put(cart.getUuid(),cart);context.world().spawnEntity(cart);
        }
    }

    private void equip(ServerPlayerEntity player,boolean wings){
        // rocket_launch_start clears rockets from adventure players before
        // granting one. Native /clear includes2x2 input and cursor; main-only
        // prepare would let a practice rocket survive there and become a second.
        player.getInventory().remove(stack->stack.isOf(Items.FIREWORK_ROCKET),-1,player.playerScreenHandler.getCraftingInput());
        if(wings){
            ItemStack elytra=new ItemStack(Items.ELYTRA);
            elytra.set(DataComponentTypes.UNBREAKABLE,new UnbreakableComponent(true));
            player.getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.BINDING_CURSE)
                    .ifPresent(entry->elytra.addEnchantment(entry,1));
            player.equipStack(EquipmentSlot.CHEST,elytra);
        }else player.equipStack(EquipmentSlot.CHEST,ItemStack.EMPTY);
        ItemStack rocket=new ItemStack(Items.FIREWORK_ROCKET);
        rocket.set(DataComponentTypes.FIREWORKS,new FireworksComponent(2,List.of()));
        player.getInventory().insertStack(rocket);player.currentScreenHandler.sendContentUpdates();
    }

    private void effects(PartyRoundContext context){
        for(ServerPlayerEntity player:context.players()){
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE,20,99,false,false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,20,99,false,false));
        }
    }

    @Override public void tick(PartyRoundContext context,int elapsed){
        if(!this.started||this.closed)return;
        this.sourceTick(context,++this.absoluteTick,false);
    }

    private void sourceTick(PartyRoundContext context,int clock,boolean introduction){
        this.effects(context);
        for(ServerPlayerEntity player:context.players()){
            Flight state=this.flights.get(player.getUuid());if(state==null||state.left||state.finishedAt>=0)continue;
            int z=MathHelper.floor(player.getZ()-this.offset.getZ());
            state.cachedY=MathHelper.floor(player.getY())-this.offset.getY();
            // First rules call creates the marker at intro60, not at entry.
            if(!introduction||clock>=60){
                int markerScore=MathHelper.floor(state.markerZ)-5;
                if(z<=115&&z<markerScore)state.markerZ=player.getZ()-this.offset.getZ()+5;
                // Backward finish precedes distance refresh; marker TP above
                // does not refresh that marker's already cached score.
                if(z<=115&&markerScore<z){this.complete(context,player,state,clock,false);continue;}
            }
            state.distance=distance(player.getZ()-this.offset.getZ());
            this.clampSide(context,player);
            var below=context.world().getBlockState(player.getBlockPos().down());
            if(state.distance>=2160||(player.isOnGround()&&state.distance>=2
                    &&!below.isOf(Blocks.RED_SANDSTONE)&&!below.isOf(Blocks.LIGHT)))
                this.complete(context,player,state,clock,true);
        }
        if(introduction)for(ServerPlayerEntity player:context.players()){
            Flight state=this.flights.get(player.getUuid());
            if(state!=null&&!state.left&&state.distance>=1)
                this.nativeTeleport(context,player,new Vec3d(player.getX(),player.getY(),this.offset.getZ()+117),Direction.Axis.Z);
        }
        this.updateScores(context);
        List<Flight> active=this.flights.values().stream().filter(f->!f.left).toList();
        if(!active.isEmpty()&&active.stream().allMatch(f->f.finishedAt>=0))context.finish();
    }

    private void nativeTeleport(PartyRoundContext context,ServerPlayerEntity player,Vec3d target,Direction.Axis absoluteAxis){
        // Match the original /tp relative axes: the vanilla client zeros only
        // absolute-axis velocity. An all-absolute packet spoils glide momentum.
        var flags=EnumSet.allOf(PositionFlag.class);
        flags.remove(switch(absoluteAxis){case X->PositionFlag.X;case Y->PositionFlag.Y;case Z->PositionFlag.Z;});
        player.teleport(context.world(),target.x,target.y,target.z,flags,player.getYaw(),player.getPitch());
        if(!player.isFallFlying()){
            player.setVelocity(player.getVelocity().multiply(1,0,1));player.setOnGround(true);
        }
    }

    private void clampSide(PartyRoundContext context,ServerPlayerEntity player){
        int x=MathHelper.floor(player.getX())-this.offset.getX();
        if(x>=-44||x<=-81)this.nativeTeleport(context,player,new Vec3d(this.offset.getX()+(x>=-44?-45:-80),player.getY(),player.getZ()),Direction.Axis.X);
    }

    private void complete(PartyRoundContext context,ServerPlayerEntity player,Flight state,int elapsed,boolean currentPosition){
        if(state.finishedAt>=0)return;
        if(currentPosition)state.distance=terminalDistance(state.distance,state.cachedY);
        state.finishedAt=elapsed;
        this.statue(context,player,state.seat);
        context.completed(state.owner);player.changeGameMode(GameMode.SPECTATOR);
        player.sendMessage(Text.literal("§a飞行结束："+state.distance+" 格。等待其他玩家完成。"),false);
    }

    private void statue(PartyRoundContext context,ServerPlayerEntity player,int seat){
        ArmorStandEntity stand=new ArmorStandEntity(context.world(),player.getX(),player.getY(),player.getZ());
        stand.refreshPositionAndAngles(player.getX(),player.getY(),player.getZ(),player.getYaw(),player.getPitch());
        stand.setInvulnerable(true);stand.setSilent(true);stand.setShowArms(true);stand.setHideBasePlate(true);
        EquipmentSlot[] slots={EquipmentSlot.FEET,EquipmentSlot.LEGS,EquipmentSlot.CHEST};
        for(int index=0;index<slots.length;index++){
            ItemStack armor=new ItemStack(Native.ARMOR[index]);armor.set(DataComponentTypes.DYED_COLOR,new DyedColorComponent(LEATHER[seat],false));
            stand.equipStack(slots[index],armor);
        }
        ItemStack head=new ItemStack(Items.PLAYER_HEAD);head.set(DataComponentTypes.PROFILE,new ProfileComponent(player.getGameProfile()));
        stand.equipStack(EquipmentSlot.HEAD,head);stand.addCommandTag("party_rocket_launch_stand");
        this.owned.put(stand.getUuid(),stand);context.world().spawnEntity(stand);
        ItemStack item=new ItemStack(Items.FIREWORK_ROCKET);
        int color=FIREWORK[seat],fade=seat==3?16383780:color;
        item.set(DataComponentTypes.FIREWORKS,new FireworksComponent(0,List.of(new FireworkExplosionComponent(
                FireworkExplosionComponent.Type.LARGE_BALL,IntList.of(color),IntList.of(fade),true,true))));
        FireworkRocketEntity firework=new FireworkRocketEntity(context.world(),player.getX(),player.getY(),player.getZ(),item);
        NbtCompound data=new NbtCompound();firework.writeCustomDataToNbt(data);data.putInt("LifeTime",30);firework.readCustomDataFromNbt(data);
        firework.setNoGravity(true);firework.addCommandTag("party_rocket_launch_celebration");
        this.owned.put(firework.getUuid(),firework);context.world().spawnEntity(firework);
    }

    private void updateScores(PartyRoundContext context){
        for(Flight state:this.flights.values())if(!state.left){
            List<Integer> others=this.flights.values().stream().filter(f->!f.left&&f!=state&&f.finishedAt>=0).map(f->f.finishedAt).toList();
            context.score(state.owner,rawScore(state.distance,context.initialPlayerCount(),state.finishedAt,others));
        }
    }

    @Override public ActionResult useItem(PartyRoundContext context,ServerPlayerEntity player,Hand hand){
        Flight state=this.flights.get(player.getUuid());
        return state!=null&&!state.left&&state.finishedAt<0&&player.getStackInHand(hand).isOf(Items.FIREWORK_ROCKET)?ActionResult.PASS:ActionResult.FAIL;
    }
    @Override public ActionResult useBlock(PartyRoundContext context,ServerPlayerEntity player,Hand hand,BlockHitResult hit){
        // Preserve native dungeon chest access and real block-use priority.
        if(context.world().getBlockEntity(hit.getBlockPos()) instanceof LootableContainerBlockEntity)return ActionResult.PASS;
        // Misusing the single native rocket on a block really spends it.
        return this.useItem(context,player,hand);
    }
    private boolean sceneEntity(Entity entity){
        if(this.closed||this.offset==null||entity instanceof ServerPlayerEntity||entity.getWorld()!=this.world)return false;
        double x=entity.getX()-this.offset.getX(),z=entity.getZ()-this.offset.getZ();
        return x>=-83&&x< -40&&z>=-2060&&z<128;
    }
    @Override public boolean ownsEntity(Entity entity){return this.owned.containsKey(entity.getUuid())||this.sceneEntity(entity);}
    @Override public void entityLoaded(PartyRoundContext context,Entity entity){
        if(this.sceneEntity(entity)||entity instanceof FireworkRocketEntity rocket&&rocket.getOwner()!=null&&this.flights.containsKey(rocket.getOwner().getUuid()))
            this.owned.put(entity.getUuid(),entity);
    }
    @Override public ActionResult useEntity(PartyRoundContext context,ServerPlayerEntity player,Hand hand,Entity entity){
        return entity instanceof ChestMinecartEntity||entity instanceof ArmorStandEntity?ActionResult.PASS:ActionResult.FAIL;
    }
    @Override public boolean allowsDamage(PartyRoundContext context,LivingEntity victim,DamageSource source){
        return victim instanceof ServerPlayerEntity&&!(source.getAttacker() instanceof ServerPlayerEntity);
    }
    @Override public void death(PartyRoundContext context,ServerPlayerEntity player){
        player.setHealth(player.getMaxHealth());player.fallDistance=0;context.teleport(player,this.launch);
    }
    @Override public void finish(PartyRoundContext context){this.updateScores(context);}
    private void restore(ServerPlayerEntity player){
        player.stopFallFlying();player.removeStatusEffect(StatusEffects.RESISTANCE);player.removeStatusEffect(StatusEffects.WEAKNESS);
        player.getInventory().remove(stack->true,-1,player.playerScreenHandler.getCraftingInput());
        player.currentScreenHandler.sendContentUpdates();player.playerScreenHandler.onContentChanged(player.getInventory());
    }
    @Override public void leave(PartyRoundContext context,ServerPlayerEntity player){
        Flight state=this.flights.get(player.getUuid());if(state!=null)state.left=true;this.restore(player);
    }
    @Override public void close(PartyRoundContext context){
        if(this.closed)return;this.closed=true;
        this.owned.values().forEach(Entity::discard);this.owned.clear();context.players().forEach(this::restore);
    }
    @Override public String scoreDescription(PartyRoundContext context,UUID player){
        Flight state=this.flights.get(player);return(state==null?0:state.distance)+" 格";
    }
    @Override public String status(PartyRoundContext context){
        long done=this.flights.values().stream().filter(f->!f.left&&f.finishedAt>=0).count();
        return "2160格单烟花滑翔 · "+done+" / "+this.flights.values().stream().filter(f->!f.left).count()+" 已完成";
    }
}
