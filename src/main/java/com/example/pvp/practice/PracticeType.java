package com.example.pvp.practice;

/**
 * 练习模式类型。两种练习都是「从出发点抵达目标台」的计时挑战，
 * 区别在于可用的手段：搭桥用方块铺路，末影珍珠用珍珠位移。
 */
public enum PracticeType {
    /** 搭路练习：给 64 个方块，从出发点铺到 30 格外的目标台，全程计时。 */
    BRIDGE("bridge", "搭路练习", 30, 64),
    /** 末影珍珠练习：给 16 颗珍珠，用珍珠位移到 25 格外的目标台，珠间 2 秒冷却。 */
    ENDER_PEARL("pearl", "末影珍珠", 25, 16);

    private final String id;
    private final String displayName;
    /** 出发点中心到目标台中心的距离（格，沿 +X）。 */
    private final int gap;
    /** 每轮补给的道具数量：搭桥=方块数，珍珠=珍珠数。 */
    private final int supply;

    PracticeType(String id, String displayName, int gap, int supply) {
        this.id = id;
        this.displayName = displayName;
        this.gap = gap;
        this.supply = supply;
    }

    public String getId() {
        return this.id;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public int getGap() {
        return this.gap;
    }

    public int getSupply() {
        return this.supply;
    }

    /** 本轮补给的物品名，用于提示文案。 */
    public String getSupplyName() {
        return this == BRIDGE ? "方块" : "末影珍珠";
    }

    public static PracticeType byId(String id) {
        for (PracticeType type : values()) {
            if (type.id.equalsIgnoreCase(id) || type.name().equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }
}
