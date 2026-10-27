package com.example.pvp.arena.party;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PartyTournamentScoresTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3), D = new UUID(0, 4);

    @Test void equalRawScoresGiveEqualRankAndEqualPoints() {
        PartyTournamentScores scores = new PartyTournamentScores(List.of(A, B, C, D));
        var ranking = scores.recordRound(Map.of(A, 12, B, 12, C, 8, D, -4));
        assertEquals(List.of(1, 1, 3, 4), ranking.stream().map(PartyTournamentScores.Result::place).toList());
        assertEquals(List.of(4, 4, 2, 1), ranking.stream().map(PartyTournamentScores.Result::awarded).toList());
        assertEquals(Set.of(A, B), scores.leaders(Set.of(A, B, C, D)));
    }

    @Test void fiveDifferentScoreScalesProduceRankPointsAndSharedOverallWinners() {
        PartyTournamentScores scores = new PartyTournamentScores(List.of(A, B, C, D));
        scores.recordRound(Map.of(A, 3, B, 2, C, 1, D, 0));
        scores.recordRound(Map.of(A, 100, B, 900, C, 500, D, 700));
        scores.recordRound(Map.of(A, 2000, B, 1000, C, 3000, D, 500));
        scores.recordRound(Map.of(A, 0, B, 0, C, 0, D, 0));
        scores.recordRound(Map.of(A, 15, B, 14, C, 13, D, 12));
        assertEquals(Map.of(A, 16, B, 16, C, 14, D, 10), scores.totals());
        assertEquals(5, scores.roundsPlayed());
        assertEquals(Set.of(A, B), scores.leaders(Set.of(A, B, C, D)));
        assertEquals(List.of(1, 1, 3, 4), scores.standings(Set.of(A, B, C, D)).stream()
                .map(PartyTournamentScores.Result::place).toList());
        assertThrows(IllegalStateException.class, () -> scores.recordRound(Map.of(A, 999)));
        assertEquals(16, scores.total(A));
    }

    @Test void leavingRetainsEarnedPointsButCannotWinOrReceiveLaterAwards() {
        PartyTournamentScores scores = new PartyTournamentScores(List.of(A, B, C, D));
        scores.recordRound(Map.of(A, 3, B, 2, C, 1, D, 0));
        scores.recordRound(Map.of(B, 8, C, 7, D, 6));
        assertEquals(4, scores.total(A));
        assertEquals(7, scores.total(B));
        assertEquals(Set.of(B), scores.leaders(Set.of(B, C, D)));
        assertTrue(scores.leaders(Set.of()).isEmpty());
    }

    @Test void invalidInputsDoNotPartiallyScoreARound() {
        PartyTournamentScores scores = new PartyTournamentScores(List.of(A, B));
        assertThrows(IllegalArgumentException.class, () -> scores.recordRound(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> scores.recordRound(Map.of(A, 5, C, 8)));
        assertEquals(0, scores.roundsPlayed());
        assertEquals(Map.of(A, 0, B, 0), scores.totals());
        assertThrows(IllegalArgumentException.class, () -> new PartyTournamentScores(List.of(A, A)));
        assertThrows(IllegalArgumentException.class, () -> new PartyTournamentScores(List.of()));
    }

    @Test void departedPlayersDoNotChangeTheOriginalPointScale() {
        PartyTournamentScores scores = new PartyTournamentScores(List.of(A, B, C, D));
        var ranking = scores.recordRound(Map.of(B, Integer.MIN_VALUE, D, Integer.MIN_VALUE));
        assertEquals(List.of(4, 4), ranking.stream().map(PartyTournamentScores.Result::awarded).toList());
        assertEquals(Map.of(A, 0, B, 4, C, 0, D, 4), scores.totals());
    }
}
