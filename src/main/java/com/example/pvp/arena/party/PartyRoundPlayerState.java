package com.example.pvp.arena.party;

import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 原包修改全服属性；原生模块仅保存、修改并恢复本场玩家自己的状态。 */
final class PartyRoundPlayerState {
    private final Map<UUID, Map<RegistryEntry<EntityAttribute>, Double>> attributes = new HashMap<>();

    void attribute(ServerPlayerEntity player, RegistryEntry<EntityAttribute> attribute, double value) {
        var instance = player.getAttributeInstance(attribute);
        if (instance != null) {
            this.attributes.computeIfAbsent(player.getUuid(), ignored -> new HashMap<>())
                    .putIfAbsent(attribute, instance.getBaseValue());
            instance.setBaseValue(value);
        }
    }

    void invisible(ServerPlayerEntity player) {
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, -1, 0, true, false));
    }

    void resetHealth(ServerPlayerEntity player) {
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.fallDistance = 0;
    }

    void restore(ServerPlayerEntity player) {
        Map<RegistryEntry<EntityAttribute>, Double> saved = this.attributes.remove(player.getUuid());
        if (saved != null) {
            saved.forEach((attribute, value) -> {
                var instance = player.getAttributeInstance(attribute);
                if (instance != null) instance.setBaseValue(value);
            });
        }
        player.removeStatusEffect(StatusEffects.INVISIBILITY);
    }
}
