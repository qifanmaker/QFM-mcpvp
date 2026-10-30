package com.example.pvp.arena.party;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
 * 原版427 Take Flight：完整原洞穴中的原生鞘翅/零飞行时长烟花三圈竞速。
 * 修改原因：简化圆环、直接加速度或把死亡当淘汰会改变原图的洞穴路线、烟花惯性、
 * 原位复活和源码boost81减21的连续接触机制。这里保留材料平面waypoint、随机四组门、
 * 原命令整数跨门判定与局部前移；只用本轮记录取代全服计分板和隐藏盔甲架。
 * 自查：先火箭补给→顺序waypoint/圈数→终局→boost判定/前移→均值换门；不按部分waypoint加分。
 * 源维度为无限噪声世界，导入明确比赛域中的全部原岩体；不增设安全墙或强制回位。
 */
public final class TakeFlightRound implements PartyRound {
    private static final BlockPos ANCHOR = new BlockPos(-305,101,-640);
    private static final String TOKEN = "party_take_flight";
    private static final double[][] PREVIEWS = {{-252.5,22,-646.5},{-334.5,-5,-688.5},{-361.5,-17,-562.5},{-325.5,-12,-545.5}};
    private static final class Native {
        static final BlockState[] WAYPOINTS = {Blocks.EMERALD_BLOCK.getDefaultState(),Blocks.DIAMOND_BLOCK.getDefaultState(),Blocks.NETHERITE_BLOCK.getDefaultState(),Blocks.SPONGE.getDefaultState()};
    }
    private static final int[] WAYPOINT_Y = {-20,-48,-25,10};

    private static final class Pilot {
        final int seat;
        int waypoint,laps,lastCompletion=-1,recharge=11,boost=81,protectionUntil;
        BlockPos previous;
        Vec3d lastPosition;
        float lastYaw,lastPitch;
        boolean finished;
        Pilot(int seat) { this.seat=seat; }
    }
    private record Candidate(int group,Vec3d center,boolean northSouth) { }
    private record Gate(Candidate candidate,DisplayEntity.BlockDisplayEntity glass,DisplayEntity.ItemDisplayEntity lightning) { }

    private final boolean highPing;
    private final String token=UUID.randomUUID().toString();
    private final PartyRoundPlayerState saved=new PartyRoundPlayerState();
    private final Map<UUID,Pilot> pilots=new LinkedHashMap<>();
    private final List<Candidate> candidates=new ArrayList<>();
    private final Map<Integer,Gate> gates=new HashMap<>();
    private final Map<UUID,Entity> entities=new HashMap<>();
    private final Set<UUID> restored=new HashSet<>();
    private ServerWorld world;
    private JsonObject rules;
    private boolean checkpointUsed,started,finished,closed,introPrepared,launched,cageReset;
    private int previousMean=-1;

    public TakeFlightRound() { this(false); }
    /** The source default is false; the true branch preserves its explicit high-ping option. */
    TakeFlightRound(boolean highPing) { this.highPing=highPing; }

    static int rawScore(int players,int laps,int last,List<Integer> others,boolean used) {
        return used?players*laps+(int)others.stream().filter(time->time>last).count():3;
    }
    static int averageGroup(int laps,int waypoints,int players) { return ((4*laps+waypoints)/players)%4+1; }
    static int contactBoost(int boost,boolean highPing) { return boost-(highPing?81:21); }
    static boolean insideAndCrossed(int horizontal,int vertical,int current,int previous,int gateHorizontal,int gateY,int gateNormal) {
        return horizontal>=gateHorizontal-1&&horizontal<=gateHorizontal+1&&vertical>=gateY-1&&vertical<=gateY+1
                &&(current<=gateNormal+1&&previous>=gateNormal-1||current>=gateNormal+1&&previous<=gateNormal-1);
    }

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(ANCHOR);context.timeLimit(2400);this.world=context.world();
        try(var input=TakeFlightRound.class.getResourceAsStream("/pvp/party/take_flight_rules.json")) {
            if(input==null)throw new IOException("Missing Take Flight source rules");
            this.rules=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
        // Format2 is staged as exact X runs by the shared streaming geometry path.
        context.geometry("take_flight");
        int seat=0;
        for(ServerPlayerEntity player:context.players()) {
            Pilot pilot=new Pilot(seat++);this.pilots.put(player.getUuid(),pilot);
            Vec3d source=context.initialPlayerCount()<=4?new Vec3d(PREVIEWS[pilot.seat][0],PREVIEWS[pilot.seat][1],PREVIEWS[pilot.seat][2]):new Vec3d(-235.5,22,-644.5);
            context.spawn(player.getUuid(),context.source(source.x,source.y,source.z));
        }
        for(var encoded:this.rules.getAsJsonArray("boostCandidates")) {
            JsonObject row=encoded.getAsJsonObject();var pos=row.getAsJsonArray("position");
            this.candidates.add(new Candidate(row.get("waypoint").getAsInt(),context.source(pos.get(0).getAsDouble()+.5,pos.get(1).getAsDouble(),pos.get(2).getAsDouble()+.5),row.get("orientation").getAsString().equals("ns")));
        }
    }

    @Override public boolean tracksTerrainChanges() { return true; }
    @Override public boolean handlesTimeLimit() { return true; }

    @Override public void introTick(PartyRoundContext context,int elapsed) {
        if(this.closed)return;
        if(!this.introPrepared) {
            this.introPrepared=true;
            this.cloneCage(context);
            for(ServerPlayerEntity player:context.players()) {
                this.saved.attribute(player,EntityAttributes.GENERIC_MAX_HEALTH,1);
                player.setHealth(1);player.equipStack(EquipmentSlot.CHEST,this.elytra(player));
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING,60,0,false,false));
            }
        }
        if(elapsed>=60&&!this.launched) {
            this.launched=true;
            if(context.initialPlayerCount()<=4) {
                for(BlockPos pos:BlockPos.iterate(context.sourceBlock(-247,14,-640),context.sourceBlock(-228,26,-640))) {
                    if(this.world.getBlockState(pos).isAir())context.set(pos,Blocks.POLISHED_ANDESITE.getDefaultState());
                }
            } else {
                for(BlockPos pos:BlockPos.iterate(context.sourceBlock(-240,21,-646),context.sourceBlock(-232,24,-644))) {
                    boolean edge=pos.getX()==context.sourceBlock(-240,0,0).getX()||pos.getX()==context.sourceBlock(-232,0,0).getX()
                            ||pos.getY()==context.sourceBlock(0,21,0).getY()||pos.getY()==context.sourceBlock(0,24,0).getY()
                            ||pos.getZ()==context.sourceBlock(0,0,-646).getZ()||pos.getZ()==context.sourceBlock(0,0,-644).getZ();
                    context.set(pos,edge?Blocks.GLASS.getDefaultState():Blocks.AIR.getDefaultState());
                }
            }
            for(ServerPlayerEntity player:context.players()) {
                Pilot pilot=this.pilots.get(player.getUuid());
                Vec3d position=context.initialPlayerCount()<=4?context.source(-232.5-2*pilot.seat,22,-644.5):context.source(-235.5,22,-644.5);
                context.spawn(player.getUuid(),position);player.setYaw(180);player.setPitch(0);context.teleport(player,position);
            }
        }
        if(elapsed>=120&&!this.cageReset) {this.cageReset=true;this.cloneCage(context);}
        this.effects(context);
    }

    /** Source clone copies all108 cells, including air, at setup and rule2. */
    private void cloneCage(PartyRoundContext context) {
        List<BlockState> copy=new ArrayList<>(108);
        for(BlockPos pos:BlockPos.iterate(context.sourceBlock(-238,31,-645),context.sourceBlock(-230,34,-643)))copy.add(this.world.getBlockState(pos));
        int index=0;
        for(BlockPos pos:BlockPos.iterate(context.sourceBlock(-240,21,-646),context.sourceBlock(-232,24,-644)))context.set(pos,copy.get(index++));
    }

    @Override public void start(PartyRoundContext context) {
        this.started=true;
        for(BlockPos pos:BlockPos.iterate(context.sourceBlock(-240,21,-646),context.sourceBlock(-232,24,-644)))if(this.world.getBlockState(pos).isOf(Blocks.GLASS))context.set(pos,Blocks.AIR.getDefaultState());
        for(BlockPos pos:BlockPos.iterate(context.sourceBlock(-247,14,-640),context.sourceBlock(-228,26,-640)))if(this.world.getBlockState(pos).isOf(Blocks.POLISHED_ANDESITE))context.set(pos,Blocks.AIR.getDefaultState());
        for(ServerPlayerEntity player:context.players()) {
            Pilot pilot=this.pilots.get(player.getUuid());pilot.lastPosition=player.getPos();pilot.lastYaw=player.getYaw();pilot.lastPitch=player.getPitch();
            this.saved.attribute(player,EntityAttributes.GENERIC_MAX_HEALTH,1);player.setHealth(1);
            // Shared PLAY preparation clears the intro inventory, including chest equipment.
            player.equipStack(EquipmentSlot.CHEST,this.elytra(player));player.currentScreenHandler.sendContentUpdates();
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING,60,0,false,false));
            context.score(player.getUuid(),3);
            player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,SoundCategory.MASTER,10,0);
        }
        for(int group=1;group<=4;group++)this.reroll(context,group);
        this.effects(context);
        context.title("§a起飞！","§f鞘翅＋烟花，穿过完整洞穴完成三圈");
        context.announce("§a起飞竞速：双击跳跃展开鞘翅，右键使用零时长烟花；每次消耗后至少10tick补充一枚。"
                +"\n§e按顺序经过四段地标完成一圈，黄色门提供原版持续前移加成；碰撞死亡会原位恢复并失去惯性。"
                +"\n§f先完成三圈者领先；限时120秒，按圈数和同圈先后排名。");
    }

    @Override public void tick(PartyRoundContext context,int elapsed) {
        if(!this.started||this.finished||this.closed)return;
        this.effects(context);this.cleanEntities();
        for(ServerPlayerEntity player:context.players()) {
            Pilot pilot=this.pilots.get(player.getUuid());if(pilot==null||pilot.finished)continue;
            this.saved.attribute(player,EntityAttributes.GENERIC_MAX_HEALTH,1);
            if(pilot.recharge>=0&&pilot.recharge<=10)pilot.recharge++;
            if(pilot.recharge==10)player.giveItemStack(this.rocket());
            if(pilot.recharge==11&&player.getInventory().count(Items.FIREWORK_ROCKET)==0)pilot.recharge=0;
            // The source tests fixed absolute material Y using player X/Z, with no height restriction.
            for(int waypoint=0;waypoint<4;waypoint++)if(pilot.waypoint==waypoint) {
                BlockPos feet=player.getBlockPos();int y=context.sourceBlock(0,WAYPOINT_Y[waypoint],0).getY();
                if(this.world.getBlockState(new BlockPos(feet.getX(),y,feet.getZ())).isOf(Native.WAYPOINTS[waypoint].getBlock()))pilot.waypoint++;
            }
            if(pilot.waypoint==4) {
                pilot.laps++;pilot.lastCompletion=elapsed;this.checkpointUsed=true;pilot.waypoint=0;
                player.sendMessage(Text.literal("§a已完成 "+pilot.laps+"/3 圈"),false);
                if(pilot.laps==3) {
                    pilot.finished=true;player.stopFallFlying();context.completed(player.getUuid());player.changeGameMode(GameMode.SPECTATOR);
                    context.announce("§a"+player.getName().getString()+" 已完成起飞竞速！");
                }
            }
        }
        this.scores(context);
        long done=this.pilots.values().stream().filter(pilot->pilot.finished).count();
        if(elapsed>=2400||done>=context.initialPlayerCount()-1||context.alive().size()<=1) {context.finish();return;}
        for(ServerPlayerEntity player:context.players()) {
            Pilot pilot=this.pilots.get(player.getUuid());if(pilot==null)continue;
            BlockPos current=player.getBlockPos();
            if(pilot.previous!=null&&(this.hit(current,pilot.previous,true,player.getPos())||this.hit(current,pilot.previous,false,player.getPos()))) {
                pilot.boost=contactBoost(pilot.boost,this.highPing);
                player.playSoundToPlayer(SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(),SoundCategory.MASTER,10,1);
            }
            int maximum=this.highPing?80:20;
            if(pilot.boost<=maximum)pilot.boost++;
            if(!pilot.finished&&pilot.boost<=maximum&&(!this.highPing||elapsed%4==0)) {
                Vec3d target=player.getPos().add(player.getRotationVec(1));
                if(this.world.getBlockState(BlockPos.ofFloored(target)).isOf(Blocks.AIR))this.nativeForward(player,target);
            }
            pilot.previous=player.getBlockPos();pilot.lastPosition=player.getPos();pilot.lastYaw=player.getYaw();pilot.lastPitch=player.getPitch();
        }
        int laps=this.pilots.values().stream().mapToInt(pilot->pilot.laps).sum(),waypoints=this.pilots.values().stream().mapToInt(pilot->pilot.waypoint).sum();
        int mean=averageGroup(laps,waypoints,context.initialPlayerCount());
        if(mean!=this.previousMean)this.reroll(context,mean);
        this.previousMean=mean;
    }

    private void scores(PartyRoundContext context) {
        List<Integer> times=this.pilots.values().stream().map(pilot->pilot.lastCompletion).toList();
        this.pilots.forEach((uuid,pilot)->context.score(uuid,rawScore(context.initialPlayerCount(),pilot.laps,pilot.lastCompletion,times,this.checkpointUsed)));
    }
    private boolean hit(BlockPos current,BlockPos previous,boolean northSouth,Vec3d player) {
        Candidate nearest=null;double distance=Double.MAX_VALUE;
        for(Gate gate:this.gates.values())if(gate.candidate().northSouth()==northSouth) {
            double squared=gate.candidate().center().squaredDistanceTo(player);
            if(squared<distance){distance=squared;nearest=gate.candidate();}
        }
        if(nearest==null)return false;
        BlockPos gate=BlockPos.ofFloored(nearest.center());
        return northSouth?insideAndCrossed(current.getX(),current.getY(),current.getZ(),previous.getZ(),gate.getX(),gate.getY(),gate.getZ())
                :insideAndCrossed(current.getZ(),current.getY(),current.getX(),previous.getX(),gate.getZ(),gate.getY(),gate.getX());
    }
    private void nativeForward(ServerPlayerEntity player,Vec3d target) {
        Vec3d velocity=player.getVelocity();boolean gliding=player.isFallFlying();
        player.teleport(this.world,target.x,target.y,target.z,Set.of(),player.getYaw(),player.getPitch());
        player.setVelocity(gliding?velocity:velocity.multiply(1,0,1));
        if(!gliding)player.setOnGround(true);
        player.velocityDirty=true;player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
    }
    private void reroll(PartyRoundContext context,int group) {
        Gate old=this.gates.remove(group);
        if(old!=null){old.glass().discard();old.lightning().discard();this.entities.remove(old.glass().getUuid());this.entities.remove(old.lightning().getUuid());}
        List<Candidate> choices=this.candidates.stream().filter(candidate->candidate.group()==group).toList();
        Candidate candidate=choices.get(context.random().nextInt(choices.size()));
        var glass=new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY,this.world);
        boolean ns=candidate.northSouth();
        glass.setBlockState(Blocks.YELLOW_STAINED_GLASS_PANE.getDefaultState().with(Properties.EAST,ns).with(Properties.WEST,ns).with(Properties.NORTH,!ns).with(Properties.SOUTH,!ns));
        glass.setPosition(candidate.center());glass.setViewRange(5);glass.setBillboardMode(DisplayEntity.BillboardMode.FIXED);
        glass.setTransformation(new AffineTransformation(new Vector3f(-1.5f,-1,-1.5f),new Quaternionf(),new Vector3f(3),new Quaternionf()));
        glass.addCommandTag("party_take_flight_boost");glass.addCommandTag("waypoint"+group);glass.addCommandTag(ns?"ns":"ew");
        var lightning=new DisplayEntity.ItemDisplayEntity(EntityType.ITEM_DISPLAY,this.world);
        lightning.setPosition(candidate.center().add(0,.5,0));lightning.setItemStack(new ItemStack(Items.YELLOW_DYE));lightning.setBillboardMode(DisplayEntity.BillboardMode.VERTICAL);
        lightning.addCommandTag("party_take_flight_lightning");
        this.entities.put(glass.getUuid(),glass);this.entities.put(lightning.getUuid(),lightning);
        this.world.spawnEntity(glass);this.world.spawnEntity(lightning);this.gates.put(group,new Gate(candidate,glass,lightning));
    }

    private ItemStack owned(ItemStack stack) {NbtCompound data=new NbtCompound();data.putString(TOKEN,this.token);stack.set(DataComponentTypes.CUSTOM_DATA,NbtComponent.of(data));return stack;}
    private boolean own(ItemStack stack) {return this.token.equals(stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().getString(TOKEN));}
    private ItemStack elytra(ServerPlayerEntity player) {
        ItemStack stack=this.owned(new ItemStack(Items.ELYTRA));stack.set(DataComponentTypes.UNBREAKABLE,new UnbreakableComponent(false));stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,false);
        player.getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.BINDING_CURSE).ifPresent(entry->stack.addEnchantment(entry,1));return stack;
    }
    private ItemStack rocket() {ItemStack stack=this.owned(new ItemStack(Items.FIREWORK_ROCKET));stack.set(DataComponentTypes.FIREWORKS,new FireworksComponent(0,List.of()));return stack;}
    private void effects(PartyRoundContext context) {
        for(ServerPlayerEntity player:context.players())if(!this.pilots.get(player.getUuid()).finished) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION,80,0,false,false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,20,99,false,false));
        }
    }
    private boolean active(PartyRoundContext context,ServerPlayerEntity player) {
        Pilot pilot=this.pilots.get(player.getUuid());return this.started&&!this.finished&&!this.closed&&pilot!=null&&!pilot.finished&&!context.isOut(player.getUuid())&&player.getWorld()==this.world;
    }
    @Override public ActionResult useItem(PartyRoundContext context,ServerPlayerEntity player,Hand hand) {
        if(!this.active(context,player))return ActionResult.FAIL;
        ItemStack held=player.getStackInHand(hand);
        return held.isOf(Items.FIREWORK_ROCKET)?this.own(held)&&player.isFallFlying()?ActionResult.PASS:ActionResult.FAIL:ActionResult.PASS;
    }
    @Override public ActionResult useBlock(PartyRoundContext context,ServerPlayerEntity player,Hand hand,BlockHitResult hit) {
        // Source Adventure still permits native container/rail/door interactions.
        return this.active(context,player)?this.useItem(context,player,hand):ActionResult.FAIL;
    }
    @Override public boolean allowsDamage(PartyRoundContext context,LivingEntity victim,DamageSource source) {
        if(!(victim instanceof ServerPlayerEntity player)||!this.active(context,player)||source.getAttacker() instanceof ServerPlayerEntity)return false;
        Pilot pilot=this.pilots.get(player.getUuid());return context.elapsed()>=pilot.protectionUntil||source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }
    @Override public void death(PartyRoundContext context,ServerPlayerEntity player) {
        Pilot pilot=this.pilots.get(player.getUuid());if(pilot==null||pilot.finished)return;
        Vec3d position=pilot.lastPosition==null?player.getPos():pilot.lastPosition;
        player.closeHandledScreen();player.stopFallFlying();player.setHealth(1);player.setFireTicks(0);player.fallDistance=0;
        player.timeUntilRegen=0;player.hurtTime=0;player.setAttacker(null);player.setAttacking(null);
        player.setYaw(pilot.lastYaw);player.setPitch(pilot.lastPitch);context.teleport(player,position);
        pilot.boost=81;pilot.previous=null;pilot.protectionUntil=context.elapsed()+40;
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE,40,99,false,false));
        player.sendMessage(Text.literal("§e已在原位恢复，圈数保留；重新展开鞘翅继续飞行。"),false);
    }
    @Override public boolean ownsEntity(Entity entity) {
        return !this.closed&&entity.getWorld()==this.world&&(this.entities.containsKey(entity.getUuid())
                ||entity instanceof FireworkRocketEntity rocket&&this.own(rocket.getStack())
                ||entity instanceof ItemEntity item&&this.own(item.getStack()));
    }
    @Override public void entityLoaded(PartyRoundContext context,Entity entity) {
        if(!this.ownsEntity(entity))return;
        if(entity instanceof ItemEntity){entity.discard();return;}
        if(entity instanceof FireworkRocketEntity){entity.addCommandTag("party_take_flight_rocket");this.entities.put(entity.getUuid(),entity);}
    }
    private void cleanEntities() {this.entities.values().removeIf(entity->entity.isRemoved());}
    private void restore(ServerPlayerEntity player) {
        if(!this.restored.add(player.getUuid()))return;
        player.stopFallFlying();this.saved.restore(player);player.setHealth(player.getMaxHealth());
        for(var effect:List.of(StatusEffects.NIGHT_VISION,StatusEffects.WEAKNESS,StatusEffects.SLOW_FALLING,StatusEffects.RESISTANCE))player.removeStatusEffect(effect);
        for(int slot=0;slot<player.getInventory().size();slot++)if(this.own(player.getInventory().getStack(slot)))player.getInventory().setStack(slot,ItemStack.EMPTY);
        player.currentScreenHandler.sendContentUpdates();
    }
    @Override public void leave(PartyRoundContext context,ServerPlayerEntity player) {this.restore(player);}
    @Override public void finish(PartyRoundContext context) {if(this.finished)return;this.scores(context);this.finished=true;context.players().forEach(this::restore);}
    @Override public void close(PartyRoundContext context) {context.players().forEach(this::restore);this.entities.values().forEach(Entity::discard);this.entities.clear();this.gates.clear();this.closed=true;}
    @Override public String scoreDescription(PartyRoundContext context,UUID player) {Pilot pilot=this.pilots.get(player);return (pilot==null?0:pilot.laps)+" / 3 圈（"+context.score(player)+"分）";}
    @Override public String status(PartyRoundContext context) {return "起飞竞速 · "+Math.max(0,(2400-context.elapsed()+19)/20)+"秒 · 黄色门持续加成 · "+(this.highPing?"高延迟模式":"普通模式");}
}
