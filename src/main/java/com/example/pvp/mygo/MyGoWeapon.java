package com.example.pvp.mygo;

import java.util.Locale;

/**
 * 首个 MyGO 版本需要独立、可审计的枪械数值，避免把工具攻击属性误当成枪械伤害。
 * 短点射周期依据原版客户端右键重复输入设计；服务端逐颗排程，不缓存提前输入。
 */
public enum MyGoWeapon {
    P9("P9", 0, 71001, 12, 36, 30, .60, 16, 40, .75, 4, 32, new int[]{0}),
    MP5("MP5", 1250, 71002, 30, 90, 25, .65, 12, 32, .55, 4, 40, new int[]{0, 2}),
    AK("AK", 2700, 71003, 30, 90, 36, .80, 24, 64, .85, 12, 52, new int[]{0, 3, 6}),
    M4("M4", 3100, 71004, 30, 90, 33, .75, 24, 64, .85, 8, 44, new int[]{0, 2, 4}),
    AW("AW", 4750, 71005, 5, 15, 105, .98, 80, 96, 1, 24, 64, new int[]{0});

    public final String label;
    public final int price, model, magazine, reserve, cycleTicks, reloadTicks;
    public final double damage, penetration, fullDamageRange, maxRange, farMultiplier;
    private final int[] burst;

    MyGoWeapon(String label, int price, int model, int magazine, int reserve, double damage,
               double penetration, double fullDamageRange, double maxRange, double farMultiplier,
               int cycleTicks, int reloadTicks, int[] burst) {
        this.label = label;
        this.price = price;
        this.model = model;
        this.magazine = magazine;
        this.reserve = reserve;
        this.damage = damage;
        this.penetration = penetration;
        this.fullDamageRange = fullDamageRange;
        this.maxRange = maxRange;
        this.farMultiplier = farMultiplier;
        this.cycleTicks = cycleTicks;
        this.reloadTicks = reloadTicks;
        this.burst = burst;
    }

    public int[] burstOffsets() { return this.burst.clone(); }

    public static MyGoWeapon parse(String name) {
        if (name == null) return null;
        try { return valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public double distanceMultiplier(double distance) {
        if (distance > this.maxRange) return 0;
        if (distance <= this.fullDamageRange || this.maxRange == this.fullDamageRange) return 1;
        return 1 - (1 - this.farMultiplier) * (distance - this.fullDamageRange)
                / (this.maxRange - this.fullDamageRange);
    }
}
