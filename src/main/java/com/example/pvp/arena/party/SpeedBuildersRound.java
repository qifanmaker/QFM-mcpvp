package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BlockPredicatesChecker;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.predicate.BlockPredicate;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版417 Speed Builders：28幅原模板无重复抽4幅，每幅独立随机8色，原生冒险模式搭建。
 * 修改原因：原图依赖全服clone/计分板/命令方块，无法直接在共享大厅运行；虚拟放置会失去
 * can_place_on/can_break、金镐采掘、完整空气比较与可编辑但每tick恢复的预览。
 * 自查：保留同方向X-8目标、3600tick、逐tick补给/先恢复后比较、10个原生30tick按钮及
 * 1tick后限定fill、N<=7结束N-1/N>=8结束N-2、同tick并列；只跟踪本场坐标/实体/物品。
 * 根据相同SHA源文件复核，FlowerBlocker实际40次召唤（奇数座2、偶数座6），非旧审计摘要的50。
 */
public final class SpeedBuildersRound implements PartyRound {
    private static final BlockPos ANCHOR = new BlockPos(-361,34,-140);
    private static final BlockPos MINIMUM = new BlockPos(-389,27,-180);
    private static final BlockPos MAXIMUM = new BlockPos(-337,54,-100);
    private static final String TOKEN_KEY = "pvp_party_speed_builders";
    private static final Block[] WOODS = {Blocks.OAK_PLANKS,Blocks.SPRUCE_PLANKS,Blocks.BIRCH_PLANKS,
            Blocks.JUNGLE_PLANKS,Blocks.ACACIA_PLANKS,Blocks.DARK_OAK_PLANKS,Blocks.CRIMSON_PLANKS,Blocks.WARPED_PLANKS};
    private static final Block[] COLORS = {Blocks.RED_CONCRETE,Blocks.ORANGE_CONCRETE,Blocks.YELLOW_CONCRETE,
            Blocks.LIME_CONCRETE,Blocks.LIGHT_BLUE_CONCRETE,Blocks.BLUE_CONCRETE,Blocks.MAGENTA_CONCRETE,Blocks.GRAY_CONCRETE};
    private static final Block[] PLACE_ON = {Blocks.WHITE_CONCRETE_POWDER,Blocks.RED_CONCRETE,Blocks.ORANGE_CONCRETE,
            Blocks.YELLOW_CONCRETE,Blocks.LIME_CONCRETE,Blocks.GREEN_CONCRETE,Blocks.CYAN_CONCRETE,Blocks.LIGHT_BLUE_CONCRETE,
            Blocks.BLUE_CONCRETE,Blocks.PURPLE_CONCRETE,Blocks.MAGENTA_CONCRETE,Blocks.PINK_CONCRETE,Blocks.BROWN_CONCRETE,
            Blocks.GRAY_CONCRETE,Blocks.LIGHT_GRAY_CONCRETE,Blocks.WHITE_CONCRETE};
    private static final class Builder {
        final UUID uuid;
        final int seat;
        int copied;
        int lastCompletion;
        boolean left;
        Builder(UUID uuid,int seat) { this.uuid=uuid;this.seat=seat; }
    }
    private record Blocker(InteractionEntity entity,int seat) { }
    private final String token=UUID.randomUUID().toString();
    private final Map<UUID,Builder> builders=new LinkedHashMap<>();
    private final List<Map<BlockPos,BlockState>> models=new ArrayList<>();
    private final Map<BlockPos,Integer> buttons=new HashMap<>();
    private final Map<Integer,Integer> resets=new HashMap<>();
    private final List<Blocker> blockers=new ArrayList<>();
    private final Set<UUID> owned=new HashSet<>();
    private final Set<UUID> dropped=new HashSet<>();
    private final List<ItemStack> supply=new ArrayList<>();
    private JsonObject audit;
    private ServerWorld world;
    private Box bounds;
    private boolean started;
    private boolean closed;
    private boolean checkpointUsed;

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(ANCHOR);
        context.timeLimit(3600);
        this.world=context.world();
        this.bounds=new Box(context.source(MINIMUM.getX(),MINIMUM.getY(),MINIMUM.getZ()),
                context.source(MAXIMUM.getX()+1,MAXIMUM.getY()+1,MAXIMUM.getZ()+1));
        try(var input=SpeedBuildersRound.class.getResourceAsStream("/pvp/party/speed_builders_rules.json")) {
            if(input==null)throw new IOException("Missing audited417 rules");
            try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8)) { this.audit=JsonParser.parseReader(reader).getAsJsonObject(); }
        }
        Map<BlockPos,BlockState> sourceScene=new HashMap<>();
        PartyMapBlueprint.load("speed_builders_arena").forEachTranslated(BlockPos.ORIGIN,(pos,state)->{
            sourceScene.put(pos.toImmutable(),state);
            context.stage(context.sourceBlock(pos.getX(),pos.getY(),pos.getZ()),state);
        });
        int count=context.initialPlayerCount();
        for(int z:new int[]{-148,-133,-118}) {
            boolean wall=z==-148?count<=4:z==-133?count>=5&&count<=6:count>=7&&count<=8;
            for(BlockPos pos:BlockPos.iterate(new BlockPos(-384,43,z),new BlockPos(-338,52,z))) {
                BlockState state=sourceScene.getOrDefault(pos,Blocks.AIR.getDefaultState());
                if(wall&&state.isAir()) context.stage(context.sourceBlock(pos.getX(),pos.getY(),pos.getZ()),Blocks.DEEPSLATE_BRICKS.getDefaultState());
                else if(!wall&&state.isOf(Blocks.DEEPSLATE_BRICKS))context.stage(context.sourceBlock(pos.getX(),pos.getY(),pos.getZ()),Blocks.AIR.getDefaultState());
            }
        }
        this.generateModels(context);
        int seat=0;
        for(ServerPlayerEntity player:context.players()) {
            Builder builder=new Builder(player.getUuid(),seat++);
            this.builders.put(builder.uuid,builder);
            context.spawn(builder.uuid,context.source(builder.seat%2==0?-372.0:-349.0,34,-166.0+15*(builder.seat/2)));
        }
        for(JsonElement row:this.audit.getAsJsonArray("resetCommands")) {
            BlockPos command=sourcePosition(context,row.getAsJsonObject().getAsJsonArray("pos"));
            int seatIndex=command.getX()==context.sourceBlock(-373,35,-164).getX()?0:1;
            int rowIndex=(command.getZ()-context.sourceBlock(0,35,-164).getZ())/15;
            this.buttons.put(command.north(2),rowIndex*2+seatIndex);
        }
        this.createSupply();
    }

    private void generateModels(PartyRoundContext context) throws IOException {
        List<Integer> remaining=new ArrayList<>();
        for(int index=1;index<=28;index++)remaining.add(index);
        for(int build=0;build<4;build++) {
            int id=remaining.remove(context.random().nextInt(remaining.size()));
            int column=(id-1)%7,row=(id-1)/7;
            BlockPos origin=new BlockPos(-343-7*column,28,-153-7*row);
            List<Block> colors=new ArrayList<>(List.of(COLORS));
            Collections.shuffle(colors,context.random());
            Map<BlockPos,BlockState> model=new LinkedHashMap<>();
            PartyMapBlueprint blueprint=PartyMapBlueprint.load("speed_builders_pattern_"+id);
            blueprint.forEachPosition(origin.multiply(-1),pos->model.put(pos.toImmutable(),Blocks.AIR.getDefaultState()));
            blueprint.forEachTranslated(origin.multiply(-1),(pos,state)->{
                int material=-1;
                for(int index=0;index<WOODS.length;index++)if(state.isOf(WOODS[index]))material=index;
                if(material<0)throw new IllegalStateException("417 template contains non-plank material");
                model.put(pos.toImmutable(),colors.get(material).getDefaultState());
            });
            this.models.add(Map.copyOf(model));
        }
        // The original recursive generator chooses a fifth model then discards
        // its selectors without recoloring/cloning. Preserve that random draw.
        remaining.remove(context.random().nextInt(remaining.size()));
    }

    @Override public void start(PartyRoundContext context) {
        this.started=true;
        this.restoreDecorations(context);
        for(JsonElement row:this.audit.getAsJsonArray("flowerBlockers")) {
            JsonObject data=row.getAsJsonObject();int seat=data.get("seat").getAsInt();
            if(seat>=this.builders.size())continue;
            BlockPos pos=sourcePosition(context,data.getAsJsonArray("pos"));
            InteractionEntity entity=new InteractionEntity(EntityType.INTERACTION,this.world);
            entity.setPosition(pos.getX(),pos.getY(),pos.getZ());
            entity.addCommandTag("party_speed_flower_blocker");
            this.blockers.add(new Blocker(entity,seat));this.owned.add(entity.getUuid());
            this.world.spawnEntity(entity);
        }
        for(ServerPlayerEntity player:context.players()) {
            player.setYaw(180);player.setPitch(0);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST,19999980,2,false,false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,19999980,255,false,false));
            this.replenish(player);
            context.score(player.getUuid(),3);
        }
        for(Builder builder:this.builders.values())this.restorePreview(context,builder);
        context.title("§4§lBuild!","§f复制右侧建筑到左侧白色方格，保持方向一致");
        context.announce("§a快速建造：3分钟复制4幅建筑。每幅随机配色，模型不会镜像。"
                +"\n§e使用原生金镐和8色方块；预览会自动恢复，按钮可清空当前房间的模型和目标后重新展示。"
                +"\n§7完成数优先，相同进度先到者优先。");
    }

    @Override public void tick(PartyRoundContext context,int elapsed) {
        if(!this.started||this.closed)return;
        for(var iterator=this.resets.entrySet().iterator();iterator.hasNext();) {
            var reset=iterator.next();
            if(elapsed>=reset.getValue()) {
                BlockPos button=context.sourceBlock(reset.getKey()%2==0?-373:-350,35,-166+15*(reset.getKey()/2));
                // Only an actual accepted native button press powers the source
                // command. A canceled/sneaking/off-hand use must not clear builds.
                if(this.world.getBlockState(button).isOf(Blocks.OAK_BUTTON)&&this.world.getBlockState(button).get(ButtonBlock.POWERED))this.resetLane(context,reset.getKey());
                iterator.remove();
            }
        }
        this.discardDrops();
        for(ServerPlayerEntity player:context.players())if(this.active(context,player))this.replenish(player);
        // Source restores every preview before any of the ten completion tests.
        for(Builder builder:this.builders.values())if(!builder.left&&builder.copied<4)this.restorePreview(context,builder);
        for(ServerPlayerEntity player:context.players()) {
            Builder builder=this.builders.get(player.getUuid());
            if(this.active(context,player)&&this.matches(context,builder))this.complete(context,player,builder,elapsed);
        }
        this.updateScores(context);
        int threshold=context.initialPlayerCount()-(context.initialPlayerCount()>=8?2:1);
        if(this.builders.values().stream().filter(builder->builder.copied>=4).count()>=threshold)context.finish();
    }

    private void restorePreview(PartyRoundContext context,Builder builder) {
        BlockPos base=preview(context,builder.seat);
        this.models.get(builder.copied).forEach((relative,state)->{
            BlockPos pos=base.add(relative);
            if(!this.world.getBlockState(pos).equals(state))context.set(pos,state);
        });
    }

    private boolean matches(PartyRoundContext context,Builder builder) {
        BlockPos target=preview(context,builder.seat).west(8);
        return this.models.get(builder.copied).entrySet().stream()
                .allMatch(cell->this.world.getBlockState(target.add(cell.getKey())).equals(cell.getValue()));
    }

    private void complete(PartyRoundContext context,ServerPlayerEntity player,Builder builder,int elapsed) {
        builder.copied++;builder.lastCompletion=elapsed;this.checkpointUsed=true;
        player.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP,SoundCategory.MASTER,1,0);
        BlockPos min=preview(context,builder.seat).add(-9,0,-1);
        this.clear(context,min,min.add(7,7,7));
        player.sendMessage(Text.literal("§a已复制建筑 "+builder.copied+"/4"),false);
        if(builder.copied>=4) {
            player.removeStatusEffect(StatusEffects.JUMP_BOOST);
            this.clearItems(player);
            context.teleport(player,new Vec3d(player.getX(),context.source(0,44,0).y,player.getZ()));
            player.changeGameMode(GameMode.SPECTATOR);
            context.completed(player.getUuid());
            context.announce("§7"+player.getName().getString()+" 已复制全部4幅建筑！");
        }
    }

    private void resetLane(PartyRoundContext context,int seat) {
        BlockPos command=context.sourceBlock(seat%2==0?-373:-350,35,-164+15*(seat/2));
        this.clear(context,command.add(-7,-1,-10),command.add(8,6,-3));
    }

    private void clear(PartyRoundContext context,BlockPos minimum,BlockPos maximum) {
        for(BlockPos pos:BlockPos.iterate(minimum,maximum))context.set(pos,Blocks.AIR.getDefaultState());
    }

    private void createSupply() {
        ItemStack pickaxe=new ItemStack(Items.GOLDEN_PICKAXE);
        pickaxe.set(DataComponentTypes.CAN_BREAK,new BlockPredicatesChecker(List.of(BlockPredicate.Builder.create().blocks(COLORS).build()),true));
        pickaxe.set(DataComponentTypes.UNBREAKABLE,new UnbreakableComponent(true));
        this.tag(pickaxe);this.supply.add(pickaxe);
        for(Block color:COLORS) {
            ItemStack stack=new ItemStack(color,64);
            stack.set(DataComponentTypes.CAN_PLACE_ON,new BlockPredicatesChecker(List.of(BlockPredicate.Builder.create().blocks(PLACE_ON).build()),true));
            this.tag(stack);this.supply.add(stack);
        }
    }

    private void tag(ItemStack stack) {
        NbtCompound data=new NbtCompound();data.putString(TOKEN_KEY,this.token);
        stack.set(DataComponentTypes.CUSTOM_DATA,NbtComponent.of(data));
    }

    private boolean ownItem(ItemStack stack) {
        return stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().getString(TOKEN_KEY).equals(this.token);
    }

    private void replenish(ServerPlayerEntity player) {
        for(int slot=0;slot<this.supply.size();slot++) {
            if(!ItemStack.areEqual(player.getInventory().getStack(slot),this.supply.get(slot)))player.getInventory().setStack(slot,this.supply.get(slot).copy());
        }
    }

    private boolean active(PartyRoundContext context,ServerPlayerEntity player) {
        Builder builder=this.builders.get(player.getUuid());
        return this.started&&!this.closed&&builder!=null&&!builder.left&&builder.copied<4
                &&!context.isOut(player.getUuid())&&player.getWorld()==this.world;
    }

    @Override public boolean canBreak(PartyRoundContext context,ServerPlayerEntity player,BlockPos pos) {
        if(!this.active(context,player)||!this.bounds.contains(Vec3d.ofCenter(pos))
                ||!player.getMainHandStack().isOf(Items.GOLDEN_PICKAXE)||!this.ownItem(player.getMainHandStack()))return false;
        return concrete(this.world.getBlockState(pos));
    }
    @Override public ActionResult attackBlock(PartyRoundContext context,ServerPlayerEntity player,BlockPos pos) {
        return this.canBreak(context,player,pos)?ActionResult.PASS:ActionResult.FAIL;
    }
    @Override public boolean canPlace(PartyRoundContext context,ServerPlayerEntity player,BlockPos pos) {
        return this.active(context,player)&&this.bounds.contains(Vec3d.ofCenter(pos));
    }
    @Override public ActionResult useItem(PartyRoundContext context,ServerPlayerEntity player,Hand hand) {
        ItemStack stack=player.getStackInHand(hand);
        return this.active(context,player)&&this.ownItem(stack)&&stack.getItem() instanceof BlockItem item
                &&concrete(item.getBlock().getDefaultState())?ActionResult.PASS:ActionResult.FAIL;
    }
    @Override public ActionResult useBlock(PartyRoundContext context,ServerPlayerEntity player,Hand hand,BlockHitResult hit) {
        if(!this.active(context,player))return ActionResult.FAIL;
        Integer seat=this.buttons.get(hit.getBlockPos());
        if(seat!=null&&this.world.getBlockState(hit.getBlockPos()).isOf(Blocks.OAK_BUTTON)) {
            boolean cancelsDefault=player.shouldCancelInteraction()&&(!player.getMainHandStack().isEmpty()||!player.getOffHandStack().isEmpty());
            if(hand==Hand.MAIN_HAND&&!cancelsDefault&&!this.world.getBlockState(hit.getBlockPos()).get(ButtonBlock.POWERED))this.resets.put(seat,context.elapsed()+1);
            return ActionResult.PASS;
        }
        return this.useItem(context,player,hand);
    }
    @Override public ActionResult useEntity(PartyRoundContext context,ServerPlayerEntity player,Hand hand,Entity entity) {
        return this.active(context,player)&&entity instanceof InteractionEntity&&this.owned.contains(entity.getUuid())?ActionResult.PASS:ActionResult.FAIL;
    }
    @Override public boolean ownsEntity(Entity entity) {return this.owned.contains(entity.getUuid())||entity instanceof ItemEntity item&&this.ownItem(item.getStack());}
    @Override public void entityLoaded(PartyRoundContext context,Entity entity) {
        if(entity instanceof ItemEntity item&&this.ownItem(item.getStack())) {this.dropped.add(entity.getUuid());this.owned.add(entity.getUuid());}
    }

    private static boolean concrete(BlockState state) {for(Block color:COLORS)if(state.isOf(color))return true;return false;}
    private static BlockPos preview(PartyRoundContext context,int seat) {return context.sourceBlock(seat%2==0?-371:-348,34,-173+15*(seat/2));}
    private static BlockPos sourcePosition(PartyRoundContext context,JsonArray array) {return context.sourceBlock(array.get(0).getAsInt(),array.get(1).getAsInt(),array.get(2).getAsInt());}

    private void updateScores(PartyRoundContext context) {
        for(Builder builder:this.builders.values()) {
            int recency=this.checkpointUsed?(int)this.builders.values().stream().filter(other->other.lastCompletion>builder.lastCompletion).count():3;
            context.score(builder.uuid,builder.copied*context.initialPlayerCount()+recency);
        }
    }
    private void discardDrops() {
        for(UUID uuid:this.dropped) {Entity entity=this.world.getEntity(uuid);if(entity!=null)entity.discard();this.owned.remove(uuid);}
        this.dropped.clear();
    }
    private void clearItems(ServerPlayerEntity player) {
        for(int slot=0;slot<player.getInventory().size();slot++)if(this.ownItem(player.getInventory().getStack(slot)))player.getInventory().setStack(slot,ItemStack.EMPTY);
        if(this.ownItem(player.currentScreenHandler.getCursorStack()))player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
    }
    private void restore(ServerPlayerEntity player) {
        this.clearItems(player);player.removeStatusEffect(StatusEffects.JUMP_BOOST);player.removeStatusEffect(StatusEffects.WEAKNESS);
    }
    @Override public void leave(PartyRoundContext context,ServerPlayerEntity player) {
        Builder builder=this.builders.get(player.getUuid());
        if(builder!=null) {builder.left=true;for(Blocker blocker:this.blockers)if(blocker.seat()==builder.seat){blocker.entity().discard();this.owned.remove(blocker.entity().getUuid());}}
        this.restore(player);
    }
    @Override public void finish(PartyRoundContext context) {this.updateScores(context);}
    @Override public void close(PartyRoundContext context) {
        if(this.closed)return;this.closed=true;
        for(ServerPlayerEntity player:context.players())this.restore(player);
        this.discardDrops();
        for(Blocker blocker:this.blockers)blocker.entity().discard();
        this.owned.clear();this.resets.clear();
    }
    @Override public String scoreDescription(PartyRoundContext context,UUID player) {
        Builder builder=this.builders.get(player);return builder==null?"0 / 4 幅":builder.copied+" / 4 幅";
    }
    @Override public String status(PartyRoundContext context) {return "§e复制4幅 · §f"+Math.max(0,(3600-context.elapsed()+19)/20)+"s";}

    private void restoreDecorations(PartyRoundContext context) {
        for(JsonElement row:this.audit.getAsJsonArray("signs")) {
            JsonObject data=row.getAsJsonObject();BlockPos pos=sourcePosition(context,data.getAsJsonArray("pos"));
            if(this.world.getBlockEntity(pos) instanceof SignBlockEntity sign) {
                for(String side:new String[]{"front","back"}) {
                    SignText text=new SignText().withColor(DyeColor.byName(data.get(side+"Color").getAsString(),DyeColor.BLACK))
                            .withGlowing(data.get(side+"Glowing").getAsBoolean());
                    JsonArray lines=data.getAsJsonArray(side);
                    for(int index=0;index<Math.min(4,lines.size());index++)text=text.withMessage(index,visibleText(lines.get(index).getAsJsonObject()));
                    sign.setText(text,side.equals("front"));
                }
                sign.markDirty();this.world.updateListeners(pos,sign.getCachedState(),sign.getCachedState(),Block.NOTIFY_LISTENERS);
            }
        }
        var patterns=this.world.getRegistryManager().getWrapperOrThrow(RegistryKeys.BANNER_PATTERN);
        for(JsonElement row:this.audit.getAsJsonArray("banners")) {
            JsonObject data=row.getAsJsonObject();BlockPos pos=sourcePosition(context,data.getAsJsonArray("pos"));
            if(this.world.getBlockEntity(pos) instanceof BannerBlockEntity banner) {
                BannerPatternsComponent.Builder layers=new BannerPatternsComponent.Builder();
                for(JsonElement encoded:data.getAsJsonArray("patterns")) {
                    JsonObject layer=encoded.getAsJsonObject();
                    // Resolve the audited registry key explicitly; the legacy
                    // Builder.add(lookup,key,color) overload is deprecated.
                    layers.add(patterns.getOrThrow(RegistryKey.of(RegistryKeys.BANNER_PATTERN,Identifier.of(layer.get("pattern").getAsString()))),
                            DyeColor.byName(layer.get("color").getAsString(),DyeColor.WHITE));
                }
                ItemStack stack=banner.getPickStack();stack.set(DataComponentTypes.BANNER_PATTERNS,layers.build());
                banner.readFrom(stack,banner.getColorForState());banner.markDirty();
                this.world.updateListeners(pos,banner.getCachedState(),banner.getCachedState(),Block.NOTIFY_LISTENERS);
            }
        }
    }

    private static Text visibleText(JsonObject encoded) {
        Style style=Style.EMPTY;
        if(encoded.has("color")) {var color=TextColor.parse(encoded.get("color").getAsString()).result();if(color.isPresent())style=style.withColor(color.get());}
        if(encoded.has("bold"))style=style.withBold(encoded.get("bold").getAsBoolean());
        if(encoded.has("italic"))style=style.withItalic(encoded.get("italic").getAsBoolean());
        if(encoded.has("underlined"))style=style.withUnderline(encoded.get("underlined").getAsBoolean());
        return Text.literal(encoded.get("text").getAsString()).setStyle(style);
    }
}
