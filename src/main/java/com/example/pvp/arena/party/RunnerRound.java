package com.example.pvp.arena.party;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版 415 Runner，依据 runner{,_start,_tick,_tick2}, runnerstillcheck 与 end_runner。
 * 原函数使用全服 marker/armor_stand 和统计项；改为本场状态，避免清理其他模式的实体和记分板。
 * 保留原两套上下双层场景、踩踏后 40 tick 坍塌、致命落块、行走/潜行和停步判定。
 * 原说明提到落块破坏下层地板，但包内实际代码只清理 Y=5/12/-38 的落地彩块，
 * 原场地也无对应命令方块；因此按可验证的实际行为保留橡木地板，不发明穿透破坏规则。
 * 自查：统计读增量而不重置玩家永久统计；同 tick 淘汰一起计分；所有原生落块明确归属并清场；
 * small 模板覆盖前先清空 large 的外围；不改共享世界的 fallDamage 或其他全服设置。
 */
public final class RunnerRound implements PartyRound {
    private static final Block[] COLORS = {
            Blocks.BLUE_CONCRETE, Blocks.RED_CONCRETE, Blocks.LIME_CONCRETE, Blocks.YELLOW_CONCRETE,
            Blocks.CYAN_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.RED_TERRACOTTA, Blocks.GREEN_CONCRETE,
            Blocks.PURPLE_CONCRETE, Blocks.BLUE_TERRACOTTA
    };
    private record Crumbling(BlockPos position, BlockState state, int fallsAt) { }
    private static final class Movement {
        Vec3d checkpoint;
        int previousSneak;
        int previousWalk;
        int sneak;
        int walk;

        Movement(ServerPlayerEntity player) {
            this.checkpoint = player.getPos();
            this.previousSneak = player.getStatHandler().getStat(Stats.CUSTOM, Stats.SNEAK_TIME);
            this.previousWalk = player.getStatHandler().getStat(Stats.CUSTOM, Stats.WALK_ONE_CM);
        }
    }

    /**
     * 使用原生 falling_block 的物理与落地伤害，而不模拟一个近似的粒子障碍。
     * 只读入本类构造的固定方块 NBT；不加载外部实体或命令数据。
     */
    private static final class RunnerBlock extends FallingBlockEntity {
        RunnerBlock(ServerWorld world, Vec3d position, BlockState state) {
            super(EntityType.FALLING_BLOCK, world);
            NbtCompound data = new NbtCompound();
            data.put("BlockState", NbtHelper.fromBlockState(state));
            data.putInt("Time", 1);
            data.putBoolean("DropItem", false);
            this.readCustomDataFromNbt(data);
            this.setPosition(position);
            this.setVelocity(Vec3d.ZERO);
            this.setFallingBlockPos(BlockPos.ofFloored(position));
            this.setHurtEntities(99.0f, 999);
            this.fallDistance = 99.0f;
            this.setSilent(true);
        }
    }

    private final Map<UUID, Movement> movement = new HashMap<>();
    private final List<Crumbling> crumbling = new ArrayList<>();
    private final Map<UUID, RunnerBlock> falling = new HashMap<>();
    private final Map<UUID, String> pendingDeaths = new LinkedHashMap<>();
    private final Set<UUID> active = new HashSet<>();
    private int stillCheck;
    private boolean started;

    @Override
    public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(new BlockPos(184, 12, 8));
        context.geometry("runner_shell");
        // 原 clone 会连同空气覆盖；蓝图只存非空气格，必须明确抹去 shell 中旧 large 场地。
        for (BlockPos position : BlockPos.iterate(context.sourceBlock(173, 4, -3), context.sourceBlock(194, 11, 18))) {
            context.stage(position, Blocks.AIR.getDefaultState());
        }
        boolean small = context.initialPlayerCount() <= 4;
        context.geometry(small ? "runner_small" : "runner_large",
                context.sourceBlock(0, 0, 0).add(0, 61, small ? 32 : 2));
        context.timeLimit(0);
        int index = 0;
        List<ServerPlayerEntity> players = context.players();
        for (ServerPlayerEntity player : players) {
            Vec3d spawn;
            if (small) {
                int[][] positions = {{186, 5}, {181, 5}, {181, 10}, {186, 10}};
                spawn = context.source(positions[index][0] + 0.5, 12, positions[index][1] + 0.5);
            } else {
                // player_circle 使用整数除法 360/N，并从南边开始，逐位朝中心排列。
                double angle = Math.toRadians(index * (360 / players.size()));
                spawn = context.source(184 - 8 * Math.sin(angle), 12, 8 + 8 * Math.cos(angle));
            }
            context.spawn(player.getUuid(), spawn);
            index++;
        }
    }

    @Override
    public void start(PartyRoundContext context) {
        this.started = true;
        for (ServerPlayerEntity player : context.alive()) {
            this.active.add(player.getUuid());
            this.movement.put(player.getUuid(), new Movement(player));
            player.setYaw(yawToCenter(context, player));
            context.teleport(player, player.getPos());
        }
        context.announce("§e疾跑者：踩过的地板 2 秒后坍塌，落块会致命。持续疾跑或跳跃，最后存活者获胜。"
                + "\n§7停步、累计步行 2 米或潜行半秒会出局；移动判定每半秒会少量回退累计值。");
        context.title("§a跑！", "§f保持疾跑，避开落块");
    }

    @Override
    public void tick(PartyRoundContext context, int elapsed) {
        this.cleanupLanded(context);
        this.tickFloor(context, elapsed);
        boolean checkStill = elapsed >= 40 && ++this.stillCheck >= 10;
        if (checkStill) {
            this.stillCheck = 0;
        }
        for (ServerPlayerEntity player : context.alive()) {
            Movement state = this.movement.get(player.getUuid());
            if (state == null || !this.active.contains(player.getUuid())) {
                continue;
            }
            int sneak = player.getStatHandler().getStat(Stats.CUSTOM, Stats.SNEAK_TIME);
            int walk = player.getStatHandler().getStat(Stats.CUSTOM, Stats.WALK_ONE_CM);
            state.sneak += Math.max(0, sneak - state.previousSneak);
            state.walk += Math.max(0, walk - state.previousWalk);
            state.previousSneak = sneak;
            state.previousWalk = walk;
            if (checkStill) {
                if (state.checkpoint.squaredDistanceTo(player.getPos()) <= 0.01) {
                    this.pendingDeaths.putIfAbsent(player.getUuid(), "停步");
                }
                state.checkpoint = player.getPos();
                // 原 stillcheck 先回退计数，再由 runner_tick2 判定门槛；可回退到负数。
                if (state.sneak > 0) {
                    state.sneak -= 3;
                }
                if (state.walk > 0) {
                    state.walk -= 20;
                }
            }
            if (state.sneak >= 10) {
                this.pendingDeaths.putIfAbsent(player.getUuid(), "潜行");
            } else if (state.walk >= 200) {
                this.pendingDeaths.putIfAbsent(player.getUuid(), "步行");
            } else if (player.getY() < context.source(0, -36, 0).y) {
                // data get Pos[1] 的整数结果向下取整，原 ypos <= -37 对应 Y < -36。
                this.pendingDeaths.putIfAbsent(player.getUuid(), "掉出场地");
            }
        }
        this.settleDeaths(context);
    }

    private void tickFloor(PartyRoundContext context, int elapsed) {
        List<Crumbling> fresh = new ArrayList<>();
        for (ServerPlayerEntity player : context.alive()) {
            BlockPos below = BlockPos.ofFloored(player.getX(), player.getY() - 1, player.getZ());
            if (this.active.contains(player.getUuid()) && inFloor(context, below)
                    && context.world().getBlockState(below).isOf(Blocks.OAK_PLANKS)) {
                fresh.add(new Crumbling(below, COLORS[Math.floorMod(context.slot(player.getUuid()), COLORS.length)].getDefaultState(),
                        elapsed + 39));
            }
        }
        // 原函数先同时记录所有人的 marker，再统一染色；同格同时踩到可产生多个落块。
        this.crumbling.addAll(fresh);
        Iterator<Crumbling> iterator = this.crumbling.iterator();
        while (iterator.hasNext()) {
            Crumbling tile = iterator.next();
            if (elapsed >= tile.fallsAt()) {
                context.set(tile.position(), Blocks.AIR.getDefaultState());
                // 原函数以一个未设置 Facing 的 item_frame 对齐坐标，再在其下方一格生成落块。
                // 在内存中使用同样的朝向定位即可，不必向世界创建和销毁临时画框实体。
                ItemFrameEntity alignment = new ItemFrameEntity(context.world(), tile.position(), Direction.DOWN);
                RunnerBlock drop = new RunnerBlock(context.world(), alignment.getPos().add(0, -1, 0), tile.state());
                if (context.world().spawnEntity(drop)) {
                    this.falling.put(drop.getUuid(), drop);
                }
                iterator.remove();
            } else if (!context.world().getBlockState(tile.position()).equals(tile.state())) {
                context.set(tile.position(), tile.state());
            }
        }
    }

    private void cleanupLanded(PartyRoundContext context) {
        Iterator<RunnerBlock> iterator = this.falling.values().iterator();
        while (iterator.hasNext()) {
            RunnerBlock block = iterator.next();
            if (block.getY() <= context.source(0, -37, 0).y) {
                block.discard();
            }
            if (!block.isRemoved()) {
                continue;
            }
            BlockPos position = block.getBlockPos();
            int sourceY = position.getY() - context.sourceBlock(0, 0, 0).getY();
            if (context.world().getBlockState(position).equals(block.getBlockState())) {
                // 原生实体直接落盘的格子也登记到会话；罕见重叠落块堆到其他高度时同样能清场。
                context.set(position, sourceY == 5 || sourceY == 12 || sourceY == -38
                        ? Blocks.AIR.getDefaultState() : block.getBlockState());
            }
            iterator.remove();
        }
    }

    private void settleDeaths(PartyRoundContext context) {
        if (!this.started) {
            return;
        }
        // 掉线也是永久出局；对同一 tick 的多名出局者只给剩余玩家加分，避免遍历顺序决定名次。
        Set<UUID> online = new HashSet<>();
        for (ServerPlayerEntity player : context.players()) {
            online.add(player.getUuid());
        }
        for (UUID uuid : this.active) {
            if (!online.contains(uuid)) {
                this.pendingDeaths.putIfAbsent(uuid, "离场");
            }
        }
        int eliminated = 0;
        for (Map.Entry<UUID, String> entry : this.pendingDeaths.entrySet()) {
            if (!this.active.remove(entry.getKey())) {
                continue;
            }
            eliminated++;
            this.movement.remove(entry.getKey());
            for (ServerPlayerEntity player : context.players()) {
                if (player.getUuid().equals(entry.getKey())) {
                    context.eliminate(player, "§c你因" + entry.getValue() + "出局，正在观看本场比赛。");
                    context.announce("§7" + player.getName().getString() + " 因" + entry.getValue() + "出局。");
                    break;
                }
            }
        }
        this.pendingDeaths.clear();
        if (eliminated > 0) {
            for (UUID uuid : this.active) {
                context.addScore(uuid, eliminated);
            }
        }
        if (this.active.size() <= 1) {
            context.finish();
        }
    }

    @Override
    public void death(PartyRoundContext context, ServerPlayerEntity player) {
        if (this.active.contains(player.getUuid())) {
            // ALLOW_DEATH 被会话拦截；先防止原版删除实体，下一 tick 统一决定同时淘汰的排名。
            player.setHealth(player.getMaxHealth());
            this.pendingDeaths.putIfAbsent(player.getUuid(), "落块或摔落伤害");
        }
    }

    @Override
    public void leave(PartyRoundContext context, ServerPlayerEntity player) {
        if (this.active.contains(player.getUuid())) {
            this.pendingDeaths.putIfAbsent(player.getUuid(), "离场");
        }
    }

    @Override
    public boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        return this.started && victim instanceof ServerPlayerEntity player && this.active.contains(player.getUuid())
                && !this.pendingDeaths.containsKey(player.getUuid())
                && (source.isOf(DamageTypes.FALL) || source.isOf(DamageTypes.OUT_OF_WORLD)
                || source.getSource() != null && this.ownsEntity(source.getSource()));
    }

    @Override
    public boolean ownsEntity(Entity entity) {
        return this.falling.containsKey(entity.getUuid());
    }

    @Override
    public void finish(PartyRoundContext context) {
        this.close(context);
    }

    @Override
    public void close(PartyRoundContext context) {
        this.cleanupLanded(context);
        for (RunnerBlock block : this.falling.values()) {
            block.discard();
        }
        this.falling.clear();
        this.crumbling.clear();
        this.started = false;
    }

    @Override
    public String scoreDescription(PartyRoundContext context, UUID player) {
        return context.score(player) + " 分";
    }

    @Override
    public String status(PartyRoundContext context) {
        return "§e疾跑者 · 剩余 " + this.active.size() + " 人";
    }

    private static boolean inFloor(PartyRoundContext context, BlockPos position) {
        BlockPos minimum = context.sourceBlock(173, 4, -3);
        BlockPos maximum = context.sourceBlock(194, 11, 18);
        return (position.getY() == minimum.getY() || position.getY() == maximum.getY())
                && position.getX() >= minimum.getX() && position.getX() <= maximum.getX()
                && position.getZ() >= minimum.getZ() && position.getZ() <= maximum.getZ();
    }

    private static float yawToCenter(PartyRoundContext context, ServerPlayerEntity player) {
        Vec3d target = context.source(184, 12, 8);
        return (float) Math.toDegrees(Math.atan2(-(target.x - player.getX()), target.z - player.getZ()));
    }
}
