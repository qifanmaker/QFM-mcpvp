package com.example.pvp.arena.skywars;

import java.util.Random;

/**
 * 材质主题之外的独立结构抽签。各结构改变中央体量、卫星岛高度和绕行层，
 * 同一局所有出生方向使用相同参数，避免多样性改变某个玩家的抢中成本。
 */
public enum SkyWarsMapStyle {
    // 名称、紧凑/标准中央半径、紧凑/标准卫星半径、卫星高度、支路高度。
    TERRACES("层叠遗迹", 12, 16, 5, 7, 0, 0),
    RING_CITADEL("环形要塞", 14, 18, 5, 7, 0, 0),
    SHATTERED_ARCHIPELAGO("破碎群岛", 12, 16, 4, 5, 2, -4),
    CANOPY("树冠营地", 12, 16, 6, 8, 4, 2),
    VIADUCT("高架回廊", 13, 17, 5, 6, 2, -2),
    EXCAVATION("下沉矿坑", 14, 18, 5, 7, -3, -5),
    VILLAGE("空中村庄", 28, 40, 0, 0, 0, 0);

    private static final SkyWarsMapStyle[] RANDOM_STYLES = {
            TERRACES, RING_CITADEL, SHATTERED_ARCHIPELAGO, CANOPY, VIADUCT, EXCAVATION
    };
    private static final long STYLE_SALT = 0x243f6a8885a308d3L;

    private final String displayName;
    private final int compactCentralRadius;
    private final int standardCentralRadius;
    private final int compactSatelliteRadius;
    private final int standardSatelliteRadius;
    private final int satelliteHeight;
    private final int branchHeight;

    SkyWarsMapStyle(String displayName, int compactCentralRadius, int standardCentralRadius,
                    int compactSatelliteRadius, int standardSatelliteRadius, int satelliteHeight,
                    int branchHeight) {
        this.displayName = displayName;
        this.compactCentralRadius = compactCentralRadius;
        this.standardCentralRadius = standardCentralRadius;
        this.compactSatelliteRadius = compactSatelliteRadius;
        this.standardSatelliteRadius = standardSatelliteRadius;
        this.satelliteHeight = satelliteHeight;
        this.branchHeight = branchHeight;
    }

    public String getDisplayName() { return this.displayName; }
    public boolean usesCustomStructure() { return this != TERRACES && this != VILLAGE; }
    public int centralRadius(boolean compact) {
        return SkyWarsLayout.scaleCentral(compact ? this.compactCentralRadius : this.standardCentralRadius);
    }
    public int satelliteRadius(boolean compact) { return compact ? this.compactSatelliteRadius : this.standardSatelliteRadius; }
    public int satelliteHeight(int variant) { return this == TERRACES ? Math.floorMod(variant, 3) - 1 : this.satelliteHeight; }
    public int branchHeight(int variant) { return this == TERRACES ? (Math.floorMod(variant, 3) == 1 ? -3 : 3) : this.branchHeight; }
    public boolean hasBranchIslands(int variant) { return this != VILLAGE && (this != TERRACES || Math.floorMod(variant, 3) != 0); }

    /** 与主题的取模和拓扑随机源分开，固定一种材质时仍可抽到全部六种结构。 */
    public static SkyWarsMapStyle pick(int seed, SkyWarsTheme theme) {
        if (theme == SkyWarsTheme.VILLAGE) return VILLAGE;
        long value = seed ^ STYLE_SALT;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return RANDOM_STYLES[new Random(value).nextInt(RANDOM_STYLES.length)];
    }
}
