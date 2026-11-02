package com.example.pvp.match;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.ArenaWorldManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
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
import net.minecraft.world.WorldAccess;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;

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

    /** 原图以原生物品规则设计；竞技场珍珠等 PvP 兼容补丁不能改写移植玩法。 */
    public static boolean usesNativeItemRules(Entity entity) {
        return isPorted(matchOf(entity));
    }

    public static boolean pearlLanded(net.minecraft.entity.projectile.thrown.EnderPearlEntity pearl, ServerPlayerEntity player) {
        if (PvPMod.MATCH == null) return false;
        Match match = PvPMod.MATCH.getMatchFor(player);
        return match != null && match.portedSession() instanceof MinecraftPartySession session
                && session.onPearlLanded(pearl, player);
    }

    /** null 沿用原世界规则；只有 Party 所属区域的原生方块掉落使用当前回合规则。 */
    public static Boolean blockDropOverride(World world, BlockPos pos) {
        if (!(world instanceof ArenaWorld arena)) return null;
        if (arena.residentMaps().isIdleAt(pos)) return false;
        MinecraftPartySession session = partySceneSessionAt(arena, pos);
        if (session != null) return session.allowsBlockDrops();
        return arena.residentMaps().contains(pos) ? false : null;
    }

    public static void terrainChanged(World world, BlockPos pos, BlockState state) {
        if (!(world instanceof ArenaWorld arena)) return;
        MinecraftPartySession session = partySceneSessionAt(arena, pos);
        if (session != null) session.onTerrainChanged(pos, state);
    }

    /** Permanent scenes need protection even while their lease has no Match. */
    public static boolean isPartyScenePosition(World world, BlockPos pos) {
        return world instanceof ArenaWorld arena
                && (arena.residentMaps().contains(pos) || partySceneSessionAt(arena, pos) != null);
    }

    private static MinecraftPartySession partySceneSessionAt(ArenaWorld world, BlockPos pos) {
        Object owner = world.residentMaps().ownerAt(pos);
        if (owner instanceof Match match && match.arenaWorld() == world
                && match.portedSession() instanceof MinecraftPartySession session) return session;
        // The dimension key alone does not identify a particular running world.
        // In-process restarts/tests can keep an old ArenaWorld instance alive.
        return PvPMod.MATCH != null && PvPMod.MATCH.getArenaManager().getWorld() == world
                ? PvPMod.MATCH.partySceneSessionAt(pos) : null;
    }

    public static boolean freezesPartyFluid(World world, BlockPos pos) {
        if (!(world instanceof ArenaWorld arena)) return false;
        if (arena.residentMaps().isIdleAt(pos)) return true;
        MinecraftPartySession session = partySceneSessionAt(arena, pos);
        return session != null ? !session.allowsFluidFlowAt(pos) : arena.residentMaps().contains(pos);
    }

    /** 原生水可以进入本场船舱，但不能穿越场地区域边界或侵入其他对局。 */
    public static boolean allowsFluidFlow(WorldAccess access, BlockPos target, Direction direction) {
        if (!(access instanceof ArenaWorld arena)) return true;
        BlockPos source = target.offset(direction.getOpposite());
        if (arena.residentMaps().isIdleAt(source) || arena.residentMaps().isIdleAt(target)) return false;
        MinecraftPartySession from = partySceneSessionAt(arena, source);
        MinecraftPartySession to = partySceneSessionAt(arena, target);
        if (from == null && to == null)
            return !arena.residentMaps().contains(source) && !arena.residentMaps().contains(target);
        return from != null && from == to && from.allowsFluidFlowAt(source) && from.allowsFluidFlowAt(target);
    }

    /** 其他场次的玩家也不能进入移植区域拆图或使用容器。 */
    public static boolean canAccessPosition(Entity player, World world, BlockPos pos) {
        var training = com.example.pvp.mygo.practice.MyGoPracticeManager.getOrNull();
        if (training != null && !training.canAccessPosition(player, world, pos)) return false;
        if (!(world instanceof ArenaWorld arena)) return true;
        Match current = matchOf(player);
        if (arena.residentMaps().contains(pos)) {
            Object owner = arena.residentMaps().ownerAt(pos);
            if (arena.residentMaps().isIdleAt(pos) || !(owner instanceof Match match)
                    || match != current || match.arenaWorld() != arena || player.getWorld() != world) return false;
        }
        if (PvPMod.MATCH == null || PvPMod.MATCH.getArenaManager().getWorld() != world) return true;
        for (Match owner : PvPMod.MATCH.getMatches()) {
            if (owner.ownsPortedPosition(pos) && owner != current) return false;
        }
        return true;
    }

    /** 额外速度注入绕过 ALLOW_DAMAGE；移植模式保留自己的原版机制，禁用 PvP 额外击退。 */
    public static boolean allowsExtraKnockback(Entity attacker, Entity victim) {
        var training = com.example.pvp.mygo.practice.MyGoPracticeManager.getOrNull();
        if (training != null && (training.isActor(attacker) || training.isActor(victim))) return false;
        // 人机练习不创建 Match，零伤害的鱼竿/雪球也不能越场干扰或跳过准备阶段。
        if (PvPMod.PRACTICE != null && !PvPMod.PRACTICE.allowsCombatInteraction(attacker, victim)) return false;
        return !isPorted(matchOf(attacker)) && !isPorted(matchOf(victim));
    }

    public static boolean allowsDamage(LivingEntity victim, DamageSource source) {
        // 练习枪战只接受本房 MyGoSession 的虚拟伤害；原版近战、环境和外场投掷物均不能绕过它。
        var training = com.example.pvp.mygo.practice.MyGoPracticeManager.getOrNull();
        if (training != null && (training.isActor(victim) || training.isActor(source.getAttacker()))) return false;
        Match victimMatch = matchOf(victim);
        Match attackerMatch = matchOf(source.getAttacker());
        // 原生风弹/落块可以没有 attacker，但其直接伤害实体仍归属于一个独立会话。
        if (attackerMatch == null) attackerMatch = matchOf(source.getSource());
        if (!isPorted(victimMatch) && !isPorted(attackerMatch)) return true;
        if ((source.getAttacker() != null || source.getSource() != null) && attackerMatch != victimMatch) return false;
        return isPorted(victimMatch) && victimMatch.getState() == MatchState.ACTIVE
                && victimMatch.portedSession().allowsDamage(victim, source);
    }

    public static void register() {
        // 原版 OITQ 在箭发出后立即设伤害；不能等 tick 扫描，否则近距离命中会抢先结算。
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof net.minecraft.entity.FallingBlockEntity block && PvPMod.MATCH != null
                    && world.getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY) {
                MinecraftPartySession session = PvPMod.MATCH.partySceneSessionAt(block.getFallingBlockPos());
                if (session != null) session.onTerrainEntityLoad(block);
            }
            Match match = matchOf(entity);
            if (isPorted(match)) match.portedSession().onEntityLoad(entity);
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayerEntity sp) {
                var training = trainingSession(sp);
                if (training != null) return new TypedActionResult<>(training.useItem(sp, hand), player.getStackInHand(hand));
            }
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
            if (player instanceof ServerPlayerEntity sp) {
                var training = trainingSession(sp);
                if (training != null) return training.useBlock(sp, hand, hit);
            }
            Match match = matchOf(player);
            if (!isPorted(match) || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (match.getState() != MatchState.ACTIVE || match.isEliminated(sp.getUuid())) return ActionResult.FAIL;
            // Party 的按钮/告示牌是独立输入；禁止空中物品使用不能提前吞掉方块点击。
            if (match.portedSession() instanceof MinecraftPartySession) return match.portedSession().useBlock(sp, hand, hit);
            ActionResult item = match.portedSession().useItem(sp, hand);
            return item != ActionResult.PASS ? item : match.portedSession().useBlock(sp, hand, hit);
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!canAccessPosition(player, world, entity.getBlockPos())) return ActionResult.FAIL;
            if (player instanceof ServerPlayerEntity sp) {
                var training = trainingSession(sp);
                if (training != null) { training.useItem(sp, hand); return ActionResult.FAIL; }
            }
            Match match = matchOf(player);
            // 准星指向玩家时右键仍可开镜，且不能落回原版工具行为。
            if (player instanceof ServerPlayerEntity sp && match != null
                    && match.portedSession() instanceof com.example.pvp.mygo.MyGoSession session) {
                if (match.getState() != MatchState.ACTIVE) return ActionResult.FAIL;
                session.useItem(sp, hand);
                return ActionResult.FAIL;
            }
            Match owner = matchOf(entity);
            if (!isPorted(match) && !isPorted(owner)) return ActionResult.PASS;
            // 原生船必须保留登乘入口；目标、玩家和场次仍须一一对应，不能借载具跨场交互。
            if (!(player instanceof ServerPlayerEntity sp) || !isPorted(match) || owner != match
                    || match.getState() != MatchState.ACTIVE || match.isEliminated(sp.getUuid())) return ActionResult.FAIL;
            return match.portedSession().useEntity(sp, hand, entity);
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!canAccessPosition(player, world, entity.getBlockPos())) return ActionResult.FAIL;
            if (player instanceof ServerPlayerEntity sp) {
                var training = trainingSession(sp);
                if (training != null) { training.attack(sp, entity); return ActionResult.FAIL; }
            }
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
            if (player instanceof ServerPlayerEntity sp && trainingSession(sp) != null) return ActionResult.FAIL;
            Match match = matchOf(player);
            if (!isPorted(match) || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (match.getState() != MatchState.ACTIVE || match.isEliminated(sp.getUuid())) return ActionResult.FAIL;
            return match.portedSession().attackBlock(sp, pos);
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!canAccessPosition(player, world, pos)) return false;
            if (player instanceof ServerPlayerEntity sp && trainingSession(sp) != null) return false;
            Match match = matchOf(player);
            return !isPorted(match) || player instanceof ServerPlayerEntity sp
                    && match.getState() == MatchState.ACTIVE && !match.isEliminated(sp.getUuid())
                    && match.portedSession().canBreak(sp, pos);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> allowsDamage(entity, source));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            Match match = matchOf(entity);
            if (isPorted(match) && match.getState() == MatchState.ACTIVE) {
                match.portedSession().onDamage(entity, source, taken);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            Match match = matchOf(entity);
            if (isPorted(match) && !(entity instanceof ServerPlayerEntity)) {
                match.portedSession().onMobDeath(entity, source);
            }
        });
    }

    private static com.example.pvp.mygo.MyGoSession trainingSession(ServerPlayerEntity player) {
        var training = com.example.pvp.mygo.practice.MyGoPracticeManager.getOrNull();
        return training == null ? null : training.session(player);
    }
}
