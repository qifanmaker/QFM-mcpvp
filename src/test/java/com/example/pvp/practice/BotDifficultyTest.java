package com.example.pvp.practice;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the public difficulty selection and the limits that keep bot play fair. */
final class BotDifficultyTest {
    @Test void menuLevelsAndCommandsSelectTheSameFiveProfiles() {
        assertEquals(5, BotDifficulty.values().length);
        var ids = new HashSet<String>();
        for (BotDifficulty difficulty : BotDifficulty.values()) {
            assertTrue(ids.add(difficulty.getId()));
            assertSame(difficulty, BotDifficulty.byId(difficulty.getId()));
            assertSame(difficulty, BotDifficulty.byId(" " + difficulty.getId().toUpperCase(Locale.ROOT) + " "));
            assertSame(difficulty, BotDifficulty.byId(Integer.toString(difficulty.getLevel())));
        }
        assertNull(BotDifficulty.byId(null));
        assertNull(BotDifficulty.byId(""));
        assertNull(BotDifficulty.byId("0"));
        assertNull(BotDifficulty.byId("6"));
        assertNull(BotDifficulty.byId("impossible"));
    }

    @Test void everyProfileRetainsFiniteReactionAndFallibleAim() {
        assertEquals(3.0, BotDifficulty.MELEE_REACH);
        for (BotDifficulty difficulty : BotDifficulty.values()) {
            assertTrue(difficulty.reactionTicks() >= 3);
            assertTrue(difficulty.attackDelayTicks() >= 1);
            assertTrue(difficulty.maxTurnDegrees() > 0 && difficulty.maxTurnDegrees() < 45);
            assertTrue(difficulty.aimErrorDegrees() > 0);
            assertTrue(difficulty.hitChance() > 0 && difficulty.hitChance() < 1);
            assertTrue(difficulty.critChance() >= 0 && difficulty.critChance() <= 0.5);
            assertTrue(difficulty.bowChargeTicks() > 0 && difficulty.bowChargeTicks() <= 20);
            assertTrue(difficulty.healingThreshold() > 0 && difficulty.healingThreshold() <= 10);
            assertTrue(difficulty.strafeStrength() >= 0 && difficulty.strafeStrength() <= 1);
            assertTrue(difficulty.strafeDurationTicks() > 0);
        }
    }

    @Test void higherDifficultyImprovesInputsWithoutRemovingHumanLimits() {
        BotDifficulty[] profiles = BotDifficulty.values();
        for (int i = 1; i < profiles.length; i++) {
            BotDifficulty previous = profiles[i - 1];
            BotDifficulty current = profiles[i];
            assertEquals(previous.getLevel() + 1, current.getLevel());
            assertTrue(current.reactionTicks() < previous.reactionTicks());
            assertTrue(current.maxTurnDegrees() > previous.maxTurnDegrees());
            assertTrue(current.aimErrorDegrees() < previous.aimErrorDegrees());
            assertTrue(current.hitChance() > previous.hitChance());
            assertTrue(current.attackDelayTicks() < previous.attackDelayTicks());
            assertTrue(current.strafeStrength() > previous.strafeStrength());
        }
    }
}
