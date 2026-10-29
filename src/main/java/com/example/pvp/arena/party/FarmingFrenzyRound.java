package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BlockPredicatesChecker;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.predicate.BlockPredicate;
import net.minecraft.predicate.StatePredicate;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版420 Farming Frenzy：原生成熟采收、MINED增量、十秒季节及59条按空气补种。
 * 修改原因：物品交售或直接加龄会丢失原生铁锄成熟判定、踩地修复和真实随机生长，
 * 也会把源每tick精确give条件误当积分。完整农场平台/山脊/谷仓和下层模板保留原坐标。
 * 自查：INTRO仅1..60tick按max(1800,450N)原生随机采样，之后冻结；PLAY顺序为采收→补种→
 * 脚下耕地修复→200随机采样。源remaining==1结束，实际处理1799tick；边界采收使用旧季节。
 * 不执行原全服函数、计分板、gamerule或kill；动态生长/瓜果/门均由共享原生地形追踪清场。
 */
public final class FarmingFrenzyRound implements PartyRound {
    private static final BlockPos ANCHOR = new BlockPos(-166,36,-271);
    private static final String TOKEN = "party_farming_round";
    private static final String[] IDS = {"wheat","melon","beetroots","potatoes","pumpkin","carrots","nether_wart","cocoa"};
    private static final String[] GIFTS = {"wheat","melon","beetroot","potato","pumpkin","carrot","nether_wart","cocoa_beans"};
    private static final String[] NAMES = {"小麦","西瓜","甜菜","马铃薯","南瓜","胡萝卜","下界疣","可可豆"};
    private static final int[] POINTS = {2,6,1,2,6,2,1,4};
    private static final Set<String> CLEANUP = Set.of("iron_hoe","wheat_seeds","melon_seeds","beetroot_seeds","pumpkin_seeds","nether_wart","cocoa_beans");
    private record Operation(String type, BlockPos from, BlockPos to, BlockPos destination, BlockState state, Block replace) { }
    private static final class Farmer {
        final int[] previous = new int[8];
        int points;
    }
    private final String token = UUID.randomUUID().toString();
    private final Map<UUID,Farmer> farmers = new LinkedHashMap<>();
    private final Set<UUID> items = new HashSet<>();
    private final List<Operation> replant = new ArrayList<>();
    private JsonObject rules;
    private ServerWorld world;
    private BlockPos minimum;
    private BlockPos maximum;
    private Vec3d spawn;
    private Box bounds;
    private ItemStack hoe;
    private int season;
    private boolean started;
    private boolean closed;

    static int introSpeed(int elapsed, int players) { return elapsed>=1&&elapsed<=60 ? Math.max(1800,450*players) : 0; }
    static int harvestPoints(int crop, int count, int season) { return count*(crop+1==season?2:1)*POINTS[crop]; }
    static int giftCount(int crop, int count, int season) {
        int total=count*(crop+1==season?2:1);
        return total==1||total==2||total==4&&(crop!=1&&crop!=4&&crop!=7) ? total : 0;
    }

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(ANCHOR);
        context.timeLimit(1799);
        this.world=context.world();
        try(var input=FarmingFrenzyRound.class.getResourceAsStream("/pvp/party/farming_frenzy_rules.json")) {
            if(input==null)throw new IOException("Missing Farming Frenzy rules");
            this.rules=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
        this.minimum=source(context,this.rules.getAsJsonObject("bounds").getAsJsonArray("min"));
        this.maximum=source(context,this.rules.getAsJsonObject("bounds").getAsJsonArray("max"));
        this.bounds=new Box(Vec3d.of(this.minimum),Vec3d.of(this.maximum.add(1,1,1)));
        this.spawn=context.source(-152.5,36,-275.5);
        Map<Long,BlockState> scene=new LinkedHashMap<>();
        PartyMapBlueprint.load("farming_frenzy").forEachTranslated(BlockPos.ORIGIN,(pos,state)->scene.put(pos.asLong(),state));
        for(JsonElement encoded:this.rules.getAsJsonArray("setupOperations"))applyStage(scene,readOperation(encoded.getAsJsonObject()));
        scene.forEach((packed,state)->{
            BlockPos pos=BlockPos.fromLong(packed);
            context.stage(context.sourceBlock(pos.getX(),pos.getY(),pos.getZ()),state);
        });
        for(JsonElement encoded:this.rules.getAsJsonArray("replantOperations"))this.replant.add(readOperation(encoded.getAsJsonObject()));
        this.hoe=this.createHoe();
        for(ServerPlayerEntity player:context.players()) {
            this.farmers.put(player.getUuid(),new Farmer());
            context.spawn(player.getUuid(),this.spawn);
        }
    }

    private static void applyStage(Map<Long,BlockState> scene,Operation operation) {
        if(operation.type().equals("clone")) {
            BlockPos a=operation.from(),b=operation.to();
            BlockPos low=new BlockPos(Math.min(a.getX(),b.getX()),Math.min(a.getY(),b.getY()),Math.min(a.getZ(),b.getZ()));
            Map<Long,BlockState> copy=new LinkedHashMap<>();
            for(BlockPos pos:BlockPos.iterate(a,b))copy.put(pos.subtract(low).add(operation.destination()).asLong(),scene.getOrDefault(pos.asLong(),Blocks.AIR.getDefaultState()));
            scene.putAll(copy);
        } else for(BlockPos pos:BlockPos.iterate(operation.from(),operation.to())) {
            if(operation.replace()==null||scene.getOrDefault(pos.asLong(),Blocks.AIR.getDefaultState()).isOf(operation.replace()))scene.put(pos.asLong(),operation.state());
        }
    }

    @Override public boolean tracksTerrainChanges() { return true; }
    @Override public boolean handlesTimeLimit() { return true; }

    @Override public void introTick(PartyRoundContext context,int elapsed) {
        if(this.closed)return;
        if(elapsed==0)this.restoreBanners(context);
        this.effects(context);
        int speed=introSpeed(elapsed,context.initialPlayerCount());
        if(speed>0)context.randomTicks(this.minimum,this.maximum,speed);
    }

    @Override public void start(PartyRoundContext context) {
        this.started=true;
        for(ServerPlayerEntity player:context.players()) {
            Farmer farmer=this.farmers.get(player.getUuid());
            for(int crop=0;crop<8;crop++)farmer.previous[crop]=stat(player,crop);
            context.score(player.getUuid(),0);
            this.ensureHoe(player);
            player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,SoundCategory.MASTER,10,2);
        }
        this.effects(context);
        context.announce("§a农场狂欢：90秒内，用忠诚的铁锄采收成熟作物！\n§e甜菜/下界疣1分，小麦/马铃薯/胡萝卜2分，可可4分，西瓜/南瓜6分。\n§6每10秒随机进入一个双倍季节，可以重复；田地会自动补种，正常生长。");
    }

    @Override public void tick(PartyRoundContext context,int elapsed) {
        if(!this.started||this.closed)return;
        this.cleanDrops();
        for(ServerPlayerEntity player:context.players()) {
            Farmer farmer=this.farmers.get(player.getUuid());
            if(farmer==null)continue;
            this.ensureHoe(player);
            for(int crop=0;crop<8;crop++) {
                int now=stat(player,crop),count=Math.max(0,now-farmer.previous[crop]);
                farmer.previous[crop]=now;
                if(count==0)continue;
                farmer.points+=harvestPoints(crop,count,this.season);
                int gift=giftCount(crop,count,this.season);
                if(gift>0)player.giveItemStack(new ItemStack(Registries.ITEM.get(Identifier.ofVanilla(GIFTS[crop])),gift));
            }
            context.score(player.getUuid(),farmer.points);
        }
        for(Operation operation:this.replant) {
            BlockPos a=context.sourceBlock(operation.from().getX(),operation.from().getY(),operation.from().getZ());
            BlockPos b=context.sourceBlock(operation.to().getX(),operation.to().getY(),operation.to().getZ());
            for(BlockPos pos:BlockPos.iterate(a,b)) {
                if(operation.replace()==null||this.world.getBlockState(pos).isOf(operation.replace()))context.set(pos,operation.state());
            }
        }
        int remaining=1800-elapsed;
        if(remaining%200==0) {
            this.season=1+context.random().nextInt(8);
            context.announce("§6"+NAMES[this.season-1]+"季节！接下来这类作物的采收积分和产量翻倍！");
            for(ServerPlayerEntity player:context.players())player.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(),SoundCategory.MASTER,10,1);
        }
        // The source's all-tick function repairs the floor after the core
        // farming function, including its final tick. Trampling is not mining.
        for(ServerPlayerEntity player:context.players()) {
            BlockPos floor=player.getBlockPos().down();
            if(this.bounds.contains(Vec3d.ofCenter(floor))&&this.world.getBlockState(floor).isOf(Blocks.DIRT))context.set(floor,Blocks.FARMLAND.getDefaultState());
        }
        this.effects(context);
        if(remaining==1) { context.finish(); return; }
        context.randomTicks(this.minimum,this.maximum,200);
    }

    private void effects(PartyRoundContext context) {
        for(ServerPlayerEntity player:context.players())player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,40,255,false,false));
    }
    private static int stat(ServerPlayerEntity player,int crop) {
        return player.getStatHandler().getStat(Stats.MINED,Registries.BLOCK.get(Identifier.ofVanilla(IDS[crop])));
    }

    private ItemStack createHoe() throws IOException {
        List<BlockPredicate> predicates=new ArrayList<>();
        for(JsonElement encoded:this.rules.getAsJsonArray("nativeBreak")) {
            JsonObject row=encoded.getAsJsonObject();
            Block block=Registries.BLOCK.get(Identifier.of(row.get("block").getAsString()));
            BlockPredicate.Builder predicate=BlockPredicate.Builder.create().blocks(block);
            if(!row.get("age").isJsonNull()) {
                Property<?> age=block.getStateManager().getProperty("age");
                if(age==null)throw new IOException("Farming maturity predicate has no age property");
                predicate.state(StatePredicate.Builder.create().exactMatch(age,row.get("age").getAsString()));
            }
            predicates.add(predicate.build());
        }
        ItemStack stack=new ItemStack(Items.IRON_HOE);
        stack.set(DataComponentTypes.CAN_BREAK,new BlockPredicatesChecker(predicates,true));
        stack.set(DataComponentTypes.UNBREAKABLE,new UnbreakableComponent(false));
        stack.set(DataComponentTypes.CUSTOM_NAME,Text.literal("The Loyal Hoe").formatted(Formatting.WHITE,Formatting.BOLD));
        NbtCompound data=new NbtCompound();data.putString(TOKEN,this.token);stack.set(DataComponentTypes.CUSTOM_DATA,NbtComponent.of(data));
        return stack;
    }
    private void ensureHoe(ServerPlayerEntity player) {
        if(player.getInventory().count(Items.IRON_HOE)==0)player.giveItemStack(this.hoe.copy());
    }
    private boolean ownHoe(ItemStack stack) {
        return stack.isOf(Items.IRON_HOE)&&stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().getString(TOKEN).equals(this.token);
    }
    private boolean active(PartyRoundContext context,ServerPlayerEntity player) {
        return this.started&&!this.closed&&this.farmers.containsKey(player.getUuid())&&!context.isOut(player.getUuid())&&player.getWorld()==this.world;
    }
    @Override public boolean canBreak(PartyRoundContext context,ServerPlayerEntity player,BlockPos position) {
        if(!this.active(context,player)||!this.bounds.contains(Vec3d.ofCenter(position))||!this.ownHoe(player.getMainHandStack()))return false;
        BlockState state=this.world.getBlockState(position);
        for(JsonElement encoded:this.rules.getAsJsonArray("nativeBreak")) {
            JsonObject row=encoded.getAsJsonObject();
            if(!state.isOf(Registries.BLOCK.get(Identifier.of(row.get("block").getAsString()))))continue;
            if(row.get("age").isJsonNull())return true;
            Property<?> age=state.getBlock().getStateManager().getProperty("age");
            return age!=null&&state.get(age).toString().equals(row.get("age").getAsString());
        }
        return false;
    }
    @Override public ActionResult attackBlock(PartyRoundContext context,ServerPlayerEntity player,BlockPos position) {
        return this.canBreak(context,player,position)?ActionResult.PASS:ActionResult.FAIL;
    }
    @Override public ActionResult useBlock(PartyRoundContext context,ServerPlayerEntity player,Hand hand,BlockHitResult hit) {
        BlockPos p=hit.getBlockPos();
        return this.active(context,player)&&hand==Hand.MAIN_HAND
                &&(p.equals(context.sourceBlock(-152,36,-269))||p.equals(context.sourceBlock(-152,36,-259)))
                &&this.world.getBlockState(p).isOf(Blocks.OAK_FENCE_GATE)?ActionResult.PASS:ActionResult.FAIL;
    }

    @Override public boolean ownsEntity(Entity entity) {
        return !this.closed&&entity.getWorld()==this.world&&entity instanceof ItemEntity item
                &&(this.items.contains(entity.getUuid())||this.ownHoe(item.getStack())||this.bounds!=null&&this.bounds.expand(6).contains(item.getPos()));
    }
    @Override public void entityLoaded(PartyRoundContext context,Entity entity) {
        if(entity instanceof ItemEntity&&this.ownsEntity(entity))this.items.add(entity.getUuid());
    }
    private void cleanDrops() {
        for(ItemEntity item:this.world.getEntitiesByClass(ItemEntity.class,this.bounds.expand(6),this::ownsEntity)) {
            if(CLEANUP.contains(Registries.ITEM.getId(item.getStack().getItem()).getPath()))item.discard();
        }
        this.items.removeIf(uuid->this.world.getEntity(uuid)==null);
    }
    private void restore(ServerPlayerEntity player) {
        player.removeStatusEffect(StatusEffects.WEAKNESS);
        for(int slot=0;slot<player.getInventory().size();slot++)if(this.ownHoe(player.getInventory().getStack(slot)))player.getInventory().setStack(slot,ItemStack.EMPTY);
    }
    @Override public void leave(PartyRoundContext context,ServerPlayerEntity player) { this.restore(player); }
    @Override public void finish(PartyRoundContext context) { context.players().forEach(this::restore); }
    @Override public void close(PartyRoundContext context) {
        context.players().forEach(this::restore);
        for(UUID uuid:this.items) { Entity entity=this.world.getEntity(uuid);if(entity!=null)entity.discard(); }
        this.items.clear();this.closed=true;
    }

    private void restoreBanners(PartyRoundContext context) {
        var lookup=this.world.getRegistryManager().getWrapperOrThrow(RegistryKeys.BANNER_PATTERN);
        for(JsonElement encoded:this.rules.getAsJsonArray("banners")) {
            JsonObject row=encoded.getAsJsonObject();BlockPos pos=source(context,row.getAsJsonArray("position"));
            if(!(this.world.getBlockEntity(pos) instanceof BannerBlockEntity banner))continue;
            BannerPatternsComponent.Builder layers=new BannerPatternsComponent.Builder();
            for(JsonElement pattern:row.getAsJsonArray("patterns")) {
                JsonObject layer=pattern.getAsJsonObject();
                layers.add(lookup.getOrThrow(RegistryKey.of(RegistryKeys.BANNER_PATTERN,Identifier.of(layer.get("pattern").getAsString()))),DyeColor.byName(layer.get("color").getAsString(),DyeColor.WHITE));
            }
            ItemStack stack=banner.getPickStack();stack.set(DataComponentTypes.BANNER_PATTERNS,layers.build());
            banner.readFrom(stack,banner.getColorForState());banner.markDirty();
            this.world.updateListeners(pos,banner.getCachedState(),banner.getCachedState(),Block.NOTIFY_LISTENERS);
        }
    }
    private static Operation readOperation(JsonObject row) throws IOException {
        BlockPos a=position(row.getAsJsonArray("from"));
        return new Operation(row.get("op").getAsString(),a,row.has("to")?position(row.getAsJsonArray("to")):a,
                row.has("destination")?position(row.getAsJsonArray("destination")):null,row.has("state")?state(row.get("state").getAsString()):null,
                row.has("replace")?Registries.BLOCK.get(Identifier.of(row.get("replace").getAsString())):null);
    }
    private static BlockState state(String encoded) throws IOException {
        int bracket=encoded.indexOf('[');String id=bracket<0?encoded:encoded.substring(0,bracket);
        Identifier identifier=Identifier.of(id);
        if(!Registries.BLOCK.containsId(identifier))throw new IOException("Unknown Farming block "+id);
        Block block=Registries.BLOCK.get(identifier);BlockState state=block.getDefaultState();
        if(bracket>=0)for(String value:encoded.substring(bracket+1,encoded.length()-1).split(",")) {
            String[] pair=value.split("=",2);Property<?> property=block.getStateManager().getProperty(pair[0]);
            if(property==null)throw new IOException("Unknown Farming state property");
            state=property(state,property,pair[1]);
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState property(BlockState state,Property<T> key,String value) throws IOException {
        return state.with(key,key.parse(value).orElseThrow(()->new IOException("Invalid Farming state value")));
    }
    private static BlockPos position(JsonArray row) { return new BlockPos(row.get(0).getAsInt(),row.get(1).getAsInt(),row.get(2).getAsInt()); }
    private static BlockPos source(PartyRoundContext context,JsonArray row) { return context.sourceBlock(row.get(0).getAsInt(),row.get(1).getAsInt(),row.get(2).getAsInt()); }
    @Override public String scoreDescription(PartyRoundContext context,UUID player) { return context.score(player)+" 采收积分"; }
    @Override public String status(PartyRoundContext context) { return "农场狂欢 · "+Math.max(0,(1800-context.elapsed()+19)/20)+"秒 · 双倍："+(this.season==0?"尚未进入季节":NAMES[this.season-1]); }
}
