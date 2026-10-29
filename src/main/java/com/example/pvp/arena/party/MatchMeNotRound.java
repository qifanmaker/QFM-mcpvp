package com.example.pvp.arena.party;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.block.AbstractBannerBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版422 Match Me Not：真实丢物隐蔽选择，八轮独选得分，21根原生活塞推动平台与玩家。
 * 修改原因：原全服物品选择器、临时marker、clone与红石不能直接运行于共享大厅；按按钮选择、
 * 虚拟模型或得分后传送会改变实际输入、碰撞与逐层材质。完整场景和十份147格平台保留空气。
 * 自查：第5准备tick同时选材、200tick窗口、全选时不减最后一tick、60计数按人数跳转、
 * 500计分/510撤电、末40tick、同tick多物品按1..7覆盖、零选择不随机；所有状态限定本场。
 * 开启本回合原生地形变化跟踪，确保活塞搬动的方块也能清理；修正原end遗漏场景Y17。
 */
public final class MatchMeNotRound implements PartyRound {
    private static final BlockPos ANCHOR=new BlockPos(26,2,533);
    private static final String TOKEN_KEY="pvp_party_match_me_not";
    private static final String DISPLAY_TAG="party_mmn_display";
    private static final String[] FLAGS={"bricks","iron","gold","netherite","nether_bricks","copper","diamond"};
    private static final String[] LABELS={"Bricks","Iron","Gold","Netherite","Nether Bricks","Copper","Diamond"};
    private static final int[] TEXT_COLORS={0xD91515,0xD9D9D9,0xFFAA00,0x363636,0x360505,0xFF6E19,0x55FFFF};
    private static final class Native {
        static final Item[] ITEMS={Items.BRICK,Items.IRON_INGOT,Items.GOLD_INGOT,Items.NETHERITE_INGOT,Items.NETHER_BRICK,Items.COPPER_INGOT,Items.DIAMOND};
        static final Block[] BLOCKS={Blocks.BRICKS,Blocks.IRON_BLOCK,Blocks.GOLD_BLOCK,Blocks.NETHERITE_BLOCK,Blocks.NETHER_BRICKS,Blocks.COPPER_BLOCK,Blocks.DIAMOND_BLOCK};
        static final DustParticleEffect MASK=new DustParticleEffect(new Vector3f(1,1,1),4);
    }
    private static final class Chip {
        final UUID owner;
        final int seat;
        final Vec3d start;
        final Vec3d inner;
        final BlockPos center;
        final float yaw;
        ItemFrameEntity frame;
        int choice;
        int points;
        boolean unique;
        boolean left;
        Chip(UUID owner,int seat,Vec3d start,Vec3d inner,float yaw) {
            this.owner=owner;this.seat=seat;this.start=start;this.inner=inner;this.center=BlockPos.ofFloored(start);this.yaw=yaw;
        }
    }
    private final String token=UUID.randomUUID().toString();
    private final Map<UUID,Chip> chips=new LinkedHashMap<>();
    private final Map<UUID,ItemEntity> dropped=new LinkedHashMap<>();
    private final Map<UUID,ItemEntity> displays=new HashMap<>();
    private final Set<UUID> owned=new HashSet<>();
    private final List<ItemStack> choices=new ArrayList<>();
    private Map<BlockPos,BlockState> introGeometry=Map.of();
    private JsonObject audit;
    private ServerWorld world;
    private int phase=1;
    private int round=1;
    private int preparation;
    private int choiceTicks=200;
    private int reveal;
    private int endingAt=-1;
    private boolean started;
    private boolean closed;
    private boolean circleReady;

    static int materialCount(int players) {return players<=4?4:players<=6?5:players<=8?6:7;}
    static int revealReset(int players) {return players<=4?440:440-20*(players-4);}
    static int angleStep(int players) {return 360/players;}
    static int circleRadius(int players) {return players>=9?12:10;}
    static int uniquePoints(int choice,int frequency) {return choice>=1&&choice<=7&&frequency==1?1:0;}
    static boolean pistonCell(int x,int z) {return Math.abs(x)<=2&&Math.abs(z)<=2&&(Math.abs(x)<=1||Math.abs(z)<=1);}

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(ANCHOR);context.timeLimit(0);this.world=context.world();
        try(var input=MatchMeNotRound.class.getResourceAsStream("/pvp/party/match_me_not_rules.json")) {
            if(input==null)throw new IOException("Missing audited422 rules");
            try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8)) {this.audit=JsonParser.parseReader(reader).getAsJsonObject();}
        }
        // Preserve all air in the original three adjoining clone boxes. Native
        // moving pistons outside those boxes use tracksTerrainChanges below.
        for(BlockPos pos:BlockPos.iterate(context.sourceBlock(0,0,500),context.sourceBlock(52,17,566)))context.stage(pos,Blocks.AIR.getDefaultState());
        Map<BlockPos,BlockState> staged=new HashMap<>();
        PartyMapBlueprint.load("match_me_not_arena").forEachTranslated(context.sourceBlock(350,12,885),(pos,state)->stage(context,staged,pos,state));
        int count=context.initialPlayerCount(),seat=0,radius=circleRadius(count);
        for(ServerPlayerEntity player:context.players()) {
            // Source player_circle walks radius unit steps at integer yaw.
            float radians=(seat*angleStep(count)+90f)*(float)(Math.PI/180);
            double sx=MathHelper.cos(radians),sz=MathHelper.sin(radians),x=26,z=533;
            for(int step=0;step<radius;step++){x+=sx;z+=sz;}
            Vec3d start=context.source(x,2,z);
            float yaw=MathHelper.wrapDegrees((float)(MathHelper.atan2(533-z,26-x)*180/Math.PI)-90);
            float facing=(yaw+90f)*(float)(Math.PI/180);
            Vec3d inner=context.source(x+MathHelper.cos(facing)*6,1,z+MathHelper.sin(facing)*6);
            Chip chip=new Chip(player.getUuid(),seat,start,inner,yaw);this.chips.put(chip.owner,chip);
            context.spawn(chip.owner,context.source(26,1,533));
            BlockPos sourceMin=new BlockPos(-361,6-4*seat,-324),destination=chip.center.add(-3,-2,-3);
            PartyMapBlueprint blueprint=PartyMapBlueprint.load("match_me_not_chip_"+(seat+1));
            blueprint.forEachPosition(destination.subtract(sourceMin),pos->stage(context,staged,pos,Blocks.AIR.getDefaultState()));
            blueprint.forEachTranslated(destination.subtract(sourceMin),(pos,state)->stage(context,staged,pos,state));
            this.stageChip(context,staged,chip);
            stage(context,staged,new BlockPos((int)Math.floor(inner.x),context.sourceBlock(0,0,0).getY(),(int)Math.floor(inner.z)),Blocks.CHISELED_STONE_BRICKS.getDefaultState());
            seat++;
        }
        this.introGeometry=Map.copyOf(staged);
        for(int material=0;material<materialCount(count);material++)this.choices.add(this.choiceItem(material));
    }

    private static void stage(PartyRoundContext context,Map<BlockPos,BlockState> staged,BlockPos pos,BlockState state) {
        staged.put(pos.toImmutable(),state);
    }

    private void stageChip(PartyRoundContext context,Map<BlockPos,BlockState> staged,Chip chip) {
        BlockPos center=chip.center;
        for(int x=-1;x<=1;x++) {
            stage(context,staged,center.add(x,-1,3),Blocks.WHITE_CARPET.getDefaultState());
            stage(context,staged,center.add(x,-1,-3),Blocks.WHITE_CARPET.getDefaultState());
        }
        // Preserve the source's asymmetric right fill, not an invented mirror.
        stage(context,staged,center.add(3,-1,-1),Blocks.WHITE_CARPET.getDefaultState());
        stage(context,staged,center.add(-3,-1,-1),Blocks.WHITE_CARPET.getDefaultState());
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)if(pistonCell(x,z)) {
            stage(context,staged,center.add(x,-3,z),Blocks.PISTON.getDefaultState().with(PistonBlock.FACING,Direction.UP));
        }
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++) {
            boolean border=Math.abs(x)==3&&Math.abs(z)<=1||Math.abs(z)==3&&Math.abs(x)<=1||Math.abs(x)==2&&Math.abs(z)==2;
            if(!border)continue;
            for(int y=0;y<=10;y++) {
                BlockPos pos=center.add(x,y,z);
                if(staged.getOrDefault(pos,Blocks.AIR.getDefaultState()).isAir())stage(context,staged,pos,Blocks.BARRIER.getDefaultState());
            }
        }
    }

    @Override public void start(PartyRoundContext context) {
        this.started=true;
        if(!this.circleReady)this.placeCircle(context);
        for(ServerPlayerEntity player:context.players()) {
            Chip chip=this.chips.get(player.getUuid());
            player.setHealth(player.getMaxHealth());
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,-1,99,false,false));
            context.score(chip.owner,0);
        }
        this.sound(context,SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,10,0);
        context.announce("§aMatch Me Not：丢出一种材料作出隐蔽选择。\n§e只有无人与你重复选材时，你得1分，原生活塞将平台升高1格。"
                +"\n§7共8次选材，每次最多10秒；不选择不计分，最终按独选次数排名。");
    }

    @Override public boolean locksIntroPosition() {return false;}

    @Override public void introTick(PartyRoundContext context,int elapsed) {
        if(this.closed)return;
        if(elapsed==0)for(ServerPlayerEntity player:context.players()) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION,80,0,false,false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,-1,99,false,false));
        }
        // Source entry schedules rules after3s; that first rule creates the
        // actual circle/field while20ticks of native levitation still remain.
        if(elapsed==60&&!this.circleReady)this.placeCircle(context);
    }

    private void placeCircle(PartyRoundContext context) {
        this.circleReady=true;
        // Original clone installs its support blocks before neighbor updates.
        // Do not let arbitrary HashMap iteration break a banner/carpet before
        // its support has appeared in the deliberately delayed native scene.
        var cells=new ArrayList<>(this.introGeometry.entrySet());
        cells.sort(Comparator.comparingInt((Map.Entry<BlockPos,BlockState> cell)->cell.getValue().isAir()?0:
                        cell.getValue().getBlock() instanceof AbstractBannerBlock||cell.getValue().getBlock() instanceof CarpetBlock
                                ||cell.getValue().getBlock() instanceof ButtonBlock?2:1)
                .thenComparingInt(cell->cell.getKey().getY()).thenComparingInt(cell->cell.getKey().getZ()).thenComparingInt(cell->cell.getKey().getX()));
        cells.forEach(cell->context.set(cell.getKey(),cell.getValue()));this.introGeometry=Map.of();
        this.restoreBanners(context);
        for(ServerPlayerEntity player:context.players()) {
            Chip chip=this.chips.get(player.getUuid());if(chip==null||chip.left)continue;
            // Session teleport sends the current rotation in its native position
            // packet. Set source facing first so the client also sees the center.
            player.setYaw(chip.yaw);player.setPitch(0);context.teleport(player,chip.start);
            if(chip.frame==null)this.createFrame(context,chip);
        }
    }

    private void createFrame(PartyRoundContext context,Chip chip) {
        BlockPos pos=new BlockPos((int)Math.floor(chip.inner.x),context.sourceBlock(0,0,0).getY(),(int)Math.floor(chip.inner.z));
        ItemFrameEntity frame=new ItemFrameEntity(this.world,pos,Direction.DOWN);
        // Safe manually constructed engine properties, not imported source NBT.
        NbtCompound data=new NbtCompound();data.putInt("TileX",pos.getX());data.putInt("TileY",pos.getY());data.putInt("TileZ",pos.getZ());
        data.putByte("Facing",(byte)Direction.DOWN.getId());data.putBoolean("Fixed",true);data.putBoolean("Invisible",true);
        frame.readCustomDataFromNbt(data);frame.setInvulnerable(true);frame.setSilent(true);frame.addCommandTag("party_mmn_frame");
        chip.frame=frame;this.owned.add(frame.getUuid());this.world.spawnEntity(frame);
    }

    @Override public boolean handlesTimeLimit() {return true;}
    @Override public boolean tracksTerrainChanges() {return true;}

    @Override public void tick(PartyRoundContext context,int elapsed) {
        if(!this.started||this.closed)return;
        if(this.endingAt>=0&&elapsed>=this.endingAt){context.finish();return;}
        if(this.phase==1) {
            if(this.preparation==0)this.beginChoice(context);
            this.preparation++;
            if(this.preparation==5)this.phase=2;
        }
        if(this.phase==2) {
            this.collectChoices(context);
            List<ServerPlayerEntity> active=this.activePlayers(context);
            for(ServerPlayerEntity player:active)if(this.chips.get(player.getUuid()).choice>0)this.clearItems(player);
            this.maskChoices(active);
            if(active.stream().allMatch(player->this.chips.get(player.getUuid()).choice>0))this.phase=3;
            if(this.phase==2&&--this.choiceTicks<=0)this.phase=3;
        }
        if(this.phase==3) {
            this.reveal++;
            for(ServerPlayerEntity player:this.activePlayers(context))this.clearItems(player);
            if(this.reveal==20)this.sound(context,SoundEvents.UI_BUTTON_CLICK.value(),10,0);
            if(this.reveal==60) {
                this.sound(context,SoundEvents.BLOCK_GRINDSTONE_USE,10,.75f);
                this.spawnReveals(context);
                this.reveal=revealReset(context.initialPlayerCount());
            }
            if(this.reveal==500)this.scoreChoices(context);
            if(this.reveal==510)this.finishChoice(context,elapsed);
        }
    }

    private void beginChoice(PartyRoundContext context) {
        this.reveal=0;this.choiceTicks=200;
        for(ServerPlayerEntity player:this.activePlayers(context)) {
            Chip chip=this.chips.get(player.getUuid());chip.choice=0;chip.unique=false;
            this.clearItems(player);
            for(ItemStack item:this.choices)player.getInventory().insertStack(item.copy());
            for(BlockPos pos:BlockPos.iterate(chip.center.add(-2,-2,-2),chip.center.add(2,-2,2)))context.set(pos,Blocks.WHITE_WOOL.getDefaultState());
            player.sendMessage(Text.literal("§e选材 "+this.round+"/8：丢出1种材料；只有无人重复才得分。"),false);
        }
    }

    private void collectChoices(PartyRoundContext context) {
        List<ServerPlayerEntity> active=this.activePlayers(context);
        // The source processes all brick items, then all iron, ... diamond;
        // simultaneous distinct drops overwrite in that same material order.
        for(int material=1;material<=7;material++)for(ItemEntity item:List.copyOf(this.dropped.values())) {
            if(item.isRemoved()||this.material(item.getStack())!=material)continue;
            ServerPlayerEntity nearest=active.stream().min(java.util.Comparator.comparingDouble(player->player.squaredDistanceTo(item))).orElse(null);
            if(nearest!=null) {
                this.chips.get(nearest.getUuid()).choice=material;
                nearest.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,SoundCategory.MASTER,10,2);
                nearest.sendMessage(Text.literal("§7已选择："+LABELS[material-1]),false);
            }
        }
        this.discardDrops();
    }

    private void maskChoices(List<ServerPlayerEntity> active) {
        for(ServerPlayerEntity subject:active)for(ServerPlayerEntity viewer:active)if(viewer!=subject) {
            this.world.spawnParticles(viewer,Native.MASK,true,subject.getX(),subject.getY()+1,subject.getZ(),10,.75,.75,.75,0);
        }
    }

    private void spawnReveals(PartyRoundContext context) {
        for(ServerPlayerEntity viewer:context.players())for(Chip chip:this.chips.values())if(!chip.left) {
            this.world.spawnParticles(viewer,ParticleTypes.ASH,true,chip.inner.x,chip.inner.y,chip.inner.z,100,.1,.1,.1,1);
        }
        for(Chip chip:this.chips.values())if(!chip.left&&chip.choice>=1&&chip.choice<=7&&chip.frame!=null) {
            ItemEntity display=new ItemEntity(this.world,chip.frame.getX(),chip.frame.getY()+.5,chip.frame.getZ(),this.choiceItem(chip.choice-1));
            display.setVelocity(Vec3d.ZERO);display.resetPickupDelay();display.addCommandTag(DISPLAY_TAG);
            this.owned.add(display.getUuid());this.displays.put(display.getUuid(),display);this.world.spawnEntity(display);
        }
    }

    private void scoreChoices(PartyRoundContext context) {
        int[] frequency=new int[8];
        for(ServerPlayerEntity player:this.activePlayers(context))frequency[this.chips.get(player.getUuid()).choice]++;
        for(ServerPlayerEntity player:this.activePlayers(context)) {
            Chip chip=this.chips.get(player.getUuid());chip.unique=uniquePoints(chip.choice,frequency[chip.choice])==1;
            if(!chip.unique)continue;
            chip.points++;context.score(chip.owner,chip.points);
            // Native piston push, including actual stacked material and player
            // collision. Never replace this with a TP or an animated display.
            for(BlockPos pos:BlockPos.iterate(chip.center.add(-2,-2,-2),chip.center.add(2,-2,2))) {
                if(this.world.getBlockState(pos).isOf(Blocks.WHITE_WOOL))context.set(pos,Native.BLOCKS[chip.choice-1].getDefaultState());
            }
            this.power(context,chip,true);
            player.sendMessage(Text.literal("§a独选成功！平台升高1格，累计 "+chip.points+"/8 分。"),false);
        }
        context.announce("§7第 "+this.round+"/8 次选材已结算。");
    }

    private void finishChoice(PartyRoundContext context,int elapsed) {
        for(Chip chip:this.chips.values()) {
            if(chip.unique)this.power(context,chip,false);
            chip.choice=0;chip.unique=false;
        }
        this.discardDisplays();this.round++;this.preparation=0;
        if(this.round>=9)this.endingAt=elapsed+40;
        else this.phase=1;
    }

    private void power(PartyRoundContext context,Chip chip,boolean enabled) {
        for(BlockPos pos:BlockPos.iterate(chip.center.add(-3,-4,-3),chip.center.add(3,-4,3)))context.set(pos,enabled?Blocks.REDSTONE_BLOCK.getDefaultState():Blocks.AIR.getDefaultState());
    }

    private ItemStack choiceItem(int material) {
        ItemStack stack=new ItemStack(Native.ITEMS[material]);
        stack.set(DataComponentTypes.CUSTOM_NAME,Text.literal(LABELS[material]).formatted(Formatting.BOLD).styled(style->style.withColor(TEXT_COLORS[material]).withItalic(false)));
        NbtCompound data=new NbtCompound();data.putString(TOKEN_KEY,this.token);data.putBoolean("mmn",true);data.putBoolean(FLAGS[material],true);data.putInt("material",material+1);
        stack.set(DataComponentTypes.CUSTOM_DATA,NbtComponent.of(data));return stack;
    }
    private boolean ownItem(ItemStack stack) {return stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().getString(TOKEN_KEY).equals(this.token);}
    private int material(ItemStack stack) {return this.ownItem(stack)?stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().getInt("material"):0;}
    private List<ServerPlayerEntity> activePlayers(PartyRoundContext context) {return context.players().stream().filter(player->{Chip chip=this.chips.get(player.getUuid());return chip!=null&&!chip.left&&!context.isOut(chip.owner)&&player.getWorld()==this.world;}).toList();}
    private void clearItems(ServerPlayerEntity player) {
        // Source phase2/3 uses unconditional /clear. Native removal also covers
        // 2x2 crafting input, cursor and crafted items that no longer carry our
        // token; restricting this to marked inventory stacks leaves a hiding hole.
        player.getInventory().remove(stack->true,-1,player.playerScreenHandler.getCraftingInput());
        player.currentScreenHandler.sendContentUpdates();
        player.playerScreenHandler.onContentChanged(player.getInventory());
    }
    private void sound(PartyRoundContext context,net.minecraft.sound.SoundEvent sound,float volume,float pitch) {for(ServerPlayerEntity player:context.players())player.playSoundToPlayer(sound,SoundCategory.MASTER,volume,pitch);}
    @Override public boolean ownsEntity(Entity entity) {return this.owned.contains(entity.getUuid())||entity instanceof ItemEntity item&&this.ownItem(item.getStack());}
    @Override public void entityLoaded(PartyRoundContext context,Entity entity) {
        if(entity instanceof ItemEntity item&&this.ownItem(item.getStack())) {
            this.owned.add(item.getUuid());
            if(!item.getCommandTags().contains(DISPLAY_TAG))this.dropped.put(item.getUuid(),item);
        }
    }
    private void discardDrops() {for(ItemEntity item:this.dropped.values()){item.discard();this.owned.remove(item.getUuid());}this.dropped.clear();}
    private void discardDisplays() {for(ItemEntity item:this.displays.values()){item.discard();this.owned.remove(item.getUuid());}this.displays.clear();}
    @Override public void leave(PartyRoundContext context,ServerPlayerEntity player) {
        Chip chip=this.chips.get(player.getUuid());
        if(chip!=null){chip.left=true;if(chip.unique)this.power(context,chip,false);if(chip.frame!=null){chip.frame.discard();this.owned.remove(chip.frame.getUuid());}}
        for(var iterator=this.dropped.values().iterator();iterator.hasNext();) {ItemEntity item=iterator.next();if(item.getOwner()==player){item.discard();this.owned.remove(item.getUuid());iterator.remove();}}
        this.clearItems(player);player.removeStatusEffect(StatusEffects.WEAKNESS);player.removeStatusEffect(StatusEffects.LEVITATION);
    }
    @Override public void finish(PartyRoundContext context) {for(Chip chip:this.chips.values())context.score(chip.owner,chip.points);}
    @Override public void close(PartyRoundContext context) {
        if(this.closed)return;this.closed=true;
        for(Chip chip:this.chips.values()){if(chip.unique)this.power(context,chip,false);if(chip.frame!=null)chip.frame.discard();}
        for(ServerPlayerEntity player:context.players()){this.clearItems(player);player.removeStatusEffect(StatusEffects.WEAKNESS);player.removeStatusEffect(StatusEffects.LEVITATION);}
        this.discardDrops();this.discardDisplays();this.owned.clear();
    }
    @Override public String scoreDescription(PartyRoundContext context,UUID player) {Chip chip=this.chips.get(player);return (chip==null?0:chip.points)+" / 8 次独选";}
    @Override public String status(PartyRoundContext context) {return this.round>=9?"§a8次选材结束": "§e选材 "+this.round+"/8 · "+(this.phase==2?"§f剩余 "+((this.choiceTicks+19)/20)+"s":this.phase==3?"§7揭示结果":"§7准备");}

    private void restoreBanners(PartyRoundContext context) {
        var registry=this.world.getRegistryManager().getWrapperOrThrow(RegistryKeys.BANNER_PATTERN);
        for(JsonElement encoded:this.audit.getAsJsonArray("banners")) {
            JsonObject data=encoded.getAsJsonObject();var p=data.getAsJsonArray("pos");
            BlockPos pos=context.sourceBlock(p.get(0).getAsInt()+350,p.get(1).getAsInt()+12,p.get(2).getAsInt()+885);
            if(this.world.getBlockEntity(pos) instanceof BannerBlockEntity banner) {
                BannerPatternsComponent.Builder patterns=new BannerPatternsComponent.Builder();
                for(JsonElement row:data.getAsJsonArray("patterns")) {
                    JsonObject layer=row.getAsJsonObject();patterns.add(registry.getOrThrow(RegistryKey.of(RegistryKeys.BANNER_PATTERN,Identifier.of(layer.get("pattern").getAsString()))),DyeColor.byName(layer.get("color").getAsString(),DyeColor.WHITE));
                }
                ItemStack stack=banner.getPickStack();stack.set(DataComponentTypes.BANNER_PATTERNS,patterns.build());banner.readFrom(stack,banner.getColorForState());banner.markDirty();
                this.world.updateListeners(pos,banner.getCachedState(),banner.getCachedState(),Block.NOTIFY_LISTENERS);
            }
        }
    }
}
