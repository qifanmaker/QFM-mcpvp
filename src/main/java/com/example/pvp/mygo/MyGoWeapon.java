package com.example.pvp.mygo;

import java.util.Locale;

/**
 * 首个 MyGO 版本需要独立、可审计的枪械数值，避免把工具攻击属性误当成枪械伤害。
 * 左键挥手触发短点射组；服务端逐颗排程并检查周期，不缓存提前输入。
 */
public enum MyGoWeapon {
    P9("P18C", 0, 71001, 12, 36, 30, .60, 16, 40, .75, 4, 32, new int[]{0}),
    MP5("UMP45", 1250, 71002, 30, 90, 25, .65, 12, 32, .55, 4, 40, new int[]{0, 2}),
    AK("AKM", 2700, 71003, 30, 90, 36, .80, 24, 64, .85, 12, 52, new int[]{0, 3, 6}),
    M4("M416", 3100, 71004, 30, 90, 33, .75, 24, 64, .85, 8, 44, new int[]{0, 2, 4}),
    AW("AWM", 4750, 71005, 5, 15, 105, .98, 80, 96, 1, 24, 64, new int[]{0}),
    M16("M16A4", 2050, 71011, 30, 90, 30, .70, 20, 60, .75, 10, 50, new int[]{0, 2, 4}),
    SCAR("SCAR-L", 3000, 71012, 30, 90, 34, .75, 22, 64, .80, 10, 48, new int[]{0, 2, 4}),
    MINI14("Mini14", 1800, 71013, 20, 60, 60, .75, 32, 80, .85, 8, 44, new int[]{0}),
    MK14("Mk14", 3600, 71014, 20, 60, 72, .85, 36, 88, .90, 10, 56, new int[]{0}),
    P90("P90", 2350, 71015, 50, 100, 26, .65, 15, 40, .60, 4, 52, new int[]{0, 2}),
    UZI("Uzi", 1050, 71016, 32, 96, 22, .60, 10, 30, .50, 4, 42, new int[]{0, 2}),
    VECTOR("Vector", 1550, 71017, 25, 100, 25, .65, 12, 32, .55, 4, 40, new int[]{0, 2}),
    TOMMY("Tommy Gun", 1300, 71018, 30, 90, 28, .60, 12, 36, .55, 4, 48, new int[]{0, 2}),
    M24("M24", 1700, 71019, 10, 30, 80, .85, 64, 88, .90, 22, 56, new int[]{0}),
    S686("S686", 1400, 71020, 2, 24, 12, .55, 5, 18, .20, 12, 52, new int[]{0});

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
    public boolean sniper() { return this == AW || this == M24; }
    public boolean smg() { return this == MP5 || this == P90 || this == UZI || this == VECTOR || this == TOMMY; }
    public int pellets() { return this == S686 ? 8 : 1; }
    public double spread() { return this == S686 ? 3.8 : sniper() ? 5.5 : smg() ? .8 : .55; }
    public double recoil() { return sniper() ? 2.4 : this == S686 ? 2.2 : this == MINI14 || this == MK14 ? 1.8 : smg() ? .25 : this == P9 ? .5 : .65; }
    public boolean availableTo(boolean attackers) { return this == AK ? attackers : this != M4 || !attackers; }
    public String category() { return this == P9 ? "副枪" : sniper() ? "狙击枪" : smg() ? "冲锋枪" : this == S686 ? "霰弹枪" : this == MINI14 || this == MK14 ? "精确射手" : "步枪"; }

    public static MyGoWeapon parse(String name) {
        if (name == null) return null;
        String key = switch (name.toLowerCase(Locale.ROOT)) {
            case "p18c", "glock" -> "P9"; case "ump", "ump45" -> "MP5";
            case "akm", "ak47" -> "AK"; case "m416" -> "M4"; case "awm", "awp" -> "AW";
            case "m16a4" -> "M16"; case "scar-l" -> "SCAR"; default -> name.toUpperCase(Locale.ROOT);
        };
        try { return valueOf(key); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public double distanceMultiplier(double distance) {
        if (distance > this.maxRange) return 0;
        if (distance <= this.fullDamageRange || this.maxRange == this.fullDamageRange) return 1;
        return 1 - (1 - this.farMultiplier) * (distance - this.fullDamageRange)
                / (this.maxRange - this.fullDamageRange);
    }
}
