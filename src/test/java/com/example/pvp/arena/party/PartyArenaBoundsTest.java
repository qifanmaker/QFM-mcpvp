package com.example.pvp.arena.party;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PartyArenaBoundsTest {
    @Test void longCourseKeepsBothOriginalEndsAndIncludesTheSeparateLobbyInProtection() {
        BlockPos center = new BlockPos(88, 101, 88);
        PartyArenaBounds rocket = new PartyArenaBounds(-200, 200, -2250, 200);
        assertTrue(rocket.contains(center, center.add(-21, -165, -2180)));
        assertTrue(rocket.contains(center, center.add(21, 154, 7)));
        assertFalse(rocket.contains(center, center.add(0, 0, -2251)));
        PartyArenaBounds protection = PartyArenaBounds.LOBBY_PROTECTION.union(rocket.expand(16));
        assertTrue(protection.contains(center, center.add(230, 54, 230)));
        assertFalse(rocket.contains(center, center.add(230, 54, 230)));
    }

    @Test void remoteIslandsRemainInsideOwnedLanesAndNeighboringNormalArenaIsOutside() {
        BlockPos center = new BlockPos(88, 101, 88);
        PartyArenaBounds pearl = new PartyArenaBounds(-1536, 1536, -1536, 1536).expand(16);
        assertTrue(pearl.contains(center, center.add(-1136, 100, 1200)));
        assertFalse(pearl.contains(center, new BlockPos(4 * 512 - 240, 100, 88)));
        assertThrows(IllegalArgumentException.class, () -> new PartyArenaBounds(-1553, 1536, 0, 1));
    }
}
