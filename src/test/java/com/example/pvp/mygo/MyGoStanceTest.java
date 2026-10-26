package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MyGoStanceTest {
    @Test void defaultsToRunAndShiftWalksWhileCtrlCrouches() {
        assertEquals(MyGoStance.RUN, MyGoStance.of(false, false));
        assertEquals(MyGoStance.WALK, MyGoStance.of(true, false));
        assertEquals(MyGoStance.CROUCH, MyGoStance.of(false, true));
    }

    @Test void sneakWinsWhenBothVanillaStatesArePresent() {
        assertEquals(MyGoStance.WALK, MyGoStance.of(true, true));
    }

    @Test void compensationCancelsTheVanillaClientMultiplierToReachTheTargetRatio() {
        assertEquals(1, MyGoStance.RUN.compensation(), 1e-9);
        assertEquals(.52, MyGoStance.WALK.compensation() * .3, 1e-9);
        assertEquals(.34, MyGoStance.CROUCH.compensation() * 1.3, 1e-9);
    }

    @Test void onlyRunningIsAudible() {
        assertFalse(MyGoStance.RUN.quiet());
        assertTrue(MyGoStance.WALK.quiet());
        assertTrue(MyGoStance.CROUCH.quiet());
    }
}
