package com.example.pvp.mygo;

/**
 * 按末期 CS:GO 经典竞技统一经济与时限，避免旧快速局的起始金钱、败补和安拆窗口改变战术。
 * 冻结与解冻后的购买窗口分开；败补先按当前档发放，再调整下一局档位，获胜只降一档。
 * Minecraft 以 20 tick/s 调度，安装 3.2 秒可精确表示为 64 tick。
 */
public final class MyGoRules {
    public static final int FREEZE_TICKS = 15 * 20;
    public static final int BUY_TICKS = 20 * 20;
    public static final int ROUND_TICKS = 115 * 20;
    public static final int BOMB_TICKS = 40 * 20;
    public static final int PLANT_TICKS = 64;
    public static final int DEFUSE_TICKS = 10 * 20;
    public static final int KIT_DEFUSE_TICKS = 5 * 20;
    public static final int POST_TICKS = 7 * 20;
    public static final int HALF_ROUNDS = 15;
    public static final int MAX_ROUNDS = 30;
    public static final int WIN_ROUNDS = 16;
    public static final int START_MONEY = 800;
    public static final int MAX_MONEY = 16000;
    public static final int START_LOSS_LEVEL = 1;

    public enum Part { HEAD, BODY, LEGS }
    public enum Outcome { ATTACKERS, DEFENDERS }
    public enum WinCause {
        ELIMINATION, TIMEOUT, BOMB_EXPLODED, BOMB_DEFUSED
    }
    public record Damage(double health, double armor) { }

    private MyGoRules() { }

    public static double damage(MyGoWeapon gun, double distance, Part part, boolean vest, boolean helmet) {
        return damageWithArmor(gun, distance, part, vest ? 100 : 0, helmet).health();
    }

    /** 护甲伤害比例与护甲消耗独立计算；甲耗尽后剩余伤害继续扣血，不提供无限减伤。 */
    public static Damage damageWithArmor(MyGoWeapon gun, double distance, Part part, double armor, boolean helmet) {
        double raw = gun.damage * gun.distanceMultiplier(distance)
                * (part == Part.HEAD ? gun.headshotMultiplier : part == Part.LEGS ? .75 : 1);
        return protect(raw, gun.penetration, armor, part == Part.HEAD ? helmet : part == Part.BODY);
    }

    public static Damage protect(double raw, double healthRatio, double armor, boolean protectedPart) {
        if (!protectedPart || armor <= 0) return new Damage(raw, 0);
        double health = raw * Math.min(1, healthRatio);
        double used = (raw - health) * .5;
        if (used > armor) return new Damage(Math.max(0, raw - armor * 2), armor);
        return new Damage(health, used);
    }

    public static int armorCost(double armor, boolean helmet, boolean withHelmet) {
        if (withHelmet) return armor >= 100 ? (helmet ? 0 : 350) : helmet ? 650 : 1000;
        return armor >= 100 ? 0 : 650;
    }

    public static int addMoney(int cash, int amount) { return Math.max(0, Math.min(MAX_MONEY, cash + amount)); }
    public static int lossReward(int level) { return 1400 + Math.clamp(level, 0, 4) * 500; }
    public static int afterLoss(int level) { return Math.clamp(level + 1, 0, 4); }
    public static int afterWin(int level) { return Math.clamp(level - 1, 0, 4); }
    public static int roundReward(WinCause cause) { return cause == WinCause.BOMB_EXPLODED || cause == WinCause.BOMB_DEFUSED ? 3500 : 3250; }
    public static boolean finished(int team0, int team1) { return team0 >= WIN_ROUNDS || team1 >= WIN_ROUNDS || team0 + team1 >= MAX_ROUNDS; }
    public static boolean switchedSides(int round) { return round > HALF_ROUNDS; }
    public static boolean beforeDeadline(int tick, int deadline) { return tick < deadline; }

    /** Called before channel progress: deadline ties always resolve to timeout/explosion. */
    public static Outcome timedOutcome(boolean planted, int tick, int deadline) {
        return tick >= deadline ? (planted ? Outcome.ATTACKERS : Outcome.DEFENDERS) : null;
    }

    public static Outcome eliminationOutcome(boolean planted, int attackersAlive, int defendersAlive) {
        if (defendersAlive == 0 && (attackersAlive > 0 || planted)) return Outcome.ATTACKERS;
        if (attackersAlive == 0 && !planted) return Outcome.DEFENDERS;
        if (defendersAlive == 0 && attackersAlive == 0 && !planted) return Outcome.DEFENDERS;
        return null;
    }
}
