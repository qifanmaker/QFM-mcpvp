package com.example.pvp.arena.party;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.util.UUID;

/**
 * 每项原版小游戏独立保存规则；共享会话只负责投票、生命周期和最终排名。
 * 继续迁移时不再把全部游戏状态堆入同一会话，避免互相污染复活、计分与交互规则。
 */
public interface PartyRound {
    void stage(PartyRoundContext context) throws IOException;
    default GameMode gameMode() { return GameMode.ADVENTURE; }
    void start(PartyRoundContext context);
    void tick(PartyRoundContext context, int elapsed);
    default void death(PartyRoundContext context, ServerPlayerEntity player) {
        context.eliminate(player, "§c你已出局，正在观看本场比赛。");
    }
    default void death(PartyRoundContext context, ServerPlayerEntity player, DamageSource source) { death(context, player); }
    default void leave(PartyRoundContext context, ServerPlayerEntity player) { }
    /** 必须在排名冻结之前结清模块内部进度。 */
    default void finish(PartyRoundContext context) { }
    default void close(PartyRoundContext context) { }
    default String scoreDescription(PartyRoundContext context, UUID player) { return Integer.toString(context.score(player)); }
    default String status(PartyRoundContext context) { return null; }
    default boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) { return false; }
    default void damaged(PartyRoundContext context, LivingEntity victim, DamageSource source, float taken) { }
    default boolean ownsEntity(Entity entity) { return false; }
    default void entityLoaded(PartyRoundContext context, Entity entity) { }
    default boolean allowsNaturalRegeneration() { return true; }
    default ActionResult useItem(PartyRoundContext context, ServerPlayerEntity player, Hand hand) { return ActionResult.FAIL; }
    default ActionResult useBlock(PartyRoundContext context, ServerPlayerEntity player, Hand hand, BlockHitResult hit) { return ActionResult.FAIL; }
    default ActionResult attackBlock(PartyRoundContext context, ServerPlayerEntity player, BlockPos pos) { return ActionResult.FAIL; }
    default boolean canBreak(PartyRoundContext context, ServerPlayerEntity player, BlockPos pos) { return false; }
    default boolean canPlace(PartyRoundContext context, ServerPlayerEntity player, BlockPos pos) { return false; }
}
