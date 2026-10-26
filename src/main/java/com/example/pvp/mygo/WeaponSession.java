package com.example.pvp.mygo;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;

/**
 * MyGO-compatible weapon input contract shared by Dust II and gun SkyWars.
 *
 * <p>Reason: the vanilla client sends the same hand-swing, slot, reload and
 * drop packets for both modes, while the two modes must keep separate round
 * and FFA state. Keeping only this input/lifecycle contract shared avoids
 * coupling SkyWars to MyGO's teams, economy and bomb phases.</p>
 */
public interface WeaponSession {
    default void tick() { }
    default void onAttackInput(ServerPlayerEntity player, Hand hand) { }
    default void onAttackRelease(ServerPlayerEntity player) { }
    default boolean handleSyntheticAction(ServerPlayerEntity player, PlayerActionC2SPacket packet) { return false; }
    default void onSelectedSlotChanged(ServerPlayerEntity player) { }
    default void reload(ServerPlayerEntity player) { }
    default boolean drop(ServerPlayerEntity player) { return false; }
    default ActionResult attack(ServerPlayerEntity player, Entity entity) { return ActionResult.PASS; }
    default ActionResult useItem(ServerPlayerEntity player, Hand hand) { return ActionResult.PASS; }
    default ActionResult useBlock(ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        return useItem(player, hand);
    }
    default ActionResult attackBlock(ServerPlayerEntity player, BlockPos pos) { return ActionResult.PASS; }
    default boolean allowsDamage(net.minecraft.entity.LivingEntity victim,
                                 net.minecraft.entity.damage.DamageSource source) { return false; }
    default void start() { }
    default void onDeath(ServerPlayerEntity player) { }
    default void onLeave(ServerPlayerEntity player) { }
    default void onEnd() { }
}
