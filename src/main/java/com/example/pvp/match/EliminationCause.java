package com.example.pvp.match;

/**
 * 淘汰原因。
 */
public enum EliminationCause {
    DEATH("阵亡"),
    VOID("掉出世界"),
    RING_OUT("出场"),
    DISCONNECT("中途退出"),
    FORFEIT("弃权");

    private final String displayName;

    EliminationCause(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }
}
