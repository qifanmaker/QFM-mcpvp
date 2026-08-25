package com.example.pvp.config;

/**
 * 单个玩家的 PvP 战绩。
 */
public class PlayerStats {
    public int wins;
    public int losses;
    public int matches;

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
}
