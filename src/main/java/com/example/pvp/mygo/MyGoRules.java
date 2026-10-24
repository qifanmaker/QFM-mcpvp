package com.example.pvp.mygo;

/**
 * 将 MyGO 的小数伤害、经济及压线规则提取为无世界依赖的规则，防止取整误差和同 tick 双重结算。
 * 修改前审查：腿部不受防弹衣影响；截止 tick 优先于安装/拆除；第十二回合结束允许 6:6 平局。
 */
public final class MyGoRules {
    public static final int BUY_TICKS = 200;
    public static final int ROUND_TICKS = 1800;
    public static final int BOMB_TICKS = 800;
    public static final int PLANT_TICKS = 60;
    public static final int DEFUSE_TICKS = 160;
    public static final int KIT_DEFUSE_TICKS = 80;
    public static final int POST_TICKS = 80;
    public static final int START_MONEY = 1600;
    public static final int MAX_MONEY = 16000;

    public enum Part { HEAD, BODY, LEGS }
    public enum Outcome { ATTACKERS, DEFENDERS }

    private MyGoRules() { }

    public static double damage(MyGoWeapon gun, double distance, Part part, boolean vest, boolean helmet) {
        double multiplier = part == Part.HEAD ? 4 : part == Part.LEGS ? .5 : 1;
        boolean protectedPart = part == Part.HEAD ? helmet : part == Part.BODY && vest;
        return gun.damage * gun.distanceMultiplier(distance) * multiplier
                * (protectedPart ? gun.penetration : 1);
    }

    public static int addMoney(int cash, int amount) { return Math.max(0, Math.min(MAX_MONEY, cash + amount)); }
    public static int lossReward(int consecutiveLosses) { return 2200 + Math.min(3, Math.max(0, consecutiveLosses - 1)) * 500; }
    public static boolean finished(int team0, int team1) { return team0 >= 7 || team1 >= 7 || team0 + team1 >= 12; }
    public static boolean switchedSides(int round) { return round > 6; }
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
