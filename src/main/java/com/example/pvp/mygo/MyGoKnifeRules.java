package com.example.pvp.mygo;

/**
 * 修改原因：统一 40 点伤害的刀没有重击、背刺和连续轻击的取舍。
 * 预审：伤害独立于头部命中，按轻重/前后/首击选值，再结算有限护甲；
 * 射线与实体选择留给会话，因此不受 Minecraft 近战伤害或攻击冷却倍率影响。
 * armor ratio=1.7 来自末期 items_game melee；轻/重击基准亦可对照公开实现：
 * https://github.com/xDShot/csgo_knives_sweps （仅参数对照，不引入其模型、声音或代码）。
 */
public final class MyGoKnifeRules {
    public static final double BACKSTAB_DOT = .475;
    public static final int FIRST_SLASH_RECOVERY_TICKS = 8;
    public static final int KILL_REWARD = 1500;

    private MyGoKnifeRules() { }

    public static double rangeBlocks(boolean secondary) {
        return (secondary ? 32 : 48) / MyGoWeapon.SOURCE_UNITS_PER_BLOCK;
    }

    public static int cooldownTicks(boolean secondary, boolean hit) {
        return secondary ? (hit ? 22 : 20) : (hit ? 10 : 8);
    }

    public static boolean firstSlash(int tick, int previousReadyTick) {
        return tick > previousReadyTick + FIRST_SLASH_RECOVERY_TICKS;
    }

    public static MyGoRules.Damage damage(boolean secondary, boolean backstab, boolean firstSwing, double armor) {
        double raw = secondary ? (backstab ? 180 : 65) : backstab ? 90 : firstSwing ? 40 : 25;
        return MyGoRules.roundBulletDamage(MyGoRules.protect(raw, .85, armor, true));
    }

    /** Horizontal target-facing dot attacker-to-target direction; pitch cannot turn a front hit into a backstab. */
    public static boolean isBackstab(double victimForwardX, double victimForwardZ,
                                     double attackerToVictimX, double attackerToVictimZ) {
        double length = Math.hypot(victimForwardX, victimForwardZ) * Math.hypot(attackerToVictimX, attackerToVictimZ);
        return length > 1e-9 && (victimForwardX * attackerToVictimX + victimForwardZ * attackerToVictimZ) / length > BACKSTAB_DOT;
    }
}
