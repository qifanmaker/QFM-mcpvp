package com.example.pvp.match;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 外部地图玩法的原生运行时。原地图的全服命令不能在共享大厅执行，
 * 因此只把本场玩家、实体和区域交给独立会话，生命周期由 Match 统一管理。
 */
public interface PortedGameSession {
    List<BlockPos> spawnPositions();
    void stageMap();
    void start();
    void tick();
    void onDeath(ServerPlayerEntity player);
    default void onDeath(ServerPlayerEntity player, DamageSource source) { onDeath(player); }
    void onLeave(ServerPlayerEntity player);
    void onEnd();
    void clearArena();
    Set<UUID> winners();
    List<String> scoreboardLines();

    /** 排名已经公布时，即使全员离场仍需完成战绩结算。 */
    default boolean hasFinalResult() { return false; }

    default boolean allowsDamage(LivingEntity victim, DamageSource source) { return false; }
    /** 命中后记录原小游戏的传递或击杀归属；不能依赖全服伤害计分板。 */
    default void onDamage(LivingEntity victim, DamageSource source, float taken) { }
    default boolean ownsEntity(Entity entity) { return false; }
    default void onEntityLoad(Entity entity) { }
    default void onMobDeath(LivingEntity entity, DamageSource source) { }
    default boolean canBreak(ServerPlayerEntity player, BlockPos pos) { return false; }
    default boolean canPlace(ServerPlayerEntity player, BlockPos pos) { return false; }
    default void onPlaced(ServerPlayerEntity player, BlockPos pos) { }
    default ActionResult useItem(ServerPlayerEntity player, Hand hand) { return ActionResult.PASS; }
    default ActionResult useBlock(ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        return ActionResult.PASS;
    }
    default ActionResult attackBlock(ServerPlayerEntity player, BlockPos pos) {
        return ActionResult.PASS;
    }
}
