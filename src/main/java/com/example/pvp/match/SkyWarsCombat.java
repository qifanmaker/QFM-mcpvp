package com.example.pvp.match;

import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;

/** 无攻击冷却时，斧头恢复为同材质剑少 1 点基础伤害，保留药水和附魔的独立作用。 */
public final class SkyWarsCombat {
    private SkyWarsCombat() { }

    public static double attackDamage(PlayerEntity player, double original) {
        if (!(player.getMainHandStack().getItem() instanceof AxeItem axe)) return original;
        var attribute = player.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (attribute == null || attribute.getModifier(Item.BASE_ATTACK_DAMAGE_MODIFIER_ID) == null) return original;

        // 只替换武器的基础加值；直接覆盖最终伤害会吞掉力量/虚弱等效果。
        double base = attribute.getBaseValue();
        for (EntityAttributeModifier modifier : attribute.getModifiers()) {
            if (modifier.operation() == EntityAttributeModifier.Operation.ADD_VALUE) {
                base += modifier.id().equals(Item.BASE_ATTACK_DAMAGE_MODIFIER_ID)
                        ? axe.getMaterial().getAttackDamage() + 2.0 : modifier.value();
            }
        }
        double value = base;
        for (EntityAttributeModifier modifier : attribute.getModifiers()) {
            if (modifier.operation() == EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                value += base * modifier.value();
            }
        }
        for (EntityAttributeModifier modifier : attribute.getModifiers()) {
            if (modifier.operation() == EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
                value *= 1.0 + modifier.value();
            }
        }
        return EntityAttributes.GENERIC_ATTACK_DAMAGE.value().clamp(value);
    }
}
