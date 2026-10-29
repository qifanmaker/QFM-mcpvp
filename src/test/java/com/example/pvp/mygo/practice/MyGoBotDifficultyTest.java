package com.example.pvp.mygo.practice;

import org.junit.jupiter.api.Test;
import com.example.pvp.mygo.MyGoWeapon;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** Difficulty selection must never grant instant reactions or perfect gun aim. */
final class MyGoBotDifficultyTest {
    @Test void menuAndCommandLevelsResolveTheSameFiveProfiles() {
        assertEquals(5, MyGoBotDifficulty.values().length);
        var ids = new HashSet<String>();
        for (MyGoBotDifficulty value : MyGoBotDifficulty.values()) {
            assertTrue(ids.add(value.getId()));
            assertSame(value, MyGoBotDifficulty.byId(value.getId()));
            assertSame(value, MyGoBotDifficulty.byId(" " + value.getId().toUpperCase(java.util.Locale.ROOT) + " "));
            assertSame(value, MyGoBotDifficulty.byId(Integer.toString(value.getLevel())));
            assertSame(value, MyGoBotDifficulty.fromLevel(value.getLevel()));
        }
        assertNull(MyGoBotDifficulty.byId(null)); assertNull(MyGoBotDifficulty.byId("0"));
        assertNull(MyGoBotDifficulty.byId("6")); assertNull(MyGoBotDifficulty.byId("aimbot"));
    }

    @Test void higherDifficultyImprovesDecisionsWithoutRemovingHumanLimits() {
        var profiles = MyGoBotDifficulty.values();
        for (int i = 0; i < profiles.length; i++) {
            var value = profiles[i];
            assertTrue(value.reactionTicks() >= 3);
            assertTrue(value.aimErrorDegrees() > .5);
            assertTrue(value.maxTurnDegrees() > 0 && value.maxTurnDegrees() < 30);
            assertTrue(value.headAimChance() >= 0 && value.headAimChance() < .5);
            assertTrue(value.memoryTicks() >= value.reactionTicks() && value.memoryTicks() <= 120);
            assertTrue(value.burstShots() >= 1 && value.burstShots() <= 7);
            assertTrue(value.burstPauseTicks() >= 8);
            assertTrue(value.recoilControl() > 0 && value.recoilControl() < 1);
            if (i > 0) {
                assertTrue(value.reactionTicks() < profiles[i - 1].reactionTicks());
                assertTrue(value.aimErrorDegrees() < profiles[i - 1].aimErrorDegrees());
                assertTrue(value.maxTurnDegrees() > profiles[i - 1].maxTurnDegrees());
            }
        }
    }

    @Test void weaponRolesPreferClosingPistolSightlinesWithoutChangingActualWeaponRange() {
        assertTrue(MyGoBot.preferredEngagementRange(MyGoWeapon.GLOCK) < MyGoBot.preferredEngagementRange(MyGoWeapon.AK47));
        assertTrue(MyGoBot.preferredEngagementRange(MyGoWeapon.NOVA) < MyGoBot.preferredEngagementRange(MyGoWeapon.MP9));
        assertTrue(MyGoBot.preferredEngagementRange(MyGoWeapon.AK47) < MyGoBot.preferredEngagementRange(MyGoWeapon.AWP));
        assertTrue(MyGoWeapon.GLOCK.maxRange > MyGoBot.preferredEngagementRange(MyGoWeapon.GLOCK));
    }
}
