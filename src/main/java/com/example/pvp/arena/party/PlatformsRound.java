package com.example.pvp.arena.party;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
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
 * 原版 414 Platforms：四个起台、五个钻石台，六轮同时选择；同台碰面者不得钻石。
 * 依据 platforms{,_tick,_round_start*,_jump*,_[a-d]_*} 和场景命令方块的随机钻石构造器。
 * 修改原因：原函数依赖全服物品扫描、记分板和带命令的告示牌，直接执行会影响同服其他比赛；
 * 本类用本场玩家/实体重现纸张丢弃、后两轮隐身告示牌选择和原生盔甲架抛物运动。
 * 自查：本游戏只供四人开局；四个源座位独立于外层比赛编号；104/20/40 tick 流程不混为立即计分；
 * 钻石必须原生拾取后才计分，退出者永久失去后续收入，避免原版等待离线玩家导致同服比赛卡死。
 */
public final class PlatformsRound implements PartyRound {
    private static final String TOKEN_TAG = "pvp_party_platforms";
    private static final int[][] STARTS = {{-11, -47}, {-7, -43}, {-3, -47}, {-7, -51}};
    private static final float[] YAWS = {-90, 180, 90, 0};
    private static final int[][] GEM_CENTERS = {{-11, -43}, {-3, -43}, {-3, -51}, {-11, -51}, {-7, -47}};
    private static final int[][] OUTER_GEMS = {{1, 2}, {1, 2, 3}, {2, 3, 4}, {2, 3, 4, 5}, {3, 4, 5}, {4, 5}};
    private static final int[][] MIDDLE_GEMS = {{3, 4}, {4, 5, 6}, {5, 6, 7}, {6, 7, 8}, {7, 8, 9}, {8, 9}};
    private static final double[][][] VELOCITIES = {
            {{0, 1, -.6}, {.6, 1, 0}, {0, 1, .6}},
            {{-.6, 1, 0}, {0, 1, -.6}, {.6, 1, 0}},
            {{0, 1, .6}, {-.6, 1, 0}, {0, 1, -.6}},
            {{.6, 1, 0}, {0, 1, .6}, {-.6, 1, 0}}
    };
    private static final int[][][] SIGNS = {
            {{-11, -48}, {-10, -47}, {-11, -46}},
            {{-8, -43}, {-7, -44}, {-6, -43}},
            {{-3, -46}, {-4, -47}, {-3, -48}},
            {{-6, -51}, {-7, -50}, {-8, -51}}
    };
    private static final String[] CHOICES = {"向左跳", "向中间跳", "向右跳"};
    private enum Phase { PREPARE, GIVE_PAPERS, CHOOSE, COUNTDOWN, FLIGHT, COLLECT, DISPLAY, GAP, FINISHED }
    private record SignChoice(int spot, int choice) { }

    private final PartyRoundPlayerState saved = new PartyRoundPlayerState();
    private final String token = UUID.randomUUID().toString();
    private final Map<UUID, Integer> gems = new LinkedHashMap<>();
    private final Map<UUID, Integer> spots = new HashMap<>();
    private final Map<UUID, Integer> choices = new HashMap<>();
    private final Map<UUID, ArmorStandEntity> markers = new HashMap<>();
    private final Map<UUID, Entity> entities = new HashMap<>();
    private final Map<BlockPos, SignChoice> signs = new HashMap<>();
    private final Set<UUID> departed = new HashSet<>();
    private ServerWorld world;
    private Box bounds;
    private Vec3d diamondCenter;
    private PartyMapBlueprint hidden;
    private Phase phase = Phase.PREPARE;
    private int round;
    private int nextAt;
    private boolean started;

    @Override
    public void stage(PartyRoundContext context) throws IOException {
        if (context.players().size() != 4) throw new IllegalStateException("Platforms requires exactly four starting players");
        context.sourceAnchor(new BlockPos(-7, 45, -47));
        context.geometry("platforms");
        this.hidden = PartyMapBlueprint.load("platforms_hidden");
        this.world = context.world();
        this.bounds = new Box(context.source(-19, 38, -61), context.source(6, 60, -33));
        this.diamondCenter = context.source(-6.5, 45, -46.5);
        context.timeLimit(0);
        // 原 source 的空心准备笼；PLAY 开始后按 platforms_start 完整移除。
        for (BlockPos pos : BlockPos.iterate(-9, 48, -49, -5, 52, -45)) {
            boolean shell = pos.getX() == -9 || pos.getX() == -5 || pos.getY() == 48 || pos.getY() == 52
                    || pos.getZ() == -49 || pos.getZ() == -45;
            context.stage(context.sourceBlock(pos.getX(), pos.getY(), pos.getZ()),
                    (shell ? Blocks.GLASS : Blocks.AIR).getDefaultState());
        }
        for (ServerPlayerEntity player : context.players()) {
            this.gems.put(player.getUuid(), 0);
            context.spawn(player.getUuid(), context.source(-6.5, 49, -46.5));
        }
    }

    @Override
    public void start(PartyRoundContext context) {
        this.started = true;
        this.fill(context, -9, 48, -49, -5, 52, -45, Blocks.AIR.getDefaultState());
        context.announce("§b钻石跳台：前四轮丢出对应纸张选择方向；后两轮隐身，右键告示牌选择。"
                + "\n§e所有人选择后同时跳跃，同台相撞者没有钻石。共六轮，钻石最多者获胜。");
        this.beginRound(context);
    }

    private List<ServerPlayerEntity> participants(PartyRoundContext context) {
        return context.alive().stream().filter(player -> this.gems.containsKey(player.getUuid())
                && !this.departed.contains(player.getUuid())).toList();
    }

    private void beginRound(PartyRoundContext context) {
        this.discardEntities();
        this.choices.clear();
        this.spots.clear();
        this.signs.clear();
        this.round++;
        this.barriers(context, true);
        this.phase = Phase.PREPARE;
        this.nextAt = context.elapsed() + 5;
    }

    private void prepareChoices(PartyRoundContext context) {
        List<Integer> available = new ArrayList<>(List.of(0, 1, 2, 3));
        Collections.shuffle(available, context.random());
        int index = 0;
        for (ServerPlayerEntity player : this.participants(context)) {
            UUID uuid = player.getUuid();
            int spot = available.get(index++);
            this.spots.put(uuid, spot);
            Vec3d point = this.start(context, spot);
            ArmorStandEntity marker = new ArmorStandEntity(this.world, point.x, point.y, point.z);
            marker.setInvisible(true);
            marker.setInvulnerable(true);
            marker.setYaw(YAWS[spot]);
            var scale = marker.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
            if (scale != null) scale.setBaseValue(.5);
            this.entities.put(marker.getUuid(), marker);
            this.markers.put(uuid, marker);
            this.world.spawnEntity(marker);
            player.getInventory().clear();
            player.setYaw(YAWS[spot]);
            context.teleport(player, point);
            if (this.round >= 5) this.saved.invisible(player);
        }
        this.spawnGems(context);
        context.title("§b钻石跳台", "§f第 " + this.round + "/6 轮" + (this.round >= 5 ? " · 隐身选择" : ""));
        if (this.round >= 5) {
            // clone 包含空气，只在原函数执行 clone 的后两轮覆盖这一整层；前四轮保留外圈屏障。
            this.fill(context, -13, 46, -53, -1, 46, -41, Blocks.AIR.getDefaultState());
            this.hidden.forEachTranslated(context.sourceBlock(0, 10, -29), context::set);
            this.setupSigns(context);
            this.phase = Phase.CHOOSE;
        } else {
            this.phase = Phase.GIVE_PAPERS;
            this.nextAt = context.elapsed() + 1;
        }
    }

    private void spawnGems(PartyRoundContext context) {
        for (int platform = 0; platform < GEM_CENTERS.length; platform++) {
            int[] counts = platform == 4 ? MIDDLE_GEMS[this.round - 1] : OUTER_GEMS[this.round - 1];
            int count = counts[context.random().nextInt(counts.length)];
            List<int[]> positions = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) positions.add(new int[] {dx, dz});
            Collections.shuffle(positions, context.random());
            for (int i = 0; i < count; i++) {
                int[] offset = positions.get(i);
                Vec3d point = context.source(GEM_CENTERS[platform][0] + .5 + offset[0], 45,
                        GEM_CENTERS[platform][1] + .5 + offset[1]);
                ItemStack diamond = new ItemStack(Items.DIAMOND);
                diamond.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§bDiamond"));
                ItemEntity item = new ItemEntity(this.world, point.x, point.y, point.z, diamond, 0, 0, 0);
                item.setPickupDelayInfinite();
                item.setNeverDespawn();
                this.entities.put(item.getUuid(), item);
                this.world.spawnEntity(item);
            }
        }
    }

    private void givePapers(ServerPlayerEntity player) {
        for (int choice = 0; choice < 3; choice++) {
            ItemStack paper = new ItemStack(Items.PAPER);
            paper.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§d" + CHOICES[choice] + " §7[Q 丢出选择]"));
            NbtCompound data = new NbtCompound();
            data.putString(TOKEN_TAG, this.token);
            data.putString("player", player.getUuidAsString());
            data.putInt("round", this.round);
            data.putInt("choice", choice);
            paper.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
            player.getInventory().setStack(choice, paper);
        }
        player.currentScreenHandler.sendContentUpdates();
    }

    @Override
    public void tick(PartyRoundContext context, int elapsed) {
        if (!this.started || this.phase == Phase.FINISHED) return;
        // Thrower 有时在 ENTITY_LOAD 回调后才赋值，因此只扫描本场边界内的带令牌纸张兜底。
        if (this.phase == Phase.CHOOSE && this.round <= 4) {
            for (ItemEntity item : this.world.getEntitiesByClass(ItemEntity.class, this.bounds, this::ownsEntity)) {
                this.consumePaper(context, item);
            }
        }
        if (this.phase == Phase.PREPARE && elapsed >= this.nextAt) this.prepareChoices(context);
        else if (this.phase == Phase.GIVE_PAPERS && elapsed >= this.nextAt) {
            this.participants(context).forEach(this::givePapers);
            this.phase = Phase.CHOOSE;
        } else if (this.phase == Phase.CHOOSE && this.participants(context).stream().allMatch(p -> this.choices.containsKey(p.getUuid()))) {
            this.phase = Phase.COUNTDOWN;
            this.nextAt = elapsed + 20;
        } else if (this.phase == Phase.COUNTDOWN && elapsed >= this.nextAt) this.launch(context);
        else if (this.phase == Phase.FLIGHT) {
            this.followMarkers(context);
            if (elapsed >= this.nextAt) this.resolveLanding(context);
        } else if (this.phase == Phase.COLLECT && elapsed >= this.nextAt) this.tally(context);
        else if (this.phase == Phase.DISPLAY && elapsed >= this.nextAt) {
            this.participants(context).forEach(player -> removeItem(player, Items.DIAMOND));
            this.discardEntities();
            if (this.round >= 6) {
                this.phase = Phase.FINISHED;
                context.finish();
            } else {
                if (this.round == 4) context.announce("§d最后两轮选择时玩家隐身；右键自己身边的方向告示牌选择。");
                if (this.round >= 4) this.participants(context).forEach(this.saved::invisible);
                this.phase = Phase.GAP;
                this.nextAt = elapsed + 1;
            }
        } else if (this.phase == Phase.GAP && elapsed >= this.nextAt) this.beginRound(context);
    }

    private void consumePaper(PartyRoundContext context, ItemEntity item) {
        if (this.phase != Phase.CHOOSE || this.round > 4 || !(item.getOwner() instanceof ServerPlayerEntity player)) return;
        NbtCompound data = item.getStack().getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        if (!this.token.equals(data.getString(TOKEN_TAG)) || data.getInt("round") != this.round
                || !player.getUuidAsString().equals(data.getString("player"))) return;
        this.select(context, player, data.getInt("choice"));
        item.discard();
    }

    private void select(PartyRoundContext context, ServerPlayerEntity player, int choice) {
        UUID uuid = player.getUuid();
        if (this.phase != Phase.CHOOSE || !this.spots.containsKey(uuid) || context.isOut(uuid)
                || this.departed.contains(uuid) || this.choices.containsKey(uuid) || choice < 0 || choice > 2) return;
        this.choices.put(uuid, choice);
        removeItem(player, Items.PAPER);
        int spot = this.spots.get(uuid);
        int[] point = STARTS[spot];
        this.fill(context, point[0] - 1, 46, point[1] - 1, point[0] + 1, 46, point[1] + 1, Blocks.AIR.getDefaultState());
        this.participants(context).forEach(p -> p.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_BOTTLE_THROW,
                SoundCategory.MASTER, 1, 1.5f));
        player.sendMessage(Text.literal("§a已选择" + CHOICES[choice] + "，等待其他玩家。"), true);
    }

    private void launch(PartyRoundContext context) {
        // 原 launch 不只移除四条隔墙，也移除后两轮告示牌层的所有 barrier。
        for (BlockPos point : BlockPos.iterate(-12, 45, -52, -2, 47, -42)) {
            BlockPos pos = context.sourceBlock(point.getX(), point.getY(), point.getZ());
            if (this.world.getBlockState(pos).isOf(Blocks.BARRIER)) context.set(pos, Blocks.AIR.getDefaultState());
        }
        for (ServerPlayerEntity player : this.participants(context)) {
            player.removeStatusEffect(StatusEffects.INVISIBILITY);
            ArmorStandEntity marker = this.markers.get(player.getUuid());
            Integer choice = this.choices.get(player.getUuid());
            if (marker == null || choice == null) continue;
            double[] motion = VELOCITIES[this.spots.get(player.getUuid())][choice];
            marker.setVelocity(motion[0], motion[1], motion[2]);
            marker.velocityModified = true;
            player.playSoundToPlayer(SoundEvents.BLOCK_BELL_USE, SoundCategory.MASTER, 1, .5f);
        }
        this.phase = Phase.FLIGHT;
        this.nextAt = context.elapsed() + 104;
    }

    private void followMarkers(PartyRoundContext context) {
        for (ServerPlayerEntity player : this.participants(context)) {
            ArmorStandEntity marker = this.markers.get(player.getUuid());
            if (marker == null || marker.isRemoved()) continue;
            boolean landed = this.world.getBlockState(marker.getBlockPos().down()).isOf(Blocks.CHISELED_QUARTZ_BLOCK);
            if (!landed || player.squaredDistanceTo(marker) >= .01) context.teleport(player, marker.getPos());
        }
    }

    private void resolveLanding(PartyRoundContext context) {
        List<ServerPlayerEntity> players = this.participants(context);
        Set<UUID> knocked = new HashSet<>();
        for (ServerPlayerEntity player : players) for (ServerPlayerEntity other : players) {
            double distance = player.squaredDistanceTo(other);
            if (player != other && distance >= .000001 && distance <= 4) {
                knocked.add(player.getUuid());
                knocked.add(other.getUuid());
            }
        }
        this.markers.values().forEach(Entity::discard);
        this.markers.clear();
        for (ServerPlayerEntity player : players) {
            if (knocked.contains(player.getUuid())) {
                context.teleport(player, this.start(context, this.spots.get(player.getUuid())));
                player.sendMessage(Text.literal("§c有人跳到同一平台，本轮都没有钻石。"), false);
                player.playSoundToPlayer(SoundEvents.BLOCK_ANVIL_DESTROY, SoundCategory.MASTER, 1, .5f);
            } else {
                player.sendMessage(Text.literal("§a你赢得了这个平台的钻石！快拾取。"), false);
                player.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_FLUTE.value(), SoundCategory.MASTER, 1, .8f);
            }
        }
        this.barriers(context, true);
        // 原 jump2 按场地球形范围查询所有钻石，玩家丢出的钻石也在同一拾取规则内。
        this.nearbyDiamonds().forEach(ItemEntity::resetPickupDelay);
        this.phase = Phase.COLLECT;
        this.nextAt = context.elapsed() + 20;
    }

    private void tally(PartyRoundContext context) {
        for (ServerPlayerEntity player : this.participants(context)) {
            int amount = 0;
            boolean[] counts = new boolean[10];
            // 原 platforms_jump3 对完整 Inventory（包含副手）逐个查询 count=1..9 是否存在。
            // 两叠相同数量只命中同一次查询；保留原行为，不把手动分堆改成另一套总数规则。
            for (int slot = 0; slot < player.getInventory().size(); slot++) {
                ItemStack stack = player.getInventory().getStack(slot);
                if (stack.isOf(Items.DIAMOND) && stack.getCount() >= 1 && stack.getCount() <= 9) counts[stack.getCount()] = true;
            }
            for (int count = 1; count <= 9; count++) if (counts[count]) amount += count;
            this.gems.merge(player.getUuid(), amount, Integer::sum);
            context.score(player.getUuid(), this.gems.get(player.getUuid()) * 2);
            player.sendMessage(Text.literal("§b本轮拾取 " + amount + " 颗钻石 · 累计 " + this.gems.get(player.getUuid()) + " 颗"), false);
        }
        this.phase = Phase.DISPLAY;
        this.nextAt = context.elapsed() + 40;
    }

    private void setupSigns(PartyRoundContext context) {
        for (int spot = 0; spot < 4; spot++) for (int choice = 0; choice < 3; choice++) {
            int[] point = SIGNS[spot][choice];
            BlockPos pos = context.sourceBlock(point[0], 46, point[1]);
            this.signs.put(pos, new SignChoice(spot, choice));
            if (this.world.getBlockEntity(pos) instanceof SignBlockEntity sign) {
                SignText text = new SignText().withMessage(1, Text.literal("§5" + CHOICES[choice]));
                sign.setText(text, true);
                sign.setText(text, false);
                sign.setWaxed(true);
                sign.markDirty();
                this.world.updateListeners(pos, sign.getCachedState(), sign.getCachedState(), 3);
            }
        }
    }

    @Override
    public ActionResult useBlock(PartyRoundContext context, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        SignChoice sign = this.signs.get(hit.getBlockPos());
        if (hand == Hand.MAIN_HAND && this.round >= 5 && sign != null && this.phase == Phase.CHOOSE
                && this.spots.getOrDefault(player.getUuid(), -1) == sign.spot()
                && player.getPos().squaredDistanceTo(hit.getBlockPos().toCenterPos()) <= 25) {
            this.select(context, player, sign.choice());
            return ActionResult.SUCCESS;
        }
        return ActionResult.FAIL;
    }

    @Override
    public boolean ownsEntity(Entity entity) {
        if (this.entities.containsKey(entity.getUuid())) return true;
        if (!this.started || this.phase == Phase.FINISHED || !(entity instanceof ItemEntity item)
                || entity.getWorld() != this.world || !this.bounds.contains(entity.getPos()) || !item.getStack().isOf(Items.PAPER)) return false;
        NbtComponent component = item.getStack().get(DataComponentTypes.CUSTOM_DATA);
        return component != null && this.token.equals(component.copyNbt().getString(TOKEN_TAG));
    }

    @Override
    public void entityLoaded(PartyRoundContext context, Entity entity) {
        if (entity instanceof ItemEntity item && item.getStack().isOf(Items.PAPER) && this.ownsEntity(item)) {
            this.entities.put(item.getUuid(), item);
            this.consumePaper(context, item);
        }
    }

    @Override
    public void death(PartyRoundContext context, ServerPlayerEntity player) {
        if (this.departed.contains(player.getUuid()) || !this.spots.containsKey(player.getUuid())) return;
        this.saved.resetHealth(player);
        ArmorStandEntity marker = this.markers.get(player.getUuid());
        context.teleport(player, marker != null && !marker.isRemoved() ? marker.getPos() : this.start(context, this.spots.get(player.getUuid())));
    }

    @Override
    public void leave(PartyRoundContext context, ServerPlayerEntity player) {
        this.departed.add(player.getUuid());
        this.saved.restore(player);
        ArmorStandEntity marker = this.markers.remove(player.getUuid());
        if (marker != null) marker.discard();
    }

    @Override
    public void finish(PartyRoundContext context) {
        this.phase = Phase.FINISHED;
        this.gems.forEach((uuid, amount) -> context.score(uuid, amount * 2));
    }

    @Override
    public void close(PartyRoundContext context) {
        this.phase = Phase.FINISHED;
        this.discardEntities();
        context.players().forEach(this.saved::restore);
    }

    @Override
    public String scoreDescription(PartyRoundContext context, UUID player) { return this.gems.getOrDefault(player, 0) + " 颗钻石"; }

    @Override
    public String status(PartyRoundContext context) {
        int chosen = (int) this.participants(context).stream().filter(p -> this.choices.containsKey(p.getUuid())).count();
        return "第 " + this.round + "/6 轮" + (this.phase == Phase.CHOOSE ? " · 已选 " + chosen + "/" + this.participants(context).size() : "");
    }

    private Vec3d start(PartyRoundContext context, int spot) { return context.source(STARTS[spot][0] + .5, 45, STARTS[spot][1] + .5); }

    private void discardEntities() {
        this.entities.values().forEach(Entity::discard);
        this.entities.clear();
        this.markers.clear();
        // 原 jump4 同时清掉玩家在结算后丢出的钻石，防止下一轮捡回后重复计分。
        this.nearbyDiamonds().forEach(Entity::discard);
    }

    private List<ItemEntity> nearbyDiamonds() {
        if (this.world == null || this.diamondCenter == null) return List.of();
        return this.world.getEntitiesByClass(ItemEntity.class, new Box(this.diamondCenter, this.diamondCenter).expand(11),
                item -> item.getStack().isOf(Items.DIAMOND) && item.squaredDistanceTo(this.diamondCenter) <= 121);
    }

    private static void removeItem(ServerPlayerEntity player, net.minecraft.item.Item item) {
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            if (player.getInventory().getStack(slot).isOf(item)) player.getInventory().setStack(slot, ItemStack.EMPTY);
        }
        player.currentScreenHandler.sendContentUpdates();
    }

    private void barriers(PartyRoundContext context, boolean enabled) {
        this.fillBarriers(context, (enabled ? Blocks.BARRIER : Blocks.AIR).getDefaultState(), false);
    }

    private void fillBarriers(PartyRoundContext context, BlockState state, boolean barriersOnly) {
        int[][] walls = {{-12, 45, -45, -2, 47, -45}, {-12, 45, -49, -2, 47, -49},
                {-5, 45, -52, -5, 47, -42}, {-9, 45, -52, -9, 47, -42}};
        for (int[] box : walls) for (BlockPos point : BlockPos.iterate(box[0], box[1], box[2], box[3], box[4], box[5])) {
            BlockPos pos = context.sourceBlock(point.getX(), point.getY(), point.getZ());
            if (!barriersOnly || this.world.getBlockState(pos).isOf(Blocks.BARRIER)) context.set(pos, state);
        }
    }

    private void fill(PartyRoundContext context, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        for (BlockPos point : BlockPos.iterate(x1, y1, z1, x2, y2, z2)) {
            context.set(context.sourceBlock(point.getX(), point.getY(), point.getZ()), state);
        }
    }
}
