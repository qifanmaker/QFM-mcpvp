package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BedBlock;
import net.minecraft.block.DecoratedPotBlock;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 原版421 Port Priority：12处选7处信标，真实锁箱/槽13/28个固定展示框，每人同集异序找3件物品。
 * 修改原因：原图1.21.10的组件锁、clone补货和全服计分/区块命令不能直接运行在1.21.1共享大厅。
 * 自查：保留完整港口船舱/门/机关/海水、原生钥匙与箱子GUI，240秒/正常N−1完成及checkpoint并列；
 * 只恢复安全标牌/旗帜字段，任务箱原位补货避免打开的ScreenHandler引用过期，区块只释放自己的租约。
 * 保留源南侧未锁箱的实际安全物品以及完整海底深度，不以“疑似调试”理由删除可达源内容。
 * 原生机关/流体变化需共享可选地形追踪钩子登记，不能禁用舱门或漏清活塞新位置。
 */
public final class PortPriorityRound implements PartyRound {
    private static final BlockPos ANCHOR=new BlockPos(-410,36,-375);
    private static final int TIME_LIMIT=4800;
    private static final int MIN_X=-524,MAX_X=-297,MIN_Z=-512,MAX_Z=-238,MIN_Y=-64,MAX_Y=68;
    private static final String[] ITEM_TEXT={"铜锭","下界合金锭","海晶碎片","下界石英","金锭","绿宝石","钻石"};
    private static final String[] KEY_NAMES={"Copper Ingot Key","Netherite Ingot Key","Prismarine Shard Key","Quartz Key","Gold Ingot Key","Emerald Key","Diamond Key"};
    private static final int[] COLORS={0xE47B55,0x595659,0x00FFC0,0xD0D0D0,0xFFF000,0x40F082,0x00FFFF};
    private final Item[] items={Items.COPPER_INGOT,Items.NETHERITE_INGOT,Items.PRISMARINE_SHARD,Items.QUARTZ,Items.GOLD_INGOT,Items.EMERALD,Items.DIAMOND};
    private static final int[][] PERMUTATIONS={{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
    record Layout(List<Integer> sites,List<Integer> contents,List<Integer> targets,List<List<Integer>> orders) { }
    /** Pure source marker-pool equivalent; does not initialize Minecraft item/block registries in unit tests. */
    static Layout generate(Random random,int players) {
        if(players<1||players>10)throw new IllegalArgumentException("Port Priority requires1..10 players");
        List<Integer> remaining=new ArrayList<>();for(int i=0;i<12;i++)remaining.add(i);
        List<Integer> sites=new ArrayList<>();for(int i=0;i<7;i++)sites.add(remaining.remove(random.nextInt(remaining.size())));
        remaining=new ArrayList<>();for(int i=0;i<7;i++)remaining.add(i);
        List<Integer> contents=new ArrayList<>();while(!remaining.isEmpty())contents.add(remaining.remove(random.nextInt(remaining.size())));
        remaining=new ArrayList<>(contents);List<Integer> targets=new ArrayList<>();for(int i=0;i<3;i++)targets.add(remaining.remove(random.nextInt(remaining.size())));
        remaining=new ArrayList<>();for(int i=0;i<6;i++)remaining.add(i);
        int[] assigned=new int[6];List<List<Integer>> orders=new ArrayList<>();
        for(int i=0;i<players;i++) {
            int index=random.nextInt(remaining.size()),permutation=remaining.get(index);
            orders.add(Arrays.stream(PERMUTATIONS[permutation]).mapToObj(targets::get).toList());
            if(players<=4||++assigned[permutation]==2)remaining.remove(index);
        }
        return new Layout(List.copyOf(sites),List.copyOf(contents),List.copyOf(targets),List.copyOf(orders));
    }
    static int rawScore(int players,int found,int lastCompletion,List<Integer> others,boolean checkpointUsed) {
        return checkpointUsed?players*found+(int)others.stream().filter(time->time>lastCompletion).count():3;
    }
    private static final class Searcher {
        final List<Integer> order;
        final boolean[] acquired=new boolean[7];
        final ServerBossBar bar=new ServerBossBar(Text.empty(),BossBar.Color.RED,BossBar.Style.NOTCHED_10);
        int found,lastCompletion=-1,protectionUntil;
        Searcher(List<Integer> order){this.order=order;}
        int current(){return this.found<3?this.order.get(this.found):-1;}
    }
    private record Site(BlockPos position,Direction facing,int item) { }
    private record BedSpawn(BlockPos position,float angle) { }
    private final Map<UUID,Searcher> searchers=new LinkedHashMap<>();
    private final Map<UUID,Entity> entities=new HashMap<>();
    private final Map<UUID,BedSpawn> bedSpawns=new HashMap<>();
    private final List<Site> sites=new ArrayList<>();
    private final Set<Long> forcedChunks=new HashSet<>();
    private JsonObject rules;
    private Layout layout;
    private ServerWorld world;
    private Box bounds;
    private int finishers;
    private boolean checkpointUsed,closed;

    @Override public void stage(PartyRoundContext context)throws IOException {
        context.sourceAnchor(ANCHOR);context.timeLimit(TIME_LIMIT);this.world=context.world();
        this.bounds=new Box(context.source(MIN_X-1,MIN_Y-1,MIN_Z-1),context.source(MAX_X+2,76,MAX_Z+2));
        context.geometry("port_priority");
        try(var stream=PortPriorityRound.class.getResourceAsStream("/pvp/party/port_priority_rules.json")) {
            if(stream==null)throw new IOException("Missing Port Priority source rules");
            try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)){this.rules=JsonParser.parseReader(reader).getAsJsonObject();}
        }
        List<ServerPlayerEntity> players=context.players();this.layout=generate(context.random(),players.size());
        for(int i=0;i<players.size();i++) {
            ServerPlayerEntity player=players.get(i);this.searchers.put(player.getUuid(),new Searcher(this.layout.orders().get(i)));
            double angle=Math.toRadians(i*(360/players.size()));context.spawn(player.getUuid(),context.source(-367.5-Math.sin(angle)*2,36,-307.5+Math.cos(angle)*2));
        }
        for(var value:this.rules.getAsJsonArray("sites")) {
            BlockPos pos=position(context,value.getAsJsonObject().getAsJsonArray("position"));
            for(BlockPos cell:BlockPos.iterate(pos.add(-1,-1,-1),pos.add(1,1,1)))context.stage(cell,Blocks.AIR.getDefaultState());
            context.stage(pos.up(2),Blocks.AIR.getDefaultState());
        }
        for(int i=0;i<7;i++) {
            JsonObject source=this.rules.getAsJsonArray("sites").get(this.layout.sites().get(i)).getAsJsonObject();
            Site site=new Site(position(context,source.getAsJsonArray("position")),Direction.byName(source.get("facing").getAsString()),this.layout.contents().get(i));this.sites.add(site);
            for(BlockPos pos:BlockPos.iterate(site.position().add(-1,-1,-1),site.position().add(1,-1,1)))context.stage(pos,Blocks.IRON_BLOCK.getDefaultState());
            context.stage(site.position(),Blocks.BEACON.getDefaultState());
            context.stage(site.position().up(),Blocks.CHEST.getDefaultState().with(net.minecraft.block.ChestBlock.FACING,site.facing()));
            context.stage(site.position().up(2),Blocks.BLACK_STAINED_GLASS_PANE.getDefaultState());
            context.stage(context.sourceBlock(-360-i*2,13,-308),Blocks.CHEST.getDefaultState().with(net.minecraft.block.ChestBlock.FACING,site.facing()));
            this.forceChunk(site.position());
        }
        // The source ocean is open toward unrelated maps; seal this finite server-owned ocean.
        for(int y=MIN_Y;y<=75;y++) {
            for(int x=MIN_X-1;x<=MAX_X+1;x++){context.stage(context.sourceBlock(x,y,MIN_Z-1),Blocks.BARRIER.getDefaultState());context.stage(context.sourceBlock(x,y,MAX_Z+1),Blocks.BARRIER.getDefaultState());}
            for(int z=MIN_Z;z<=MAX_Z;z++){context.stage(context.sourceBlock(MIN_X-1,y,z),Blocks.BARRIER.getDefaultState());context.stage(context.sourceBlock(MAX_X+1,y,z),Blocks.BARRIER.getDefaultState());}
        }
    }
    @Override public void start(PartyRoundContext context) {
        this.restoreDecorations(context);
        this.restoreContainers(context);
        for(int index=0;index<this.sites.size();index++) {
            Site site=this.sites.get(index);
            this.configureChest(site.position().up(),site.item());
            this.configureChest(context.sourceBlock(-360-index*2,13,-308),site.item());
            this.refill(site);
            for(Direction direction:List.of(Direction.EAST,Direction.WEST,Direction.SOUTH,Direction.NORTH)) {
                ItemFrameEntity frame=new ItemFrameEntity(this.world,site.position().up(2).offset(direction),direction);
                frame.setInvisible(true);frame.setInvulnerable(true);NbtCompound nbt=frame.writeNbt(new NbtCompound());nbt.putBoolean("Fixed",true);frame.readNbt(nbt);
                frame.setHeldItemStack(new ItemStack(this.items[site.item()]));this.entities.put(frame.getUuid(),frame);this.world.spawnEntity(frame);
            }
        }
        for(ServerPlayerEntity player:context.players()) {
            context.score(player.getUuid(),3);this.updateTarget(player,this.searchers.get(player.getUuid()));
            player.setYaw((float)Math.toDegrees(Math.atan2(player.getX()-context.source(-367.5,36,-307.5).x,context.source(-367.5,36,-307.5).z-player.getZ())));
            context.teleport(player,player.getPos());
        }
        this.effects(context);this.inventory(context);
        context.announce("§a港口寻宝：用手中钥匙打开对应物品的信标箱，取走物品后寻找下一件。"
                +"\n§e所有人寻找相同的三件物品，顺序不同；展示框显示箱内物品。限时240秒，完成者达到人数−1时结算。");
    }
    @Override public void tick(PartyRoundContext context,int elapsed) {
        if(this.closed)return;this.effects(context);
        for(ServerPlayerEntity player:context.alive())if(player.getY()<context.source(0,-128,0).y)this.death(context,player);
        for(Site site:this.sites)this.refill(site);
        // Source evaluates ids in ascending order, allowing an already-held later target in the same tick.
        for(int item=0;item<7;item++)for(ServerPlayerEntity player:List.copyOf(context.alive())) {
            Searcher state=this.searchers.get(player.getUuid());
            if(state!=null&&state.current()==item&&player.getInventory().count(this.items[item])>0)this.complete(context,player,state,item,elapsed);
        }
        this.inventory(context);this.cleanDrops(context);this.updateScores(context);
        if(this.finishers>=context.initialPlayerCount()-1||context.alive().size()<=1)context.finish();
    }
    private void complete(PartyRoundContext context,ServerPlayerEntity player,Searcher state,int item,int elapsed) {
        state.acquired[item]=true;state.found++;state.lastCompletion=elapsed;this.checkpointUsed=true;
        player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,SoundCategory.MASTER,10,2);
        player.sendMessage(Text.literal("§a已找到 "+state.found+"/3 件物品"),false);
        if(state.found==3) {
            this.finishers++;player.closeHandledScreen();player.getInventory().clear();state.bar.clearPlayers();
            context.completed(player.getUuid());context.eliminate(player,"§a已找到全部三件物品，正在观看其他玩家。");
            context.announce("§a"+player.getName().getString()+" 找到了全部三件物品！");
        } else this.updateTarget(player,state);
    }
    private void updateTarget(ServerPlayerEntity player,Searcher state) {
        int item=state.current();if(item<0){state.bar.clearPlayers();return;}
        Text target=Text.literal("下一个物品：").append(Text.literal(ITEM_TEXT[item]).withColor(COLORS[item]));
        state.bar.setName(target);state.bar.setPercent(state.found*.33F);state.bar.setColor(switch(item){case 0->BossBar.Color.RED;case 1->BossBar.Color.PURPLE;case 2,5->BossBar.Color.GREEN;case 3->BossBar.Color.WHITE;case 4->BossBar.Color.YELLOW;default->BossBar.Color.BLUE;});state.bar.addPlayer(player);
        player.sendMessage(target,false);
    }
    private void refill(Site site) {
        if(!(this.world.getBlockEntity(site.position().up()) instanceof ChestBlockEntity chest))return;
        boolean changed=false;
        for(int slot=0;slot<chest.size();slot++) {
            ItemStack stack=chest.getStack(slot);
            if(slot==13) {
                if(!stack.isOf(this.items[site.item()])||stack.getCount()!=1){chest.setStack(slot,new ItemStack(this.items[site.item()]));changed=true;}
            }else if(!stack.isEmpty()){chest.setStack(slot,ItemStack.EMPTY);changed=true;}
        }
        if(changed)chest.markDirty();
    }
    private void inventory(PartyRoundContext context) {
        boolean[] anybodyHas=new boolean[7];for(Searcher state:this.searchers.values())for(int i=0;i<7;i++)anybodyHas[i]|=state.acquired[i];
        for(ServerPlayerEntity player:context.players()) {
            Searcher state=this.searchers.get(player.getUuid());if(state==null)continue;boolean changed=false;
            if(!player.getOffHandStack().isEmpty()){player.setStackInHand(Hand.OFF_HAND,ItemStack.EMPTY);changed=true;}
            // Use the same native primitive as /clear, including crafting input and cursor.
            int removed=player.getInventory().remove(stack->{
                int key=keyType(stack);if(key>=0&&key!=state.current())return true;
                for(int i=0;i<7;i++)if(stack.isOf(this.items[i])&&!anybodyHas[i])return true;
                return false;
            },-1,player.playerScreenHandler.getCraftingInput());
            changed|=removed>0;
            for(int i=0;i<7;i++)if(state.acquired[i]&&player.getInventory().count(this.items[i])==0){player.getInventory().insertStack(new ItemStack(this.items[i]));changed=true;}
            int current=state.current();
            if(current>=0&&!hasKey(player,current)) {
                ItemStack key=new ItemStack(Items.TRIPWIRE_HOOK);key.set(DataComponentTypes.CUSTOM_NAME,Text.literal(KEY_NAMES[current]));player.getInventory().insertStack(key);changed=true;
            }
            if(changed){player.currentScreenHandler.sendContentUpdates();player.playerScreenHandler.onContentChanged(player.getInventory());}
        }
    }
    private void effects(PartyRoundContext context) {
        for(ServerPlayerEntity player:context.players()) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.DOLPHINS_GRACE,-1,0,false,false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED,-1,3,false,false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,-1,255,false,false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION,-1,0,false,false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING,-1,0,false,false));
        }
    }
    private void updateScores(PartyRoundContext context) {
        List<Integer> times=this.searchers.values().stream().map(state->state.lastCompletion).toList();
        this.searchers.forEach((uuid,state)->context.score(uuid,rawScore(context.initialPlayerCount(),state.found,state.lastCompletion,times,this.checkpointUsed)));
    }
    @Override public boolean allowsDamage(PartyRoundContext context,LivingEntity victim,DamageSource source) {
        if(this.closed||!(victim instanceof ServerPlayerEntity player)||context.isOut(player.getUuid()))return false;
        if(source.isOf(DamageTypes.FALL)||source.getAttacker() instanceof ServerPlayerEntity)return false;
        Searcher state=this.searchers.get(player.getUuid());
        return state!=null&&(context.elapsed()>=state.protectionUntil||source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY));
    }
    @Override public void death(PartyRoundContext context,ServerPlayerEntity player) {
        Searcher state=this.searchers.get(player.getUuid());if(state==null||context.isOut(player.getUuid()))return;
        player.closeHandledScreen();player.setHealth(player.getMaxHealth());player.setFireTicks(0);player.fallDistance=0;player.timeUntilRegen=0;player.hurtTime=0;player.setAttacker(null);player.setAttacking(null);
        state.protectionUntil=context.elapsed()+60;Vec3d respawn=context.source(-367.5,36,-307.5);
        BedSpawn bed=this.bedSpawns.get(player.getUuid());
        if(bed!=null) {
            BlockState block=this.world.getBlockState(bed.position());
            if(block.getBlock() instanceof BedBlock)respawn=BedBlock.findWakeUpPosition(EntityType.PLAYER,this.world,bed.position(),block.get(BedBlock.FACING),bed.angle()).orElse(respawn);
        }
        context.teleport(player,respawn);
        player.sendMessage(Text.literal("§e已返回港口中央，寻宝进度保留。"),false);
    }
    @Override public boolean ownsEntity(Entity entity){return this.entities.containsKey(entity.getUuid());}
    @Override public ActionResult useEntity(PartyRoundContext context,ServerPlayerEntity player,Hand hand,Entity entity){return this.ownsEntity(entity)?ActionResult.PASS:ActionResult.FAIL;}
    @Override public ActionResult useBlock(PartyRoundContext context,ServerPlayerEntity player,Hand hand,BlockHitResult hit) {
        BlockPos pos=hit.getBlockPos();if(this.closed||!this.bounds.contains(pos.toCenterPos()))return ActionResult.FAIL;
        for(Site site:this.sites)if(pos.equals(site.position().up())||pos.equals(site.position()))return ActionResult.PASS;
        Block block=this.world.getBlockState(pos).getBlock();
        if(block instanceof BedBlock)return this.useBed(player,hit);
        return block instanceof DoorBlock||block instanceof TrapdoorBlock||block instanceof ButtonBlock||block instanceof LeverBlock||block instanceof FenceGateBlock||this.world.getBlockEntity(pos) instanceof SignBlockEntity
                ||block instanceof DecoratedPotBlock
                ||this.world.getBlockState(pos).createScreenHandlerFactory(this.world,pos)!=null?ActionResult.PASS:ActionResult.FAIL;
    }
    /** Keep enabled through CLEANUP so the shared queue can absorb late native piston writes. */
    @Override public boolean tracksTerrainChanges(){return true;}
    @Override public boolean allowsFluidFlow(){return true;}
    @Override public boolean allowsFluidFlowAt(PartyRoundContext context,BlockPos pos){return pos.getY()>=context.sourceBlock(0,MIN_Y,0).getY()&&this.bounds.contains(pos.toCenterPos());}
    private void cleanDrops(PartyRoundContext context) {
        for(Entity entity:this.world.getOtherEntities(null,this.bounds,e->e instanceof ItemEntity)) {
            ItemEntity item=(ItemEntity)entity;boolean owned=item.getOwner()!=null&&context.playerIds().contains(item.getOwner().getUuid());
            if(owned&&item.getStack().getCount()==1&&(keyType(item.getStack())>=0||Arrays.stream(this.items).anyMatch(item.getStack()::isOf)))item.discard();
        }
    }
    @Override public void leave(PartyRoundContext context,ServerPlayerEntity player){Searcher state=this.searchers.get(player.getUuid());if(state!=null)state.bar.removePlayer(player);this.bedSpawns.remove(player.getUuid());}
    @Override public void finish(PartyRoundContext context){this.updateScores(context);}
    @Override public String scoreDescription(PartyRoundContext context,UUID player){Searcher state=this.searchers.get(player);return(state==null?0:state.found)+"/3 物品";}
    @Override public void close(PartyRoundContext context) {
        if(this.closed)return;this.closed=true;
        for(Searcher state:this.searchers.values())state.bar.clearPlayers();for(Entity entity:this.entities.values())entity.discard();this.entities.clear();
        for(ServerPlayerEntity player:context.players()) {
            if(player.isSleeping())player.wakeUp(false,true);
            player.closeHandledScreen();player.getInventory().clear();
            player.removeStatusEffect(StatusEffects.DOLPHINS_GRACE);player.removeStatusEffect(StatusEffects.SPEED);player.removeStatusEffect(StatusEffects.WEAKNESS);player.removeStatusEffect(StatusEffects.SATURATION);player.removeStatusEffect(StatusEffects.WATER_BREATHING);
        }
        this.cleanDrops(context);
        for(long chunk:this.forcedChunks)this.world.setChunkForced(ChunkPos.getPackedX(chunk),ChunkPos.getPackedZ(chunk),false);this.forcedChunks.clear();
    }
    private void restoreDecorations(PartyRoundContext context) {
        for(var value:this.rules.getAsJsonArray("banners")) {
            JsonObject data=value.getAsJsonObject();BlockPos pos=position(context,data.getAsJsonArray("position"));if(!(this.world.getBlockEntity(pos) instanceof BannerBlockEntity banner))continue;
            NbtCompound nbt=new NbtCompound();NbtList patterns=new NbtList();
            for(var part:data.getAsJsonArray("patterns")){JsonObject p=part.getAsJsonObject();NbtCompound entry=new NbtCompound();entry.putString("color",p.get("color").getAsString());entry.putString("pattern",p.get("pattern").getAsString());patterns.add(entry);}nbt.put("patterns",patterns);
            if(data.has("name"))nbt.putString("CustomName",Text.Serialization.toJsonString(Text.literal(data.get("name").getAsString()),this.world.getRegistryManager()));
            banner.readComponentlessNbt(nbt,this.world.getRegistryManager());banner.markDirty();this.world.getChunkManager().markForUpdate(pos);
        }
        for(var value:this.rules.getAsJsonArray("signs")) {
            JsonObject data=value.getAsJsonObject();BlockPos pos=position(context,data.getAsJsonArray("position"));if(!(this.world.getBlockEntity(pos) instanceof SignBlockEntity sign))continue;
            for(String face:List.of("front","back")) {
                JsonObject source=data.getAsJsonObject(face);Text[] lines=new Text[4];for(int i=0;i<4;i++)lines[i]=Text.literal(source.getAsJsonArray("messages").get(i).getAsString());
                sign.setText(new SignText(lines,lines,DyeColor.byName(source.get("color").getAsString(),DyeColor.BLACK),source.get("glowing").getAsBoolean()),face.equals("front"));
            }
            sign.setWaxed(data.get("waxed").getAsBoolean());sign.markDirty();this.world.getChunkManager().markForUpdate(pos);
        }
        for(var value:this.rules.getAsJsonArray("skulls")) {
            JsonObject data=value.getAsJsonObject();BlockPos pos=position(context,data.getAsJsonArray("position"));
            if(!(this.world.getBlockEntity(pos) instanceof SkullBlockEntity skull))continue;
            JsonObject source=data.getAsJsonObject("profile");NbtCompound profile=new NbtCompound();int[] uuid=new int[4];for(int i=0;i<4;i++)uuid[i]=source.getAsJsonArray("id").get(i).getAsInt();profile.putIntArray("id",uuid);
            if(source.has("name"))profile.putString("name",source.get("name").getAsString());
            NbtList properties=new NbtList();for(var property:source.getAsJsonArray("properties")){NbtCompound entry=new NbtCompound();entry.putString("name","textures");entry.putString("value",property.getAsJsonObject().get("value").getAsString());properties.add(entry);}profile.put("properties",properties);
            NbtCompound nbt=new NbtCompound();nbt.put("profile",profile);skull.readComponentlessNbt(nbt,this.world.getRegistryManager());skull.markDirty();this.world.getChunkManager().markForUpdate(pos);
        }
    }
    private void configureChest(BlockPos pos,int item) {
        if(!(this.world.getBlockEntity(pos) instanceof ChestBlockEntity chest))throw new IllegalStateException("Port Priority chest missing at "+pos);
        NbtCompound lock=new NbtCompound();lock.putString("Lock",KEY_NAMES[item]);chest.readComponentlessNbt(lock,this.world.getRegistryManager());chest.setStack(13,new ItemStack(this.items[item]));chest.markDirty();
    }
    private void forceChunk(BlockPos pos) {
        int cx=pos.getX()>>4,cz=pos.getZ()>>4;long chunk=ChunkPos.toLong(cx,cz);
        if(!this.world.getForcedChunks().contains(chunk)){this.world.setChunkForced(cx,cz,true);this.forcedChunks.add(chunk);}
    }
    private void restoreContainers(PartyRoundContext context) {
        for(var value:this.rules.getAsJsonArray("containers")) {
            JsonObject data=value.getAsJsonObject();BlockPos pos=position(context,data.getAsJsonArray("position"));
            var entity=this.world.getBlockEntity(pos);if(!(entity instanceof Inventory inventory))continue;
            NbtCompound nbt=new NbtCompound();if(data.has("lock"))nbt.putString("Lock",data.get("lock").getAsString());
            if(data.has("name"))nbt.putString("CustomName",Text.Serialization.toJsonString(Text.literal(data.get("name").getAsString()),this.world.getRegistryManager()));
            entity.readComponentlessNbt(nbt,this.world.getRegistryManager());inventory.clear();
            for(var itemValue:data.getAsJsonArray("items")) {
                JsonObject item=itemValue.getAsJsonObject();int slot=item.get("slot").getAsInt();
                if(slot<0||slot>=inventory.size())continue;
                Registries.ITEM.getOrEmpty(Identifier.of(item.get("id").getAsString())).ifPresent(type->inventory.setStack(slot,new ItemStack(type,item.get("count").getAsInt())));
            }
            entity.markDirty();
        }
    }
    /** Retain native bed interaction/respawn inside this round without replacing the player's permanent home. */
    private ActionResult useBed(ServerPlayerEntity player,BlockHitResult hit) {
        var dimension=player.getSpawnPointDimension();BlockPos original=player.getSpawnPointPosition();float angle=player.getSpawnAngle();boolean forced=player.isSpawnForced();
        try {
            ActionResult result=this.world.getBlockState(hit.getBlockPos()).onUse(this.world,player,hit);
            BlockPos bed=player.getSpawnPointPosition();
            if(player.getSpawnPointDimension().equals(this.world.getRegistryKey())&&bed!=null&&this.bounds.contains(bed.toCenterPos()))this.bedSpawns.put(player.getUuid(),new BedSpawn(bed.toImmutable(),player.getSpawnAngle()));
            return result;
        }finally{player.setSpawnPoint(dimension,original,angle,forced,false);}
    }
    private static int keyType(ItemStack stack) {
        if(!stack.isOf(Items.TRIPWIRE_HOOK))return -1;Text name=stack.get(DataComponentTypes.CUSTOM_NAME);if(name==null)return -1;
        for(int i=0;i<7;i++)if(name.getString().equals(KEY_NAMES[i]))return i;return -1;
    }
    private static boolean hasKey(ServerPlayerEntity player,int type){for(int i=0;i<player.getInventory().size();i++)if(keyType(player.getInventory().getStack(i))==type)return true;return false;}
    private static BlockPos position(PartyRoundContext context,JsonArray p){return context.sourceBlock(p.get(0).getAsInt(),p.get(1).getAsInt(),p.get(2).getAsInt());}
}
