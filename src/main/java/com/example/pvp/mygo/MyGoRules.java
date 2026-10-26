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

    public enum Part { HEAD, BODY, STOMACH, LEGS }
    public enum Outcome { ATTACKERS, DEFENDERS }
    public enum WinCause {
        ELIMINATION, TIMEOUT, BOMB_EXPLODED, BOMB_DEFUSED
    }
    public record Damage(double health, double armor) { }

    private MyGoRules() { }

    public static double damage(MyGoWeapon gun, double distance, Part part, boolean vest, boolean helmet) {
        return roundBulletDamage(damageWithArmor(gun, distance, part, vest ? 100 : 0, helmet)).health();
    }

    /**
     * 原版腹部为 1.25 倍，胸/臂为 1 倍，腿为 .75 倍；不能把整个躯干当成胸部。
     * 此处保留中间精度，霰弹同一枪的弹丸累加后才进入整数生命/护甲结算。
     */
    public static Damage damageWithArmor(MyGoWeapon gun, double distance, Part part, double armor, boolean helmet) {
        return damageWithArmor(gun, distance, part, armor, helmet, 1);
    }

    /** Penetration/team damage must scale raw damage before finite armour absorbs it. */
    public static Damage damageWithArmor(MyGoWeapon gun, double distance, Part part, double armor, boolean helmet,
                                         double damageScale) {
        double raw = gun.damage * gun.distanceMultiplier(distance) * Math.clamp(damageScale, 0, 1)
                * switch (part) {
                    case HEAD -> gun.headshotMultiplier;
                    case STOMACH -> 1.25;
                    case LEGS -> .75;
                    case BODY -> 1;
                };
        return protect(raw, gun.penetration, armor, part == Part.HEAD ? helmet : part != Part.LEGS);
    }

    /** Minecraft 碰撞盒分区随站立/潜行高度缩放；胸和胳膊使用相同伤害倍率。 */
    public static Part hitPart(double heightFraction) {
        if (heightFraction >= .75) return Part.HEAD;
        if (heightFraction <= .38) return Part.LEGS;
        return heightFraction < .55 ? Part.STOMACH : Part.BODY;
    }

    /** CS:GO 舍弃最终生命伤害的小数，整数护甲则扣掉向上取整的消耗。 */
    public static Damage roundBulletDamage(Damage damage) {
        return new Damage(Math.floor(damage.health()), Math.ceil(damage.armor()));
    }

    public static Damage protect(double raw, double healthRatio, double armor, boolean protectedPart) {
        if (!protectedPart || armor <= 0) return new Damage(raw, 0);
        double health = raw * Math.min(1, healthRatio);
        double used = (raw - health) * .5;
        if (used > armor) return new Damage(Math.max(0, raw - armor * 2), armor);
        return new Damage(health, used);
    }

    /**
     * 修改原因：按“还有甲”硬封顶 57 会让最后一点甲也挡掉大量伤害。
     * 预审：HE 沿用有限护甲吸收，耗尽后余量伤害照常扣血，不改变子弹结算。
     * legacy explosive_grenade 的 armor ratio 为 1.2，健康伤害比例为其一半 .6。
     */
    public static Damage heDamage(double raw, double armor) {
        if (!Double.isFinite(raw) || raw <= 0) return new Damage(0, 0);
        return roundBulletDamage(protect(raw, .6, armor, true));
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
