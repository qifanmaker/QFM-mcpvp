package com.example.pvp.arena;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArenaRegionAllocatorTest {
    @Test void widePartyKeepsItsOriginalCenterAndReservesAllSevenSlotsUntilRelease() {
        ArenaRegionAllocator allocator = new ArenaRegionAllocator();
        assertEquals(0, allocator.allocate(true));
        assertEquals(4, allocator.allocate(false));
        assertEquals(8, allocator.allocate(true));
        allocator.release(0);
        assertEquals(0, allocator.allocate(false));
        assertEquals(1, allocator.allocate(false));
        assertEquals(2, allocator.allocate(false));
        assertEquals(3, allocator.allocate(false));
        allocator.release(0);
        allocator.release(0); // stale/double cleanup cannot release a neighbor's slots
        assertEquals(0, allocator.allocate(false));
        assertEquals(12, allocator.allocate(false));
    }

    @Test void mixedConcurrentAllocationsDoNotShareSlotsOrPracticeRooms() {
        ArenaRegionAllocator allocator = new ArenaRegionAllocator();
        Set<Integer> claimed = new HashSet<>();
        for (int i = 0; i < 90; i++) {
            boolean wide = i % 3 == 0;
            int anchor = allocator.allocate(wide), radius = wide ? 3 : 0;
            for (int slot = anchor - radius; slot <= anchor + radius; slot++) {
                assertTrue(claimed.add(slot), "overlapping arena reservation: " + slot);
                assertFalse(slot >= 100 && slot < 132 || slot >= 200 && slot < 209,
                        "training room was allocated to a match: " + slot);
            }
        }
    }
}
