package com.example.pvp.config;

/**
 * 单个玩家的 PvP 战绩（隐藏分 + 胜/负/场次）。
 *
 * <p><b>{@code rating} 是隐藏分，不展示给玩家</b>：只供系统计算（空岛弱势补偿等），
 * 玩家界面里出现的一律是胜/负/场次与胜率。新玩家（以及老 stats.json 里没有该字段的玩家）
 * 默认 {@link EloRating#INITIAL}。
 */
public class PlayerStats {
    public int wins;
    public int losses;
    public int matches;
    /** 隐藏分；字段初始化即为默认值，Gson 反序列化缺字段时会保留它。 */
    public double rating = EloRating.INITIAL;

    public PlayerStats() {
    }

    public PlayerStats(int wins, int losses, int matches) {
        this.wins = wins;
        this.losses = losses;
        this.matches = matches;
    }

    public int getWins() {
        return this.wins;
    }

    public int getLosses() {
        return this.losses;
    }

    public int getMatches() {
        return this.matches;
    }

    public double getRating() {
        return this.rating;
    }

    /** 胜率（0~1；无场次为 0）。这是玩家可见的排序依据。 */
    public double winRate() {
        return this.matches <= 0 ? 0 : (double) this.wins / this.matches;
    }
}
