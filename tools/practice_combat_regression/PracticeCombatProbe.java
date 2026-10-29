package com.example.pvp.validation;

import com.example.pvp.PvPMod;
import com.example.pvp.practice.PracticeBot;
import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameRules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Test-only event observer: compile into the isolated mod snapshot, never the
 * production mod. Raw attack input and post-armor HP distinguish real vanilla
 * criticals from an AI status label. The next-tick HP reset permits a sample of
 * natural AI swings without injecting attacks or changing attack attributes.
 */
public final class PracticeCombatProbe implements ModInitializer {
    private final Gson gson = new Gson();
    private final Map<UUID, Float> before = new HashMap<>();
    private final Set<UUID> healNextTick = new HashSet<>();
    private int lastAirSample;

    @Override
    public void onInitialize() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
            if (victim instanceof ServerPlayerEntity player
                    && source.getAttacker() instanceof ServerPlayerEntity attacker
                    && PracticeBot.isBot(attacker)) before.put(player.getUuid(), player.getHealth());
            return true;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
            if (!(victim instanceof ServerPlayerEntity player)
                    || !(source.getAttacker() instanceof ServerPlayerEntity attacker)
                    || !PracticeBot.isBot(attacker)) return;
            Map<String, Object> row = state(attacker);
            row.put("event", "hit");
            row.put("base", base);
            row.put("taken", taken);
            row.put("blocked", blocked);
            row.put("directMelee", source.getSource() == attacker);
            float prior = before.getOrDefault(player.getUuid(), player.getHealth());
            row.put("healthBefore", prior);
            row.put("healthAfter", player.getHealth());
            row.put("healthLoss", prior - player.getHealth());
            row.put("armor", player.getArmor());
            row.put("toughness", player.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS));
            write(row);
            healNextTick.add(player.getUuid());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (healNextTick.remove(player.getUuid())) player.setHealth(player.getMaxHealth());
                if (PvPMod.PRACTICE == null) continue;
                var session = PvPMod.PRACTICE.getSession(player);
                if (session == null || session.getPvpDuel() == null) continue;
                player.getWorld().getGameRules().get(GameRules.NATURAL_REGENERATION).set(false, server);
                var bot = session.getPvpDuel().getBotEntity();
                if (bot != null && !bot.isOnGround() && server.getTicks() - lastAirSample >= 2) {
                    lastAirSample = server.getTicks();
                    Map<String, Object> row = state(bot);
                    row.put("event", "airborne");
                    write(row);
                }
            }
        });
    }

    private Map<String, Object> state(ServerPlayerEntity attacker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tick", attacker.getServer().getTicks());
        row.put("weapon", attacker.getMainHandStack().getItem().toString());
        row.put("attackDamageAttribute", attacker.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE));
        row.put("attackSpeedAttribute", attacker.getAttributeValue(EntityAttributes.GENERIC_ATTACK_SPEED));
        row.put("fallDistance", attacker.fallDistance);
        row.put("ground", attacker.isOnGround());
        row.put("sprinting", attacker.isSprinting());
        row.put("velocityY", attacker.getVelocity().y);
        return row;
    }

    private void write(Map<String, Object> row) {
        try {
            Files.writeString(Path.of("practice-combat-events.jsonl"), gson.toJson(row) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception error) {
            throw new IllegalStateException("Combat regression observer could not save evidence", error);
        }
    }
}
