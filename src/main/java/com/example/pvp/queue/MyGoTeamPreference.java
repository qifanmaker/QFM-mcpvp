package com.example.pvp.queue;

/** 玩家加入 MyGO 时对进攻方/防守方的队伍偏好。 */
public enum MyGoTeamPreference {
    AUTO("auto", "自动平衡"),
    T("t", "T 进攻方"),
    CT("ct", "CT 防守方");

    private final String id;
    private final String displayName;

    MyGoTeamPreference(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return this.id;
    }

    public String displayName() {
        return this.displayName;
    }

    public static MyGoTeamPreference parse(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "t", "terrorist", "attacker", "进攻" -> T;
            case "ct", "counter", "defender", "防守" -> CT;
            case "auto", "random", "balance", "自动" -> AUTO;
            default -> null;
        };
    }
}
