package com.example.pvp.arena.party;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 五轮赛按名次积分，避免把击杀数、下落深度和持肉 tick 直接相加。
 * 每轮积分为开局人数 N - 名次 + 1；并列采用 1、1、3 的名次，得到相同积分。
 * 原始人数固定，退赛者已得积分保留，之后不再得分，也不能成为最终赢家。
 */
public final class PartyTournamentScores {
    public static final int ROUNDS = 5;
    public record Result(UUID player, int value, int place, int awarded, int total) { }

    private final List<UUID> players;
    private final Map<UUID, Integer> order = new HashMap<>();
    private final Map<UUID, Integer> totals = new LinkedHashMap<>();
    private int roundsPlayed;

    public PartyTournamentScores(List<UUID> players) {
        if (players.isEmpty() || new HashSet<>(players).size() != players.size()) {
            throw new IllegalArgumentException("Party tournament needs a nonempty unique player list");
        }
        this.players = List.copyOf(players);
        for (int index = 0; index < this.players.size(); index++) {
            UUID player = this.players.get(index);
            this.order.put(player, index);
            this.totals.put(player, 0);
        }
    }

    /** 在一轮排名固定时调用一次；所有输入校验先于总分写入。 */
    public List<Result> recordRound(Map<UUID, Integer> values) {
        if (this.roundsPlayed >= ROUNDS) throw new IllegalStateException("All five rounds are already scored");
        if (values.isEmpty()) throw new IllegalArgumentException("An empty round cannot award points");
        this.validate(values.keySet());
        for (Integer value : values.values()) if (value == null) throw new IllegalArgumentException("Missing round score");
        List<Result> ranking = this.rank(values);
        List<Result> awarded = new ArrayList<>(ranking.size());
        for (Result result : ranking) {
            int points = this.players.size() - result.place() + 1;
            int total = this.totals.get(result.player()) + points;
            this.totals.put(result.player(), total);
            awarded.add(new Result(result.player(), result.value(), result.place(), points, total));
        }
        this.roundsPlayed++;
        return List.copyOf(awarded);
    }

    /** 最终名次只比较累计积分；调用者决定哪些人仍有资格参赛。 */
    public List<Result> standings(Set<UUID> eligible) {
        this.validate(eligible);
        Map<UUID, Integer> values = new HashMap<>();
        for (UUID player : eligible) values.put(player, this.totals.get(player));
        return this.rank(values);
    }

    public Set<UUID> leaders(Set<UUID> eligible) {
        Set<UUID> leaders = new HashSet<>();
        for (Result result : this.standings(eligible)) if (result.place() == 1) leaders.add(result.player());
        return Set.copyOf(leaders);
    }

    public int roundsPlayed() { return this.roundsPlayed; }

    public int total(UUID player) {
        Integer total = this.totals.get(player);
        if (total == null) throw new IllegalArgumentException("Unknown tournament player: " + player);
        return total;
    }

    public Map<UUID, Integer> totals() { return Map.copyOf(this.totals); }

    private List<Result> rank(Map<UUID, Integer> values) {
        List<UUID> sorted = values.keySet().stream().sorted(Comparator.<UUID>comparingInt(values::get).reversed()
                .thenComparingInt(this.order::get)).toList();
        List<Result> result = new ArrayList<>(sorted.size());
        Integer previous = null;
        int place = 0;
        for (int index = 0; index < sorted.size(); index++) {
            UUID player = sorted.get(index);
            int value = values.get(player);
            if (previous == null || value != previous) place = index + 1;
            previous = value;
            result.add(new Result(player, value, place, 0, this.totals.get(player)));
        }
        return List.copyOf(result);
    }

    private void validate(Set<UUID> eligible) {
        if (!this.order.keySet().containsAll(eligible)) throw new IllegalArgumentException("Unknown tournament participants");
    }
}
