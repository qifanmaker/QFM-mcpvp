package com.example.pvp.mygo;

import java.util.Locale;

/**
 * 用末期 CS:GO 的 34 种枪械替换旧 PUBG 混合目录，让阵营、买枪和击杀经济使用同一份真实数据。
 * 数值依据随游戏公开分发的 items_game（含 prefab 继承），并合入 2022 年的 AWP / M4A1-S 调整；
 * 可审计的来源和原始属性见测试资源 mygo/csgo-weapon-facts.json，避免误用未更新的旧 weapon 脚本。
 * 一格按一米换算 Source 射程；射击与换弹向上取整到服务端 20 Hz。换弹是动画时长的近似适配，
 * 散布和后坐力仍适配 Minecraft 输入；左键一次发射一颗子弹，不再强制 AK / M4 三连发。
 */
public enum MyGoWeapon {
    // 副枪：十种手枪独立购买，T 默认 Glock-18，CT 默认 USP-S。
    GLOCK("Glock-18", Group.PISTOL, Side.T, 200, 71001, 20, 120, 30, 0.94, 0.85, 4096, 0.15, 2.27, 300, 1, 4),
    USP_S("USP-S", Group.PISTOL, Side.CT, 200, 71002, 12, 24, 35, 1.01, 0.91, 4096, 0.17, 2.17, 300, 1, 4),
    P2000("P2000", Group.PISTOL, Side.CT, 200, 71003, 13, 52, 35, 1.01, 0.91, 4096, 0.17, 2.27, 300, 1, 4),
    P250("P250", Group.PISTOL, Side.ANY, 300, 71004, 13, 26, 38, 1.28, 0.9, 4096, 0.15, 2.17, 300, 1, 4),
    FIVESEVEN("Five-SeveN", Group.PISTOL, Side.CT, 500, 71005, 20, 100, 32, 1.823, 0.81, 4096, 0.15, 2.2, 300, 1, 4),
    TEC9("Tec-9", Group.PISTOL, Side.T, 500, 71007, 18, 90, 33, 1.812, 0.79, 4096, 0.12, 2.5, 300, 1, 4),
    CZ75("CZ75-Auto", Group.PISTOL, Side.ANY, 500, 71008, 12, 12, 31, 1.553, 0.85, 4096, 0.1, 2.73, 100, 1, 4),
    DUALS("Dual Berettas", Group.PISTOL, Side.ANY, 300, 71009, 30, 120, 38, 1.15, 0.79, 4096, 0.12, 3.77, 300, 1, 4),
    DEAGLE("Desert Eagle", Group.PISTOL, Side.ANY, 700, 71011, 7, 35, 53, 1.864, 0.85, 4096, 0.225, 2.2, 300, 1, 3.9),
    REVOLVER("R8 Revolver", Group.PISTOL, Side.ANY, 600, 71012, 8, 8, 86, 1.864, 0.94, 4096, 0.5, 2.3, 300, 1, 4),
    // 冲锋枪：除 P90 外击杀奖励为 $600。
    MAC10("MAC-10", Group.SMG, Side.T, 1050, 71013, 30, 100, 29, 1.15, 0.8, 3600, 0.075, 2.6, 600, 1, 4),
    MP9("MP9", Group.SMG, Side.CT, 1250, 71014, 30, 120, 26, 1.2, 0.87, 3600, 0.07, 2.1, 600, 1, 4),
    MP7("MP7", Group.SMG, Side.ANY, 1500, 71015, 30, 120, 29, 1.25, 0.85, 3600, 0.08, 3.13, 600, 1, 4),
    MP5SD("MP5-SD", Group.SMG, Side.ANY, 1500, 71016, 30, 120, 27, 1.25, 0.85, 3600, 0.08, 2.97, 600, 1, 4),
    UMP45("UMP-45", Group.SMG, Side.ANY, 1200, 71017, 25, 100, 35, 1.3, 0.75, 3700, 0.09, 3.5, 600, 1, 4),
    P90("P90", Group.SMG, Side.ANY, 2350, 71018, 50, 100, 26, 1.38, 0.86, 3700, 0.07, 3.37, 300, 1, 4),
    BIZON("PP-Bizon", Group.SMG, Side.ANY, 1400, 71019, 64, 120, 27, 1.26, 0.8, 3600, 0.08, 2.43, 600, 1, 4),
    // 步枪：保留 T / CT 阵营军械；AUG 与 SG 553 有一级光学镜。
    GALIL("Galil AR", Group.RIFLE, Side.T, 1800, 71020, 35, 90, 30, 1.55, 0.98, 8192, 0.09, 3.03, 300, 1, 4),
    FAMAS("FAMAS", Group.RIFLE, Side.CT, 2050, 71021, 25, 90, 30, 1.4, 0.96, 8192, 0.09, 3.3, 300, 1, 4),
    AK47("AK-47", Group.RIFLE, Side.T, 2700, 71022, 30, 90, 36, 1.55, 0.98, 8192, 0.1, 2.43, 300, 1, 4),
    M4A4("M4A4", Group.RIFLE, Side.CT, 3100, 71023, 30, 90, 33, 1.4, 0.97, 8192, 0.09, 3.07, 300, 1, 4),
    M4A1S("M4A1-S", Group.RIFLE, Side.CT, 2900, 71024, 20, 80, 38, 1.4, 0.94, 8192, 0.1, 3.07, 300, 1, 3.475),
    AUG("AUG", Group.RIFLE, Side.CT, 3300, 71025, 30, 90, 28, 1.8, 0.98, 8192, 0.1, 3.8, 300, 1, 4, 45),
    SG553("SG 553", Group.RIFLE, Side.T, 3000, 71026, 30, 90, 30, 2, 0.98, 8192, 0.11, 2.77, 300, 1, 4, 45),
    // 狙击枪：两级光学镜，AWP 使用 2022 年末的五发弹匣。
    SSG08("SSG 08", Group.SNIPER, Side.ANY, 1700, 71027, 10, 90, 88, 1.7, 0.98, 8192, 1.25, 3.7, 300, 1, 4, 40, 15),
    AWP("AWP", Group.SNIPER, Side.ANY, 4750, 71028, 5, 30, 115, 1.95, 0.99, 8192, 1.455, 3.67, 100, 1, 4, 40, 10),
    G3SG1("G3SG1", Group.SNIPER, Side.T, 5000, 71029, 20, 90, 80, 1.65, 0.98, 8192, 0.25, 4.7, 300, 1, 4, 40, 15),
    SCAR20("SCAR-20", Group.SNIPER, Side.CT, 5000, 71030, 20, 90, 80, 1.65, 0.98, 8192, 0.25, 3.07, 300, 1, 4, 40, 15),
    // 霰弹枪：每颗弹丸独立命中，弹丸数来自游戏物品数据。
    NOVA("Nova", Group.SHOTGUN, Side.ANY, 1050, 71031, 8, 32, 26, 1, 0.7, 3000, 0.88, 4.74, 900, 9, 4),
    XM1014("XM1014", Group.SHOTGUN, Side.ANY, 2000, 71032, 7, 32, 20, 1.6, 0.7, 3000, 0.35, 4.1, 900, 6, 4),
    MAG7("MAG-7", Group.SHOTGUN, Side.CT, 1300, 71033, 5, 32, 30, 1.5, 0.45, 1400, 0.85, 2.4, 900, 8, 4),
    SAWEDOFF("Sawed-Off", Group.SHOTGUN, Side.T, 1100, 71034, 7, 32, 32, 1.5, 0.45, 1400, 0.85, 3.2, 900, 8, 4),
    // 机枪。
    M249("M249", Group.MACHINE_GUN, Side.ANY, 5200, 71035, 100, 200, 32, 1.6, 0.97, 8192, 0.08, 5.7, 300, 1, 4),
    NEGEV("Negev", Group.MACHINE_GUN, Side.ANY, 1700, 71036, 150, 300, 35, 1.42, 0.97, 8192, 0.075, 5.7, 300, 1, 4);

    /** 保留旧指令和调用方的 Java 别名；不向商店与资源包注册重复枪械。 */
    public static final MyGoWeapon P9 = GLOCK, MP5 = UMP45, AK = AK47, M4 = M4A4, AW = AWP;
    public static final MyGoWeapon M16 = FAMAS, SCAR = M4A1S, MINI14 = SSG08, MK14 = G3SG1;
    public static final MyGoWeapon UZI = MAC10, VECTOR = MP9, TOMMY = BIZON, M24 = SSG08, S686 = NOVA;

    public static final double SOURCE_UNITS_PER_BLOCK = 100 / 2.54;
    private enum Group { PISTOL, SMG, RIFLE, SNIPER, SHOTGUN, MACHINE_GUN }
    private enum Side { T, CT, ANY }

    public final String label;
    public final int price, model, magazine, reserve, cycleTicks, reloadTicks, killReward;
    public final double damage, penetration, armorRatio, headshotMultiplier;
    public final double fullDamageRange, maxRange, farMultiplier, rangeModifier, cycleSeconds, reloadSeconds;
    private final Group group;
    private final Side side;
    private final int pelletCount;
    private final int[] scopeFovs;

    MyGoWeapon(String label, Group group, Side side, int price, int model, int magazine, int reserve,
               double damage, double armorRatio, double rangeModifier, double sourceRange,
               double cycleSeconds, double reloadSeconds, int killReward, int pelletCount,
               double headshotMultiplier, int... scopeFovs) {
        this.label = label;
        this.group = group;
        this.side = side;
        this.price = price;
        this.model = model;
        this.magazine = magazine;
        this.reserve = reserve;
        this.damage = damage;
        this.armorRatio = armorRatio;
        // 既有 penetration 字段表示护甲后的生命伤害比例，并不是墙体穿透次数。
        this.penetration = armorRatio / 2;
        this.headshotMultiplier = headshotMultiplier;
        this.rangeModifier = rangeModifier;
        this.fullDamageRange = 0;
        this.maxRange = sourceRange / (100 / 2.54);
        this.farMultiplier = Math.pow(rangeModifier, sourceRange / 500);
        this.cycleSeconds = cycleSeconds;
        this.reloadSeconds = reloadSeconds;
        this.cycleTicks = Math.max(1, (int) Math.ceil(cycleSeconds * 20 - 1e-9));
        this.reloadTicks = Math.max(1, (int) Math.ceil(reloadSeconds * 20 - 1e-9));
        this.killReward = killReward;
        this.pelletCount = pelletCount;
        this.scopeFovs = scopeFovs.clone();
    }

    public int[] burstOffsets() { return new int[]{0}; }
    public boolean pistol() { return this.group == Group.PISTOL; }
    public boolean sniper() { return this.group == Group.SNIPER; }
    public boolean unzoomAfterShot() { return this == AWP || this == SSG08; }
    public boolean smg() { return this.group == Group.SMG; }
    public boolean automatic() { return this.group == Group.SMG || this.group == Group.RIFLE || this.group == Group.MACHINE_GUN; }
    public boolean shotgun() { return this.group == Group.SHOTGUN; }
    public int pellets() { return this.pelletCount; }
    public int scopeLevels() { return this.scopeFovs.length; }
    /** level 为 1 起算；零或越界表示未开镜的基准 FOV。 */
    public int scopeFov(int level) { return level > 0 && level <= scopeLevels() ? this.scopeFovs[level - 1] : 90; }
    public String soundKey() { return name().toLowerCase(Locale.ROOT); }
    public static MyGoWeapon defaultPistol(boolean attackers) { return attackers ? GLOCK : USP_S; }

    /**
     * Minecraft 用角度散布；不再让同一类别的所有武器共享一个数值。
     * 手枪首发最稳定，冲锋枪/步枪按 CS:GO 的定位逐级增加，霰弹枪保留明显散布。
     */
    public double spread() {
        return switch (this) {
            case USP_S, P2000, GLOCK -> .16;
            case P250, FIVESEVEN, TEC9, DUALS -> .24;
            case CZ75 -> .3;
            case DEAGLE, REVOLVER -> .38;
            case MAC10, MP9, MP7, MP5SD -> .62;
            case UMP45, BIZON -> .7;
            case P90 -> .78;
            case GALIL, FAMAS, M4A1S -> .38;
            case AK47, M4A4, AUG, SG553 -> .32;
            case G3SG1, SCAR20 -> .48;
            case SSG08, AWP -> 5.5;
            case NOVA, XM1014 -> 3.8;
            case MAG7 -> 3.3;
            case SAWEDOFF -> 5.5;
            case M249, NEGEV -> .72;
        };
    }
    public double recoil() {
        return sniper() ? (this == AWP ? 2.4 : 1.8) : shotgun() ? 2.2
                : this == DEAGLE || this == REVOLVER ? 1.8 : smg() ? .3 : pistol() ? .5 : .65;
    }
    public boolean availableTo(boolean attackers) { return this.side == Side.ANY || (this.side == Side.T) == attackers; }
    public String sideLabel() { return this.side == Side.T ? "进攻方" : this.side == Side.CT ? "防守方" : "双方"; }
    public String category() {
        return switch (this.group) {
            case PISTOL -> "副枪";
            case SMG -> "冲锋枪";
            case RIFLE -> "步枪";
            case SNIPER -> "狙击枪";
            case SHOTGUN -> "霰弹枪";
            case MACHINE_GUN -> "机枪";
        };
    }

    public static MyGoWeapon parse(String name) {
        if (name == null) return null;
        String key = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        return switch (key) {
            case "glock", "glock18", "p9", "p18c" -> GLOCK;
            case "usp", "usps", "uspsilencer" -> USP_S;
            case "p2000", "hkp2000" -> P2000;
            case "p250" -> P250;
            case "fiveseven", "57" -> FIVESEVEN;
            case "tec9" -> TEC9;
            case "cz75", "cz75a", "cz75auto" -> CZ75;
            case "duals", "dualberettas", "elite", "berettas" -> DUALS;
            case "deagle", "deserteagle" -> DEAGLE;
            case "revolver", "r8", "r8revolver" -> REVOLVER;
            case "mac10", "uzi" -> MAC10;
            case "mp9", "vector" -> MP9;
            case "mp7" -> MP7;
            case "mp5", "mp5sd" -> MP5SD;
            case "ump", "ump45" -> UMP45;
            case "p90" -> P90;
            case "bizon", "ppbizon", "tommy", "tommygun" -> BIZON;
            case "galil", "galilar" -> GALIL;
            case "famas", "m16", "m16a4" -> FAMAS;
            case "ak", "ak47", "akm" -> AK47;
            case "m4", "m4a4", "m416" -> M4A4;
            case "m4a1s", "m4a1silencer", "scar", "scarl" -> M4A1S;
            case "aug" -> AUG;
            case "sg553", "sg556", "krieg" -> SG553;
            case "ssg08", "scout", "mini14", "m24" -> SSG08;
            case "aw", "awp", "awm" -> AWP;
            case "g3sg1", "mk14" -> G3SG1;
            case "scar20" -> SCAR20;
            case "nova", "s686" -> NOVA;
            case "xm1014", "autoshotgun" -> XM1014;
            case "mag7" -> MAG7;
            case "sawedoff" -> SAWEDOFF;
            case "m249" -> M249;
            case "negev" -> NEGEV;
            default -> null;
        };
    }

    /** CS:GO 的每 500 Source unit 指数衰减，避免旧分段常数让远距离伤害失真。 */
    public double distanceMultiplier(double distance) {
        if (!Double.isFinite(distance) || distance > this.maxRange) return 0;
        return Math.pow(this.rangeModifier, Math.max(0, distance) * SOURCE_UNITS_PER_BLOCK / 500);
    }
}
