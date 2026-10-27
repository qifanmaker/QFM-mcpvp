package com.example.pvp.arena.party;

import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版 401 Dropper（dropper_generate/2/3、dropper_start/tick、end_dropper）的原生移植。
 * 使用原包的起台、16 色环和落水池；逐层随机移动环心，正常难度间距 10+2*min(level,5)。
 * 不执行原包全服传送/属性/命令方块；过时的 dropper0..4 四塔玩法不属于当前 401。
 *
 * <p>修改前自查：Y=319 到 -61 必须保留原高度，死亡只重试，不淘汰；最远深度跨重试保留；
 * 前 9 秒完成者按原 end_dropper 同分，所有清场写入和实体状态由本场 Context 跟踪。
 */
public final class DropperRound implements PartyRound {
    private static final int DURATION = 1_800;
    private static final int FIRST_ATTEMPT_TICKS = 180;
    private static final int TOP_Y = 319;
    private static final int[][] FOUR_SPAWNS = {{-181, -41}, {-184, -44}, {-187, -41}, {-184, -38}};
    private static final int[][] TEN_SPAWNS = {
            {-181, -40}, {-183, -44}, {-187, -42}, {-185, -38}, {-181, -42},
            {-185, -44}, {-187, -40}, {-183, -38}, {-184, -43}, {-184, -39}
    };
    private static final float[] SPAWN_YAW = {90, 0, -90, 180, 90, 0, -90, 180, 0, 180};

    private final PartyRoundPlayerState state = new PartyRoundPlayerState();
    private final Map<UUID, Vec3d> starts = new HashMap<>();
    private final Map<UUID, Float> startYaw = new HashMap<>();
    private final Map<UUID, Integer> deepest = new HashMap<>();
    private final Map<UUID, Integer> completionTick = new HashMap<>();
    private final Set<UUID> firstAttempt = new HashSet<>();
    private int playerCount;

    @Override
    public void stage(PartyRoundContext context) throws IOException {
        this.playerCount = context.initialPlayerCount();
        // Context 的 anchorY 与目标 centerY 相等，令 source Y 不平移，不超出 -64..319 世界高度。
        context.sourceAnchor(new BlockPos(-184, context.center().getY(), -41));
        context.timeLimit(DURATION);
        context.geometry("dropper_pool");
        context.geometry("dropper_start_platform", context.sourceBlock(-48, 310, -1));

        PartyMapBlueprint ringPalette = PartyMapBlueprint.load("dropper_rings");
        int x = -184;
        int z = -41;
        int y = 318;
        int level = 0;
        do {
            y -= 10 + 2 * Math.min(level, 5);
            List<int[]> directions = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx == 0 && dz == 0) || (dx > 0 && x > -180) || (dx < 0 && x < -188)
                            || (dz > 0 && z > -37) || (dz < 0 && z < -45)) continue;
                    directions.add(new int[] {dx, dz});
                }
            }
            int[] direction = directions.get(context.random().nextInt(directions.size()));
            x += direction[0];
            z += direction[1];
            int templateY = 26 - level % 16;
            int targetY = y;
            // 原每层 clone 一张 25×1×25 环；同一个几何包中其他 15 层不写入。
            ringPalette.forEachTranslated(context.sourceBlock(x + 132, y - templateY, z + 36), (position, block) -> {
                if (position.getY() == targetY) context.stage(position, block);
            });
            level++;
        } while (y >= -42);

        // dropper_generate3 在环生成后封住四壁，防止从环外绕过；尺寸逐格对应原 fill。
        for (int yy = -63; yy <= 317; yy++) {
            for (int zz = -49; zz <= -33; zz++) {
                if (yy >= -62) context.stage(context.sourceBlock(-176, yy, zz), Blocks.SMOOTH_QUARTZ.getDefaultState());
                context.stage(context.sourceBlock(-192, yy, zz), Blocks.SMOOTH_QUARTZ.getDefaultState());
            }
            if (yy >= -62) {
                for (int xx = -191; xx <= -177; xx++) {
                    context.stage(context.sourceBlock(xx, yy, -49), Blocks.SMOOTH_QUARTZ.getDefaultState());
                    context.stage(context.sourceBlock(xx, yy, -33), Blocks.SMOOTH_QUARTZ.getDefaultState());
                }
            }
        }
        int[][] spawns = this.playerCount <= 4 ? FOUR_SPAWNS : TEN_SPAWNS;
        int index = 0;
        for (ServerPlayerEntity player : context.players()) {
            int[] source = spawns[index];
            // 原 tp 的 Vec3ArgumentType 会把绝对整数 X/Z 居中 +0.5；Y 和 clone 方块坐标不变。
            Vec3d spawn = context.source(source[0] + 0.5, TOP_Y, source[1] + 0.5);
            this.starts.put(player.getUuid(), spawn);
            this.startYaw.put(player.getUuid(), SPAWN_YAW[index++]);
            this.deepest.put(player.getUuid(), 0);
            context.spawn(player.getUuid(), spawn);
        }
    }

    @Override
    public void start(PartyRoundContext context) {
        for (ServerPlayerEntity player : context.players()) {
            this.state.attribute(player, EntityAttributes.GENERIC_JUMP_STRENGTH, 0);
            this.state.invisible(player);
            this.state.resetHealth(player);
            player.teleport(context.world(), player.getX(), player.getY(), player.getZ(), this.startYaw.get(player.getUuid()), 0);
        }
        context.title("§a向下跳！", "§f穿过圆环，落入最底层水池");
    }

    @Override
    public void tick(PartyRoundContext context, int elapsed) {
        List<ServerPlayerEntity> finishedNow = new ArrayList<>();
        for (ServerPlayerEntity player : context.alive()) {
            int depth = Math.max(0, TOP_Y - (int) Math.floor(player.getY()));
            this.deepest.merge(player.getUuid(), depth, Math::max);
            if (player.getY() <= -61 && context.world().getBlockState(player.getBlockPos()).isOf(Blocks.WATER)) {
                finishedNow.add(player);
            } else if (player.getY() < -64 || Math.abs(player.getX() - context.center().getX()) > 18
                    || Math.abs(player.getZ() - context.center().getZ()) > 18) {
                this.retry(context, player);
            }
        }
        // 同 tick 到达者保留并列，与原 checkpoint 的 temp_checkpoint_tie 一致。
        int remaining = this.playerCount - this.completionTick.size();
        for (ServerPlayerEntity player : finishedNow) {
            UUID uuid = player.getUuid();
            this.completionTick.put(uuid, elapsed);
            if (elapsed <= FIRST_ATTEMPT_TICKS) this.firstAttempt.add(uuid);
            this.deepest.merge(uuid, remaining, Integer::sum);
            context.completed(uuid);
            context.eliminate(player, "§a已完成极限跳水，正在观看其他玩家。");
            context.announce("§a" + player.getName().getString() + " 已完成 Dropper！");
        }
        this.updateScores(context);
        if (elapsed > FIRST_ATTEMPT_TICKS && context.alive().size() <= 1) context.finish();
    }

    private void updateScores(PartyRoundContext context) {
        for (UUID uuid : context.playerIds()) {
            if (this.firstAttempt.contains(uuid)) {
                // 原 end_dropper 将首 9 秒完成者 max_blocks_fallen=400、Points=players。
                context.score(uuid, 400 * this.playerCount + this.playerCount);
                continue;
            }
            int recencyRank = 0;
            int last = this.completionTick.getOrDefault(uuid, -1);
            for (UUID other : context.playerIds()) {
                if (this.completionTick.getOrDefault(other, -1) > last) recencyRank++;
            }
            context.score(uuid, this.deepest.getOrDefault(uuid, 0) * this.playerCount + recencyRank);
        }
    }

    @Override
    public void death(PartyRoundContext context, ServerPlayerEntity player) {
        this.retry(context, player);
    }

    private void retry(PartyRoundContext context, ServerPlayerEntity player) {
        if (context.isOut(player.getUuid()) || this.completionTick.containsKey(player.getUuid())) return;
        this.state.resetHealth(player);
        context.teleport(player, this.starts.get(player.getUuid()));
        player.teleport(context.world(), player.getX(), player.getY(), player.getZ(), this.startYaw.get(player.getUuid()), 0);
        this.state.attribute(player, EntityAttributes.GENERIC_JUMP_STRENGTH, 0);
        this.state.invisible(player);
        player.sendMessage(net.minecraft.text.Text.literal("§e重新从塔顶尝试；最远下落深度保留。"), true);
    }

    @Override
    public boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        return victim instanceof ServerPlayerEntity player && !context.isOut(player.getUuid())
                && (source.isOf(DamageTypes.FALL) || source.isOf(DamageTypes.OUT_OF_WORLD));
    }

    @Override
    public boolean allowsNaturalRegeneration() {
        return false;
    }

    @Override
    public void finish(PartyRoundContext context) {
        this.updateScores(context);
    }

    @Override
    public void leave(PartyRoundContext context, ServerPlayerEntity player) {
        this.state.restore(player);
    }

    @Override
    public void close(PartyRoundContext context) {
        context.players().forEach(this.state::restore);
    }

    @Override
    public String scoreDescription(PartyRoundContext context, UUID player) {
        return this.completionTick.containsKey(player) ? "已完赛" : this.deepest.getOrDefault(player, 0) + " 格";
    }
}
