package com.example.pvp.match;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaWorldManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** 将地图交互限定到对应会话；先于通用 TNT、指南针和其他模式回调注册。 */
public final class PortedGameEvents {
    private PortedGameEvents() { }

    public static Match matchOf(Entity entity) {
        if (entity == null || PvPMod.MATCH == null) return null;
        if (entity instanceof ServerPlayerEntity player) return PvPMod.MATCH.getMatchFor(player);
        for (Match match : PvPMod.MATCH.getMatches()) {
            if (match.portedSession() != null && match.portedSession().ownsEntity(entity)) return match;
        }
        return null;
    }

    private static boolean isPorted(Match match) {
        return match != null && match.portedSession() != null;
    }

    /** 其他场次的玩家也不能进入移植区域拆图或使用容器。 */
    public static boolean canAccessPosition(Entity player, World world, BlockPos pos) {
        if (PvPMod.MATCH == null || world.getRegistryKey() != ArenaWorldManager.ARENA_WORLD_KEY) return true;
        Match current = matchOf(player);
        for (Match owner : PvPMod.MATCH.getMatches()) {
            if (owner.ownsPortedPosition(pos) && owner != current) return false;
        }
        return true;
    }

    /** 额外速度注入绕过 ALLOW_DAMAGE；移植模式保留自己的原版机制，禁用 PvP 额外击退。 */
    public static boolean allowsExtraKnockback(Entity attacker, Entity victim) {
        // 人机练习不创建 Match，零伤害的鱼竿/雪球也不能越场干扰或跳过准备阶段。
        if (PvPMod.PRACTICE != null && !PvPMod.PRACTICE.allowsCombatInteraction(attacker, victim)) return false;
        return !isPorted(matchOf(attacker)) && !isPorted(matchOf(victim));
    }

    public static boolean allowsDamage(LivingEntity victim, DamageSource source) {
        Match victimMatch = matchOf(victim);
        Match attackerMatch = matchOf(source.getAttacker());
        if (!isPorted(victimMatch) && !isPorted(attackerMatch)) return true;
        if (source.getAttacker() != null && attackerMatch != victimMatch) return false;
        return isPorted(victimMatch) && victimMatch.getState() == MatchState.ACTIVE
                && victimMatch.portedSession().allowsDamage(victim, source);
    }

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            Match match = matchOf(player);
            var stack = player.getStackInHand(hand);
            if (!isPorted(match) || !(player instanceof ServerPlayerEntity sp)) {
                return TypedActionResult.pass(stack);
            }
            if (match.getState() != MatchState.ACTIVE || match.isEliminated(sp.getUuid())) {
                return TypedActionResult.fail(stack);
            }
            ActionResult result = match.portedSession().useItem(sp, hand);
            // 保留 CONSUME 等原始结果，不能将无挥手的交互重新包装成 SUCCESS。
            return new TypedActionResult<>(result, stack);
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!canAccessPosition(player, world, hit.getBlockPos())) return ActionResult.FAIL;
            Match match = matchOf(player);
            if (!isPorted(match) || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (match.getState() != MatchState.ACTIVE || match.isEliminated(sp.getUuid())) return ActionResult.FAIL;
            ActionResult item = match.portedSession().useItem(sp, hand);
            return item != ActionResult.PASS ? item : match.portedSession().useBlock(sp, hand, hit);
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            Match match = matchOf(player);
            // 准星指向玩家时右键仍可开镜，且不能落回原版工具行为。
            if (player instanceof ServerPlayerEntity sp && match != null
                    && match.portedSession() instanceof com.example.pvp.mygo.MyGoSession session) {
                if (match.getState() != MatchState.ACTIVE) return ActionResult.FAIL;
                session.useItem(sp, hand);
                return ActionResult.FAIL;
            }
            return isPorted(match) || isPorted(matchOf(entity)) ? ActionResult.FAIL : ActionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            Match attackerMatch = matchOf(player);
            Match victimMatch = matchOf(entity);
            if (player instanceof ServerPlayerEntity sp && attackerMatch != null
                    && attackerMatch.portedSession() instanceof com.example.pvp.mygo.MyGoSession session) {
                session.attack(sp, entity);
                return ActionResult.FAIL;
            }
            if (!isPorted(attackerMatch) && !isPorted(victimMatch)) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp) || !(entity instanceof LivingEntity victim)) {
                return ActionResult.FAIL;
            }
            return allowsDamage(victim, sp.getDamageSources().playerAttack(sp)) ? ActionResult.PASS : ActionResult.FAIL;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!canAccessPosition(player, world, pos)) return ActionResult.FAIL;
            Match match = matchOf(player);
            if (!isPorted(match) || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (match.getState() != MatchState.ACTIVE || match.isEliminated(sp.getUuid())) return ActionResult.FAIL;
            return match.portedSession().attackBlock(sp, pos);
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!canAccessPosition(player, world, pos)) return false;
            Match match = matchOf(player);
            return !isPorted(match) || player instanceof ServerPlayerEntity sp
                    && match.getState() == MatchState.ACTIVE && !match.isEliminated(sp.getUuid())
                    && match.portedSession().canBreak(sp, pos);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> allowsDamage(entity, source));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            Match match = matchOf(entity);
            if (isPorted(match) && !(entity instanceof ServerPlayerEntity)) {
                match.portedSession().onMobDeath(entity, source);
            }
        });
    }
}
