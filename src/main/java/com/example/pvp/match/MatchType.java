package com.example.pvp.match;

import com.example.pvp.config.PvPConfig;

/**
 * 匹配模式：1v1 决斗、2v2 团队、自由乱斗(FFA)。
 */
public enum MatchType {
    DUEL_1V1("1v1", "1v1 决斗"),
    DUEL_2V2("2v2", "2v2 团队"),
    FFA("ffa", "自由乱斗"),
    SUMO("sumo", "相扑"),
    PVP_1_8("1.8", "1.8 经典PvP");

    private final String id;
    private final String displayName;

    MatchType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return this.id;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public int requiredPlayers() {
        return switch (this) {
            case DUEL_1V1, SUMO, PVP_1_8 -> 2;
            case DUEL_2V2 -> 4;
            case FFA -> PvPConfig.INSTANCE.ffaMinPlayers;
        };
    }

    public static MatchType byId(String id) {
        for (MatchType type : values()) {
            if (type.id.equalsIgnoreCase(id) || type.name().equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }
}
