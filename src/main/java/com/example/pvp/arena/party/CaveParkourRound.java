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
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

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
 * 原版 404 Cave Parkour：移植完整洞穴、11 节点的随机分岔、39 个检查点位置和复位棒。
 * 原图通过 22 条命令方块链改变道路；只复制静态洞穴会留下固定甚至断开的路线，因此在本场
 * 生成前按审计后的有限 set/fill/clone 数据走原分岔图，不执行原图命令或加载命令方块 NBT。
 * 自查：分岔使用同一不可变模板，replace 匹配方块类型；检查点必须同时满足距离及脚下方块；
 * 进度不回退，同 tick 到达保留并列；死亡/右键/丢弃只回个人检查点，所有改动由 Context 清场。
 */
public final class CaveParkourRound implements PartyRound {
    private static final int DURATION = 4000;
    private static final String RESET_NAME = "§b回到检查点";
    private static final int[] BOOT_COLORS = {255, 16711680, 65280, 16776960, 1481884,
            13061821, 9502720, 28672, 8991416, 112};
    private record Checkpoint(int stage, int x, int y, int z, float yaw) { }
    private static final List<Checkpoint> CHECKPOINTS = List.of(
            new Checkpoint(1, 201, -9, -139, 0),
            new Checkpoint(2, 190, -9, -147, 180),
            new Checkpoint(2, 180, -9, -139, 90),
            new Checkpoint(2, 178, -9, -133, 90),
            new Checkpoint(3, 158, -9, -141, 90),
            new Checkpoint(3, 158, -8, -136, 90),
            new Checkpoint(3, 187, -9, -156, 180),
            new Checkpoint(4, 190, -4, -154, -90),
            new Checkpoint(4, 141, -8, -142, 180),
            new Checkpoint(5, 167, -5, -165, -90),
            new Checkpoint(5, 138, -8, -169, 180),
            new Checkpoint(5, 192, -2, -160, 45),
            new Checkpoint(6, 189, -7, -172, 180),
            new Checkpoint(6, 177, -10, -179, 180),
            new Checkpoint(6, 138, -6, -200, -135),
            new Checkpoint(7, 147, 6, -210, -90),
            new Checkpoint(7, 159, -3, -201, -135),
            new Checkpoint(7, 197, -5, -179, 90),
            new Checkpoint(7, 180, -5, -209, 180),
            new Checkpoint(8, 187, -1, -191, 90),
            new Checkpoint(8, 172, 0, -209, 135),
            new Checkpoint(8, 157, 4, -183, 180),
            new Checkpoint(8, 151, 10, -218, 0),
            new Checkpoint(9, 152, 14, -215, -90),
            new Checkpoint(9, 174, 2, -218, 180),
            new Checkpoint(9, 196, 4, -191, 180),
            new Checkpoint(10, 195, 2, -230, -135),
            new Checkpoint(10, 226, 5, -212, -90),
            new Checkpoint(10, 174, 4, -242, 180),
            new Checkpoint(11, 186, 7, -251, 180),
            new Checkpoint(11, 208, 9, -239, 180),
            new Checkpoint(11, 235, 10, -227, 180),
            new Checkpoint(11, 170, 5, -259, -135),
            new Checkpoint(12, 211, 12, -244, -90),
            new Checkpoint(12, 209, 12, -268, -90),
            new Checkpoint(12, 217, 10, -237, 180),
            new Checkpoint(13, 231, 11, -259, 180),
            new Checkpoint(14, 241, 15, -276, -90),
            new Checkpoint(15, 259, 12, -274, -90));
    private record Operation(String op, BlockPos from, BlockPos to, BlockPos destination,
                             BlockState state, Block replace) { }
    private record Branch(String nextPath, List<Operation> operations) { }
    private record SignData(List<String> front, List<String> back, DyeColor frontColor, DyeColor backColor,
                            boolean frontGlowing, boolean backGlowing) { }
    private record Routes(Map<String, List<Branch>> branches, Long2ObjectOpenHashMap<SignData> signs,
                          Long2ObjectOpenHashMap<SignData> templateSigns) { }
    private static final class Progress {
        int checkpoint;
        int lastProgressTick;
        Vec3d respawn;
        float yaw;
        int protectedUntil;
        Progress(Vec3d respawn) { this.respawn = respawn; }
    }

    private final PartyRoundPlayerState playerState = new PartyRoundPlayerState();
    private final Map<UUID, Progress> progress = new LinkedHashMap<>();
    private final Set<UUID> active = new HashSet<>();
    private final Set<UUID> pendingResets = new HashSet<>();
    private final Long2ObjectOpenHashMap<SignData> signs = new Long2ObjectOpenHashMap<>();
    private net.minecraft.server.world.ServerWorld world;
    private Box bounds;
    private boolean closed;

    @Override
    public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(new BlockPos(200, 0, -204));
        context.timeLimit(DURATION);
        this.world = context.world();
        this.bounds = new Box(context.source(125, -24, -290), context.source(271, 25, -124));
        Long2ObjectOpenHashMap<BlockState> cave = new Long2ObjectOpenHashMap<>();
        PartyMapBlueprint.load("cave_parkour").forEachTranslated(BlockPos.ORIGIN,
                (position, state) -> cave.put(position.asLong(), state));
        Long2ObjectOpenHashMap<BlockState> templates = new Long2ObjectOpenHashMap<>();
        PartyMapBlueprint.load("cave_parkour_templates").forEachTranslated(BlockPos.ORIGIN,
                (position, state) -> templates.put(position.asLong(), state));
        Routes data = loadRoutes();
        Map<String, List<Branch>> routes = data.branches();
        this.signs.putAll(data.signs());
        String node = "path1";
        Set<String> visited = new HashSet<>();
        while (node != null) {
            if (!visited.add(node)) throw new IOException("Cycle in Cave Parkour route graph");
            List<Branch> choices = routes.get(node);
            if (choices == null) throw new IOException("Missing Cave Parkour route " + node);
            Branch branch = choices.get(context.random().nextInt(choices.size()));
            for (Operation operation : branch.operations()) apply(cave, templates, operation, this.signs, data.templateSigns());
            node = branch.nextPath();
        }
        // parkour_start 激活终点信标底座，不复制其控制线路。
        cave.put(BlockPos.asLong(260, 9, -274), Blocks.IRON_BLOCK.getDefaultState());
        for (Long2ObjectMap.Entry<BlockState> entry : cave.long2ObjectEntrySet()) {
            BlockPos source = BlockPos.fromLong(entry.getLongKey());
            context.stage(context.sourceBlock(source.getX(), source.getY(), source.getZ()), entry.getValue());
        }
        for (ServerPlayerEntity player : context.players()) {
            Vec3d spawn = context.source(204.5, -9, -170.5);
            this.progress.put(player.getUuid(), new Progress(spawn));
            this.active.add(player.getUuid());
            context.spawn(player.getUuid(), spawn);
        }
    }

    @Override
    public void start(PartyRoundContext context) {
        // 箭头是洞穴及分岔的原有路标；只恢复白名单字面文本，不加载告示牌事件或原 NBT。
        for (Long2ObjectMap.Entry<SignData> entry : this.signs.long2ObjectEntrySet()) {
            BlockPos source = BlockPos.fromLong(entry.getLongKey());
            BlockPos position = context.sourceBlock(source.getX(), source.getY(), source.getZ());
            if (context.world().getBlockEntity(position) instanceof SignBlockEntity sign) {
                SignData data = entry.getValue();
                sign.setText(signText(data.front(), data.frontColor(), data.frontGlowing()), true);
                sign.setText(signText(data.back(), data.backColor(), data.backGlowing()), false);
                sign.setWaxed(true);
                sign.markDirty();
                context.world().updateListeners(position, sign.getCachedState(), sign.getCachedState(), 3);
            }
        }
        for (ServerPlayerEntity player : context.players()) {
            this.playerState.resetHealth(player);
            this.keepEffects(player);
            this.equip(player, context.slot(player.getUuid()));
            player.teleport(context.world(), player.getX(), player.getY(), player.getZ(), 0, 0);
        }
        context.announce("§e洞穴跑酷：踩过红石灯保存检查点，右键或丢弃复位棒返回检查点。"
                + "\n§7共 15 个检查点，限时 200 秒；按进度及到达先后排名。");
    }

    @Override
    public void tick(PartyRoundContext context, int elapsed) {
        for (Checkpoint checkpoint : CHECKPOINTS) {
            Vec3d anchor = context.source(checkpoint.x(), checkpoint.y(), checkpoint.z());
            for (ServerPlayerEntity player : context.alive()) {
                Progress own = this.progress.get(player.getUuid());
                if (own == null || own.checkpoint >= checkpoint.stage() || player.squaredDistanceTo(anchor) > 1) continue;
                BlockPos below = player.getBlockPos().down(checkpoint.stage() == 15 ? 2 : 1);
                if (!context.world().getBlockState(below).isOf(checkpoint.stage() == 15 ? Blocks.BEACON : Blocks.REDSTONE_LAMP)) continue;
                own.checkpoint = checkpoint.stage();
                own.lastProgressTick = elapsed;
                // 原 spawnpoint 使用方块坐标；返回时居中站在已踩到的检查点方块上。
                own.respawn = new Vec3d(player.getBlockX() + 0.5, player.getBlockY(), player.getBlockZ() + 0.5);
                own.yaw = checkpoint.yaw();
                player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, 2);
                player.sendMessage(Text.literal("§a检查点 " + own.checkpoint + "/15"), true);
                if (own.checkpoint == 15) {
                    player.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
                    context.completed(player.getUuid());
                    context.eliminate(player, "§a已完成洞穴跑酷，正在观看其他玩家。");
                    context.announce("§a" + player.getName().getString() + " 已完成洞穴跑酷！");
                }
            }
        }
        // ENTITY_LOAD 时物品的投掷者可能尚未赋值；按实际 thrower 再查一次，绝不按最近玩家猜归属。
        for (ItemEntity item : context.world().getEntitiesByClass(ItemEntity.class, this.bounds, this::ownsEntity)) {
            this.consumeResetDrop(item);
        }
        for (ServerPlayerEntity player : context.alive()) {
            UUID uuid = player.getUuid();
            if (elapsed % 20 == 0) this.keepEffects(player);
            if (this.pendingResets.remove(uuid) || player.getY() < this.bounds.minY - 8
                    || !this.bounds.expand(4).contains(player.getPos())) {
                this.retry(context, player);
            }
            if (elapsed % 20 == 0) {
                this.ensureReset(player);
                player.sendMessage(Text.literal("§e检查点 " + this.progress.get(uuid).checkpoint + "/15  §7右键复位棒返回"), true);
            }
        }
        this.updateScores(context);
        if (context.alive().size() <= 1) context.finish();
    }

    private void updateScores(PartyRoundContext context) {
        int count = Math.max(1, context.initialPlayerCount());
        for (Map.Entry<UUID, Progress> entry : this.progress.entrySet()) {
            Progress own = entry.getValue();
            int earlier = 0;
            for (Progress other : this.progress.values()) if (other.lastProgressTick > own.lastProgressTick) earlier++;
            context.score(entry.getKey(), own.checkpoint * count + earlier);
        }
    }

    @Override
    public ActionResult useItem(PartyRoundContext context, ServerPlayerEntity player, Hand hand) {
        if (resetStack(player.getStackInHand(hand))) {
            this.pendingResets.add(player.getUuid());
            return ActionResult.SUCCESS;
        }
        return ActionResult.FAIL;
    }

    @Override
    public boolean ownsEntity(Entity entity) {
        return !this.closed && entity instanceof ItemEntity item && item.getWorld() == this.world
                && this.bounds != null && this.bounds.expand(4).contains(item.getPos()) && resetStack(item.getStack())
                && item.getOwner() instanceof ServerPlayerEntity owner && this.active.contains(owner.getUuid())
                && owner.getWorld() == this.world && this.bounds.expand(4).contains(owner.getPos());
    }

    @Override
    public void entityLoaded(PartyRoundContext context, Entity entity) {
        if (entity instanceof ItemEntity item && this.ownsEntity(item)) this.consumeResetDrop(item);
    }

    private void consumeResetDrop(ItemEntity item) {
        Entity owner = item.getOwner();
        if (owner != null) this.pendingResets.add(owner.getUuid());
        item.discard();
    }

    @Override
    public void death(PartyRoundContext context, ServerPlayerEntity player) {
        this.retry(context, player);
    }

    private void retry(PartyRoundContext context, ServerPlayerEntity player) {
        Progress own = this.progress.get(player.getUuid());
        if (own == null || context.isOut(player.getUuid())) return;
        this.playerState.resetHealth(player);
        player.timeUntilRegen = 0;
        player.hurtTime = 0;
        player.setAttacker(null);
        own.protectedUntil = context.elapsed() + 60;
        context.teleport(player, own.respawn);
        player.teleport(context.world(), player.getX(), player.getY(), player.getZ(), own.yaw, 0);
        this.keepEffects(player);
        // 原 clear carrot_on_a_stick 后重新放入 hotbar.0，避免副手/移动槽位制造重复复位棒。
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            if (resetStack(player.getInventory().getStack(slot))) player.getInventory().setStack(slot, ItemStack.EMPTY);
        }
        this.ensureReset(player);
    }

    @Override
    public boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        if (!(victim instanceof ServerPlayerEntity player) || source.getAttacker() != null) return false;
        Progress own = this.progress.get(player.getUuid());
        return own != null && !context.isOut(player.getUuid()) && (context.elapsed() >= own.protectedUntil
                || source.isOf(DamageTypes.FALL) || source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY));
    }

    @Override
    public void finish(PartyRoundContext context) {
        this.updateScores(context);
    }

    @Override
    public void leave(PartyRoundContext context, ServerPlayerEntity player) {
        this.active.remove(player.getUuid());
        this.pendingResets.remove(player.getUuid());
        this.removeEffects(player);
    }

    @Override
    public void close(PartyRoundContext context) {
        this.closed = true;
        this.pendingResets.clear();
        this.active.clear();
        context.players().forEach(this::removeEffects);
    }

    @Override
    public String scoreDescription(PartyRoundContext context, UUID player) {
        Progress own = this.progress.get(player);
        return own == null ? "0/15" : own.checkpoint == 15 ? "已完赛" : own.checkpoint + "/15 检查点";
    }

    private void keepEffects(ServerPlayerEntity player) {
        this.playerState.invisible(player);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 30, 99, false, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, 30, 99, false, false, false));
    }

    private void removeEffects(ServerPlayerEntity player) {
        this.playerState.restore(player);
        player.removeStatusEffect(StatusEffects.WEAKNESS);
        player.removeStatusEffect(StatusEffects.SATURATION);
    }

    private void equip(ServerPlayerEntity player, int slot) {
        ItemStack boots = new ItemStack(Items.LEATHER_BOOTS);
        boots.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(BOOT_COLORS[Math.floorMod(slot, BOOT_COLORS.length)], false));
        boots.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(false));
        player.getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.BINDING_CURSE)
                .ifPresent(entry -> boots.addEnchantment(entry, 1));
        player.equipStack(EquipmentSlot.FEET, boots);
        this.ensureReset(player);
    }

    private void ensureReset(ServerPlayerEntity player) {
        boolean hasReset = false;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            if (resetStack(player.getInventory().getStack(slot))) { hasReset = true; break; }
        }
        if (!hasReset) {
            ItemStack stack = new ItemStack(Items.CARROT_ON_A_STICK);
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(RESET_NAME));
            stack.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(false));
            player.getInventory().setStack(0, stack);
            player.currentScreenHandler.sendContentUpdates();
        }
    }

    private static boolean resetStack(ItemStack stack) {
        Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
        return stack.isOf(Items.CARROT_ON_A_STICK) && name != null && RESET_NAME.equals(name.getString());
    }

    private static Routes loadRoutes() throws IOException {
        String path = "/pvp/party/cave_parkour_routes.json";
        try (InputStream input = CaveParkourRound.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing Cave Parkour route graph");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("formatVersion").getAsInt() != 1 || !"path1".equals(root.get("start").getAsString())) {
                throw new IOException("Unsupported Cave Parkour route graph");
            }
            JsonObject encoded = root.getAsJsonObject("branches");
            if (encoded.size() != 11) throw new IOException("Cave Parkour requires 11 branch nodes");
            Map<String, List<Branch>> routes = new HashMap<>();
            for (int node = 1; node <= 11; node++) {
                String name = "path" + node;
                JsonArray choices = encoded.getAsJsonArray(name);
                if (choices == null || choices.size() != 2) throw new IOException("Cave Parkour requires 2 choices at " + name);
                List<Branch> branches = new ArrayList<>();
                for (JsonElement choice : choices) {
                    JsonObject branch = choice.getAsJsonObject();
                    String next = branch.has("nextPath") && !branch.get("nextPath").isJsonNull() ? branch.get("nextPath").getAsString() : null;
                    JsonArray operations = branch.getAsJsonArray("operations");
                    if (operations.size() > 16) throw new IOException("Excessive Cave Parkour branch operations");
                    List<Operation> decoded = new ArrayList<>();
                    for (JsonElement element : operations) {
                        JsonObject operation = element.getAsJsonObject();
                        String op = operation.get("op").getAsString();
                        if (!Set.of("set", "fill", "clone").contains(op)) throw new IOException("Unknown Cave Parkour operation " + op);
                        BlockPos from = point(operation.getAsJsonArray(op.equals("set") ? "pos" : "from"));
                        BlockPos to = op.equals("set") ? from : point(operation.getAsJsonArray("to"));
                        BlockPos destination = op.equals("clone") ? point(operation.getAsJsonArray("destination")) : null;
                        BlockState state = op.equals("clone") ? null : state(operation.get("state").getAsString());
                        Block replace = operation.has("replace") ? state(operation.get("replace").getAsString()).getBlock() : null;
                        decoded.add(new Operation(op, from, to, destination, state, replace));
                    }
                    branches.add(new Branch(next, List.copyOf(decoded)));
                }
                routes.put(name, List.copyOf(branches));
            }
            for (String node : routes.keySet()) validatePaths(routes, node, new HashSet<>());
            return new Routes(routes, signData(root.getAsJsonArray("signs")), signData(root.getAsJsonArray("templateSigns")));
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | IndexOutOfBoundsException exception) {
            throw new IOException("Invalid Cave Parkour route graph", exception);
        }
    }

    private static Long2ObjectOpenHashMap<SignData> signData(JsonArray encoded) throws IOException {
        if (encoded == null || encoded.size() > 128) throw new IOException("Invalid Cave Parkour signs");
        Long2ObjectOpenHashMap<SignData> signs = new Long2ObjectOpenHashMap<>();
        for (JsonElement element : encoded) {
            JsonObject sign = element.getAsJsonObject();
            BlockPos position = point(sign.getAsJsonArray("pos"));
            List<String> front = signLines(sign.getAsJsonArray("front"));
            List<String> back = signLines(sign.getAsJsonArray("back"));
            DyeColor frontColor = DyeColor.byName(sign.get("frontColor").getAsString(), null);
            DyeColor backColor = DyeColor.byName(sign.get("backColor").getAsString(), null);
            if (frontColor == null || backColor == null) throw new IOException("Invalid Cave Parkour sign color");
            signs.put(position.asLong(), new SignData(front, back, frontColor, backColor,
                    sign.get("frontGlowing").getAsBoolean(), sign.get("backGlowing").getAsBoolean()));
        }
        return signs;
    }

    private static List<String> signLines(JsonArray encoded) throws IOException {
        if (encoded == null || encoded.size() != 4) throw new IOException("Invalid Cave Parkour sign lines");
        List<String> lines = new ArrayList<>();
        for (JsonElement element : encoded) {
            String line = element.getAsString();
            if (!line.matches("[<>^\\- ]{0,24}")) throw new IOException("Cave Parkour sign is not a plain direction arrow");
            lines.add(line);
        }
        return List.copyOf(lines);
    }

    private static SignText signText(List<String> lines, DyeColor color, boolean glowing) {
        SignText text = new SignText().withColor(color).withGlowing(glowing);
        for (int line = 0; line < 4; line++) text = text.withMessage(line, Text.literal(lines.get(line)));
        return text;
    }

    private static void validatePaths(Map<String, List<Branch>> routes, String node, Set<String> seen) throws IOException {
        if (!seen.add(node)) throw new IOException("Cyclic Cave Parkour graph");
        List<Branch> branches = routes.get(node);
        if (branches == null) throw new IOException("Unknown Cave Parkour branch " + node);
        for (Branch branch : branches) {
            if (branch.nextPath() != null) validatePaths(routes, branch.nextPath(), new HashSet<>(seen));
        }
    }

    private static BlockPos point(JsonArray array) throws IOException {
        if (array == null || array.size() != 3) throw new IOException("Invalid Cave Parkour coordinate");
        BlockPos point = new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
        if (point.getX() < 125 || point.getX() > 270 || point.getY() < -24 || point.getY() > 30
                || point.getZ() < -290 || point.getZ() > -125) throw new IOException("Cave Parkour operation exceeds source region");
        return point;
    }

    private static BlockState state(String encoded) throws IOException {
        int properties = encoded.indexOf('[');
        String name = properties < 0 ? encoded : encoded.substring(0, properties);
        Identifier id = Identifier.of(name);
        if (!Registries.BLOCK.containsId(id)) throw new IOException("Unavailable Cave Parkour block " + id);
        Block block = Registries.BLOCK.get(id);
        BlockState state = block.getDefaultState();
        if (properties >= 0) {
            if (!encoded.endsWith("]")) throw new IOException("Invalid Cave Parkour block state");
            for (String property : encoded.substring(properties + 1, encoded.length() - 1).split(",")) {
                String[] pair = property.split("=", -1);
                Property<?> key = pair.length == 2 ? block.getStateManager().getProperty(pair[0]) : null;
                if (key == null) throw new IOException("Invalid Cave Parkour block property " + property);
                state = with(state, key, pair[1]);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) throws IOException {
        return state.with(property, property.parse(value).orElseThrow(() -> new IOException("Invalid Cave Parkour property value " + value)));
    }

    private static void apply(Long2ObjectOpenHashMap<BlockState> cave, Long2ObjectOpenHashMap<BlockState> templates,
                              Operation operation, Long2ObjectOpenHashMap<SignData> signs,
                              Long2ObjectOpenHashMap<SignData> templateSigns) throws IOException {
        BlockPos from = operation.from();
        BlockPos to = operation.to();
        int minX = Math.min(from.getX(), to.getX()), minY = Math.min(from.getY(), to.getY()), minZ = Math.min(from.getZ(), to.getZ());
        int maxX = Math.max(from.getX(), to.getX()), maxY = Math.max(from.getY(), to.getY()), maxZ = Math.max(from.getZ(), to.getZ());
        if ((long) (maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1) > 4096) throw new IOException("Cave Parkour operation too large");
        for (BlockPos mutable : BlockPos.iterate(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockPos position = mutable.toImmutable();
            if (operation.op().equals("clone")) {
                BlockPos destination = operation.destination().add(position.getX()-minX, position.getY()-minY, position.getZ()-minZ);
                // clone 默认为 replace，模板没有记录的空气也必须覆盖目的格。
                cave.put(destination.asLong(), templates.getOrDefault(position.asLong(), Blocks.AIR.getDefaultState()));
                SignData text = templateSigns.get(position.asLong());
                if (text != null) signs.put(destination.asLong(), text);
                else signs.remove(destination.asLong());
            } else if (operation.replace() == null || cave.getOrDefault(position.asLong(), Blocks.AIR.getDefaultState()).isOf(operation.replace())) {
                cave.put(position.asLong(), operation.state());
                signs.remove(position.asLong());
            }
        }
    }
}
