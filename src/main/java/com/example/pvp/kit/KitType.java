package com.example.pvp.kit;

/**
 * 套件类型：内置三种 + 配置自定义。
 */
public enum KitType {
    SWORD("剑战"),
    BOW("弓箭"),
    FULL_GEAR("全装备"),
    CUSTOM("自定义");

    private final String displayName;

    KitType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }
}
