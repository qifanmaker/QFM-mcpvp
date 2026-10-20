package com.example.pvp.config;

/**
 * 单个玩家的 PvP 战绩（Elo 积分 + 胜/负/场次）。
 *
 * <p>{@code rating} 是水平的唯一排序依据；胜/负/场次只作展示与"打了多少场"的参考。
 * 新玩家（以及老 stats.json 里没有该字段的玩家）默认 {@link EloRating#INITIAL}。
 */
public class PlayerStats {
    public int wins;
    public int losses;
    public int matches;
    /** Elo 积分；字段初始化即为默认值，Gson 反序列化缺字段时会保留它。 */
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

    /** 展示用整数 Elo（例如 1624）。 */
    public int getRatingDisplay() {
        return EloRating.display(this.rating);
    }

    /** 胜率（0~1；无场次为 0）。Elo 才是排序依据，胜率只作参考。 */
    public double winRate() {
        return this.matches <= 0 ? 0 : (double) this.wins / this.matches;
    }
}
