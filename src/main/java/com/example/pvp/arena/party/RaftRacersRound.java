package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
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
 * 原版 405 Raft Racers：现行入口在末地生成四条冰道，并非旧 overworld 的六条赛道。
 * 原地图长 383 格，故保留完整场景和原坐标、原生竹筏物理及气泡柱，不能复用另一个 Boat Race 模式。
 * 自查：只解释经过审计的 fill/clone/箭头数据；检查点按玩家脚下/上方原偏移、顺序和两圈判定；
 * 分数保留整圈×人数+过圈先后，不把中途 waypoint 计分。原图的全服实体/物品选择器收敛至本场 UUID。
 * 1.21.1 的竹筏由第一位乘客控制，因此玩家先坐、原隐形兔子后坐；仍使用客户端原生划桨和移动包。
 */
public final class RaftRacersRound implements PartyRound {
    private static final int DURATION = 3600;
    private static final int RECALL_TICKS = 200;
    private static final String RECALL_NAME = "Teleport Boat To You";
    private static final String OWNER_KEY = "party_raft_owner";
    private static final BlockPos ANCHOR = new BlockPos(850008, 64, -56);
    private static final BlockPos MINIMUM = new BlockPos(849852, 47, -247);
    private static final BlockPos MAXIMUM = new BlockPos(850164, 117, 135);

    private record Operation(String op, BlockPos from, BlockPos to, BlockPos destination,
                             BlockState state, Block replace, boolean hollow, Vec3d arrow, float yaw) { }
    private record Rules(Map<Integer, List<Operation>> routes, List<Operation> start) { }
    private static final class Racer {
        Vec3d safe;
        float safeYaw = 180;
        int waypoint;
        int laps;
        int lastLapTick;
        int recallReadyAt;
        BoatEntity raft;
        RabbitEntity rabbit;
        Racer(Vec3d start) { this.safe = start; }
    }

    private final Map<UUID, Racer> racers = new LinkedHashMap<>();
    private final Map<UUID, UUID> boatOwners = new HashMap<>();
    private final Set<UUID> owned = new HashSet<>();
    private final Set<UUID> pendingRecall = new HashSet<>();
    private final List<Entity> entities = new ArrayList<>();
    private final List<Operation> arrows = new ArrayList<>();
    private List<Operation> startOperations = List.of();
    private net.minecraft.server.world.ServerWorld world;
    private Box bounds;
    private int route;
    private Team collisionTeam;
    private boolean closed;

    @Override
    public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(ANCHOR);
        context.timeLimit(DURATION);
        this.world = context.world();
        this.bounds = new Box(context.source(MINIMUM.getX(), 40, MINIMUM.getZ()),
                context.source(MAXIMUM.getX() + 1, MAXIMUM.getY() + 20, MAXIMUM.getZ() + 1));
        Rules rules = loadRules();
        this.route = 11 + context.random().nextInt(4);
        this.startOperations = rules.start();
        Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        PartyMapBlueprint.load("raft_racers").forEachTranslated(BlockPos.ORIGIN,
                (position, state) -> blocks.put(position.asLong(), state));
        for (Operation operation : rules.routes().get(this.route)) {
            if (operation.op().equals("arrow")) this.arrows.add(operation);
            else apply(blocks, operation);
        }
        for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
            BlockPos source = BlockPos.fromLong(entry.getLongKey());
            context.stage(context.sourceBlock(source.getX(), source.getY(), source.getZ()), entry.getValue());
        }
        // player_line: same alternating one-block positions, half-block offset for even player counts.
        int count = context.players().size();
        int slot = 0;
        for (ServerPlayerEntity player : context.players()) {
            int offset = slot == 0 ? 0 : ((slot + 1) / 2) * (slot % 2 == 1 ? 1 : -1);
            Vec3d spawn = context.source(850000.5 + offset - (count % 2 == 0 ? 0.5 : 0), 64, 1);
            this.racers.put(player.getUuid(), new Racer(spawn));
            context.spawn(player.getUuid(), spawn);
            slot++;
        }
    }

    @Override
    public void start(PartyRoundContext context) {
        // Source rr boats/rabbits join no_collision. Scope the native client collision rule
        // to this round's entity UUIDs, without changing participant or other match teams.
        var scoreboard = this.world.getServer().getScoreboard();
        String teamName;
        do { teamName = "p2r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
        while (scoreboard.getTeam(teamName) != null);
        this.collisionTeam = scoreboard.addTeam(teamName);
        this.collisionTeam.setCollisionRule(AbstractTeam.CollisionRule.NEVER);
        for (Operation operation : this.startOperations) {
            if (!operation.op().equals("fill")) throw new IllegalStateException("Unexpected Raft Racers start operation");
            for (BlockPos position : BlockPos.iterate(operation.from(), operation.to())) {
                context.set(context.sourceBlock(position.getX(), position.getY(), position.getZ()), operation.state());
            }
        }
        for (Operation arrow : this.arrows) this.spawnArrow(context, arrow);
        for (ServerPlayerEntity player : context.players()) {
            Racer racer = this.racers.get(player.getUuid());
            player.setHealth(player.getMaxHealth());
            player.setFireTicks(0);
            player.fallDistance = 0;
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, -1, 99, true, false));
            this.newRaft(context, player, racer, racer.safe, 180);
            this.giveRecall(player);
        }
        context.announce("§e竹筏竞速：驾驶竹筏沿冰道通过三个路标，先完成两圈者领先。"
                + "\n§7气泡柱会弹起竹筏；卡住时下船，右键或丢弃召回棒，冷却 10 秒。限时 180 秒。");
    }

    @Override
    public void tick(PartyRoundContext context, int elapsed) {
        // Thrower assignment follows ENTITY_LOAD in vanilla; poll again by actual thrower rather than proximity.
        for (ItemEntity item : context.world().getEntitiesByClass(ItemEntity.class, this.bounds.expand(16), this::ownsEntity)) {
            this.consumeDrop(item);
        }
        for (ServerPlayerEntity player : context.alive()) {
            UUID uuid = player.getUuid();
            Racer racer = this.racers.get(uuid);
            if (racer == null) continue;
            if (racer.raft == null || racer.raft.isRemoved() || racer.raft.getY() < context.source(0, 44, 0).y) {
                this.newRaft(context, player, racer, racer.safe.add(0, 1, 0), racer.safeYaw);
            }
            // The source marker follows the player's position and yaw only over packed ice.
            if (context.world().getBlockState(BlockPos.ofFloored(player.getPos().add(0, -0.5, 0))).isOf(Blocks.PACKED_ICE)) {
                racer.safe = player.getPos();
                racer.safeYaw = player.getYaw();
            }
            if (this.pendingRecall.remove(uuid)) this.recall(context, player, racer, elapsed);
            if (player.getVehicle() instanceof BoatEntity boat && boat != racer.raft) player.stopRiding();
            // Source's 15-block tether prevents a player leaving their own raft/course.
            if (player.squaredDistanceTo(racer.raft) >= 225) {
                player.stopRiding();
                context.teleport(player, racer.raft.getPos());
            }
            if (elapsed >= racer.recallReadyAt) this.ensureRecall(player);
            BlockState marker = context.world().getBlockState(BlockPos.ofFloored(player.getPos().add(0, 10, 0)));
            if (racer.waypoint < 3 && marker.isOf(Blocks.LIGHT)
                    && marker.get(Properties.LEVEL_15) == racer.waypoint + 1) racer.waypoint++;
            if (racer.waypoint == 3 && context.world().getBlockState(
                    BlockPos.ofFloored(player.getPos().add(0, -1.5, 0))).isOf(Blocks.DEAD_HORN_CORAL_BLOCK)) {
                racer.laps++;
                racer.lastLapTick = elapsed;
                racer.waypoint = 0;
                if (racer.laps >= 2) {
                    context.completed(uuid);
                    player.stopRiding();
                    this.removeRaft(racer);
                    this.clearRecall(player);
                    context.eliminate(player, "§a已完成两圈，正在观看其他玩家。");
                    context.announce("§a" + player.getName().getString() + " 已完成竹筏竞速！");
                } else player.sendMessage(Text.literal("§a已完成第 1 圈，继续下一圈！"), false);
            }
            if (elapsed % 20 == 0 && !context.isOut(uuid)) {
                int cooldown = Math.max(0, (racer.recallReadyAt - elapsed + 19) / 20);
                player.sendMessage(Text.literal("§e第 " + (racer.laps + 1) + "/2 圈 §7｜路标 " + racer.waypoint
                        + "/3｜召回 " + (cooldown == 0 ? "§a就绪" : "§c" + cooldown + "s")), true);
            }
        }
        this.updateScores(context);
        if (context.alive().size() <= 1) context.finish();
    }

    private void newRaft(PartyRoundContext context, ServerPlayerEntity player, Racer racer, Vec3d position, float yaw) {
        player.stopRiding();
        this.removeRaft(racer);
        // Vanilla 1.21.1 destroys boats after >3-block falls even when Invulnerable. The source
        // uses invulnerable rafts and bubble-column jumps, so keep the raft while retaining native physics.
        BoatEntity boat = new BoatEntity(context.world(), position.x, position.y, position.z) {
            @Override
            protected void fall(double difference, boolean onGround, BlockState state, BlockPos landed) {
                this.fallDistance = 0;
                super.fall(difference, onGround, state, landed);
            }
        };
        boat.setVariant(BoatEntity.Type.BAMBOO);
        boat.setInvulnerable(true);
        boat.setSilent(true);
        boat.setYaw(yaw);
        boat.setPitch(0);
        boat.addCommandTag("party_raft");
        this.track(boat);
        this.boatOwners.put(boat.getUuid(), player.getUuid());
        context.world().spawnEntity(boat);
        racer.raft = boat;
        RabbitEntity rabbit = new RabbitEntity(EntityType.RABBIT, context.world());
        rabbit.refreshPositionAndAngles(position.x, position.y, position.z, yaw, 0);
        rabbit.setAiDisabled(true);
        rabbit.setSilent(true);
        rabbit.setInvulnerable(true);
        rabbit.setNoGravity(true);
        rabbit.setPersistent();
        rabbit.setBreedingAge(-9999);
        rabbit.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, -1, 0, true, false));
        this.track(rabbit);
        context.world().spawnEntity(rabbit);
        racer.rabbit = rabbit;
        this.mount(player, racer);
    }

    private void mount(ServerPlayerEntity player, Racer racer) {
        if (racer.raft == null || racer.raft.isRemoved()) return;
        if (racer.rabbit != null) racer.rabbit.stopRiding();
        player.startRiding(racer.raft, true);
        if (racer.rabbit != null && !racer.rabbit.isRemoved()) racer.rabbit.startRiding(racer.raft, true);
    }

    private void removeRaft(Racer racer) {
        if (racer.rabbit != null) {
            racer.rabbit.stopRiding();
            this.leaveCollisionTeam(racer.rabbit);
            racer.rabbit.discard();
            this.owned.remove(racer.rabbit.getUuid());
            this.entities.remove(racer.rabbit);
            racer.rabbit = null;
        }
        if (racer.raft != null) {
            racer.raft.removeAllPassengers();
            this.leaveCollisionTeam(racer.raft);
            racer.raft.discard();
            this.boatOwners.remove(racer.raft.getUuid());
            this.owned.remove(racer.raft.getUuid());
            this.entities.remove(racer.raft);
            racer.raft = null;
        }
    }

    @Override
    public ActionResult useEntity(PartyRoundContext context, ServerPlayerEntity player, Hand hand, Entity entity) {
        if (hand != Hand.MAIN_HAND || context.isOut(player.getUuid()) || !player.getUuid().equals(this.boatOwners.get(entity.getUuid()))) {
            return ActionResult.FAIL;
        }
        if (player.isSneaking()) return ActionResult.FAIL;
        Racer racer = this.racers.get(player.getUuid());
        this.mount(player, racer);
        return ActionResult.SUCCESS;
    }

    @Override
    public ActionResult useItem(PartyRoundContext context, ServerPlayerEntity player, Hand hand) {
        if (!recallStack(player.getStackInHand(hand), player.getUuid()) || context.isOut(player.getUuid())) return ActionResult.FAIL;
        this.pendingRecall.add(player.getUuid());
        return ActionResult.SUCCESS;
    }

    private void recall(PartyRoundContext context, ServerPlayerEntity player, Racer racer, int elapsed) {
        if (elapsed < racer.recallReadyAt) return;
        this.clearRecall(player);
        // Dropping the source stick whilst mounted consumes its cooldown but does not move the raft.
        if (!player.hasVehicle()) {
            Vec3d target = player.getPos();
            if (context.world().getBlockState(player.getBlockPos()).isOf(Blocks.WATER)) target = target.add(0, 2, 0);
            racer.raft.refreshPositionAndAngles(target.x, target.y, target.z, player.getYaw(), player.getPitch());
            racer.raft.setVelocity(Vec3d.ZERO);
            racer.raft.velocityModified = true;
        }
        racer.recallReadyAt = elapsed + RECALL_TICKS;
    }

    private void giveRecall(ServerPlayerEntity player) {
        ItemStack stack = new ItemStack(Items.CARROT_ON_A_STICK);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(RECALL_NAME));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal("§7Drop to reset if stuck"))));
        stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(9));
        NbtCompound data = new NbtCompound();
        data.putString(OWNER_KEY, player.getUuidAsString());
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
        player.getInventory().insertStack(stack);
    }

    private void ensureRecall(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (recallStack(player.getInventory().getStack(i), player.getUuid())) return;
        }
        this.giveRecall(player);
    }

    private void clearRecall(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (recallStack(player.getInventory().getStack(i), player.getUuid())) player.getInventory().setStack(i, ItemStack.EMPTY);
        }
    }

    private static boolean recallStack(ItemStack stack, UUID owner) {
        if (!stack.isOf(Items.CARROT_ON_A_STICK)) return false;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data != null && owner.toString().equals(data.copyNbt().getString(OWNER_KEY));
    }

    @Override
    public boolean ownsEntity(Entity entity) {
        if (this.closed || entity.getWorld() != this.world) return false;
        if (this.owned.contains(entity.getUuid())) return true;
        if (!(entity instanceof ItemEntity item) || this.bounds == null || !this.bounds.expand(16).contains(item.getPos())) return false;
        return item.getOwner() instanceof ServerPlayerEntity owner && this.racers.containsKey(owner.getUuid())
                && owner.getWorld() == this.world && recallStack(item.getStack(), owner.getUuid());
    }

    @Override
    public void entityLoaded(PartyRoundContext context, Entity entity) {
        if (entity instanceof ItemEntity item && this.ownsEntity(item)) this.consumeDrop(item);
    }

    private void consumeDrop(ItemEntity item) {
        if (item.getOwner() != null) this.pendingRecall.add(item.getOwner().getUuid());
        item.discard();
    }

    @Override
    public boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        return victim instanceof ServerPlayerEntity && context.playerIds().contains(victim.getUuid()) && !context.isOut(victim.getUuid());
    }

    @Override
    public void death(PartyRoundContext context, ServerPlayerEntity player) {
        Racer racer = this.racers.get(player.getUuid());
        if (racer == null) return;
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.fallDistance = 0;
        context.teleport(player, racer.safe);
        this.newRaft(context, player, racer, racer.safe.add(0, 1, 0), racer.safeYaw);
    }

    @Override
    public void leave(PartyRoundContext context, ServerPlayerEntity player) {
        Racer racer = this.racers.get(player.getUuid());
        if (racer != null) this.removeRaft(racer);
        this.pendingRecall.remove(player.getUuid());
        this.clearRecall(player);
        player.removeStatusEffect(StatusEffects.WEAKNESS);
    }

    private void updateScores(PartyRoundContext context) {
        int count = Math.max(1, context.initialPlayerCount());
        for (Map.Entry<UUID, Racer> entry : this.racers.entrySet()) {
            Racer racer = entry.getValue();
            int earlier = 0;
            for (Racer other : this.racers.values()) if (other.lastLapTick > racer.lastLapTick) earlier++;
            context.score(entry.getKey(), racer.laps * count + earlier);
        }
    }

    @Override
    public void finish(PartyRoundContext context) { this.updateScores(context); }

    @Override
    public void close(PartyRoundContext context) {
        if (this.closed) return;
        for (ServerPlayerEntity player : context.players()) {
            player.stopRiding();
            player.removeStatusEffect(StatusEffects.WEAKNESS);
            this.clearRecall(player);
        }
        for (Racer racer : this.racers.values()) this.removeRaft(racer);
        for (Entity entity : List.copyOf(this.entities)) entity.discard();
        for (ItemEntity item : context.world().getEntitiesByClass(ItemEntity.class, this.bounds.expand(16), this::ownsEntity)) item.discard();
        this.entities.clear();
        this.owned.clear();
        this.boatOwners.clear();
        this.pendingRecall.clear();
        if (this.collisionTeam != null) {
            var scoreboard = this.world.getServer().getScoreboard();
            if (scoreboard.getTeam(this.collisionTeam.getName()) == this.collisionTeam) scoreboard.removeTeam(this.collisionTeam);
            this.collisionTeam = null;
        }
        this.closed = true;
    }

    @Override
    public String scoreDescription(PartyRoundContext context, UUID player) {
        Racer racer = this.racers.get(player);
        return racer == null ? "0/2 圈" : racer.laps + "/2 圈";
    }

    @Override
    public String status(PartyRoundContext context) { return "§f两圈竞速 · 路线 " + this.route; }

    private void leaveCollisionTeam(Entity entity) {
        var scoreboard = this.world.getServer().getScoreboard();
        if (this.collisionTeam != null && scoreboard.getScoreHolderTeam(entity.getUuidAsString()) == this.collisionTeam) {
            scoreboard.removeScoreHolderFromTeam(entity.getUuidAsString(), this.collisionTeam);
        }
    }

    private void track(Entity entity) {
        if (this.collisionTeam != null && (entity instanceof BoatEntity || entity instanceof RabbitEntity)) {
            this.world.getServer().getScoreboard().addScoreHolderToTeam(entity.getUuidAsString(), this.collisionTeam);
        }
        this.owned.add(entity.getUuid());
        this.entities.add(entity);
    }

    private void spawnArrow(PartyRoundContext context, Operation operation) {
        Vec3d position = context.source(operation.arrow().x, operation.arrow().y, operation.arrow().z);
        DisplayEntity.TextDisplayEntity display = new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, context.world());
        display.refreshPositionAndAngles(position.x, position.y, position.z, operation.yaw(), 0);
        display.setBillboardMode(DisplayEntity.BillboardMode.FIXED);
        display.setText(Text.literal("←").styled(style -> style.withBold(true).withColor(0)));
        display.setBackground(16711680);
        display.setTransformation(new AffineTransformation(new Vector3f(), new Quaternionf(),
                new Vector3f(16), new Quaternionf()));
        this.track(display);
        context.world().spawnEntity(display);
    }

    private static void apply(Long2ObjectOpenHashMap<BlockState> blocks, Operation operation) {
        if (operation.op().equals("clone")) {
            // Clone reads its entire one-block source before writing, preserving overlapping-source semantics.
            Long2ObjectOpenHashMap<BlockState> copy = new Long2ObjectOpenHashMap<>();
            BlockPos minimum = new BlockPos(Math.min(operation.from().getX(), operation.to().getX()),
                    Math.min(operation.from().getY(), operation.to().getY()), Math.min(operation.from().getZ(), operation.to().getZ()));
            for (BlockPos position : BlockPos.iterate(operation.from(), operation.to())) {
                BlockPos destination = position.subtract(minimum).add(operation.destination());
                copy.put(destination.asLong(), blocks.getOrDefault(position.asLong(), Blocks.AIR.getDefaultState()));
            }
            copy.forEach((position, state) -> blocks.put(position.longValue(), state));
            return;
        }
        int x1 = Math.min(operation.from().getX(), operation.to().getX()), x2 = Math.max(operation.from().getX(), operation.to().getX());
        int y1 = Math.min(operation.from().getY(), operation.to().getY()), y2 = Math.max(operation.from().getY(), operation.to().getY());
        int z1 = Math.min(operation.from().getZ(), operation.to().getZ()), z2 = Math.max(operation.from().getZ(), operation.to().getZ());
        for (BlockPos position : BlockPos.iterate(x1, y1, z1, x2, y2, z2)) {
            BlockState current = blocks.getOrDefault(position.asLong(), Blocks.AIR.getDefaultState());
            if (operation.replace() != null && !current.isOf(operation.replace())) continue;
            boolean inside = position.getX() > x1 && position.getX() < x2 && position.getY() > y1
                    && position.getY() < y2 && position.getZ() > z1 && position.getZ() < z2;
            blocks.put(position.asLong(), operation.hollow() && inside ? Blocks.AIR.getDefaultState() : operation.state());
        }
    }

    private static Rules loadRules() throws IOException {
        try (InputStream input = RaftRacersRound.class.getResourceAsStream("/pvp/party/raft_racers_rules.json")) {
            if (input == null) throw new IOException("Missing Raft Racers rules");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("formatVersion").getAsInt() != 1 || root.get("sourceId").getAsInt() != 405) throw new IOException("Invalid Raft Racers rules");
            Map<Integer, List<Operation>> routes = new HashMap<>();
            for (int path = 11; path <= 14; path++) routes.put(path, readOperations(root.getAsJsonObject("routes").getAsJsonArray(Integer.toString(path))));
            return new Rules(routes, readOperations(root.getAsJsonArray("startOperations")));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new IOException("Invalid Raft Racers rules", exception);
        }
    }

    private static List<Operation> readOperations(JsonArray array) throws IOException {
        List<Operation> result = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject encoded = element.getAsJsonObject();
            String op = encoded.get("op").getAsString();
            if (op.equals("arrow")) {
                JsonArray position = encoded.getAsJsonArray("position");
                result.add(new Operation(op, null, null, null, null, null, false,
                        new Vec3d(position.get(0).getAsDouble(), position.get(1).getAsDouble(), position.get(2).getAsDouble()), encoded.get("yaw").getAsFloat()));
                continue;
            }
            if (!op.equals("fill") && !op.equals("clone")) throw new IOException("Unsupported Raft Racers operation " + op);
            result.add(new Operation(op, position(encoded.getAsJsonArray("from")), position(encoded.getAsJsonArray("to")),
                    encoded.has("destination") ? position(encoded.getAsJsonArray("destination")) : null,
                    encoded.has("state") ? state(encoded.get("state").getAsString()) : null,
                    encoded.has("replace") ? Registries.BLOCK.get(Identifier.of(encoded.get("replace").getAsString())) : null,
                    encoded.has("hollow") && encoded.get("hollow").getAsBoolean(), null, 0));
        }
        return List.copyOf(result);
    }

    private static BlockPos position(JsonArray array) throws IOException {
        if (array == null || array.size() != 3) throw new IOException("Invalid Raft Racers coordinates");
        BlockPos position = new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
        if (position.getX() < MINIMUM.getX() || position.getX() > MAXIMUM.getX()
                || position.getY() < MINIMUM.getY() || position.getY() > MAXIMUM.getY()
                || position.getZ() < MINIMUM.getZ() || position.getZ() > MAXIMUM.getZ()) throw new IOException("Raft Racers operation outside course");
        return position;
    }

    private static BlockState state(String value) throws IOException {
        int properties = value.indexOf('[');
        Identifier id = Identifier.of(properties < 0 ? value : value.substring(0, properties));
        if (!Registries.BLOCK.containsId(id)) throw new IOException("Unknown Raft Racers block " + id);
        BlockState state = Registries.BLOCK.get(id).getDefaultState();
        if (properties >= 0) {
            for (String encoded : value.substring(properties + 1, value.length() - 1).split(",")) {
                String[] pair = encoded.split("=", 2);
                Property<?> key = state.getBlock().getStateManager().getProperty(pair[0]);
                if (key == null || pair.length != 2) throw new IOException("Invalid Raft Racers block state");
                state = withProperty(state, key, pair[1]);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) throws IOException {
        T parsed = property.parse(value).orElseThrow(() -> new IOException("Invalid Raft Racers state " + value));
        return state.with(property, parsed);
    }
}
