package com.example.pvp.arena.party;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 原版 425 Memory Lane：记住三乘五矿石画布，再按随机指定的矿石逐格还原。
 * 依据 memory{,_start,_generate,_tick,_new_ore,_wrong_choice,_spectate,_end} 与 checkpoint 函数。
 * 原图用全服记分板和带命令的告示牌控制个人画布；本类把它们限定在本场 UUID 和原图坐标内，
 * 保留 200 tick 初看、80 tick 惩罚、60 tick 再看、2000 tick 总时间与同进度先到者优先。
 * 自查：每种矿石恰好三格；抽取按尚未填完的数量加权；不可点击别人或已完成的格子；
 * 跳跃只结束当前观看；同 tick 完成保留并列；观战切换不影响分数和其他对局。
 */
public final class MemoryLaneRound implements PartyRound {
    private static final int TOTAL_TICKS = 2000;
    private static final Block[] ORES = {
            Blocks.GOLD_ORE, Blocks.COAL_ORE, Blocks.EMERALD_ORE, Blocks.COPPER_ORE, Blocks.DIAMOND_ORE
    };
    private static final String[] ORE_NAMES = {"金矿石", "煤矿石", "绿宝石矿石", "铜矿石", "钻石矿石"};
    private static final BlockState BUTTON = Blocks.CRIMSON_BUTTON.getDefaultState()
            .with(ButtonBlock.FACE, BlockFace.WALL).with(ButtonBlock.FACING, Direction.WEST);
    private enum Phase { VIEW, PLAY, PENALTY, COMPLETE }

    private static final class Canvas {
        final int slot;
        final boolean[] filled = new boolean[15];
        final int[] remaining = {3, 3, 3, 3, 3};
        Phase phase = Phase.VIEW;
        int phaseTicks;
        int ore;
        int progress;
        int lastProgressTick;
        int jumpStat;
        int watching;
        int lastClickTick = -1;

        Canvas(int slot) {
            this.slot = slot;
            this.watching = slot;
        }
    }

    private final Map<UUID, Canvas> canvases = new HashMap<>();
    private final Map<BlockPos, Integer> spectatorSigns = new HashMap<>();
    private final int[] pattern = new int[15];

    @Override
    public void stage(PartyRoundContext context) throws IOException {
        // 两组原场景相距 240 格；以中点为锚，完整保留原图个人隔间而不压缩空间。
        context.sourceAnchor(new BlockPos(-47, 40, -431));
        context.geometry("memory_lane_first");
        context.geometry("memory_lane_second");
        context.timeLimit(TOTAL_TICKS);
        List<Integer> shuffled = new ArrayList<>(15);
        for (int ore = 0; ore < ORES.length; ore++) {
            for (int count = 0; count < 3; count++) {
                shuffled.add(ore);
            }
        }
        Collections.shuffle(shuffled, context.random());
        for (int cell = 0; cell < this.pattern.length; cell++) {
            this.pattern[cell] = shuffled.get(cell);
            for (int group = 0; group < 2; group++) {
                context.stage(context.sourceBlock(-78, baseY(group * 5) + cell / 5,
                        baseZ(group * 5) + 2 - cell % 5), ORES[this.pattern[cell]].getDefaultState());
            }
        }
        int nextSlot = 0;
        for (ServerPlayerEntity player : context.players()) {
            UUID uuid = player.getUuid();
            int slot = nextSlot++;
            this.canvases.put(uuid, new Canvas(slot));
            context.spawn(uuid, ownPosition(context, slot));
            for (int cell = 0; cell < 15; cell++) {
                BlockPos wall = wallPosition(context, slot, cell);
                context.stage(wall, Blocks.STONE.getDefaultState());
                context.stage(wall.west(), BUTTON);
            }
        }
    }

    @Override
    public void start(PartyRoundContext context) {
        this.setupSpectatorSigns(context);
        for (ServerPlayerEntity player : context.players()) {
            Canvas canvas = this.canvases.get(player.getUuid());
            if (canvas == null) {
                continue;
            }
            canvas.ore = drawOre(context, canvas);
            this.beginView(context, player, canvas, 200);
        }
        context.announce("§e记忆长廊：10 秒记住 15 格矿石，跳跃可提前结束观看。按手中矿石点击对应按钮。"
                + "\n§7点错后等待 4 秒，再看原图 3 秒；总时间 100 秒，先填满者获胜。");
    }

    @Override
    public void tick(PartyRoundContext context, int elapsed) {
        for (ServerPlayerEntity player : context.players()) {
            Canvas canvas = this.canvases.get(player.getUuid());
            if (canvas == null || context.isOut(player.getUuid()) && canvas.phase != Phase.COMPLETE) {
                continue;
            }
            if (canvas.phase == Phase.VIEW) {
                BlockPos marker = new BlockPos(player.getBlockX(), context.sourceBlock(0, 35, 0).getY(), player.getBlockZ());
                if (!context.world().getBlockState(marker).isOf(Blocks.LAPIS_BLOCK)) {
                    context.teleport(player, location(context, -85, baseY(canvas.slot), baseZ(canvas.slot)));
                }
                int jumps = player.getStatHandler().getStat(Stats.CUSTOM, Stats.JUMP);
                if (--canvas.phaseTicks <= 0 || jumps > canvas.jumpStat) {
                    canvas.phase = Phase.PLAY;
                    player.removeStatusEffect(StatusEffects.INVISIBILITY);
                    context.teleport(player, ownPosition(context, canvas.slot));
                    showOre(player, canvas.ore);
                } else {
                    keepInvisible(player);
                    if (elapsed % 20 == 0) {
                        player.sendMessage(Text.literal("§e记忆原图 · " + seconds(canvas.phaseTicks) + " 秒  §7跳跃开始填写"), true);
                    }
                }
            } else if (canvas.phase == Phase.PENALTY) {
                keepInvisible(player);
                if (--canvas.phaseTicks <= 0) {
                    this.beginView(context, player, canvas, 60);
                } else if (elapsed % 20 == 0) {
                    player.sendMessage(Text.literal("§c位置错误 · " + seconds(canvas.phaseTicks) + " 秒后重新观察"), true);
                }
            } else if (canvas.phase == Phase.PLAY && elapsed % 20 == 0) {
                // 原版每 tick 补满九个快捷栏；只在变化及每秒补齐即可保持选择行为而少发背包包。
                showOre(player, canvas.ore);
                player.sendMessage(Text.literal("§e请选择：" + ORE_NAMES[canvas.ore] + "  §f" + canvas.progress + "/15"), true);
            }
            if (player.getY() < context.source(0, baseY(canvas.slot) - 5, 0).y) {
                this.death(context, player);
            }
        }
        this.updateScores(context);
        long remaining = context.players().stream().filter(player -> !context.isOut(player.getUuid()))
                .filter(player -> this.canvases.get(player.getUuid()).phase != Phase.COMPLETE).count();
        // Quick Play assign_finish_threshold 的默认值为一人未完成；点击先记状态，再在 tick 统一结算。
        if (remaining <= 1) {
            context.finish();
        }
    }

    @Override
    public ActionResult useBlock(PartyRoundContext context, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.FAIL;
        }
        Canvas canvas = this.canvases.get(player.getUuid());
        if (canvas == null || context.isOut(player.getUuid()) && canvas.phase != Phase.COMPLETE) {
            return ActionResult.FAIL;
        }
        if (canvas.phase == Phase.COMPLETE) {
            Integer target = this.spectatorSigns.get(hit.getBlockPos());
            if (target != null && player.squaredDistanceTo(hit.getBlockPos().toCenterPos()) <= 36) {
                this.spectate(context, player, canvas, target);
                return ActionResult.SUCCESS;
            }
            return ActionResult.FAIL;
        }
        if (canvas.phase != Phase.PLAY || canvas.lastClickTick == context.elapsed()
                || player.squaredDistanceTo(hit.getBlockPos().toCenterPos()) > 36) {
            return ActionResult.FAIL;
        }
        for (int cell = 0; cell < this.pattern.length; cell++) {
            BlockPos wall = wallPosition(context, canvas.slot, cell);
            if (!wall.west().equals(hit.getBlockPos()) || canvas.filled[cell]) {
                continue;
            }
            canvas.lastClickTick = context.elapsed();
            if (this.pattern[cell] != canvas.ore) {
                canvas.phase = Phase.PENALTY;
                canvas.phaseTicks = 80;
                keepInvisible(player);
                context.teleport(player, penaltyPosition(context, canvas.slot));
                player.sendMessage(Text.literal("§c位置错误！等待 4 秒后可以再看原图。"), false);
                context.world().playSound(null, player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                        SoundCategory.PLAYERS, 0.8f, 0.6f);
                return ActionResult.SUCCESS;
            }
            canvas.filled[cell] = true;
            canvas.remaining[canvas.ore]--;
            canvas.progress++;
            canvas.lastProgressTick = context.elapsed();
            context.set(wall, ORES[canvas.ore].getDefaultState());
            context.set(wall.west(), Blocks.AIR.getDefaultState());
            context.world().playSound(null, wall, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                    SoundCategory.PLAYERS, 0.8f, 2.0f);
            if (canvas.progress == 15) {
                canvas.phase = Phase.COMPLETE;
                clearInventory(player);
                context.completed(player.getUuid());
                context.teleport(player, spectatorPosition(context, canvas.slot));
                context.announce("§a" + player.getName().getString() + " 已完成画布！");
                player.sendMessage(Text.literal("§7点击观战台告示牌可以切换玩家。"), false);
            } else {
                canvas.ore = drawOre(context, canvas);
                showOre(player, canvas.ore);
            }
            this.updateScores(context);
            return ActionResult.SUCCESS;
        }
        return ActionResult.FAIL;
    }

    @Override
    public void death(PartyRoundContext context, ServerPlayerEntity player) {
        Canvas canvas = this.canvases.get(player.getUuid());
        if (canvas == null) {
            return;
        }
        player.setHealth(player.getMaxHealth());
        Vec3d position = switch (canvas.phase) {
            case VIEW -> location(context, -82, baseY(canvas.slot), baseZ(canvas.slot));
            case PENALTY -> penaltyPosition(context, canvas.slot);
            case PLAY -> ownPosition(context, canvas.slot);
            case COMPLETE -> spectatorPosition(context, canvas.watching);
        };
        context.teleport(player, position);
    }

    @Override
    public void finish(PartyRoundContext context) {
        this.updateScores(context);
        this.close(context);
    }

    @Override
    public void close(PartyRoundContext context) {
        for (ServerPlayerEntity player : context.players()) {
            player.removeStatusEffect(StatusEffects.INVISIBILITY);
        }
    }

    @Override
    public String scoreDescription(PartyRoundContext context, UUID player) {
        Canvas canvas = this.canvases.get(player);
        return canvas == null ? "0/15" : canvas.progress + "/15";
    }

    @Override
    public String status(PartyRoundContext context) {
        long finished = context.players().stream().filter(player -> this.canvases.containsKey(player.getUuid()))
                .filter(player -> this.canvases.get(player.getUuid()).phase == Phase.COMPLETE).count();
        return "§e记忆长廊 · 已完成 " + finished + "/" + context.players().size();
    }

    private void beginView(PartyRoundContext context, ServerPlayerEntity player, Canvas canvas, int ticks) {
        canvas.phase = Phase.VIEW;
        canvas.phaseTicks = ticks;
        canvas.jumpStat = player.getStatHandler().getStat(Stats.CUSTOM, Stats.JUMP);
        clearInventory(player);
        keepInvisible(player);
        player.setYaw(-90);
        player.setPitch(0);
        context.teleport(player, location(context, -82, baseY(canvas.slot), baseZ(canvas.slot)));
    }

    private void updateScores(PartyRoundContext context) {
        int count = Math.max(1, Math.max(context.initialPlayerCount(), this.canvases.size()));
        for (Map.Entry<UUID, Canvas> entry : this.canvases.entrySet()) {
            Canvas own = entry.getValue();
            int earlier = 0;
            for (Canvas other : this.canvases.values()) {
                if (other.lastProgressTick > own.lastProgressTick) {
                    earlier++;
                }
            }
            context.score(entry.getKey(), own.progress * count + earlier);
        }
    }

    private void setupSpectatorSigns(PartyRoundContext context) {
        boolean individual = context.initialPlayerCount() <= 4;
        for (Canvas canvas : this.canvases.values()) {
            int slot = canvas.slot;
            int x = -67 + 14 * (slot % 5);
            int y = baseY(slot) + 7;
            int z = baseZ(slot);
            if (individual) {
                int[] zOffsets = {-2, -1, 1, 2};
                for (Canvas target : this.canvases.values()) {
                    int targetSlot = target.slot;
                    if (targetSlot < zOffsets.length) {
                        this.sign(context, context.sourceBlock(x, y, z + zOffsets[targetSlot]),
                                targetSlot, "观看玩家 " + (targetSlot + 1));
                    }
                }
            } else {
                this.sign(context, context.sourceBlock(x, y, z - 1), -1, "上一位玩家");
                this.sign(context, context.sourceBlock(x, y, z + 1), -2, "下一位玩家");
            }
        }
    }

    private void sign(PartyRoundContext context, BlockPos position, int target, String label) {
        context.set(position, Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.WEST));
        this.spectatorSigns.put(position, target);
        if (context.world().getBlockEntity(position) instanceof SignBlockEntity sign) {
            sign.setText(new SignText().withMessage(1, Text.literal(label))
                    .withMessage(2, Text.literal("右键切换观战")), true);
            sign.setWaxed(true);
            sign.markDirty();
            context.world().updateListeners(position, sign.getCachedState(), sign.getCachedState(), 3);
        }
    }

    private void spectate(PartyRoundContext context, ServerPlayerEntity player, Canvas canvas, int target) {
        List<Integer> slots = context.players().stream().filter(other -> this.canvases.containsKey(other.getUuid()))
                .map(other -> this.canvases.get(other.getUuid()).slot).sorted().toList();
        if (slots.isEmpty()) {
            return;
        }
        if (target < 0) {
            int index = slots.indexOf(canvas.watching);
            target = slots.get(Math.floorMod(index + (target == -1 ? -1 : 1), slots.size()));
        }
        if (slots.contains(target)) {
            canvas.watching = target;
            context.teleport(player, spectatorPosition(context, target));
        }
    }

    private static int drawOre(PartyRoundContext context, Canvas canvas) {
        int choice = context.random().nextInt(15 - canvas.progress);
        for (int ore = 0; ore < canvas.remaining.length; ore++) {
            choice -= canvas.remaining[ore];
            if (choice < 0) {
                return ore;
            }
        }
        throw new IllegalStateException("Memory Lane remaining ore count does not match progress");
    }

    private static void keepInvisible(ServerPlayerEntity player) {
        StatusEffectInstance effect = player.getStatusEffect(StatusEffects.INVISIBILITY);
        if (effect == null || effect.getDuration() <= 10) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 30, 0, false, false, false));
        }
    }

    private static void showOre(ServerPlayerEntity player, int ore) {
        for (int slot = 0; slot < 9; slot++) {
            if (!player.getInventory().getStack(slot).isOf(ORES[ore].asItem())) {
                player.getInventory().setStack(slot, ORES[ore].asItem().getDefaultStack());
            }
        }
        player.currentScreenHandler.sendContentUpdates();
    }

    private static void clearInventory(ServerPlayerEntity player) {
        player.getInventory().clear();
        player.currentScreenHandler.sendContentUpdates();
    }

    private static int baseY(int slot) { return slot < 5 ? 40 : 37; }
    private static int baseZ(int slot) { return slot < 5 ? -311 : -551; }
    private static int seconds(int ticks) { return Math.max(0, (ticks + 19) / 20); }
    private static BlockPos wallPosition(PartyRoundContext context, int slot, int cell) {
        return context.sourceBlock(-64 + 14 * (slot % 5), baseY(slot) + cell / 5, baseZ(slot) + 2 - cell % 5);
    }
    private static Vec3d ownPosition(PartyRoundContext context, int slot) {
        return location(context, -68 + 14 * (slot % 5), baseY(slot), baseZ(slot));
    }
    private static Vec3d spectatorPosition(PartyRoundContext context, int slot) {
        return location(context, -69 + 14 * (slot % 5), baseY(slot) + 6, baseZ(slot));
    }
    /** 原 /tp 的整数 X/Z 参数由 Vec3ArgumentType 居中，Y 不加半格。 */
    private static Vec3d location(PartyRoundContext context, int x, int y, int z) {
        return context.source(x + 0.5, y, z + 0.5);
    }
    private static Vec3d penaltyPosition(PartyRoundContext context, int slot) {
        return slot < 5 ? location(context, -83, 40, -304) : location(context, -79, 38, -544);
    }
}
