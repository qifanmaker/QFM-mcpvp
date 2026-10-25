package com.example.pvp.mygo;

/**
 * 修改原因：旧 C4 在 12 格内直接处死，忽略距离、生命和剩余护甲，保枪边界失真。
 * 预审：使用经典 Dust II 的 500 基准及 Source 高斯距离衰减，不加墙体阻挡，
 * 一格按一米换算；有限护甲仍走本模式公共伤害规则，不破坏世界。
 *
 * 500 与 radius = damage * 3.5 可由原版 map_showbombradius 检查；公开原服命令记录：
 * https://forums.alliedmods.net/showthread.php?p=2761495
 * 此处为经典参数的服务端适配，不声称已用 Valve 原服逐距离认证。
 */
public final class MyGoBombDamage {
    public static final double DUST2_BASE_DAMAGE = 500;
    public static final double DUST2_RADIUS_UNITS = DUST2_BASE_DAMAGE * 3.5;
    private static final double SIGMA_UNITS = DUST2_RADIUS_UNITS / 3;

    private MyGoBombDamage() { }

    public static double radiusBlocks() { return DUST2_RADIUS_UNITS / MyGoWeapon.SOURCE_UNITS_PER_BLOCK; }

    public static double rawDamage(double distanceBlocks) {
        if (!Double.isFinite(distanceBlocks)) return 0;
        double distanceUnits = Math.max(0, distanceBlocks) * MyGoWeapon.SOURCE_UNITS_PER_BLOCK;
        if (distanceUnits > DUST2_RADIUS_UNITS) return 0;
        return DUST2_BASE_DAMAGE * Math.exp(-distanceUnits * distanceUnits / (2 * SIGMA_UNITS * SIGMA_UNITS));
    }

    public static MyGoRules.Damage damage(double distanceBlocks, double armor) {
        return MyGoRules.roundBulletDamage(MyGoRules.protect(rawDamage(distanceBlocks), .5, armor, true));
    }
}
