package com.example.pvp.arena.party;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.BlockPredicatesChecker;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.predicate.BlockPredicate;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradedItem;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * 原版411 Tedious Tasks；依据 tedious_tasks{,_start,_tick,_end} 与16个独立座位函数。
 * 修改原因：八项原生任务依赖全服统计、村民交易、真实合成和随机任务池，不能简化成按钮挑战，
 * 也不能把原图的破坏性全服选择器、计分板与 schedule 放入共享大厅。
 * 自查：2~4人、每人八项无重复；保留真实铃声统计/音高/珍珠/树木掉落/交易、240秒和N−1结束；
 * 只恢复本场属性、效果、实体与地形，允许本场玩家互相干扰，不允许跨场交互。
 * 源P1 checkpoint误指P2与不存在的player_regen调用按实际玩家修正；taskplayer奖励原本无效，
 * 不虚构额外分数。原291双人组队变体不在本模块中启用。
 */
public final class TediousTasksRound implements PartyRound {
    private static final BlockPos ANCHOR = new BlockPos(34, -12, -322);
    private static final int TIME_LIMIT = 4800;
    private static final String ITEM_TOKEN = "party_tedious_round";
    private static final double[][] SPAWNS = {{34.5,-12,-340.5},{34.5,-12,-302.5},{53.5,-12,-321.5},{15.5,-12,-321.5}};
    private static final float[] SPAWN_YAW = {0,180,90,-90};
    private static final int[][] BELLS = {{34,-9,-327},{34,-9,-317},{39,-9,-322},{29,-9,-322}};
    private static final int[][] WORKSTATIONS = {{34,-12,-324},{34,-12,-320},{36,-12,-322},{32,-12,-322}};
    private static final int[][] TREES = {{32,-12,-334},{32,-12,-314},{42,-12,-324},{22,-12,-324}};
    private static final int[][] NOTES = {{33,-12,-344},{33,-12,-300},{56,-12,-323},{12,-12,-323}};
    private static final float[] VILLAGER_YAW = {180,-90,-90,90};
    private static final Item[] TRADE_ITEMS = {Items.LAPIS_LAZULI,Items.COPPER_INGOT,Items.IRON_INGOT,
            Items.GOLD_INGOT,Items.DIAMOND,Items.NETHERITE_INGOT,Items.EMERALD,Items.AMETHYST_SHARD,Items.NETHER_STAR};
    private static final int[] TRADE_ORDER = {0,5,2,7,3,1,6,4};
    private static final Item[] WOOD_ITEMS = {Items.WARPED_STEM,Items.WARPED_PLANKS,Items.STICK,Items.WOODEN_AXE};
    private static final Item[] CLEAR_ITEMS = {Items.CRAFTING_TABLE,Items.WOODEN_PICKAXE,Items.WOODEN_SHOVEL,
            Items.WOODEN_HOE,Items.WOODEN_SWORD};
    private static final String[] TASK_TEXT = {"", "击杀另一名玩家", "用末影珍珠到中央平台顶端的金块上",
            "把钟恰好敲响30次", "用剑砍树，真实合成一把木斧", "与村民交易获得下界之星",
            "访问全部四个出生台，再返回自己的出生台", "把三个音符盒都调到最低音", "恢复到20点生命值"};

    private static final class TaskPlayer {
        final UUID uuid;
        final int seat;
        final List<Integer> remaining = new ArrayList<>(List.of(1,2,3,4,5,6,7,8));
        int task;
        int completed;
        int lastCompletion = -1;
        int bellStart;
        int ringCheckAt;
        int visited;
        int kills;
        int respawnAt;
        int diedAt = -1000;
        int protectionUntil;
        int deathHealth;
        boolean takeDamage;
        boolean finished;
        VillagerEntity villager;
        TaskPlayer(UUID uuid, int seat) { this.uuid=uuid; this.seat=seat; }
    }

    private final String token = UUID.randomUUID().toString();
    private final Map<UUID, TaskPlayer> tasks = new LinkedHashMap<>();
    private final Map<UUID, Entity> entities = new LinkedHashMap<>();
    private final Set<UUID> owned = new HashSet<>();
    private final Map<BlockPos, BlockState> noteGoalHorizontal = new HashMap<>();
    private final Map<BlockPos, BlockState> noteGoalVertical = new HashMap<>();
    private final Map<BlockPos, BlockState> tree = new LinkedHashMap<>();
    private final Map<BlockPos, BlockState> notesHorizontal = new LinkedHashMap<>();
    private final Map<BlockPos, BlockState> notesVertical = new LinkedHashMap<>();
    private ServerWorld world;
    private Box bounds;
    private BlockPos translation;
    private int finishers;
    private boolean checkpointUsed;
    private boolean grace;
    private boolean started;
    private boolean closed;

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(ANCHOR);
        context.timeLimit(TIME_LIMIT);
        this.world = context.world();
        this.translation = context.sourceBlock(0,0,0);
        this.bounds = new Box(context.source(7,-18,-349),context.source(62,43,-294));
        PartyMapBlueprint.load("tedious_tasks").forEachTranslated(this.translation,(pos,state) -> {
            // Literal entrance fill converts only the lower source field's wart blocks into static fire.
            boolean fire = state.isOf(Blocks.NETHER_WART_BLOCK) && pos.getY() >= context.sourceBlock(0,-13,0).getY()
                    && pos.getY() <= context.sourceBlock(0,-2,0).getY()
                    && pos.getX() >= context.sourceBlock(12,0,0).getX() && pos.getX() <= context.sourceBlock(56,0,0).getX()
                    && pos.getZ() >= context.sourceBlock(0,0,-344).getZ() && pos.getZ() <= context.sourceBlock(0,0,-300).getZ();
            context.stage(pos,fire ? Blocks.FIRE.getDefaultState() : state);
        });
        readTemplate("tedious_tree",new BlockPos(52,15,-304),this.tree);
        readTemplate("tedious_notes_horizontal",new BlockPos(11,15,-339),this.notesHorizontal);
        readTemplate("tedious_notes_vertical",new BlockPos(11,15,-344),this.notesVertical);
        readTemplate("tedious_goal_horizontal",new BlockPos(12,15,-345),this.noteGoalHorizontal);
        readTemplate("tedious_goal_vertical",new BlockPos(17,15,-345),this.noteGoalVertical);
        for (int seat=0;seat<4;seat++) {
            BlockPos center = context.sourceBlock((int)Math.floor(SPAWNS[seat][0]),-13,(int)Math.floor(SPAWNS[seat][2]));
            BlockState floor = seat < context.players().size()
                    ? new net.minecraft.block.Block[]{Blocks.BLUE_CONCRETE,Blocks.RED_CONCRETE,Blocks.LIME_CONCRETE,Blocks.YELLOW_CONCRETE}[seat].getDefaultState()
                    : Blocks.BLACK_CONCRETE.getDefaultState();
            for (BlockPos pos : BlockPos.iterate(center.add(-1,0,-1),center.add(1,0,1))) context.stage(pos,floor);
            this.clearTaskBlocks(context,seat,context::stage);
        }
        int seat=0;
        for (ServerPlayerEntity player : context.players()) {
            TaskPlayer state=new TaskPlayer(player.getUuid(),seat++);
            this.tasks.put(player.getUuid(),state);
            context.spawn(player.getUuid(),spawn(context,state.seat));
        }
    }

    private static void readTemplate(String name, BlockPos minimum, Map<BlockPos,BlockState> output) throws IOException {
        PartyMapBlueprint data=PartyMapBlueprint.load(name);
        // Clone's default mode includes air, not only the non-air records in the compressed source.
        data.forEachPosition(minimum.multiply(-1),pos -> output.put(pos.toImmutable(),Blocks.AIR.getDefaultState()));
        data.forEachTranslated(minimum.multiply(-1),(pos,state) -> output.put(pos.toImmutable(),state));
    }

    @Override public void start(PartyRoundContext context) {
        this.started=true;
        this.grace=context.initialPlayerCount()==2;
        for (ServerPlayerEntity player : context.players()) {
            this.resetLife(player);
            player.clearStatusEffects();
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION,20,255,false,false));
            TaskPlayer state=this.tasks.get(player.getUuid());
            this.selectTask(context,player,state);
            this.ensureSword(player);
        }
        this.updateScores(context);
        context.announce("§a繁杂任务：每人随机完成八项不同任务，限时240秒。任务写在聊天和动作栏中。"
                +"\n§e可以干扰其他玩家；死亡只重置当前任务。完成者达到人数−1时提前结算。");
    }

    @Override public void tick(PartyRoundContext context,int elapsed) {
        if (!this.started || this.closed) return;
        this.collectDrops(context);
        List<TaskPlayer> completions=new ArrayList<>();
        Map<UUID,Integer> killedThisTick=new HashMap<>();
        for (ServerPlayerEntity player : context.players()) {
            TaskPlayer state=this.tasks.get(player.getUuid());
            if (state==null || state.finished || context.isOut(player.getUuid())) continue;
            if (state.respawnAt>0) {
                if (elapsed>=state.respawnAt) this.respawn(context,player,state);
                else continue;
                if (state.finished || context.isOut(player.getUuid())) continue;
            }
            if (elapsed==state.diedAt+2) player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE,60,255,false,false));
            killedThisTick.put(state.uuid,state.kills);
            this.ensureSword(player);
            if (!player.hasStatusEffect(StatusEffects.REGENERATION)) player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION,-1,1,false,false));
            if (!player.hasStatusEffect(StatusEffects.SATURATION)) player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION,-1,1,false,false));
            if (state.ringCheckAt>0 && elapsed>=state.ringCheckAt) {
                state.ringCheckAt=0;
                if (state.task==3 && this.rings(player,state)==30) completions.add(state);
                else {
                    player.sendMessage(Text.literal("§c钟必须恰好响30次；本项任务重置。"),false);
                    this.death(context,player,this.world.getDamageSources().genericKill()); continue;
                }
            }
            if (!completions.contains(state)) {
                if (state.task==1 && state.kills==1) completions.add(state);
                else if (state.task==2 && this.world.getBlockState(player.getBlockPos().down()).isOf(Blocks.GOLD_BLOCK)) completions.add(state);
                else if (state.task==3 && this.rings(player,state)>=30 && state.ringCheckAt==0) state.ringCheckAt=elapsed+20;
                else if (state.task==4 && player.getInventory().count(Items.WOODEN_AXE)>0) completions.add(state);
                else if (state.task==5 && player.getInventory().count(Items.NETHER_STAR)>0) completions.add(state);
                else if (state.task==6) {
                    int oldVisits=state.visited;
                    // Source checks arrival home BEFORE updating this tick's visits.
                    int others=15^(1<<state.seat);
                    if ((state.visited&others)==others && player.squaredDistanceTo(pedestal(context,state.seat))<=2.25) completions.add(state);
                    for (int seat=0;seat<4;seat++) if (player.squaredDistanceTo(pedestal(context,seat))<=2.25) state.visited|=1<<seat;
                    if (oldVisits!=state.visited) this.world.playSound(null,player.getBlockPos(),SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,SoundCategory.MASTER,1,2);
                } else if (state.task==7 && this.notesMatch(context,state.seat)) completions.add(state);
                else if (state.task==8 && !state.takeDamage && elapsed>state.diedAt && MathHelper.ceil(player.getHealth()+player.getAbsorptionAmount())==20) completions.add(state);
            }
            state.kills=0;
            this.cleanInventory(player,state);
            if (state.task==2) this.setHotbar(player,1,new ItemStack(Items.ENDER_PEARL));
            if (state.task==8 && state.takeDamage && elapsed>state.diedAt) {
                StatusEffects.INSTANT_DAMAGE.value().applyInstantEffect(null,null,player,1,1);
                if (MathHelper.ceil(player.getHealth()+player.getAbsorptionAmount())<=19) state.takeDamage=false;
            }
            if (this.grace && state.task>=2) player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,40,255,false,false));
            if (elapsed%20==0) player.sendMessage(Text.literal("§9任务"+ (state.completed+1)+"/8 §f"+TASK_TEXT[state.task]
                    +(state.task==3 ? " §e("+this.rings(player,state)+"/30)" : "")),true);
        }
        // Freeze same-tick completion timestamps together, rather than letting seat order resolve ties.
        for (TaskPlayer state : completions) {
            ServerPlayerEntity player=this.player(context,state.uuid);
            if (player==null || state.finished || state.respawnAt>0) continue;
            this.completeTask(context,player,state,elapsed);
        }
        for (ServerPlayerEntity player : context.players()) {
            TaskPlayer state=this.tasks.get(player.getUuid());
            if (state!=null && state.task>=2 && state.respawnAt==0 && killedThisTick.getOrDefault(state.uuid,0)>0) {
                StatusEffects.INSTANT_HEALTH.value().applyInstantEffect(null,null,player,0,1);
            }
            if (state!=null && !state.finished) this.cleanInventory(player,state);
        }
        this.updateScores(context);
        int active=(int)context.players().stream().filter(p -> !context.isOut(p.getUuid())).count();
        if (active<=1 || this.finishers>=context.initialPlayerCount()-1) context.finish();
    }

    private void completeTask(PartyRoundContext context,ServerPlayerEntity player,TaskPlayer state,int elapsed) {
        this.clearCurrentTask(context,state);
        state.completed++;
        state.lastCompletion=elapsed;
        this.checkpointUsed=true;
        this.world.playSound(null,player.getBlockPos(),SoundEvents.ENTITY_PLAYER_LEVELUP,SoundCategory.MASTER,1,1);
        player.sendMessage(Text.literal("§a已完成任务 "+state.completed+"/8"),false);
        if (state.completed==8) {
            state.task=-1;
            state.finished=true;
            this.finishers++;
            context.completed(state.uuid);
            context.eliminate(player,"§a已完成全部八项任务，正在观看其他玩家。");
            context.teleport(player,context.source(34.5,-4,-321.5));
            context.announce("§a"+player.getName().getString()+" 已完成全部八项任务！");
            boolean wasGrace=this.grace;
            this.grace=this.grace || (context.initialPlayerCount()==3 && this.finishers>=1)
                    || (context.initialPlayerCount()>=4 && this.finishers>=2);
            if (!wasGrace && this.grace && this.finishers<context.initialPlayerCount()-1) context.announce("§c宽限期开始：只有击杀任务可以造成攻击伤害。");
        } else this.selectTask(context,player,state);
    }

    private void selectTask(PartyRoundContext context,ServerPlayerEntity player,TaskPlayer state) {
        this.teleportHome(context,player,state);
        state.task=state.remaining.remove(context.random().nextInt(state.remaining.size()));
        state.bellStart=this.bellStat(player);
        state.ringCheckAt=0;
        state.visited=0;
        state.kills=0;
        state.takeDamage=state.task==8;
        this.createCurrentTask(context,player,state);
        player.sendMessage(Text.literal("§9任务> §a["+state.task+"] "+TASK_TEXT[state.task]+" §7（已完成"+state.completed+"/8）"),false);
    }

    private void createCurrentTask(PartyRoundContext context,ServerPlayerEntity player,TaskPlayer state) {
        if (state.task==3) context.set(source(context,BELLS[state.seat]),Blocks.BELL.getDefaultState().with(net.minecraft.block.BellBlock.ATTACHMENT,net.minecraft.block.enums.Attachment.CEILING));
        else if (state.task==4) {
            this.copy(context,this.tree,TREES[state.seat]);
            context.set(source(context,WORKSTATIONS[state.seat]),Blocks.CRAFTING_TABLE.getDefaultState());
        } else if (state.task==5) {
            this.spawnVillager(context,state);
            this.setHotbar(player,1,new ItemStack(Items.LAPIS_LAZULI));
        } else if (state.task==7) {
            this.copy(context,state.seat<2 ? this.notesHorizontal : this.notesVertical,NOTES[state.seat]);
            this.teleportHome(context,player,state);
        } else if (state.task==8) player.removeStatusEffect(StatusEffects.REGENERATION);
    }

    private void clearCurrentTask(PartyRoundContext context,TaskPlayer state) {
        if (state.task==3) context.set(source(context,BELLS[state.seat]),Blocks.AIR.getDefaultState());
        else if (state.task==4) {
            this.clearBox(context,source(context,TREES[state.seat]),new BlockPos(4,7,4),context::set);
            context.set(source(context,WORKSTATIONS[state.seat]),Blocks.AIR.getDefaultState());
        } else if (state.task==5) this.discardVillager(state);
        else if (state.task==7) this.clearBox(context,source(context,NOTES[state.seat]),state.seat<2 ? new BlockPos(2,1,0) : new BlockPos(0,1,2),context::set);
        if (state.task==2) { ServerPlayerEntity player=this.player(context,state.uuid); if (player!=null) clearItems(player,Items.ENDER_PEARL); }
    }

    private void clearTaskBlocks(PartyRoundContext context,int seat,BiConsumer<BlockPos,BlockState> writer) {
        writer.accept(source(context,BELLS[seat]),Blocks.AIR.getDefaultState());
        writer.accept(source(context,WORKSTATIONS[seat]),Blocks.AIR.getDefaultState());
        this.clearBox(context,source(context,TREES[seat]),new BlockPos(4,7,4),writer);
        this.clearBox(context,source(context,NOTES[seat]),seat<2 ? new BlockPos(2,1,0) : new BlockPos(0,1,2),writer);
    }

    private void clearBox(PartyRoundContext context,BlockPos start,BlockPos size,BiConsumer<BlockPos,BlockState> writer) {
        for (BlockPos pos : BlockPos.iterate(start,start.add(size))) writer.accept(pos,Blocks.AIR.getDefaultState());
    }

    private void copy(PartyRoundContext context,Map<BlockPos,BlockState> template,int[] destination) {
        BlockPos base=source(context,destination);
        template.forEach((relative,state) -> context.set(base.add(relative),state));
    }

    private boolean notesMatch(PartyRoundContext context,int seat) {
        Map<BlockPos,BlockState> goal=seat<2 ? this.noteGoalHorizontal : this.noteGoalVertical;
        BlockPos base=source(context,NOTES[seat]);
        return goal.entrySet().stream().allMatch(entry -> this.world.getBlockState(base.add(entry.getKey())).equals(entry.getValue()));
    }

    private void spawnVillager(PartyRoundContext context,TaskPlayer state) {
        this.discardVillager(state);
        Vec3d pos=source(context,WORKSTATIONS[state.seat]).toCenterPos().add(0,-.5,0);
        VillagerEntity villager=new VillagerEntity(EntityType.VILLAGER,this.world);
        villager.refreshPositionAndAngles(pos.x,pos.y,pos.z,VILLAGER_YAW[state.seat],0);
        villager.setVillagerData(villager.getVillagerData().withProfession(VillagerProfession.NITWIT));
        villager.setSilent(true);
        villager.setInvulnerable(true);
        villager.setPersistent();
        var speed=villager.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed!=null) speed.setBaseValue(0);
        villager.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS,9999,255,false,false));
        TradeOfferList offers=new TradeOfferList();
        for (int index : TRADE_ORDER) {
            ItemStack result=new ItemStack(TRADE_ITEMS[index+1]);
            if (result.isOf(Items.NETHER_STAR)) result.set(DataComponentTypes.CUSTOM_NAME,Text.literal("§e§lStar"));
            offers.add(new TradeOffer(new TradedItem(TRADE_ITEMS[index]),result,999,1,0));
        }
        villager.setOffers(offers);
        villager.addCommandTag("party_tedious_villager");
        state.villager=villager;
        this.track(villager);
        this.world.spawnEntity(villager);
    }

    private void discardVillager(TaskPlayer state) {
        VillagerEntity villager=state.villager;
        if (villager==null) return;
        if (villager.getCustomer() instanceof ServerPlayerEntity customer) customer.closeHandledScreen();
        villager.setCustomer(null);
        villager.discard();
        this.entities.remove(villager.getUuid());
        this.owned.remove(villager.getUuid());
        state.villager=null;
    }

    @Override public boolean playerCombatEnabled() { return this.started && !this.closed; }
    @Override public boolean allowsNaturalRegeneration() { return false; }
    @Override public boolean allowsUndiscoveredRecipes() { return true; }
    @Override public boolean allowsDamage(PartyRoundContext context,LivingEntity victim,DamageSource source) {
        if (!(victim instanceof ServerPlayerEntity player) || !this.active(context,player)) return false;
        TaskPlayer state=this.tasks.get(player.getUuid());
        if (context.elapsed()<state.protectionUntil && !source.isOf(DamageTypes.FALL) && !source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        return !(source.getAttacker() instanceof ServerPlayerEntity attacker) || this.active(context,attacker);
    }

    @Override public void death(PartyRoundContext context,ServerPlayerEntity player) { this.death(context,player,null); }
    @Override public void death(PartyRoundContext context,ServerPlayerEntity player,DamageSource damage) {
        if (!this.active(context,player)) return;
        TaskPlayer state=this.tasks.get(player.getUuid());
        if (player.getPrimeAdversary() instanceof ServerPlayerEntity killer && killer!=player) {
            TaskPlayer attacker=this.tasks.get(killer.getUuid());
            if (attacker!=null && !attacker.finished) attacker.kills++;
        }
        player.closeHandledScreen();
        // KeepInventory leaves real goal items available for the source's completion-before-death-reset check.
        // Retain the pre-revival health criterion so the artificial full-health reset cannot complete task8.
        state.deathHealth=MathHelper.ceil(player.getHealth()+player.getAbsorptionAmount());
        player.clearStatusEffects();
        this.resetLife(player);
        state.diedAt=context.elapsed();
        state.respawnAt=context.elapsed()+1;
        player.changeGameMode(GameMode.SPECTATOR);
        this.teleportHome(context,player,state);
        // Source enderPearlsVanishOnDeath uses a fresh dead entity; intercepted deaths must explicitly remove its pearls.
        for (Entity entity : List.copyOf(this.entities.values())) if (entity instanceof EnderPearlEntity pearl && pearl.getOwner()==player) pearl.discard();
    }

    private void respawn(PartyRoundContext context,ServerPlayerEntity player,TaskPlayer state) {
        boolean completedBeforeReset=this.completedBeforeDeathReset(context,player,state);
        state.respawnAt=0;
        state.protectionUntil=context.elapsed()+60;
        this.resetLife(player);
        player.changeGameMode(GameMode.ADVENTURE);
        this.teleportHome(context,player,state);
        // All four source tick chains check goals before deaths=1. A craft/star/kill already achieved
        // in the lethal tick must advance, then only the newly selected task is retried on respawn.
        if (completedBeforeReset) {
            this.completeTask(context,player,state,context.elapsed());
            if (state.finished) return;
        }
        state.kills=0;
        state.visited=0;
        state.bellStart=this.bellStat(player);
        if (state.task==2) clearItems(player,Items.ENDER_PEARL);
        if (state.task==4) { clearItems(player,WOOD_ITEMS); this.copy(context,this.tree,TREES[state.seat]); }
        if (state.task==5) { clearItems(player,TRADE_ITEMS); this.setHotbar(player,1,new ItemStack(Items.LAPIS_LAZULI)); }
        if (state.task==7) this.copy(context,state.seat<2 ? this.notesHorizontal : this.notesVertical,NOTES[state.seat]);
        if (state.task==8) state.takeDamage=true;
        player.sendMessage(Text.literal("§9任务重置> §a["+state.task+"] "+TASK_TEXT[state.task]),false);
    }

    private boolean completedBeforeDeathReset(PartyRoundContext context,ServerPlayerEntity player,TaskPlayer state) {
        // The source reassigns spawnpoint to this pedestal and enables immediate respawn. Our
        // intercepted death has already returned home: positional goals observe that revived
        // position, not the location of the lethal hit. The source client's respawn-packet/tick
        // phase can vary; this framework consistently checks the first revived task tick.
        return switch (state.task) {
            case 1 -> state.kills==1;
            case 2 -> this.world.getBlockState(player.getBlockPos().down()).isOf(Blocks.GOLD_BLOCK);
            // A ring count alone is insufficient: retain the original delayed validation deadline.
            case 3 -> state.ringCheckAt>0 && context.elapsed()>=state.ringCheckAt && this.rings(player,state)==30;
            case 4 -> player.getInventory().count(Items.WOODEN_AXE)>0;
            case 5 -> player.getInventory().count(Items.NETHER_STAR)>0;
            case 6 -> (state.visited&(15^(1<<state.seat)))==(15^(1<<state.seat))
                    && player.squaredDistanceTo(pedestal(context,state.seat))<=2.25;
            case 7 -> this.notesMatch(context,state.seat);
            case 8 -> !state.takeDamage && context.elapsed()>state.diedAt && state.deathHealth==20;
            default -> false;
        };
    }

    private void resetLife(ServerPlayerEntity player) {
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.fallDistance=0;
        player.timeUntilRegen=0;
        player.hurtTime=0;
        player.setAttacker(null);
        player.setAttacking(null);
    }

    @Override public ActionResult useItem(PartyRoundContext context,ServerPlayerEntity player,Hand hand) {
        TaskPlayer state=this.tasks.get(player.getUuid());
        return this.active(context,player) && state.task==2 && player.getStackInHand(hand).isOf(Items.ENDER_PEARL) ? ActionResult.PASS : ActionResult.FAIL;
    }

    @Override public ActionResult useBlock(PartyRoundContext context,ServerPlayerEntity player,Hand hand,BlockHitResult hit) {
        if (!this.active(context,player)) return ActionResult.FAIL;
        BlockPos pos=hit.getBlockPos();
        for (int seat=0;seat<4;seat++) {
            if (pos.equals(source(context,BELLS[seat])) && this.world.getBlockState(pos).isOf(Blocks.BELL)) return ActionResult.PASS;
            if (pos.equals(source(context,WORKSTATIONS[seat])) && this.world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) return ActionResult.PASS;
            if (this.inNotes(context,pos,seat) && this.world.getBlockState(pos).isOf(Blocks.NOTE_BLOCK)) return ActionResult.PASS;
        }
        return this.useItem(context,player,hand);
    }

    @Override public ActionResult useEntity(PartyRoundContext context,ServerPlayerEntity player,Hand hand,Entity entity) {
        return this.active(context,player) && entity instanceof VillagerEntity && this.owned.contains(entity.getUuid()) ? ActionResult.PASS : ActionResult.FAIL;
    }

    @Override public ActionResult attackBlock(PartyRoundContext context,ServerPlayerEntity player,BlockPos pos) {
        if (this.canBreak(context,player,pos)) return ActionResult.PASS;
        for (int seat=0;seat<4;seat++) if (this.active(context,player) && this.inNotes(context,pos,seat)) return ActionResult.PASS;
        return ActionResult.FAIL;
    }

    @Override public boolean canBreak(PartyRoundContext context,ServerPlayerEntity player,BlockPos pos) {
        if (!this.active(context,player) || !player.getMainHandStack().isOf(Items.STONE_SWORD) || !this.world.getBlockState(pos).isOf(Blocks.WARPED_STEM)) return false;
        return this.inAnyTree(context,pos.toCenterPos());
    }

    private boolean inNotes(PartyRoundContext context,BlockPos pos,int seat) {
        BlockPos base=source(context,NOTES[seat]);
        return pos.getY()==base.getY()+1 && (seat<2
                ? pos.getZ()==base.getZ() && pos.getX()>=base.getX() && pos.getX()<=base.getX()+2
                : pos.getX()==base.getX() && pos.getZ()>=base.getZ() && pos.getZ()<=base.getZ()+2);
    }

    private boolean inAnyTree(PartyRoundContext context,Vec3d pos) {
        for (TaskPlayer state : this.tasks.values()) if (state.task==4 && !state.finished) {
            BlockPos base=source(context,TREES[state.seat]);
            if (new Box(base.getX(),base.getY(),base.getZ(),base.getX()+5,base.getY()+8,base.getZ()+5).expand(1).contains(pos)) return true;
        }
        return false;
    }

    @Override public boolean ownsEntity(Entity entity) {
        if (this.closed || entity.getWorld()!=this.world) return false;
        if (this.owned.contains(entity.getUuid())) return true;
        if (entity instanceof EnderPearlEntity pearl) return pearl.getOwner() instanceof ServerPlayerEntity owner && this.tasks.containsKey(owner.getUuid());
        if (entity instanceof ItemEntity item && this.bounds!=null && this.bounds.contains(item.getPos())) {
            NbtComponent data=item.getStack().get(DataComponentTypes.CUSTOM_DATA);
            if (data!=null && this.token.equals(data.copyNbt().getString(ITEM_TOKEN))) return true;
            if (item.getOwner() instanceof ServerPlayerEntity owner && this.tasks.containsKey(owner.getUuid())) return true;
            // Native harvest/crafting drops have no thrower. A protected field and nearby owned actor limit their provenance.
            return (isOneOf(item.getStack(),WOOD_ITEMS) || isOneOf(item.getStack(),TRADE_ITEMS))
                    && this.tasks.values().stream().anyMatch(state -> {
                        ServerPlayerEntity player=this.world.getServer().getPlayerManager().getPlayer(state.uuid);
                        return player!=null && player.getWorld()==this.world && !state.finished && player.squaredDistanceTo(item)<25;
                    });
        }
        return entity instanceof ExperienceOrbEntity && this.tasks.values().stream().anyMatch(state -> state.villager!=null
                && state.villager.getCustomer()!=null && entity.squaredDistanceTo(state.villager)<9);
    }

    @Override public void entityLoaded(PartyRoundContext context,Entity entity) {
        if (!this.ownsEntity(entity)) return;
        this.track(entity);
        if (entity instanceof ItemEntity item && item.getStack().isOf(Items.STONE_SWORD)) item.discard();
    }

    private void collectDrops(PartyRoundContext context) {
        Map<UUID,ItemEntity> drops=new LinkedHashMap<>();
        for (ItemEntity item : this.world.getEntitiesByClass(ItemEntity.class,this.bounds,this::ownsEntity)) drops.put(item.getUuid(),item);
        // A participant can throw an already-owned token beyond the field's decorative edge.
        // Keep following its UUID rather than applying an unrelated global item selector.
        for (Entity entity : this.entities.values()) if (entity instanceof ItemEntity item && !item.isRemoved()) drops.put(item.getUuid(),item);
        for (ItemEntity item : drops.values()) {
            this.track(item);
            if (item.getStack().isOf(Items.STONE_SWORD)) item.discard();
            else if (isOneOf(item.getStack(),TRADE_ITEMS) && !item.getStack().isOf(Items.NETHER_STAR)) {
                ServerPlayerEntity nearest=context.alive().stream().filter(player -> {
                    TaskPlayer state=this.tasks.get(player.getUuid());
                    return state!=null && state.task==5 && state.respawnAt==0 && state.diedAt<context.elapsed();
                }).min(java.util.Comparator.comparingDouble(player -> player.squaredDistanceTo(item))).orElse(null);
                if (nearest!=null) nearest.getInventory().offerOrDrop(item.getStack().copyWithCount(1));
                item.discard();
            }
        }
        this.entities.entrySet().removeIf(entry -> {
            if (!entry.getValue().isRemoved()) return false;
            this.owned.remove(entry.getKey()); return true;
        });
    }

    private void track(Entity entity) { this.owned.add(entity.getUuid()); this.entities.put(entity.getUuid(),entity); }

    private void ensureSword(ServerPlayerEntity player) {
        if (player.getInventory().count(Items.STONE_SWORD)>0) return;
        ItemStack sword=new ItemStack(Items.STONE_SWORD);
        sword.set(DataComponentTypes.UNBREAKABLE,new UnbreakableComponent(true));
        sword.set(DataComponentTypes.CAN_BREAK,new BlockPredicatesChecker(List.of(BlockPredicate.Builder.create().blocks(Blocks.WARPED_STEM).build()),true));
        NbtCompound custom=new NbtCompound(); custom.putString(ITEM_TOKEN,this.token);
        sword.set(DataComponentTypes.CUSTOM_DATA,NbtComponent.of(custom));
        player.getInventory().offerOrDrop(sword);
    }

    private void cleanInventory(ServerPlayerEntity player,TaskPlayer state) {
        clearItems(player,CLEAR_ITEMS);
        if (state.task!=2) clearItems(player,Items.ENDER_PEARL);
        if (state.task!=4) clearItems(player,WOOD_ITEMS);
        if (state.task!=5) clearItems(player,TRADE_ITEMS);
    }

    private static boolean isOneOf(ItemStack stack,Item... items) {
        for (Item item : items) if (stack.isOf(item)) return true;
        return false;
    }

    private static void clearItems(ServerPlayerEntity player,Item... items) {
        for (int slot=0;slot<player.getInventory().size();slot++) if (isOneOf(player.getInventory().getStack(slot),items)) player.getInventory().setStack(slot,ItemStack.EMPTY);
    }

    private void setHotbar(ServerPlayerEntity player,int slot,ItemStack stack) {
        if (!ItemStack.areEqual(player.getInventory().getStack(slot),stack)) player.getInventory().setStack(slot,stack);
    }

    private boolean active(PartyRoundContext context,ServerPlayerEntity player) {
        TaskPlayer state=this.tasks.get(player.getUuid());
        return this.started && !this.closed && state!=null && !state.finished && state.respawnAt==0
                && !context.isOut(player.getUuid()) && player.getWorld()==this.world;
    }

    private ServerPlayerEntity player(PartyRoundContext context,UUID uuid) {
        return context.players().stream().filter(player -> player.getUuid().equals(uuid)).findFirst().orElse(null);
    }

    private int bellStat(ServerPlayerEntity player) { return player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.BELL_RING)); }
    private int rings(ServerPlayerEntity player,TaskPlayer state) { return Math.max(0,this.bellStat(player)-state.bellStart); }

    private void teleportHome(PartyRoundContext context,ServerPlayerEntity player,TaskPlayer state) {
        float yaw=state.task==7 ? SPAWN_YAW[state.seat]+180 : SPAWN_YAW[state.seat];
        player.setYaw(yaw); player.setPitch(0);
        context.teleport(player,spawn(context,state.seat));
    }

    private static BlockPos source(PartyRoundContext context,int[] pos) { return context.sourceBlock(pos[0],pos[1],pos[2]); }
    private static Vec3d spawn(PartyRoundContext context,int seat) { return context.source(SPAWNS[seat][0],SPAWNS[seat][1],SPAWNS[seat][2]); }
    private static Vec3d pedestal(PartyRoundContext context,int seat) { return context.source(Math.floor(SPAWNS[seat][0]),SPAWNS[seat][1],Math.floor(SPAWNS[seat][2])); }

    private void updateScores(PartyRoundContext context) {
        for (TaskPlayer state : this.tasks.values()) {
            int recency=this.checkpointUsed ? (int)this.tasks.values().stream().filter(other -> other.lastCompletion>state.lastCompletion).count() : 3;
            context.score(state.uuid,state.completed*4+recency);
        }
    }

    @Override public void leave(PartyRoundContext context,ServerPlayerEntity player) {
        TaskPlayer state=this.tasks.get(player.getUuid());
        if (state!=null) { this.clearCurrentTask(context,state); state.finished=true; }
        this.restore(player);
        for (Entity entity : List.copyOf(this.entities.values())) if (entity instanceof EnderPearlEntity pearl && pearl.getOwner()==player) pearl.discard();
    }

    @Override public void finish(PartyRoundContext context) { this.updateScores(context); }
    @Override public void close(PartyRoundContext context) {
        if (this.closed) return;
        for (TaskPlayer state : this.tasks.values()) this.discardVillager(state);
        for (ServerPlayerEntity player : context.players()) this.restore(player);
        for (Entity entity : List.copyOf(this.entities.values())) entity.discard();
        for (ItemEntity item : this.world.getEntitiesByClass(ItemEntity.class,this.bounds,this::ownsEntity)) item.discard();
        this.entities.clear(); this.owned.clear(); this.closed=true;
    }

    private void restore(ServerPlayerEntity player) {
        player.closeHandledScreen();
        for (var effect : List.of(StatusEffects.REGENERATION,StatusEffects.SATURATION,StatusEffects.WEAKNESS,StatusEffects.RESISTANCE)) player.removeStatusEffect(effect);
        player.setAttacker(null); player.setAttacking(null);
    }

    @Override public String scoreDescription(PartyRoundContext context,UUID player) {
        TaskPlayer state=this.tasks.get(player);
        return state==null ? "0/8 任务" : state.completed+"/8 任务";
    }
    @Override public String status(PartyRoundContext context) { return "八项随机任务 · 已完成 "+this.finishers+"/"+context.initialPlayerCount()+(this.grace ? " · 宽限期" : ""); }
}
