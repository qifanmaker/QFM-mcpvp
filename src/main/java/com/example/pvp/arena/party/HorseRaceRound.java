package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.SkeletonHorseEntity;
import net.minecraft.entity.mob.ZombieHorseEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
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
import java.io.InputStream;
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
 * 原版 416 Horse Race 的三条下界要塞路线，不能用平面赛马替代多层堡内/堡顶路线。
 * 修改原因：原包用全服选择器和跨维度命令控制马、黄金检查点与回位旗，需要本场原生实现。
 * 自查：保留未压缩的场景、门墙克隆、原生僵尸/骷髅马的 0.23 速度及 0.85 跳力、两圈和计分顺序。
 * 检查点读取玩家脚下而不是马脚下，范围允许的跳点与路线一的两个备用检查点也按源顺序保留。
 * 1.21.1 的未燃烧 Fire 可为负冷却，不能把这个负值当着火；只为实际燃烧回位。
 * 源 horse 队伍选择普通 horse，没有选中实际竞赛的两种亡灵马；保留其真实原生碰撞。
 */
public final class HorseRaceRound implements PartyRound {
    private static final String TOKEN = "pvp_party_horse";
    private static final String OWNER = "pvp_party_horse_owner";
    private static final BlockPos ANCHOR = new BlockPos(56, 45, -1099);
    private static final Formatting[] COLORS = {Formatting.BLUE, Formatting.RED, Formatting.GREEN, Formatting.YELLOW,
            Formatting.DARK_AQUA, Formatting.LIGHT_PURPLE, Formatting.DARK_RED, Formatting.DARK_GREEN,
            Formatting.DARK_PURPLE, Formatting.DARK_BLUE};

    private record Operation(String op, BlockPos from, BlockPos to, BlockPos destination, BlockState state, Block replace) { }
    private record Checkpoint(int target, int minimum, int maximum, int soundMinimum, int soundMaximum, Vec3d center, double radius) { }
    private record SignFace(List<String> messages, DyeColor color, boolean glowing) { }
    private record NavigationSign(BlockPos position, SignFace front, SignFace back, boolean waxed, int ownerSeat) { }
    private record Route(String name, int ticks, int unitsPerLap, List<Operation> operations,
                         List<Checkpoint> checkpoints, Map<Integer, Vec3d> respawns, List<NavigationSign> signs) { }
    private static final class Racer {
        final int seat;
        final Vec3d start;
        AbstractHorseEntity horse;
        int checkpoint;
        int laps;
        int lastCheckpointTick;
        int remountAt;
        boolean completed;
        Racer(int seat, Vec3d start) { this.seat = seat; this.start = start; }
    }

    private final String token = UUID.randomUUID().toString();
    private final Map<UUID, Racer> racers = new LinkedHashMap<>();
    private final Set<UUID> owned = new HashSet<>();
    private final Map<UUID, UUID> horseOwners = new HashMap<>();
    private final Set<UUID> pendingReset = new HashSet<>();
    private final List<BlockPos> gateCells = new ArrayList<>();
    private ServerWorld world;
    private Box itemBounds;
    private Vec3d offset;
    private Entity wrongWay;
    private Route route;
    private int routeNumber;
    private boolean checkpointEvent;
    private boolean closed;

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(ANCHOR);
        this.world = context.world();
        this.offset = context.source(0, 0, 0);
        this.itemBounds = new Box(context.source(-48, 0, -1206), context.source(162, 113, -991));
        JsonObject rules = readRules();
        this.routeNumber = 1 + context.random().nextInt(3);
        this.route = readRoute(rules.getAsJsonObject("routes").getAsJsonObject(Integer.toString(this.routeNumber)));
        context.timeLimit(this.route.ticks());
        // Only gate cells need a mutable read overlay. Retaining a boxed map of
        // the entire natural Nether scene would multiply staging memory usage.
        LongOpenHashSet needed = new LongOpenHashSet();
        for (Operation op : this.route.operations()) {
            BlockPos last = op.to() == null ? op.from() : op.to();
            for (BlockPos point : BlockPos.iterate(op.from(), last)) needed.add(point.asLong());
            if (op.destination() != null) {
                int dx = Math.abs(last.getX() - op.from().getX()), dy = Math.abs(last.getY() - op.from().getY()), dz = Math.abs(last.getZ() - op.from().getZ());
                for (BlockPos point : BlockPos.iterate(op.destination(), op.destination().add(dx, dy, dz))) needed.add(point.asLong());
            }
        }
        Long2ObjectOpenHashMap<BlockState> overlay = new Long2ObjectOpenHashMap<>();
        PartyMapBlueprint.load("horse_race").forEachTranslated(BlockPos.ORIGIN, (source, state) -> {
            context.stage(context.sourceBlock(source.getX(), source.getY(), source.getZ()), state);
            if (needed.contains(source.asLong())) overlay.put(source.asLong(), state);
        });
        Long2ObjectOpenHashMap<BlockState> changes = new Long2ObjectOpenHashMap<>();
        for (Operation op : this.route.operations()) apply(overlay, changes, op);
        changes.forEach((position, state) -> {
            BlockPos source = BlockPos.fromLong(position.longValue());
            BlockPos target = context.sourceBlock(source.getX(), source.getY(), source.getZ());
            context.stage(target, state);
            this.gateCells.add(target);
        });
        JsonArray spawnXs = rules.getAsJsonArray("spawnX");
        List<Double> available = new ArrayList<>();
        for (int i = 0; i < Math.max(4, context.players().size()); i++) available.add(spawnXs.get(i).getAsDouble());
        Collections.shuffle(available, context.random());
        int seat = 0;
        for (ServerPlayerEntity player : context.players()) {
            Vec3d start = context.source(available.get(seat), 35, -1102.5);
            this.racers.put(player.getUuid(), new Racer(seat++, start));
            context.spawn(player.getUuid(), start.add(0, 0, 1.5));
        }
    }

    @Override public void start(PartyRoundContext context) {
        // Vanilla /fill and /setblock post-process fences/walls against their
        // neighbors. Do this after staging so the complete source scene exists.
        for (BlockPos point : this.gateCells) context.set(point, Block.postProcessState(this.world.getBlockState(point), this.world, point));
        this.restoreNavigation(context);
        DisplayEntity.TextDisplayEntity display = new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, this.world);
        Vec3d sign = context.source(53.5, 54, -1094.5);
        display.refreshPositionAndAngles(sign.x, sign.y, sign.z, 0, 0);
        display.setText(Text.literal("WRONG WAY").styled(style -> style.withColor(0xFF0000)));
        display.addCommandTag("party_horse_wrong_way");
        this.wrongWay = display;
        this.owned.add(display.getUuid());
        this.world.spawnEntity(display);
        for (ServerPlayerEntity player : context.alive()) {
            Racer racer = this.racers.get(player.getUuid());
            this.resetPlayer(player);
            player.setYaw(180);
            this.spawnHorse(context, player, racer, racer.start, 180);
            this.giveFlag(player);
            player.startRiding(racer.horse, true);
            player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 10, 0);
            context.score(player.getUuid(), 3);
        }
        this.effects(context);
        context.announce("§a赛马 · " + this.route.name() + "\n§e骑乘自己的马完成两圈；按住空格蓄力跳过障碍。"
                + "\n§6经过金块保存检查点，着火会回位；右键或丢出回位旗可以重建自己的马。");
    }

    @Override public void tick(PartyRoundContext context, int elapsed) {
        if (this.closed) return;
        for (ItemEntity item : this.world.getEntitiesByClass(ItemEntity.class, this.itemBounds, this::ownsEntity)) this.consumeDrop(context, item);
        for (ServerPlayerEntity player : context.alive()) {
            UUID uuid = player.getUuid();
            Racer racer = this.racers.get(uuid);
            if (racer == null || racer.completed) continue;
            if (racer.horse == null || racer.horse.isRemoved() || !racer.horse.isAlive()) {
                this.reset(context, player, racer, elapsed, true);
            }
            if (player.hasVehicle() && player.getVehicle() != racer.horse) player.stopRiding();
            if (elapsed >= racer.remountAt && !player.hasVehicle()) player.startRiding(racer.horse, true);
            if (player.squaredDistanceTo(racer.horse) >= 25) context.teleport(player, racer.horse.getPos());
            // The original finish detector runs before the checkpoint list.
            Vec3d finish = context.source(50.5, 35, -1102);
            boolean gold = this.world.getBlockState(BlockPos.ofFloored(player.getPos().add(0, -1, 0))).isOf(Blocks.GOLD_BLOCK);
            if (gold && racer.checkpoint == this.route.unitsPerLap() - 1 && player.squaredDistanceTo(finish) <= 36) {
                player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 10, 2);
                this.checkpointEvent = true;
                racer.lastCheckpointTick = elapsed;
                racer.laps++;
                racer.checkpoint = 0;
                if (racer.laps >= 2) {
                    racer.completed = true;
                    player.stopRiding();
                    this.removeHorse(racer);
                    this.clearFlag(player);
                    context.completed(uuid);
                    context.eliminate(player, "§a已完成两圈，正在观看本场比赛。");
                    context.announce("§a" + player.getName().getString() + " 已完成赛马！");
                    continue;
                }
                player.sendMessage(Text.literal("§a第一圈完成，继续第二圈！"), false);
            }
            for (Checkpoint cp : this.route.checkpoints()) {
                if (gold && racer.checkpoint >= cp.minimum() && racer.checkpoint <= cp.maximum()
                        && player.squaredDistanceTo(cp.center()) <= cp.radius() * cp.radius()) {
                    if (racer.checkpoint >= cp.soundMinimum() && racer.checkpoint <= cp.soundMaximum()) {
                        player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 10, 2);
                    }
                    this.checkpointEvent = true;
                    racer.lastCheckpointTick = elapsed;
                    racer.checkpoint = cp.target();
                }
            }
            if (racer.horse.getFireTicks() > 0) this.reset(context, player, racer, elapsed, false);
            racer.horse.setFireTicks(0);
            if (this.pendingReset.remove(uuid)) {
                this.clearFlag(player);
                this.reset(context, player, racer, elapsed, true);
                this.giveFlag(player);
            }
            if (elapsed % 20 == 0) player.sendMessage(Text.literal("§e第 " + (racer.laps + 1) + "/2 圈 §7｜检查点 "
                    + racer.checkpoint + "/" + (this.route.unitsPerLap() - 1)), true);
        }
        this.updateScores(context);
        this.effects(context);
        if (context.alive().size() <= 1) context.finish();
    }

    private void effects(PartyRoundContext context) {
        for (ServerPlayerEntity player : context.players()) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 20, 0, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20, 99, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 20, 99, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, 20, 99, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 80, 0, false, false));
        }
    }

    /** Arrow signs are route instructions, so cloning only their block state is insufficient. */
    private void restoreNavigation(PartyRoundContext context) {
        for (NavigationSign source : this.route.signs()) {
            if (!(this.world.getBlockEntity(source.position()) instanceof SignBlockEntity sign)) continue;
            Text[] front = signMessages(source.front()), back = signMessages(source.back());
            if (source.ownerSeat() >= 0) {
                ServerPlayerEntity owner = context.players().stream().filter(player -> {
                    Racer racer = this.racers.get(player.getUuid());
                    return racer != null && racer.seat == source.ownerSeat();
                }).findFirst().orElse(null);
                front[0] = owner == null ? Text.empty() : Text.literal(owner.getName().getString() + "'s Horse")
                        .formatted(COLORS[source.ownerSeat()], Formatting.BOLD);
            }
            // No original click events, selectors or command NBT are parsed.
            sign.setText(new SignText(front, front, source.front().color(), source.front().glowing()), true);
            sign.setText(new SignText(back, back, source.back().color(), source.back().glowing()), false);
            sign.setWaxed(source.waxed());
            sign.markDirty();
            this.world.getChunkManager().markForUpdate(source.position());
        }
    }

    private static Text[] signMessages(SignFace face) {
        Text[] text = new Text[4];
        for (int line = 0; line < text.length; line++) text[line] = Text.literal(face.messages().get(line));
        return text;
    }

    private void spawnHorse(PartyRoundContext context, ServerPlayerEntity player, Racer racer, Vec3d position, float yaw) {
        this.removeHorse(racer);
        AbstractHorseEntity horse = racer.seat % 2 == 0 ? new ZombieHorseEntity(EntityType.ZOMBIE_HORSE, this.world) {
            @Override protected int getXpToDrop() { return 0; }
            @Override protected void dropLoot(DamageSource source, boolean playerCaused) { }
            @Override protected void dropEquipment(ServerWorld world, DamageSource source, boolean playerCaused) { }
            @Override protected void dropInventory() { }
        } : new SkeletonHorseEntity(EntityType.SKELETON_HORSE, this.world) {
            @Override protected int getXpToDrop() { return 0; }
            @Override protected void dropLoot(DamageSource source, boolean playerCaused) { }
            @Override protected void dropEquipment(ServerWorld world, DamageSource source, boolean playerCaused) { }
            @Override protected void dropInventory() { }
        };
        horse.refreshPositionAndAngles(position.x, position.y, position.z, yaw, 0);
        horse.setInvulnerable(true);
        horse.setPersistent();
        horse.setTame(true);
        horse.setOwnerUuid(player.getUuid());
        horse.setBreedingAge(0);
        ItemStack saddle = new ItemStack(Items.SADDLE);
        NbtCompound saddleOwner = new NbtCompound();
        saddleOwner.putString(TOKEN, this.token);
        saddleOwner.putString(OWNER, player.getUuidAsString());
        saddle.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(saddleOwner));
        horse.saddle(saddle, null);
        horse.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(.23);
        horse.getAttributeInstance(EntityAttributes.GENERIC_JUMP_STRENGTH).setBaseValue(.85);
        horse.setCustomName(Text.literal(player.getName().getString() + "'s Horse").formatted(COLORS[racer.seat], Formatting.BOLD));
        horse.setCustomNameVisible(true);
        horse.setFireTicks(0);
        horse.addCommandTag("party_horse");
        this.owned.add(horse.getUuid());
        this.horseOwners.put(horse.getUuid(), player.getUuid());
        racer.horse = horse;
        this.world.spawnEntity(horse);
    }

    private void reset(PartyRoundContext context, ServerPlayerEntity player, Racer racer, int elapsed, boolean rebuild) {
        Vec3d target = this.route.respawns().get(racer.checkpoint);
        player.stopRiding();
        context.teleport(player, target);
        if (rebuild) this.spawnHorse(context, player, racer, target, 0);
        else {
            racer.horse.refreshPositionAndAngles(target.x, target.y, target.z, racer.horse.getYaw(), racer.horse.getPitch());
            racer.horse.setVelocity(Vec3d.ZERO);
            racer.horse.setFireTicks(0);
            racer.horse.fallDistance = 0;
        }
        racer.remountAt = elapsed + (rebuild ? 1 : 5);
    }

    private void removeHorse(Racer racer) {
        if (racer.horse == null) return;
        racer.horse.removeAllPassengers();
        this.horseOwners.remove(racer.horse.getUuid());
        this.owned.remove(racer.horse.getUuid());
        racer.horse.discard();
        racer.horse = null;
    }

    @Override public ActionResult useEntity(PartyRoundContext context, ServerPlayerEntity player, Hand hand, Entity entity) {
        if (hand != Hand.MAIN_HAND || !player.getUuid().equals(this.horseOwners.get(entity.getUuid())) || context.isOut(player.getUuid())) return ActionResult.FAIL;
        if (player.isSneaking()) return ActionResult.PASS; // Preserve the owned horse's native saddle inventory.
        player.startRiding(entity, true);
        return ActionResult.SUCCESS;
    }

    @Override public ActionResult useItem(PartyRoundContext context, ServerPlayerEntity player, Hand hand) {
        if (context.isOut(player.getUuid()) || !this.flag(player.getStackInHand(hand), player.getUuid())) return ActionResult.FAIL;
        this.pendingReset.add(player.getUuid());
        return ActionResult.SUCCESS;
    }

    @Override public ActionResult useBlock(PartyRoundContext context, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        return this.useItem(context, player, hand);
    }

    private void giveFlag(ServerPlayerEntity player) {
        ItemStack stack = new ItemStack(Items.CARROT_ON_A_STICK);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Teleport To Horse (use if stuck)").formatted(Formatting.GREEN));
        stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(9));
        NbtCompound data = new NbtCompound();
        data.putString(TOKEN, this.token);
        data.putString(OWNER, player.getUuidAsString());
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
        player.getInventory().setStack(0, stack);
        player.currentScreenHandler.sendContentUpdates();
    }

    private boolean flag(ItemStack stack, UUID owner) {
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (!stack.isOf(Items.CARROT_ON_A_STICK) || data == null) return false;
        NbtCompound tag = data.copyNbt();
        return this.token.equals(tag.getString(TOKEN)) && owner.toString().equals(tag.getString(OWNER));
    }

    private void clearFlag(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) if (this.flag(player.getInventory().getStack(i), player.getUuid())) player.getInventory().setStack(i, ItemStack.EMPTY);
    }

    @Override public boolean ownsEntity(Entity entity) {
        if (this.closed || entity.getWorld() != this.world) return false;
        if (this.owned.contains(entity.getUuid())) return true;
        if (!(entity instanceof ItemEntity item)) return false;
        NbtComponent data = item.getStack().get(DataComponentTypes.CUSTOM_DATA);
        return data != null && this.token.equals(data.copyNbt().getString(TOKEN));
    }

    @Override public void entityLoaded(PartyRoundContext context, Entity entity) {
        if (entity instanceof ItemEntity item) this.consumeDrop(context, item);
    }

    private void consumeDrop(PartyRoundContext context, ItemEntity item) {
        // Thrower becomes available after ENTITY_LOAD in native dropItem.
        if (!(item.getOwner() instanceof ServerPlayerEntity thrower) || !this.ownsEntity(item)) return;
        if (!context.isOut(thrower.getUuid()) && this.flag(item.getStack(), thrower.getUuid())) this.pendingReset.add(thrower.getUuid());
        item.discard();
    }

    @Override public boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        if (victim instanceof ServerPlayerEntity) return source.getAttacker() == null;
        return this.owned.contains(victim.getUuid()) && source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    @Override public void death(PartyRoundContext context, ServerPlayerEntity player) {
        Racer racer = this.racers.get(player.getUuid());
        if (racer == null || racer.completed) return;
        this.resetPlayer(player);
        this.reset(context, player, racer, context.elapsed(), true);
        this.clearFlag(player);
        this.giveFlag(player);
    }

    private void resetPlayer(ServerPlayerEntity player) {
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.fallDistance = 0;
        player.timeUntilRegen = 0;
        player.hurtTime = 0;
        player.setAttacker(null);
        player.setAttacking(null);
    }

    private void updateScores(PartyRoundContext context) {
        for (var entry : this.racers.entrySet()) {
            Racer racer = entry.getValue();
            int recency = 3;
            if (this.checkpointEvent) {
                recency = 0;
                for (Racer other : this.racers.values()) if (other.lastCheckpointTick > racer.lastCheckpointTick) recency++;
            }
            context.score(entry.getKey(), (racer.laps * this.route.unitsPerLap() + racer.checkpoint) * 10 + recency);
        }
    }

    @Override public void finish(PartyRoundContext context) { this.updateScores(context); }
    @Override public void leave(PartyRoundContext context, ServerPlayerEntity player) {
        Racer racer = this.racers.get(player.getUuid());
        if (racer != null) this.removeHorse(racer);
        this.pendingReset.remove(player.getUuid());
        this.clearPlayer(player);
    }

    private void clearPlayer(ServerPlayerEntity player) {
        player.stopRiding();
        this.clearFlag(player);
        for (var effect : List.of(StatusEffects.FIRE_RESISTANCE, StatusEffects.RESISTANCE, StatusEffects.WEAKNESS, StatusEffects.SATURATION, StatusEffects.NIGHT_VISION)) player.removeStatusEffect(effect);
    }

    @Override public void close(PartyRoundContext context) {
        if (this.closed) return;
        context.players().forEach(this::clearPlayer);
        for (Racer racer : this.racers.values()) this.removeHorse(racer);
        if (this.wrongWay != null) this.wrongWay.discard();
        if (this.world != null && this.itemBounds != null) for (ItemEntity item : this.world.getEntitiesByClass(ItemEntity.class, this.itemBounds, this::ownsEntity)) item.discard();
        this.owned.clear();
        this.horseOwners.clear();
        this.pendingReset.clear();
        this.closed = true;
    }

    @Override public String status(PartyRoundContext context) { return "两圈赛马 · " + this.route.name(); }
    @Override public String scoreDescription(PartyRoundContext context, UUID player) {
        Racer r = this.racers.get(player);
        return r == null ? "0 检查点" : (r.laps * this.route.unitsPerLap() + r.checkpoint) + " 检查点";
    }

    private static JsonObject readRules() throws IOException {
        try (InputStream stream = HorseRaceRound.class.getResourceAsStream("/pvp/party/horse_race_rules.json")) {
            if (stream == null) throw new IOException("Missing Horse Race rules");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("formatVersion").getAsInt() != 1 || root.get("sourceId").getAsInt() != 416) throw new IOException("Invalid Horse Race rules");
            return root;
        } catch (RuntimeException error) { throw new IOException("Invalid Horse Race rules", error); }
    }

    private Route readRoute(JsonObject encoded) throws IOException {
        List<Operation> operations = new ArrayList<>();
        for (JsonElement value : encoded.getAsJsonArray("operations")) {
            JsonObject op = value.getAsJsonObject();
            operations.add(new Operation(op.get("op").getAsString(), blockPos(op.getAsJsonArray("from")),
                    op.has("to") ? blockPos(op.getAsJsonArray("to")) : null,
                    op.has("destination") ? blockPos(op.getAsJsonArray("destination")) : null,
                    op.has("state") ? state(op.get("state").getAsString()) : null,
                    op.has("replace") ? Registries.BLOCK.get(Identifier.of(op.get("replace").getAsString())) : null));
        }
        List<Checkpoint> checkpoints = new ArrayList<>();
        for (JsonElement value : encoded.getAsJsonArray("checkpointsInSourceOrder")) {
            JsonObject cp = value.getAsJsonObject(); JsonArray range = cp.getAsJsonArray("allowed"), sound = cp.getAsJsonArray("soundAllowed");
            Vec3d source = vec(cp.getAsJsonArray("center"));
            checkpoints.add(new Checkpoint(cp.get("target").getAsInt(), range.get(0).getAsInt(), range.get(1).getAsInt(), sound.get(0).getAsInt(), sound.get(1).getAsInt(),
                    source.add(this.offset), cp.get("radius").getAsDouble()));
        }
        Map<Integer, Vec3d> respawns = new HashMap<>();
        encoded.getAsJsonObject("respawns").entrySet().forEach(e -> respawns.put(Integer.parseInt(e.getKey()), vec(e.getValue().getAsJsonArray()).add(this.offset)));
        List<NavigationSign> signs = new ArrayList<>();
        for (JsonElement value : encoded.getAsJsonArray("signs")) {
            JsonObject sign = value.getAsJsonObject();
            signs.add(new NavigationSign(blockPos(sign.getAsJsonArray("position")).add(BlockPos.ofFloored(this.offset)),
                    signFace(sign.getAsJsonObject("front")), signFace(sign.getAsJsonObject("back")),
                    sign.get("waxed").getAsBoolean(), sign.has("ownerSeat") ? sign.get("ownerSeat").getAsInt() : -1));
        }
        return new Route(encoded.get("name").getAsString(), encoded.get("ticks").getAsInt(), encoded.get("unitsPerLap").getAsInt(), List.copyOf(operations), List.copyOf(checkpoints), Map.copyOf(respawns), List.copyOf(signs));
    }

    private static SignFace signFace(JsonObject source) throws IOException {
        List<String> lines = new ArrayList<>();
        for (JsonElement line : source.getAsJsonArray("messages")) lines.add(line.getAsString());
        if (lines.size() != 4) throw new IOException("Invalid Horse Race sign text");
        return new SignFace(List.copyOf(lines), DyeColor.byName(source.get("color").getAsString(), DyeColor.BLACK), source.get("glowing").getAsBoolean());
    }

    private static BlockPos blockPos(JsonArray a) { return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()); }
    private static Vec3d vec(JsonArray a) { return new Vec3d(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()); }

    private static void apply(Long2ObjectOpenHashMap<BlockState> cells, Long2ObjectOpenHashMap<BlockState> changes, Operation op) throws IOException {
        if (op.op().equals("clone")) {
            Long2ObjectOpenHashMap<BlockState> copy = new Long2ObjectOpenHashMap<>();
            BlockPos min = new BlockPos(Math.min(op.from().getX(), op.to().getX()), Math.min(op.from().getY(), op.to().getY()), Math.min(op.from().getZ(), op.to().getZ()));
            for (BlockPos source : BlockPos.iterate(op.from(), op.to())) copy.put(source.subtract(min).add(op.destination()).asLong(), cells.getOrDefault(source.asLong(), Blocks.AIR.getDefaultState()));
            copy.forEach((position, state) -> { cells.put(position.longValue(), state); changes.put(position.longValue(), state); });
        } else if (op.op().equals("fill") || op.op().equals("setblock")) {
            for (BlockPos point : BlockPos.iterate(op.from(), op.to() == null ? op.from() : op.to())) {
                BlockState previous = cells.getOrDefault(point.asLong(), Blocks.AIR.getDefaultState());
                if (op.replace() == null || previous.isOf(op.replace())) { cells.put(point.asLong(), op.state()); changes.put(point.asLong(), op.state()); }
            }
        } else throw new IOException("Unsupported Horse Race operation: " + op.op());
    }

    private static BlockState state(String value) throws IOException {
        int at = value.indexOf('[');
        Identifier id = Identifier.of(at < 0 ? value : value.substring(0, at));
        if (!Registries.BLOCK.containsId(id)) throw new IOException("Unknown Horse Race block " + id);
        BlockState result = Registries.BLOCK.get(id).getDefaultState();
        if (at >= 0) for (String pair : value.substring(at + 1, value.length() - 1).split(",")) {
            String[] fields = pair.split("=", 2); Property<?> key = result.getBlock().getStateManager().getProperty(fields[0]);
            if (fields.length != 2 || key == null) throw new IOException("Invalid Horse Race blockstate");
            result = property(result, key, fields[1]);
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState property(BlockState state, Property<T> key, String value) throws IOException {
        return state.with(key, key.parse(value).orElseThrow(() -> new IOException("Invalid Horse Race state " + value)));
    }
}
