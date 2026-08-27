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
    PVP_1_8("1.8", "1.8 经典PvP"),
    SKYWARS("skywars", "空岛战争"),
    BRIDGE_1V1("bridge1v1", "战桥 1v1"),
    BRIDGE_1V1V1V1("bridge1v1v1v1", "战桥 1v1v1v1"),
    BRIDGE_2V2("bridge2v2", "战桥 2v2"),
    BRIDGE_TEAM("bridge", "战桥 混战"),
    LUCKY_PILLAR("luckypillar", "幸运之柱");

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
            case DUEL_1V1, SUMO, PVP_1_8, BRIDGE_1V1 -> 2;
            case DUEL_2V2, BRIDGE_2V2, BRIDGE_1V1V1V1 -> 4;
            case FFA -> PvPConfig.INSTANCE.ffaMinPlayers;
            case SKYWARS -> PvPConfig.INSTANCE.skywarsMinPlayers;
            case BRIDGE_TEAM -> PvPConfig.INSTANCE.bridgeTeamMinPlayers;
            case LUCKY_PILLAR -> PvPConfig.INSTANCE.luckyPillarMinPlayers;
        };
    }

    /** 是否战桥系列玩法（1v1 / 1v1v1v1 / 2v2 / 混战）。 */
    public boolean isBridge() {
        return this == BRIDGE_1V1 || this == BRIDGE_1V1V1V1 || this == BRIDGE_2V2 || this == BRIDGE_TEAM;
    }

    /** 是否战桥混战（总人数/2 分两队，需偶数人数）。 */
    public boolean isBridgeTeam() {
        return this == BRIDGE_TEAM;
    }

    /** 是否"最后存活者获胜"的 FFA 淘汰类玩法（自由乱斗 / 空岛战争 / 幸运之柱）。 */
    public boolean isLastManStanding() {
        return this == FFA || this == SKYWARS || this == LUCKY_PILLAR;
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
