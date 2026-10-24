package com.example.pvp.arena.skywars;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyWarsLootPolicyTest {
    @Test
    void legacyRareDropsHaveOnePercentPerItemInTheOriginalZones() {
        for (SkyWarsLootPolicy.Tier tier : SkyWarsLootPolicy.Tier.values()) {
            java.util.EnumMap<SkyWarsLootPolicy.RareDrop, Integer> counts =
                    new java.util.EnumMap<>(SkyWarsLootPolicy.RareDrop.class);
            for (int roll = 0; roll < 100; roll++) {
                final int value = roll;
                Random random = new Random() {
                    @Override
                    public int nextInt(int bound) {
                        assertEquals(100, bound);
                        return value;
                    }
                };
                counts.merge(SkyWarsLootPolicy.rareDrop(random, tier), 1, Integer::sum);
            }
            assertEquals(1, counts.get(SkyWarsLootPolicy.RareDrop.TOTEM));
            if (tier == SkyWarsLootPolicy.Tier.CENTER) {
                assertEquals(97, counts.get(SkyWarsLootPolicy.RareDrop.NONE));
                assertEquals(1, counts.get(SkyWarsLootPolicy.RareDrop.ELYTRA));
                assertEquals(1, counts.get(SkyWarsLootPolicy.RareDrop.ENCHANTED_GOLDEN_APPLE));
                assertFalse(counts.containsKey(SkyWarsLootPolicy.RareDrop.AXE));
            } else {
                assertEquals(98, counts.get(SkyWarsLootPolicy.RareDrop.NONE));
                assertEquals(1, counts.get(SkyWarsLootPolicy.RareDrop.AXE));
                assertFalse(counts.containsKey(SkyWarsLootPolicy.RareDrop.ELYTRA));
                assertFalse(counts.containsKey(SkyWarsLootPolicy.RareDrop.ENCHANTED_GOLDEN_APPLE));
            }
        }
    }

    @Test
    void repeatedFillsCanRollMoreRareItemsWithoutAMatchQuota() {
        SkyWarsLootPolicy policy = new SkyWarsLootPolicy(42);
        int axes = 0;
        for (int fill = 0; fill < 10_000; fill++) {
            if (SkyWarsLootPolicy.rareDrop(policy.random(), SkyWarsLootPolicy.Tier.SPAWN)
                    == SkyWarsLootPolicy.RareDrop.AXE) axes++;
        }
        assertTrue(axes > 50, "每只箱子及补货均继续独立抽取，不限制整局一把");
    }

    @Test
    void legacySupportRanksTheLowestPlayersInEachMatch() {
        assertArrayEquals(new int[]{2, 1, 0, 0},
                SkyWarsLootPolicy.supportLevels(new double[]{1000, 1200, 1800, 1800}));
        assertArrayEquals(new int[]{0, 2, 0, 1},
                SkyWarsLootPolicy.supportLevels(new double[]{1800, 1000, 1800, 1200}));
        assertArrayEquals(new int[]{2, 0}, SkyWarsLootPolicy.supportLevels(new double[]{1599, 1600}));
    }

    @Test
    void legacySupportRetainsOriginalTieOrderAndSmallMatchBehavior() {
        assertArrayEquals(new int[]{2, 1, 0, 0},
                SkyWarsLootPolicy.supportLevels(new double[]{1600, 1600, 1600, 1600}));
        assertArrayEquals(new int[]{2, 0}, SkyWarsLootPolicy.supportLevels(new double[]{1600, 1600}));
        assertArrayEquals(new int[]{2}, SkyWarsLootPolicy.supportLevels(new double[]{1000}));
        assertArrayEquals(new int[0], SkyWarsLootPolicy.supportLevels(new double[0]));
    }

    @Test
    void legacyRandomStackCountsApplyToEveryBoxInEachZone() {
        Random random = new Random(4271);
        for (SkyWarsLootPolicy.Tier tier : SkyWarsLootPolicy.Tier.values()) {
            int expectedMinimum = tier == SkyWarsLootPolicy.Tier.CENTER ? 5 : 4;
            int expectedMaximum = tier == SkyWarsLootPolicy.Tier.CENTER ? 7 : 5;
            int minimum = Integer.MAX_VALUE;
            int maximum = Integer.MIN_VALUE;
            for (int sample = 0; sample < 10_000; sample++) {
                int count = SkyWarsLootPolicy.randomStackCount(random, tier);
                assertTrue(count >= expectedMinimum && count <= expectedMaximum);
                minimum = Math.min(minimum, count);
                maximum = Math.max(maximum, count);
            }
            assertEquals(expectedMinimum, minimum);
            assertEquals(expectedMaximum, maximum);
        }
    }

    @Test
    void legacyBowPowerUsesAllFourLevelsEqually() {
        Random random = new Random(20261024);
        int[] counts = new int[5];
        for (int sample = 0; sample < 100_000; sample++) {
            int power = SkyWarsLootPolicy.bowPower(random);
            assertTrue(power >= 1 && power <= 4);
            counts[power]++;
        }
        for (int power = 1; power <= 4; power++) {
            assertTrue(counts[power] >= 24_000 && counts[power] <= 26_000,
                    "力量I～IV应各约25%：level=" + power + ", count=" + counts[power]);
        }
    }
}
