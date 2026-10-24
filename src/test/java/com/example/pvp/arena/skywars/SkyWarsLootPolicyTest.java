package com.example.pvp.arena.skywars;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyWarsLootPolicyTest {
    @Test
    void rareAxeBudgetBelongsToTheMatchAndCannotBeClaimedDuringRefills() {
        int awardedMatches = 0;
        for (int seed = 0; seed < 100_000; seed++) {
            SkyWarsLootPolicy policy = new SkyWarsLootPolicy(seed);
            assertFalse(policy.claimRareAxe(true), "补货不能提前领取神器资格");
            if (policy.claimRareAxe(false)) {
                awardedMatches++;
            }
            assertFalse(policy.claimRareAxe(false), "增加箱子不能产生第二把神器");
            assertFalse(policy.claimRareAxe(true), "补货不能重新抽取神器");
        }
        assertTrue(awardedMatches >= 11_500 && awardedMatches <= 12_500,
                "10 万局的实际生成率应接近 12%，实际=" + awardedMatches);
    }

    @Test
    void supportDoesNotDependOnTheOrderOfEquallyRatedPlayers() {
        assertArrayEquals(new int[]{0, 0, 0, 0},
                SkyWarsLootPolicy.supportLevels(new double[]{1600, 1600, 1600, 1600}));
        assertArrayEquals(new int[]{2, 2, 0, 0},
                SkyWarsLootPolicy.supportLevels(new double[]{1000, 1000, 1800, 1800}));
        assertArrayEquals(new int[]{0, 2, 0, 2},
                SkyWarsLootPolicy.supportLevels(new double[]{1800, 1000, 1800, 1000}));
    }

    @Test
    void tinyRatingDifferencesDoNotCreateSupportAndLargeGapsOnlyHaveTwoTiers() {
        assertArrayEquals(new int[]{0, 0}, SkyWarsLootPolicy.supportLevels(new double[]{1599, 1600}));
        assertArrayEquals(new int[]{2, 1, 0, 0, 0},
                SkyWarsLootPolicy.supportLevels(new double[]{1300, 1450, 1600, 1600, 1600}));
        assertArrayEquals(new int[]{0}, SkyWarsLootPolicy.supportLevels(new double[]{1000}));
        assertArrayEquals(new int[0], SkyWarsLootPolicy.supportLevels(new double[0]));
        assertArrayEquals(new int[]{0, 0},
                SkyWarsLootPolicy.supportLevels(new double[]{Double.NaN, 1600}));
    }

    @Test
    void spawnBlockBudgetStaysWithinTheWholeIslandAllowance() {
        Random random = new Random(4271);
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (int sample = 0; sample < 10_000; sample++) {
            int count = SkyWarsLootPolicy.spawnBlockCount(random);
            assertTrue(count >= 48 && count <= 64);
            minimum = Math.min(minimum, count);
            maximum = Math.max(maximum, count);
        }
        assertEquals(48, minimum);
        assertEquals(64, maximum);
    }

    @Test
    void centerBowsAreStrongerOnAverageWhileBothZonesKeepAllFourLevels() {
        Random random = new Random(20261024);
        int[] spawnCounts = new int[5];
        int[] centerCounts = new int[5];
        long spawnTotal = 0;
        long centerTotal = 0;
        for (int sample = 0; sample < 100_000; sample++) {
            int spawn = SkyWarsLootPolicy.bowPower(random, SkyWarsLootPolicy.Tier.SPAWN);
            int center = SkyWarsLootPolicy.bowPower(random, SkyWarsLootPolicy.Tier.CENTER);
            assertTrue(spawn >= 1 && spawn <= 4);
            assertTrue(center >= 1 && center <= 4);
            spawnCounts[spawn]++;
            centerCounts[center]++;
            spawnTotal += spawn;
            centerTotal += center;
        }
        for (int level = 1; level <= 4; level++) {
            assertTrue(spawnCounts[level] > 0 && centerCounts[level] > 0);
        }
        assertTrue(spawnCounts[1] > spawnCounts[2] && spawnCounts[2] > spawnCounts[3]);
        assertTrue(centerCounts[3] + centerCounts[4] > centerCounts[1] + centerCounts[2]);
        assertTrue(centerTotal - spawnTotal > 70_000, "抢中应带来可观但非必然的弓升级");
    }
}
