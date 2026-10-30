package com.example.pvp.arena.party;

import com.google.gson.JsonParser;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.joml.Matrix4f;
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
 * 原版410 Weapon Devolution，依据七份weapon_devolution/end_gladiators、sentry箭链及checkpoint。
 * 修改原因：旧gladiators残留的铁甲/盾牌/淘汰规则并不是当前410；直接执行全服选择器还会跨场改分和射弹。
 * 保留五阶段原生武器、量化激光速度、伤害1.25的原生箭、与雪球精确配对的Pierce100箭、无限复活、
 * 每tick至多一次升级、原生近期击杀归属与死亡后失去再生，五轮框架仅将冻结后的原分转换为累计积分。
 * 自查：四塔克隆含空气，48根门柱与16/32/48tick开门、5人以上整数角度圆环、19tick激光冷却、
 * 30tick雪球补充和N-1完赛后2tick结束均依源实现；所有队伍、掉落、实体和药效只作用于本局。
 */
public final class WeaponDevolutionRound implements PartyRound {
    private static final String TOKEN_KEY = "pvp_party_weapon_devolution";
    private static final Item[] ITEMS = {Items.AIR,Items.CARROT_ON_A_STICK,Items.NETHERITE_AXE,Items.TRIDENT,Items.WOODEN_SWORD,Items.SNOWBALL};
    private static final String[] NAMES = {"", "§4§lLaser Gun", "§8§lMighty Axe", "§bTrident", "§6§lWeak Sword", "§fSharp Snowballs"};
    private static final double[][] STARTS = {{-253,25,-345},{-222,25,-376},{-253,25,-407},{-284,25,-376}};
    private static final float[] YAWS = {180,90,0,-90};
    private static final int[][] GATES = {{-283,-283,-380,-373},{-257,-250,-347,-347},{-224,-224,-380,-373},{-257,-250,-406,-406}};
    private record Start(Vec3d position, float yaw) { }
    private record Beam(DisplayEntity.BlockDisplayEntity parent, DisplayEntity.BlockDisplayEntity child, int expires) { }
    private record SnowPair(SnowballEntity snowball, ArrowEntity arrow) { }
    private static final class Fighter {
        final Start start;
        int weapon = 1;
        int lastKillTick;
        int laserReadyAt;
        int snowSupply;
        int snowSupplyTick;
        int protection;
        int respawnAt = -1;
        Fighter(Start start) { this.start = start; }
    }
    private final String token = UUID.randomUUID().toString();
    private final Map<UUID,Fighter> fighters = new LinkedHashMap<>();
    private final Map<UUID,ProjectileEntity> projectiles = new HashMap<>();
    private final Map<UUID,UUID> projectileOwners = new HashMap<>();
    private final Map<UUID,SnowPair> snowPairs = new HashMap<>();
    private final Set<UUID> pendingKills = new HashSet<>();
    private final Set<UUID> pendingLasers = new HashSet<>();
    private final List<Beam> beams = new ArrayList<>();
    private final List<DisplayEntity.BlockDisplayEntity> bars = new ArrayList<>();
    private final Set<UUID> displays = new HashSet<>();
    private ServerWorld world;
    private Box bounds;
    private Team laserTeam;
    private boolean started;
    private boolean finished;
    private int endingAt = -1;

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(new BlockPos(-253,22,-376));
        context.timeLimit(2400);
        this.world = context.world();
        this.bounds = new Box(context.source(-302,-30,-425),context.source(-206,96,-328));
        PartyMapBlueprint.load("weapon_devolution_arena").forEachTranslated(BlockPos.ORIGIN,(source,state)->{
            // Source fill is replace light[level=0], not a solid gate box: basalt/deepslate frames must survive.
            boolean gate=false;
            if(source.getY()>=25&&source.getY()<=27)for(int[] bounds:GATES) {
                if(source.getX()>=bounds[0]&&source.getX()<=bounds[1]&&source.getZ()>=bounds[2]&&source.getZ()<=bounds[3])gate=true;
            }
            BlockState staged=gate&&state.isOf(Blocks.LIGHT)&&state.get(net.minecraft.state.property.Properties.LEVEL_15)==0?Blocks.BARRIER.getDefaultState():state;
            context.stage(context.sourceBlock(source.getX(),source.getY(),source.getZ()),staged);
        });
        for (int tower=1;tower<=4;tower++) {
            PartyMapBlueprint blueprint = PartyMapBlueprint.load("weapon_devolution_tower_"+tower);
            BlockPos translation = context.sourceBlock(0,72,0);
            blueprint.forEachPosition(translation,p -> context.stage(p,Blocks.AIR.getDefaultState()));
            blueprint.forEachTranslated(translation,context::stage);
        }
        List<ServerPlayerEntity> players=context.players();
        for (int seat=0;seat<players.size();seat++) {
            Start start;
            if (players.size()<=4) start=new Start(context.source(STARTS[seat][0],STARTS[seat][1],STARTS[seat][2]),YAWS[seat]);
            else {
                int angle=seat*(360/players.size());
                Vec3d forward=new Vec3d(MathHelper.cos((angle+90f)*(float)(Math.PI/180)),0,MathHelper.sin((angle+90f)*(float)(Math.PI/180)));
                Vec3d center=context.source(-253,22,-376),position=center;
                for(int step=0;step<24;step++) position=position.add(forward);
                float yaw=MathHelper.wrapDegrees((float)(MathHelper.atan2(center.z-position.z,center.x-position.x)*180/Math.PI)-90);
                start=new Start(position,yaw);
            }
            this.fighters.put(players.get(seat).getUuid(),new Fighter(start));
            context.spawn(players.get(seat).getUuid(),start.position());
        }
    }

    @Override public void start(PartyRoundContext context) {
        this.started=true;
        String teamName;
        var scoreboard=this.world.getServer().getScoreboard();
        do {teamName="p2w_"+UUID.randomUUID().toString().replace("-","").substring(0,12);} while(scoreboard.getTeam(teamName)!=null);
        this.laserTeam=scoreboard.addTeam(teamName);
        this.laserTeam.setColor(Formatting.DARK_RED);
        try { this.createBars(context); } catch(IOException e) { throw new IllegalStateException("Missing audited 410 gate data",e); }
        for(ServerPlayerEntity player:context.players()) {
            Fighter fighter=this.fighters.get(player.getUuid());
            resetLife(player);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION,19999980,0,false,false));
            this.face(player,fighter.start.yaw());
            context.score(player.getUuid(),0);
            this.ensureWeapon(player,fighter,0);
            this.weakness(player,fighter.weapon);
            player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,SoundCategory.MASTER,10,1);
            player.playSoundToPlayer(SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(),SoundCategory.MASTER,10,0);
        }
        context.announce("§a武器退化：击杀依次切换激光枪、下界合金斧、三叉戟、木剑、锐利雪球。"
                +"\n§e两分钟，无限次复活；死亡会失去开局再生。每次击杀推进一级，完成最后武器后观战。");
    }

    @Override public boolean playerCombatEnabled() { return !this.finished; }
    @Override public boolean allowsNaturalRegeneration() { return false; }
    @Override public boolean handlesTimeLimit() { return true; }

    @Override public void tick(PartyRoundContext context,int elapsed) {
        if(!this.started||this.finished)return;
        if(this.endingAt>=0 && elapsed>=this.endingAt){context.finish();return;}
        if(context.initialPlayerCount()<=4 && elapsed<=48 && elapsed%16==0)this.releaseLayer(context,elapsed/16);
        this.removeExpiredProjectiles();
        this.beams.removeIf(beam->{if(elapsed>=beam.expires()||beam.parent().isRemoved()) {this.removeDisplay(beam.child());this.removeDisplay(beam.parent());return true;}return false;});
        for(ServerPlayerEntity player:context.players()) {
            Fighter fighter=this.fighters.get(player.getUuid());
            if(fighter==null)continue;
            if(fighter.respawnAt>=0 && elapsed>=fighter.respawnAt) {
                fighter.respawnAt=-1;resetLife(player);player.changeGameMode(GameMode.ADVENTURE);
                BlockPos block=BlockPos.ofFloored(fighter.start.position());
                // Native forced spawnpoint centers block X/Z and places feet at Y+.1, unlike initial decimal tp.
                context.teleport(player,new Vec3d(block.getX()+.5,block.getY()+.1,block.getZ()+.5));
                this.face(player,fighter.start.yaw());fighter.protection=elapsed+60;
            }
            if(fighter.respawnAt>=0||context.isOut(player.getUuid()))continue;
            this.ensureWeapon(player,fighter,elapsed);
            if(this.pendingLasers.remove(player.getUuid()) && fighter.weapon==1 && elapsed>=fighter.laserReadyAt) {
                this.shootLaser(player,elapsed);
                fighter.laserReadyAt=elapsed+19;
                this.clearItem(player,Items.CARROT_ON_A_STICK);
                player.setStackInHand(Hand.MAIN_HAND,this.weaponStack(1,true));
            }
        }
        // The source stat can exceed one during a tick, but its selector advances each player only once.
        for(ServerPlayerEntity player:context.players()) {
            Fighter fighter=this.fighters.get(player.getUuid());
            if(fighter==null||!this.pendingKills.remove(player.getUuid()))continue;
            player.getInventory().clear();
            player.playSoundToPlayer(SoundEvents.ENTITY_ZOMBIE_ATTACK_IRON_DOOR,SoundCategory.MASTER,10,.5f);
            fighter.weapon++;fighter.lastKillTick=elapsed;
            if(fighter.weapon==3)player.setStackInHand(Hand.MAIN_HAND,this.weaponStack(3,false));
            if(fighter.weapon==6) {
                context.completed(player.getUuid());
                context.eliminate(player,"§a已用最后的锐利雪球完成击杀，正在观看其他玩家。");
                context.announce("§a"+player.getName().getString()+" 已完成全部五种武器！");
            } else if(fighter.weapon<=5)player.sendMessage(Text.literal("§a击杀成功，下一把武器："+NAMES[fighter.weapon]),false);
        }
        this.updateScores(context);
        for(ServerPlayerEntity player:context.players()) {
            Fighter fighter=this.fighters.get(player.getUuid());
            if(fighter!=null)this.weakness(player,fighter.weapon);
        }
        // Source N-1 finish threshold; the server's permanent-forfeit policy removes departed seats.
        if(context.alive().size()<=1 && this.endingAt<0)this.endingAt=elapsed+2;
        if(elapsed>=2400)context.finish();
    }

    private void ensureWeapon(ServerPlayerEntity player,Fighter fighter,int elapsed) {
        if(fighter.weapon>=6)return;
        Item type=ITEMS[fighter.weapon];
        if(fighter.weapon==5) {
            fighter.snowSupplyTick--;
            if(fighter.snowSupplyTick==0 && fighter.snowSupply>=2)fighter.snowSupplyTick++;
            if(fighter.snowSupplyTick<=0){fighter.snowSupply++;fighter.snowSupplyTick+=30;}
            int amount=Math.min(2,Math.max(0,fighter.snowSupply));
            ItemStack stack=amount==0?ItemStack.EMPTY:this.weaponStack(5,false).copyWithCount(amount);
            if(!ItemStack.areEqual(player.getInventory().getStack(0),stack))player.getInventory().setStack(0,stack);
            return;
        }
        if(fighter.weapon==1 && elapsed<fighter.laserReadyAt)return;
        int count=player.getInventory().count(type);
        if(count>=2)this.clearItem(player,type);
        else if(count==0)player.getInventory().insertStack(this.weaponStack(fighter.weapon,false));
        else if(fighter.weapon==1) {
            for(int slot=0;slot<player.getInventory().size();slot++)if(player.getInventory().getStack(slot).isOf(type)) {
                ItemStack ready=this.weaponStack(1,false);
                if(!ItemStack.areItemsAndComponentsEqual(player.getInventory().getStack(slot),ready))player.getInventory().setStack(slot,ready);
            }
        }
    }

    private ItemStack weaponStack(int stage,boolean cooling) {
        ItemStack stack=new ItemStack(ITEMS[stage]);
        NbtCompound data=new NbtCompound();data.putString(TOKEN_KEY,this.token);
        stack.set(DataComponentTypes.CUSTOM_DATA,NbtComponent.of(data));
        stack.set(DataComponentTypes.CUSTOM_NAME,Text.literal(NAMES[stage]));
        if(stage>=2 && stage<=4)stack.set(DataComponentTypes.UNBREAKABLE,new UnbreakableComponent(false));
        if(stage==1)stack.set(DataComponentTypes.CUSTOM_MODEL_DATA,new CustomModelDataComponent(cooling?11:10));
        return stack;
    }

    private void releaseLayer(PartyRoundContext context,int layer) {
        for(ServerPlayerEntity player:context.players())player.playSoundToPlayer(SoundEvents.BLOCK_PISTON_CONTRACT,SoundCategory.MASTER,10,.5f);
        int y=24+layer;
        for(int[] gate:GATES)for(BlockPos p:BlockPos.iterate(gate[0],y,gate[2],gate[1],y,gate[3])) {
            BlockPos target=context.sourceBlock(p.getX(),p.getY(),p.getZ());
            if(this.world.getBlockState(target).isOf(Blocks.BARRIER))context.set(target,Blocks.LIGHT.getDefaultState().with(net.minecraft.state.property.Properties.LEVEL_15,0));
        }
        if(layer==1)for(var display:this.bars)display.refreshPositionAndAngles(display.getX(),display.getY()+2.95,display.getZ(),display.getYaw(),display.getPitch());
    }

    private static Vec3d local(float yaw,float pitch) {
        float radians=(float)(Math.PI/180);
        float x=MathHelper.cos((yaw+90f)*radians),z=MathHelper.sin((yaw+90f)*radians);
        float horizontal=MathHelper.cos(-pitch*radians),vertical=MathHelper.sin(-pitch*radians);
        return new Vec3d(x*horizontal,vertical,z*horizontal);
    }
    private static int quantizedDifference(double before,double after) {return MathHelper.floor(before*-1000)-MathHelper.floor(after*-1000);}
    /** Same floor-difference trig as the sentry marker commands, with translation-invariant thousandths. */
    static Vec3d laserMotion(Vec3d feet,float yaw,float pitch) {
        Vec3d pitched=local(0,pitch),horizontal=local(yaw,0);
        int vertical=quantizedDifference(feet.z,feet.z+pitched.z);
        int x=quantizedDifference(feet.x,feet.x+horizontal.x),z=quantizedDifference(feet.z,feet.z+horizontal.z);
        int y=quantizedDifference(feet.y,feet.y+pitched.y);
        return new Vec3d(x*vertical*.00001,y*.01,z*vertical*.00001);
    }

    private void shootLaser(ServerPlayerEntity player,int elapsed) {
        player.playSoundToPlayer(SoundEvent.of(Identifier.of("mcp:laser")),SoundCategory.MASTER,1,1);
        Vec3d direction=local(player.getYaw(),player.getPitch());
        Vec3d position=player.getEyePos().add(direction.multiply(.5));
        ArrowEntity arrow=new ArrowEntity(this.world,position.x,position.y,position.z,Items.ARROW.getDefaultStack(),null);
        arrow.addCommandTag("party_wd_laser");
        arrow.setOwner(player);arrow.setDamage(1.25);arrow.setSilent(true);
        arrow.setVelocity(laserMotion(player.getPos(),player.getYaw(),player.getPitch()));
        arrow.pickupType=PersistentProjectileEntity.PickupPermission.DISALLOWED;
        this.projectiles.put(arrow.getUuid(),arrow);this.world.spawnEntity(arrow);
        Vec3d displayPos=player.getEyePos().add(local(player.getYaw(),player.getPitch()-90).multiply(-.1));
        var parent=this.display(displayPos,player.getYaw(),player.getPitch());
        var child=this.display(displayPos,player.getYaw(),player.getPitch());
        child.setBlockState(Blocks.RED_STAINED_GLASS_PANE.getDefaultState());
        child.setTeleportDuration(1);
        // The NBT codec reads row-major numbers, while JOML's float[] setter is column-major.
        child.setTransformation(new AffineTransformation(new Matrix4f().set(new float[]{1,0,0,-.5f,0,0,-1,.5f,0,45,0,0,0,0,0,1}).transpose()));
        this.world.spawnEntity(parent);this.world.spawnEntity(child);child.startRiding(parent,true);
        this.beams.add(new Beam(parent,child,elapsed+4));
    }

    @Override public boolean ownsEntity(Entity entity) {
        if(this.finished||entity.getWorld()!=this.world)return false;
        if(this.displays.contains(entity.getUuid())||this.projectiles.containsKey(entity.getUuid()))return true;
        if(entity instanceof ProjectileEntity shot && shot.getOwner()!=null && this.fighters.containsKey(shot.getOwner().getUuid())) {
            return (shot instanceof TridentEntity || shot instanceof SnowballEntity) && this.bounds.expand(80).contains(shot.getPos());
        }
        if(entity instanceof ItemEntity item) {
            NbtComponent data=item.getStack().get(DataComponentTypes.CUSTOM_DATA);
            return data!=null && this.token.equals(data.copyNbt().getString(TOKEN_KEY));
        }
        return false;
    }

    @Override public void entityLoaded(PartyRoundContext context,Entity entity) {
        if(!this.ownsEntity(entity))return;
        if(entity instanceof ItemEntity){entity.discard();return;}
        if(entity instanceof ProjectileEntity shot) {
            if(shot.getOwner()!=null)this.projectileOwners.put(shot.getUuid(),shot.getOwner().getUuid());
            shot.addCommandTag("party_wd_projectile");
            if(shot instanceof TridentEntity)shot.addCommandTag("party_wd_trident");
            this.projectiles.put(shot.getUuid(),shot);
            if(shot instanceof PersistentProjectileEntity persistent)persistent.pickupType=PersistentProjectileEntity.PickupPermission.DISALLOWED;
            if(shot instanceof SnowballEntity snowball && shot.getOwner() instanceof ServerPlayerEntity owner) {
                snowball.addCommandTag("party_wd_snowball");
                Fighter fighter=this.fighters.get(owner.getUuid());
                if(fighter==null||fighter.weapon!=5)return;
                fighter.snowSupply=Math.max(0,fighter.snowSupply-1);
                ArrowEntity arrow=new ArrowEntity(this.world,snowball.getX(),snowball.getY(),snowball.getZ(),Items.ARROW.getDefaultStack(),null);
                arrow.addCommandTag("party_wd_snow_arrow");
                arrow.setOwner(owner);arrow.setSilent(true);arrow.setInvisible(true);
                setPiercing(arrow,100);arrow.setVelocity(snowball.getVelocity());
                arrow.pickupType=PersistentProjectileEntity.PickupPermission.DISALLOWED;
                this.projectiles.put(arrow.getUuid(),arrow);this.snowPairs.put(snowball.getUuid(),new SnowPair(snowball,arrow));
                this.world.spawnEntity(arrow);
            }
        }
    }

    private static void setPiercing(PersistentProjectileEntity arrow,int level) {
        NbtCompound data=new NbtCompound();arrow.writeCustomDataToNbt(data);data.putByte("PierceLevel",(byte)level);arrow.readCustomDataFromNbt(data);
    }
    private void removeExpiredProjectiles() {
        this.snowPairs.values().removeIf(pair->{
            if(pair.arrow().isRemoved()||pair.snowball().isRemoved())return true;
            pair.arrow().setPosition(pair.snowball().getPos());pair.arrow().setVelocity(pair.snowball().getVelocity());return false;
        });
        this.projectiles.values().removeIf(shot->{
            if(shot.isRemoved()){this.projectileOwners.remove(shot.getUuid());return true;}
            if(shot instanceof PersistentProjectileEntity persistent) {
                NbtCompound data=new NbtCompound();persistent.writeCustomDataToNbt(data);
                if(data.getBoolean("inGround")){shot.discard();this.projectileOwners.remove(shot.getUuid());return true;}
            }
            return false;
        });
        for(ItemEntity item:this.world.getEntitiesByClass(ItemEntity.class,this.bounds.expand(80),this::ownsEntity))item.discard();
    }

    @Override public ActionResult useItem(PartyRoundContext context,ServerPlayerEntity player,Hand hand) {
        Fighter fighter=this.fighters.get(player.getUuid());
        if(!this.active(context,player)||fighter==null)return ActionResult.FAIL;
        if(fighter.weapon==1 && player.getStackInHand(hand).isOf(Items.CARROT_ON_A_STICK)) {
            if(context.elapsed()>=fighter.laserReadyAt)this.pendingLasers.add(player.getUuid());
            return ActionResult.SUCCESS;
        }
        return fighter.weapon==3 && player.getStackInHand(hand).isOf(Items.TRIDENT)
                || fighter.weapon==5 && player.getStackInHand(hand).isOf(Items.SNOWBALL)?ActionResult.PASS:ActionResult.FAIL;
    }
    @Override public ActionResult useBlock(PartyRoundContext context,ServerPlayerEntity player,Hand hand,BlockHitResult hit){return this.useItem(context,player,hand);}

    private boolean active(PartyRoundContext context,ServerPlayerEntity player) {
        Fighter fighter=this.fighters.get(player.getUuid());return this.started&&!this.finished&&fighter!=null&&fighter.respawnAt<0&&!context.isOut(player.getUuid());
    }
    @Override public boolean allowsDamage(PartyRoundContext context,LivingEntity victim,DamageSource source) {
        if(!(victim instanceof ServerPlayerEntity player)||!this.active(context,player))return false;
        Fighter fighter=this.fighters.get(player.getUuid());
        if(context.elapsed()<fighter.protection && !source.isOf(DamageTypes.FALL)&&!source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY))return false;
        if(source.getAttacker() instanceof ServerPlayerEntity attacker) {
            Fighter other=this.fighters.get(attacker.getUuid());
            return attacker!=player&&other!=null&&other.respawnAt<0&&context.players().contains(attacker);
        }
        return source.getAttacker()==null;
    }
    @Override public void death(PartyRoundContext context,ServerPlayerEntity player,DamageSource source) {
        if(!this.active(context,player))return;
        if(player.getPrimeAdversary() instanceof ServerPlayerEntity killer && killer!=player && this.fighters.containsKey(killer.getUuid())
                && context.players().contains(killer))this.pendingKills.add(killer.getUuid());
        player.clearStatusEffects();resetLife(player);player.changeGameMode(GameMode.SPECTATOR);
        this.fighters.get(player.getUuid()).respawnAt=context.elapsed()+1;
    }
    @Override public void death(PartyRoundContext context,ServerPlayerEntity player){this.death(context,player,null);}

    private static void resetLife(ServerPlayerEntity player) {
        player.setHealth(player.getMaxHealth());player.setFireTicks(0);player.fallDistance=0;player.timeUntilRegen=0;player.hurtTime=0;
        player.setAttacker(null);player.setAttacking(null);
    }
    private void weakness(ServerPlayerEntity player,int stage) {
        if(stage==1||stage==5)player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,20,99,false,false));
        else if(stage==3)player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,20,1,false,false));
        else player.removeStatusEffect(StatusEffects.WEAKNESS);
    }
    private void updateScores(PartyRoundContext context) {
        for(var entry:this.fighters.entrySet()) {
            Fighter fighter=entry.getValue();int later=0;
            for(Fighter other:this.fighters.values())if(other.lastKillTick>fighter.lastKillTick)later++;
            context.score(entry.getKey(),(fighter.weapon-1)*context.initialPlayerCount()+later);
        }
    }
    private void face(ServerPlayerEntity player,float yaw){player.networkHandler.requestTeleport(player.getX(),player.getY(),player.getZ(),yaw,0);}
    private void clearItem(ServerPlayerEntity player,Item item) {
        for(int slot=0;slot<player.getInventory().size();slot++)if(player.getInventory().getStack(slot).isOf(item))player.getInventory().setStack(slot,ItemStack.EMPTY);
    }
    private DisplayEntity.BlockDisplayEntity display(Vec3d position,float yaw,float pitch) {
        var display=new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY,this.world);
        display.refreshPositionAndAngles(position.x,position.y,position.z,yaw,pitch);display.setGlowing(true);
        this.displays.add(display.getUuid());
        this.world.getServer().getScoreboard().addScoreHolderToTeam(display.getUuidAsString(),this.laserTeam);
        return display;
    }
    private void removeDisplay(DisplayEntity.BlockDisplayEntity display) {
        this.displays.remove(display.getUuid());display.discard();
        var scoreboard=this.world.getServer().getScoreboard();
        if(this.laserTeam!=null && scoreboard.getScoreHolderTeam(display.getUuidAsString())==this.laserTeam)scoreboard.removeScoreHolderFromTeam(display.getUuidAsString(),this.laserTeam);
    }
    private void createBars(PartyRoundContext context) throws IOException {
        try(var input=WeaponDevolutionRound.class.getResourceAsStream("/pvp/party/weapon_devolution_rules.json")) {
            if(input==null)throw new IOException("Missing410rules");
            var data=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var row:data.getAsJsonArray("bars")) {
                var item=row.getAsJsonObject();var pos=item.getAsJsonArray("position");var props=item.getAsJsonObject("properties");
                var bar=new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY,this.world);
                bar.refreshPositionAndAngles(context.source(pos.get(0).getAsDouble(),pos.get(1).getAsDouble(),pos.get(2).getAsDouble()),0,0);
                BlockState state=Blocks.IRON_BARS.getDefaultState();
                for(var prop:List.of(net.minecraft.state.property.Properties.NORTH,net.minecraft.state.property.Properties.SOUTH,net.minecraft.state.property.Properties.EAST,net.minecraft.state.property.Properties.WEST))state=state.with(prop,props.get(prop.getName()).getAsBoolean());
                bar.setBlockState(state);bar.setTeleportDuration(48);
                bar.setTransformation(new AffineTransformation(new Vector3f(-.5f,0,-.5f),new Quaternionf(),new Vector3f(1),new Quaternionf()));
                this.bars.add(bar);this.displays.add(bar.getUuid());this.world.spawnEntity(bar);
            }
        }
    }
    @Override public void leave(PartyRoundContext context,ServerPlayerEntity player) {
        this.pendingKills.remove(player.getUuid());this.pendingLasers.remove(player.getUuid());this.fighters.remove(player.getUuid());
        player.removeStatusEffect(StatusEffects.WEAKNESS);player.removeStatusEffect(StatusEffects.REGENERATION);
        // Native getOwner() may already be null after a disconnect; retain exact thrower UUIDs for teardown.
        this.projectiles.values().removeIf(shot->{if(player.getUuid().equals(this.projectileOwners.get(shot.getUuid()))){shot.discard();this.projectileOwners.remove(shot.getUuid());return true;}return false;});
    }
    @Override public void finish(PartyRoundContext context){this.updateScores(context);}
    @Override public void close(PartyRoundContext context) {
        if(this.finished)return;this.finished=true;
        this.projectiles.values().forEach(Entity::discard);this.projectiles.clear();this.projectileOwners.clear();this.snowPairs.clear();this.pendingKills.clear();this.pendingLasers.clear();
        for(var beam:this.beams){this.removeDisplay(beam.child());this.removeDisplay(beam.parent());}this.beams.clear();
        this.bars.forEach(Entity::discard);this.bars.clear();this.displays.clear();
        if(this.laserTeam!=null){var s=this.world.getServer().getScoreboard();if(s.getTeam(this.laserTeam.getName())==this.laserTeam)s.removeTeam(this.laserTeam);this.laserTeam=null;}
        for(ServerPlayerEntity player:context.players()){player.removeStatusEffect(StatusEffects.WEAKNESS);player.removeStatusEffect(StatusEffects.REGENERATION);}
    }
    @Override public String scoreDescription(PartyRoundContext context,UUID player){var f=this.fighters.get(player);return f==null?"0/5 击杀":Math.min(5,f.weapon-1)+"/5 击杀";}
    @Override public String status(PartyRoundContext context){return "原生五阶段武器 · 无生命上限";}
}
