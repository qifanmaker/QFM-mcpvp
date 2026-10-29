package com.example.pvp.practice;

import java.util.Locale;

/**
 * Five human skill profiles for PvP practice. Difficulty changes decision quality,
 * reaction and input precision; every profile keeps normal player health, damage
 * and the same vanilla three-block interaction range.
 */
public enum BotDifficulty {
    BEGINNER("beginner", "新手", "反应较慢，走位简单，适合熟悉攻击距离", 1,
            12, 8.0, 10.0, 18.0, 0.45, 8, 0.0, 14, 6.0, 0.12, 35),
    EASY("easy", "简单", "会追击和基础横移，攻击与瞄准有明显间隔", 2,
            9, 12.0, 7.0, 15.0, 0.60, 6, 0.05, 17, 7.0, 0.25, 30),
    NORMAL("normal", "普通", "控制距离，使用盾牌和补给，尝试跳跃暴击", 3,
            6, 18.0, 4.0, 12.0, 0.76, 4, 0.18, 20, 8.0, 0.40, 25),
    HARD("hard", "困难", "快速横移、破盾与冲刺重置，低血量寻找补给时机", 4,
            4, 25.0, 2.0, 10.0, 0.90, 2, 0.35, 20, 9.0, 0.55, 20),
    EXPERT("expert", "专家", "精准控距和预判，灵活切换攻防，仍有正常反应延迟", 5,
            3, 32.0, 1.0, 8.0, 0.97, 1, 0.50, 20, 10.0, 0.65, 16);

    public static final double MELEE_REACH = 3.0;

    private final String id;
    private final String displayName;
    private final String description;
    private final int level;
    private final int reactionTicks;
    private final double maxTurnDegrees;
    private final double aimErrorDegrees;
    private final double aimToleranceDegrees;
    private final double hitChance;
    private final int attackDelayTicks;
    private final double critChance;
    private final int bowChargeTicks;
    private final double healingThreshold;
    private final double strafeStrength;
    private final int strafeDurationTicks;

    BotDifficulty(String id, String displayName, String description, int level,
                  int reactionTicks, double maxTurnDegrees, double aimErrorDegrees,
                  double aimToleranceDegrees, double hitChance, int attackDelayTicks,
                  double critChance, int bowChargeTicks, double healingThreshold,
                  double strafeStrength, int strafeDurationTicks) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.level = level;
        this.reactionTicks = reactionTicks;
        this.maxTurnDegrees = maxTurnDegrees;
        this.aimErrorDegrees = aimErrorDegrees;
        this.aimToleranceDegrees = aimToleranceDegrees;
        this.hitChance = hitChance;
        this.attackDelayTicks = attackDelayTicks;
        this.critChance = critChance;
        this.bowChargeTicks = bowChargeTicks;
        this.healingThreshold = healingThreshold;
        this.strafeStrength = strafeStrength;
        this.strafeDurationTicks = strafeDurationTicks;
    }

    public String getId() { return this.id; }
    public String getDisplayName() { return this.displayName; }
    public String getDescription() { return this.description; }
    public int getLevel() { return this.level; }
    public int reactionTicks() { return this.reactionTicks; }
    public double maxTurnDegrees() { return this.maxTurnDegrees; }
    public double aimErrorDegrees() { return this.aimErrorDegrees; }
    public double aimToleranceDegrees() { return this.aimToleranceDegrees; }
    public double hitChance() { return this.hitChance; }
    public int attackDelayTicks() { return this.attackDelayTicks; }
    public double critChance() { return this.critChance; }
    public int bowChargeTicks() { return this.bowChargeTicks; }
    public double healingThreshold() { return this.healingThreshold; }
    public double strafeStrength() { return this.strafeStrength; }
    public int strafeDurationTicks() { return this.strafeDurationTicks; }

    /** Both the command ids and visible level numbers are accepted. */
    public static BotDifficulty byId(String id) {
        if (id == null) return null;
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (BotDifficulty difficulty : values()) {
            if (difficulty.id.equals(key) || Integer.toString(difficulty.level).equals(key)) {
                return difficulty;
            }
        }
        return null;
    }
}
