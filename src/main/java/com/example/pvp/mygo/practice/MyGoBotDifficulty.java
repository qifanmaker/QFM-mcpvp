package com.example.pvp.mygo.practice;

import java.util.Locale;

/**
 * Gun practice needs reaction, aim and tactical limits independent of melee
 * difficulty. Profiles never change weapon damage, health, armour or spread.
 */
public enum MyGoBotDifficulty {
    BEGINNER("beginner", "新手", "较慢发现目标，身体瞄准、短记忆，适合熟悉枪械", 1, 16, 5, 7, 0, 36, 3, 22, 0.20, 0.10),
    EASY("easy", "简单", "基础跟枪、短点射，听到枪声后谨慎搜索", 2, 11, 8, 4.5, .04, 55, 4, 18, .36, .20),
    NORMAL("normal", "普通", "急停点射、掩体换弹、分工进攻和回防", 3, 7, 12, 2.3, .10, 80, 5, 14, .58, .35),
    HARD("hard", "困难", "快速预瞄、队友补枪、道具协同与攻守转换", 4, 5, 17, 1.2, .20, 100, 6, 11, .76, .55),
    EXPERT("expert", "专家", "稳定控枪、有限预判和战术协同，仍保留反应与瞄准误差", 5, 3, 23, .65, .32, 120, 7, 8, .88, .70);

    private final String id, displayName, description;
    private final int level, reactionTicks, memoryTicks, burstShots, burstPauseTicks;
    private final double maxTurnDegrees, aimErrorDegrees, headAimChance, recoilControl, utilityChance;

    MyGoBotDifficulty(String id, String displayName, String description, int level, int reactionTicks,
                      double maxTurnDegrees, double aimErrorDegrees, double headAimChance,
                      int memoryTicks, int burstShots, int burstPauseTicks, double recoilControl, double utilityChance) {
        this.id = id; this.displayName = displayName; this.description = description; this.level = level;
        this.reactionTicks = reactionTicks; this.maxTurnDegrees = maxTurnDegrees; this.aimErrorDegrees = aimErrorDegrees;
        this.headAimChance = headAimChance; this.memoryTicks = memoryTicks; this.burstShots = burstShots;
        this.burstPauseTicks = burstPauseTicks; this.recoilControl = recoilControl; this.utilityChance = utilityChance;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public int getLevel() { return level; }
    public int reactionTicks() { return reactionTicks; }
    public double maxTurnDegrees() { return maxTurnDegrees; }
    public double aimErrorDegrees() { return aimErrorDegrees; }
    public double headAimChance() { return headAimChance; }
    public int memoryTicks() { return memoryTicks; }
    public int burstShots() { return burstShots; }
    public int burstPauseTicks() { return burstPauseTicks; }
    public double recoilControl() { return recoilControl; }
    public double utilityChance() { return utilityChance; }

    public static MyGoBotDifficulty byId(String id) {
        if (id == null) return null;
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (MyGoBotDifficulty value : values()) {
            if (value.id.equals(key) || Integer.toString(value.level).equals(key)) return value;
        }
        return null;
    }

    public static MyGoBotDifficulty fromLevel(int level) {
        return values()[Math.clamp(level, 1, 5) - 1];
    }
}
