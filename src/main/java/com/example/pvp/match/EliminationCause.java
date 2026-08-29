package com.example.pvp.match;

/**
 * 淘汰原因。
 */
public enum EliminationCause {
    DEATH("阵亡"),
    VOID("掉出世界"),
    RING_OUT("出场"),
    SHRINK("缩圈淘汰"),
    DISCONNECT("中途退出"),
    FORFEIT("弃权"),
    HOT_POTATO_EXPLODE("山芋爆炸");

    private final String displayName;

    EliminationCause(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }
}
