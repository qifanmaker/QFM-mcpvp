package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.block.SignBlock;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版418的19个核心函数：11种原生动作、两次无重复指令池和第12条文字陷阱。
 * 修改原因：新版Mannequin与全服命令无法直接用于1.21.1共享大厅；必须保留真实玩家输入、
 * 原教室/桌子/12处灯槽/79面旗帜，而不能用随机按钮或攻击包数量代替原生统计。
 * 自查：动作期限/3或4分/错误淘汰/淘汰后保留分数/单存活且领先才早停均独立处理；
 * 只改本场物品、效果、实体和临时碰撞组。无原函数、点击命令或旧场地坐标可被执行。
 * 同tick首名共享奖励；原旧坐标淘汰/结束后继续schedule修正为当前场内的封闭生命周期。
 */
public final class SteveSaysRound implements PartyRound {
    private static final BlockPos ANCHOR = new BlockPos(-658,33,-23);
    private static final int[] DURATIONS = {0,40,30,40,30,60,60,80,50,90,40,40};
    // Registry-backed values are initialized only for an actual server round. Pure adjudication
    // must remain loadable in the ordinary unit-test JVM without bootstrapping Minecraft registries.
    private final Item[] BOOTS = {Items.DIAMOND_BOOTS,Items.GOLDEN_BOOTS,Items.LEATHER_BOOTS};
    private final Item[] STONES = {Items.STONE,Items.COBBLESTONE,Items.DRIPSTONE_BLOCK};
    private final Item[] CONCRETES = {Items.ORANGE_CONCRETE,Items.YELLOW_CONCRETE,Items.RED_CONCRETE};
    private static final String[] BOOT_TEXT = {"钻石靴","金靴子","皮革靴"};
    private static final String[] STONE_TEXT = {"石头","圆石","滴水石块"};
    private static final String[] COLOR_TEXT = {"§4橙色","§6黄色","§e红色"};
    private final Set<Item> TASK_ITEMS = Set.of(Items.DIAMOND_BOOTS,Items.GOLDEN_BOOTS,Items.LEATHER_BOOTS,
            Items.STONE,Items.COBBLESTONE,Items.DRIPSTONE_BLOCK,Items.ORANGE_CONCRETE,Items.YELLOW_CONCRETE,Items.RED_CONCRETE);
    private enum Phase { BETWEEN, SETUP, ACTION, FINISHED }

    /** Native observations are adjudicated once at the deadline; sticky flags match source scoreboard/tag behavior. */
    static final class Input {
        int punches,sneak,jumps,plays,tunes,clicks,drops,selectedDrops,wrongDrops;
        boolean complete,bad,looked,onDesk,moved,otherStone;
        double displacementSquared;
    }
    record Outcome(int points,boolean eliminate) { }
    static Outcome adjudicate(int task,int type,Input in,boolean first) {
        boolean says=type==1,bad=in.bad,success=false,refrain=false;
        switch(task) {
            case 1 -> { success=in.punches>=1; if(!says){bad=in.punches>=1;refrain=!bad;} }
            case 2 -> { success=in.sneak>=1; if(!says){bad=in.sneak>=1;refrain=!bad;} }
            case 3 -> { success=in.complete;refrain=!bad; }
            case 4 -> { success=in.jumps==1;if(!says){bad=in.jumps>=1;refrain=!bad;} }
            case 5 -> { bad|=!says&&in.looked;success=in.looked&&!bad;refrain=!bad; }
            case 6 -> { success=in.clicks==1;bad|=says?in.clicks>=2:in.clicks>=1;refrain=in.clicks==0; }
            case 7 -> { success=in.tunes==3&&in.plays==0;bad|=says?in.tunes>=4||in.plays>=4:in.tunes>=1||in.plays>=1;refrain=!bad; }
            case 8 -> { success=in.complete;if(says)bad|=!in.otherStone;else bad|=in.drops>=1;refrain=in.drops==0&&!bad; }
            case 9 -> { bad|=!says&&in.onDesk;success=in.onDesk;refrain=!in.onDesk&&!bad; }
            case 10 -> { success=in.complete;bad|=says?in.wrongDrops>=1:in.drops>=1;refrain=!bad; }
            case 11 -> {
                if(says)return new Outcome(!bad&&!in.moved?3:0,bad||in.moved);
                return new Outcome(in.moved?(first?4:3):0,!in.moved&&in.displacementSquared==0);
            }
            default -> throw new IllegalArgumentException("Unknown Steve Says task: "+task);
        }
        // Source awards stone-task completion before its later wrong-drop elimination.
        boolean pointsAllowed=task!=5&&task!=10 || !bad;
        int points=says&&success&&pointsAllowed?(first?4:3):!says&&refrain?3:0;
        return new Outcome(points,bad);
    }

    private static final class PlayerTask {
        final Input input=new Input();
        final Map<Identifier,Integer> stats=new HashMap<>();
        final Map<Item,Integer> dropped=new HashMap<>();
        Vec3d origin=Vec3d.ZERO;
        boolean first;
    }
    private final Map<UUID,PlayerTask> records=new LinkedHashMap<>();
    private final Map<UUID,Entity> entities=new LinkedHashMap<>();
    private final Map<UUID,Team> originalTeams=new HashMap<>();
    private final List<Integer> pool=new ArrayList<>();
    private final List<BlockPos> slots=new ArrayList<>();
    private final List<UUID> viewers=new ArrayList<>();
    private JsonObject rules;
    private ServerWorld world;
    private Box bounds;
    private Team collisionTeam;
    private UUID hostUuid;
    private int hostId;
    private int action,task,type,variant,deadline;
    private boolean firstLatched,closed;
    private Phase phase=Phase.BETWEEN;

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(ANCHOR);
        context.timeLimit(2020);
        this.world=context.world();
        this.bounds=new Box(context.source(-679,32,-35),context.source(-639,44,-9));
        context.geometry("steve_says");
        try(var stream=SteveSaysRound.class.getResourceAsStream("/pvp/party/steve_says_rules.json")) {
            if(stream==null)throw new IOException("Missing Steve Says source rules");
            try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)){this.rules=JsonParser.parseReader(reader).getAsJsonObject();}
        }
        this.rules.getAsJsonArray("dynamicSlots").forEach(value->this.slots.add(position(context,value.getAsJsonArray())));
        for(UUID id:context.playerIds())context.spawn(id,context.source(-658.0,33,-22.5));
    }
    @Override public boolean handlesTimeLimit(){return true;}
    @Override public boolean playerCombatEnabled(){return true;}
    @Override public void start(PartyRoundContext context) {
        this.restoreBanners(context);
        for(ServerPlayerEntity player:context.players()) {
            this.records.put(player.getUuid(),new PlayerTask());context.score(player.getUuid(),0);
        }
        this.createCollisionTeam(context);
        for(var value:this.rules.getAsJsonArray("desks")) {
            JsonArray p=value.getAsJsonArray();InteractionEntity entity=new InteractionEntity(EntityType.INTERACTION,this.world);
            NbtCompound data=new NbtCompound();data.putFloat("width",3.1F);data.putFloat("height",1.5F);entity.readNbt(data);
            entity.setPosition(context.source(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble()));
            this.own(entity);
        }
        this.spawnHost(context);
        this.deadline=20;
        context.announce("§a史蒂夫说——开始！只有带“史蒂夫说”的有效指令才要照做。"
                +"\n§e正确动作3分，最快4分；不执行有效指令0分，做错会出局。共22条指令，按总分排名。"
                +"\n§7留意物品、次数和文字；没有说要保持不动时，需要移动。");
        this.effects(context);
    }
    @Override public void tick(PartyRoundContext context,int elapsed) {
        if(this.closed||this.phase==Phase.FINISHED)return;
        this.cleanDrops(context);this.effects(context);
        if(this.phase==Phase.ACTION)this.observe(context);
        if(elapsed<this.deadline)return;
        if(this.phase==Phase.BETWEEN)this.next(context,elapsed);
        else if(this.phase==Phase.SETUP)this.beginAction(context,elapsed);
        else if(this.phase==Phase.ACTION)this.settle(context,elapsed);
    }
    private void next(PartyRoundContext context,int elapsed) {
        if(++this.action>22||this.earlyEnd(context)){this.phase=Phase.FINISHED;context.finish();return;}
        if(this.pool.isEmpty())for(int id=1;id<=11;id++)this.pool.add(id);
        this.task=this.pool.remove(context.random().nextInt(this.pool.size()));
        int roll=context.random().nextInt(this.action==12?5:4);this.type=roll<3?1:roll==3?2:3;
        this.variant=context.random().nextInt(3);if(this.task==7)this.variant=1;
        this.firstLatched=false;
        for(ServerPlayerEntity player:context.alive())this.records.put(player.getUuid(),new PlayerTask());
        this.setCollision(this.task==11);
        this.phase=Phase.SETUP;this.deadline=elapsed+20;
    }
    private void beginAction(PartyRoundContext context,int elapsed) {
        for(ServerPlayerEntity player:context.alive()) {
            PlayerTask state=this.records.get(player.getUuid());state.origin=player.getPos();
            for(Identifier stat:List.of(Stats.DAMAGE_DEALT,Stats.SNEAK_TIME,Stats.JUMP,Stats.PLAY_NOTEBLOCK,Stats.TUNE_NOTEBLOCK,Stats.DROP))state.stats.put(stat,stat(player,stat));
            for(Item item:CONCRETES)state.dropped.put(item,player.getStatHandler().getStat(Stats.DROPPED.getOrCreateStat(item)));
            if(this.task==1)player.removeStatusEffect(StatusEffects.WEAKNESS);
            if(this.task==3)give(player,BOOTS[this.type==3?(this.variant+2)%3:this.variant]);
            if(this.task==8){give(player,STONES[this.variant]);give(player,STONES[(this.variant+1)%3]);}
            if(this.task==10)for(Item item:CONCRETES)give(player,item);
            if(this.task==7)player.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE,100,20,false,false));
            player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,SoundCategory.MASTER,10,1);
        }
        if(this.task==6||this.task==7)for(BlockPos pos:this.slots) {
            context.set(pos,this.task==6?Blocks.DARK_OAK_SIGN.getDefaultState().with(SignBlock.ROTATION,12):Blocks.NOTE_BLOCK.getDefaultState());
            if(this.task==6&&this.world.getBlockEntity(pos) instanceof SignBlockEntity sign) {
                Text[] lines={Text.empty(),Text.literal("CLICK ME").formatted(Formatting.BOLD),Text.empty(),Text.empty()};
                SignText text=new SignText(lines,lines,DyeColor.BLACK,true);sign.setText(text,true);sign.setText(text,false);sign.setWaxed(true);sign.markDirty();
                this.world.getChunkManager().markForUpdate(pos);
            }
        }
        Text instruction=Text.literal("§e第 "+this.action+"/22 条指令 §8｜ ");
        if(this.task==10) {
            int[] misleadingColors={0xAA0000,0xFF8000,0xFFFF55};
            String[] words={"橙色","黄色","红色"};
            Text phrase=Text.literal("丢出").formatted(Formatting.DARK_GREEN)
                    .append(Text.literal(words[this.variant]).withColor(misleadingColors[this.variant]))
                    .append(Text.literal(this.type==3?"羊毛":"混凝土").formatted(Formatting.DARK_GREEN));
            instruction=Text.literal("§e第 "+this.action+"/22 条指令 §8｜ "+(this.type!=2?"§2史蒂夫说：":"")).append(phrase);
        } else instruction=instruction.copy().append(Text.literal(this.instruction()));
        for(ServerPlayerEntity player:context.players())player.sendMessage(instruction,false);
        this.phase=Phase.ACTION;this.deadline=elapsed+DURATIONS[this.task];
    }
    private String instruction() {
        String command=switch(this.task) {
            case 1->"攻击另一名玩家";case 2->"潜行";case 3->"穿上"+BOOT_TEXT[this.variant];case 4->"跳跃";
            case 5->"看向天花板";case 6->"点击标牌一次";case 7->"把音符盒调音3次";
            case 8->"丢出"+STONE_TEXT[this.type==3?(this.variant+2)%3:this.variant];case 9->"站到桌子上";
            case 10->"丢出"+COLOR_TEXT[this.variant]+(this.type==3?"羊毛":"混凝土");case 11->"保持不动";
            default->throw new IllegalStateException("Unknown task");
        };
        return "§2"+(this.type==1||this.type==3&&(this.task==3||this.task==8||this.task==10)?"史蒂夫说：":"")+command;
    }
    private void observe(PartyRoundContext context) {
        List<PlayerTask> first=new ArrayList<>();
        for(ServerPlayerEntity player:context.alive()) {
            PlayerTask state=this.records.get(player.getUuid());Input in=state.input;
            in.punches=delta(player,state,Stats.DAMAGE_DEALT);in.sneak=delta(player,state,Stats.SNEAK_TIME);in.jumps=delta(player,state,Stats.JUMP);
            in.plays=delta(player,state,Stats.PLAY_NOTEBLOCK);in.tunes=delta(player,state,Stats.TUNE_NOTEBLOCK);in.drops=delta(player,state,Stats.DROP);
            in.displacementSquared=player.squaredDistanceTo(state.origin);
            boolean valid=false;
            switch(this.task) {
                case 1->valid=this.type==1&&in.punches>=1;
                case 2->valid=this.type==1&&in.sneak>=1;
                case 3->{boolean armor=player.getEquippedStack(EquipmentSlot.FEET).isOf(BOOTS[this.variant]);in.complete|=this.type==1&&armor;
                    if(this.type!=1)for(Item boot:BOOTS)in.bad|=player.getEquippedStack(EquipmentSlot.FEET).isOf(boot);valid=this.type==1&&armor;}
                case 4->valid=this.type==1&&in.jumps==1;
                case 5->{in.looked|=MathHelper.floor(player.getPitch())==-90;in.bad|=this.type!=1&&in.looked;valid=this.type==1&&in.looked;}
                case 6->valid=this.type==1&&in.clicks==1;
                case 7->{in.bad|=in.plays>=1||(this.type!=1&&in.tunes>=1);valid=this.type==1&&in.tunes==3&&in.plays==0;}
                case 8->{in.otherStone=player.getInventory().count(STONES[(this.variant+1)%3])>0;in.complete|=this.type==1&&in.drops==1&&in.otherStone;valid=this.type==1&&in.complete;}
                case 9->{in.onDesk|=this.onDesk(player);in.bad|=this.type!=1&&in.onDesk;valid=this.type==1&&in.onDesk;}
                case 10->{in.selectedDrops=0;in.wrongDrops=0;for(int i=0;i<3;i++){int amount=player.getStatHandler().getStat(Stats.DROPPED.getOrCreateStat(CONCRETES[i]))-state.dropped.get(CONCRETES[i]);if(i==this.variant)in.selectedDrops=amount;else in.wrongDrops+=amount;}
                    boolean exact=in.selectedDrops==1&&in.wrongDrops==0;in.complete|=this.type==1&&exact;valid=this.type==1&&exact;}
                case 11->{in.moved|=in.displacementSquared>=0.0001;if(this.type==1)in.bad|=in.moved;valid=this.type!=1&&in.moved;}
            }
            if(!this.firstLatched&&valid)first.add(state);
        }
        if(!first.isEmpty()){this.firstLatched=true;first.forEach(state->state.first=true);}
    }
    private boolean onDesk(ServerPlayerEntity player) {
        if(!player.isOnGround())return false;
        for(double[] offset:new double[][]{{0,0},{0,.3},{0,-.3},{.3,0},{-.3,0}})
            if(this.world.getBlockState(BlockPos.ofFloored(player.getX()+offset[0],player.getY()-.1,player.getZ()+offset[1])).isOf(Blocks.SPRUCE_TRAPDOOR))return true;
        return false;
    }
    private void settle(PartyRoundContext context,int elapsed) {
        for(ServerPlayerEntity player:List.copyOf(context.alive())) {
            PlayerTask state=this.records.get(player.getUuid());Outcome outcome=adjudicate(this.task,this.type,state.input,state.first);
            context.addScore(player.getUuid(),outcome.points());
            player.sendMessage(Text.literal("§e第 "+this.action+"/22 条结果：§f+"+outcome.points()+" §7｜累计 "+context.score(player.getUuid())+(outcome.eliminate()?" §c（出局）":"")),false);
            if(outcome.eliminate()) {
                LightningEntity bolt=EntityType.LIGHTNING_BOLT.create(this.world);if(bolt!=null){bolt.setPosition(player.getPos());bolt.setCosmetic(true);this.own(bolt);}
                context.eliminate(player,"§c没有按史蒂夫的有效指令操作，已出局；所得分数保留。");
            }
        }
        this.clearAction(context);this.phase=Phase.BETWEEN;this.deadline=elapsed+20;
    }
    private boolean earlyEnd(PartyRoundContext context) {
        List<ServerPlayerEntity> alive=context.alive();if(alive.isEmpty())return true;if(alive.size()!=1)return false;
        UUID survivor=alive.get(0).getUuid();int points=context.score(survivor);
        return context.playerIds().stream().allMatch(id->id.equals(survivor)||points>context.score(id));
    }
    private void clearAction(PartyRoundContext context) {
        if(this.task==6||this.task==7)for(BlockPos pos:this.slots)context.set(pos,Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15,0));
        for(ServerPlayerEntity player:context.players()){this.clearItems(player);player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,-1,255,false,false));}
        this.setCollision(false);
    }
    private void effects(PartyRoundContext context) {
        for(ServerPlayerEntity player:context.players()) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION,20,20,false,false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION,20,99,false,false));
            if(this.phase!=Phase.ACTION||this.task!=1)player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,20,255,false,false));
        }
    }
    private void cleanDrops(PartyRoundContext context) {
        for(Entity entity:List.copyOf(this.entities.values()))if(entity.isRemoved())this.entities.remove(entity.getUuid());
        for(Entity entity:this.world.getOtherEntities(null,this.bounds,e->e instanceof ItemEntity)) {
            ItemEntity item=(ItemEntity)entity;
            if(item.getOwner()!=null&&context.playerIds().contains(item.getOwner().getUuid()))item.discard();
        }
    }
    private void own(Entity entity){this.entities.put(entity.getUuid(),entity);this.world.spawnEntity(entity);}
    @Override public boolean ownsEntity(Entity entity){return this.entities.containsKey(entity.getUuid());}
    @Override public boolean allowsDamage(PartyRoundContext context,LivingEntity victim,DamageSource source) {
        return !this.closed&&victim instanceof ServerPlayerEntity player&&!context.isOut(player.getUuid())
                &&source.getAttacker() instanceof ServerPlayerEntity attacker&&context.playerIds().contains(attacker.getUuid())&&!context.isOut(attacker.getUuid());
    }
    @Override public ActionResult useEntity(PartyRoundContext context,ServerPlayerEntity player,Hand hand,Entity entity){return this.ownsEntity(entity)&&entity instanceof InteractionEntity?ActionResult.PASS:ActionResult.FAIL;}
    @Override public ActionResult useItem(PartyRoundContext context,ServerPlayerEntity player,Hand hand) {
        if(this.phase==Phase.ACTION&&this.task==3)for(Item boot:BOOTS)if(player.getStackInHand(hand).isOf(boot))return ActionResult.PASS;
        return ActionResult.FAIL;
    }
    @Override public ActionResult useBlock(PartyRoundContext context,ServerPlayerEntity player,Hand hand,BlockHitResult hit) {
        if(this.phase!=Phase.ACTION||!this.slots.contains(hit.getBlockPos()))return this.useItem(context,player,hand);
        if(this.task==7&&this.world.getBlockState(hit.getBlockPos()).isOf(Blocks.NOTE_BLOCK))return ActionResult.PASS;
        if(this.task==6&&this.world.getBlockState(hit.getBlockPos()).isOf(Blocks.DARK_OAK_SIGN)) {
            Input input=this.records.get(player.getUuid()).input;
            // SUCCESS consumes the native interaction, so valid offhand-only use must also count.
            if(input.clicks==0)player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,SoundCategory.MASTER,10,2);
            input.clicks++;
            return ActionResult.SUCCESS;
        }
        return ActionResult.FAIL;
    }
    @Override public ActionResult attackBlock(PartyRoundContext context,ServerPlayerEntity player,BlockPos pos){return this.phase==Phase.ACTION&&this.task==7&&this.slots.contains(pos)?ActionResult.PASS:ActionResult.FAIL;}
    @Override public void leave(PartyRoundContext context,ServerPlayerEntity player){this.restoreTeam(player);this.removeHost(player);}
    @Override public void death(PartyRoundContext context,ServerPlayerEntity player){context.eliminate(player,"§c你已出局，分数保留。");}
    @Override public String scoreDescription(PartyRoundContext context,UUID player){return context.score(player)+" 分";}
    @Override public String status(PartyRoundContext context){return "第 "+Math.min(this.action,22)+"/22 条指令";}
    @Override public void finish(PartyRoundContext context){this.close(context);}
    @Override public void close(PartyRoundContext context) {
        if(this.closed)return;this.closed=true;this.phase=Phase.FINISHED;
        for(Entity entity:this.entities.values())entity.discard();this.entities.clear();
        for(ServerPlayerEntity player:context.players()) {
            this.removeHost(player);this.restoreTeam(player);this.clearItems(player);
            player.removeStatusEffect(StatusEffects.REGENERATION);player.removeStatusEffect(StatusEffects.SATURATION);
            player.removeStatusEffect(StatusEffects.WEAKNESS);player.removeStatusEffect(StatusEffects.MINING_FATIGUE);
        }
        if(this.collisionTeam!=null)this.world.getScoreboard().removeTeam(this.collisionTeam);
    }
    private void clearItems(ServerPlayerEntity player) {
        for(int slot=0;slot<player.getInventory().size();slot++)if(TASK_ITEMS.contains(player.getInventory().getStack(slot).getItem()))player.getInventory().setStack(slot,ItemStack.EMPTY);
        player.currentScreenHandler.sendContentUpdates();
    }
    private void createCollisionTeam(PartyRoundContext context) {
        Scoreboard board=this.world.getScoreboard();this.collisionTeam=board.addTeam("p2ss_"+UUID.randomUUID().toString().substring(0,8));
        for(ServerPlayerEntity player:context.players()) {
            this.originalTeams.put(player.getUuid(),board.getScoreHolderTeam(player.getNameForScoreboard()));board.addScoreHolderToTeam(player.getNameForScoreboard(),this.collisionTeam);
        }
    }
    private void setCollision(boolean never){if(this.collisionTeam!=null)this.collisionTeam.setCollisionRule(never?AbstractTeam.CollisionRule.NEVER:AbstractTeam.CollisionRule.ALWAYS);}
    private void restoreTeam(ServerPlayerEntity player) {
        if(!this.originalTeams.containsKey(player.getUuid()))return;
        Scoreboard board=this.world.getScoreboard();Team original=this.originalTeams.remove(player.getUuid());
        if(board.getScoreHolderTeam(player.getNameForScoreboard())==this.collisionTeam) {
            board.clearTeam(player.getNameForScoreboard());if(original!=null&&board.getTeam(original.getName())==original)board.addScoreHolderToTeam(player.getNameForScoreboard(),original);
        }
    }
    private void restoreBanners(PartyRoundContext context) {
        for(var value:this.rules.getAsJsonArray("banners")) {
            JsonObject data=value.getAsJsonObject();BlockPos pos=position(context,data.getAsJsonArray("position"));
            if(!(this.world.getBlockEntity(pos) instanceof BannerBlockEntity banner))continue;
            NbtCompound nbt=new NbtCompound();NbtList patterns=new NbtList();
            for(var part:data.getAsJsonArray("patterns")){NbtCompound pattern=new NbtCompound();JsonObject p=part.getAsJsonObject();pattern.putString("color",p.get("color").getAsString());pattern.putString("pattern",p.get("pattern").getAsString());patterns.add(pattern);}
            nbt.put("patterns",patterns);if(data.has("name"))nbt.putString("CustomName",Text.Serialization.toJsonString(Text.literal(data.get("name").getAsString()),this.world.getRegistryManager()));
            banner.readComponentlessNbt(nbt,this.world.getRegistryManager());banner.markDirty();this.world.getChunkManager().markForUpdate(pos);
        }
    }
    private void spawnHost(PartyRoundContext context) {
        //1.21.1 DefaultSkinHelper has18 built-in skins; index15 is wide/steve. No texture server is contacted.
        do{this.hostUuid=UUID.randomUUID();}while(Math.floorMod(this.hostUuid.hashCode(),18)!=15);
        InteractionEntity idReservation=new InteractionEntity(EntityType.INTERACTION,this.world);this.hostId=idReservation.getId();
        Vec3d position=context.source(-674.5,33.5,-22.0);
        PlayerListS2CPacket info=this.hostInfo();
        for(ServerPlayerEntity player:context.players()) {
            this.viewers.add(player.getUuid());player.networkHandler.sendPacket(info);
            player.networkHandler.sendPacket(new EntitySpawnS2CPacket(this.hostId,this.hostUuid,position.x,position.y,position.z,0,-90,EntityType.PLAYER,0,Vec3d.ZERO,-90));
            player.networkHandler.sendPacket(new EntityEquipmentUpdateS2CPacket(this.hostId,List.of(Pair.of(EquipmentSlot.MAINHAND,new ItemStack(Items.BOOK)),Pair.of(EquipmentSlot.OFFHAND,new ItemStack(Items.APPLE)))));
        }
    }
    private PlayerListS2CPacket hostInfo() {
        // The public packet constructor reads a live network handler. Decode the native public codec
        // instead of registering a fake connected player or using reflection/private packet fields.
        RegistryByteBuf buffer=new RegistryByteBuf(Unpooled.buffer(),this.world.getRegistryManager());
        try {
            buffer.writeEnumSet(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER,PlayerListS2CPacket.Action.UPDATE_GAME_MODE,PlayerListS2CPacket.Action.UPDATE_LISTED),PlayerListS2CPacket.Action.class);
            buffer.writeVarInt(1);buffer.writeUuid(this.hostUuid);buffer.writeString("Steve",16);buffer.writeVarInt(0);
            buffer.writeVarInt(GameMode.ADVENTURE.getId());buffer.writeBoolean(false);
            return PlayerListS2CPacket.CODEC.decode(buffer);
        }finally{buffer.release();}
    }
    private void removeHost(ServerPlayerEntity player) {
        if(this.hostUuid==null||!this.viewers.remove(player.getUuid()))return;
        player.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(this.hostId));player.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(this.hostUuid)));
    }
    private static void give(ServerPlayerEntity player,Item item){player.getInventory().insertStack(new ItemStack(item));player.currentScreenHandler.sendContentUpdates();}
    private static int stat(ServerPlayerEntity player,Identifier stat){return player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(stat));}
    private static int delta(ServerPlayerEntity player,PlayerTask state,Identifier stat){return stat(player,stat)-state.stats.getOrDefault(stat,stat(player,stat));}
    private static BlockPos position(PartyRoundContext context,JsonArray p){return context.sourceBlock(p.get(0).getAsInt(),p.get(1).getAsInt(),p.get(2).getAsInt());}
}
