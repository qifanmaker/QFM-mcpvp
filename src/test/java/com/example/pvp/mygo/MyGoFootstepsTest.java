package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MyGoFootstepsTest {
    @Test void audibleRunAndQuietCrouchOrSlowWalkUseRealDistance() {
        var steps = new MyGoFootsteps();
        int run = 0;
        for (int tick = 0; tick < 40; tick++)
            if (steps.update(tick, tick * .2, 0, 0, true, false, false, false) == MyGoFootsteps.Cue.STEP) run++;
        assertTrue(run >= 4 && run <= 6);
        for (int tick = 40; tick < 80; tick++)
            assertEquals(MyGoFootsteps.Cue.NONE, steps.update(tick, 8 + (tick - 40) * .2, 0, 0, true, true, false, false));
        steps.reset();
        for (int tick = 0; tick < 40; tick++)
            assertEquals(MyGoFootsteps.Cue.NONE, steps.update(tick, tick * .08, 0, 0, true, false, false, false));
    }

    @Test void jumpAndLandingAreSingleEventsAndTeleportDoesNotMakeAFakeLanding() {
        var steps = new MyGoFootsteps();
        steps.update(0, 0, 0, 0, true, false, false, false);
        assertEquals(MyGoFootsteps.Cue.JUMP, steps.update(1, .1, .4, 0, false, false, false, false));
        assertEquals(MyGoFootsteps.Cue.NONE, steps.update(2, .2, 1.1, 0, false, false, false, false));
        assertEquals(MyGoFootsteps.Cue.NONE, steps.update(3, .3, .8, 0, false, false, false, false));
        assertEquals(MyGoFootsteps.Cue.LAND, steps.update(4, .4, 0, 0, true, false, false, false));
        assertEquals(MyGoFootsteps.Cue.NONE, steps.update(5, .4, 0, 0, true, false, false, false));
        assertEquals(MyGoFootsteps.Cue.NONE, steps.update(6, 50, 20, 0, true, false, false, false));
        assertEquals(MyGoFootsteps.Cue.NONE, steps.update(7, 50, 20, 0, true, false, false, false));
    }

    @Test void swimmingHasWaterMovementAudioAfterVanillaSwimIsMuted() {
        var steps = new MyGoFootsteps();
        int swim = 0;
        for (int tick = 0; tick < 40; tick++)
            if (steps.update(tick, tick * .06, 2, 0, false, false, false, true) == MyGoFootsteps.Cue.STEP) swim++;
        assertTrue(swim > 0, "水中没有地面碰撞也应按实际移动播放水声");
    }

    @Test void climbingEmitsStepsForVerticalMovementAndDoesNotPretendItIsJumping() {
        var steps = new MyGoFootsteps();
        int climb = 0;
        for (int tick = 0; tick < 30; tick++) {
            var cue = steps.update(tick, 0, tick * .12, 0, false, false, false, false, true);
            assertNotEquals(MyGoFootsteps.Cue.JUMP, cue);
            if (cue == MyGoFootsteps.Cue.STEP) climb++;
        }
        assertTrue(climb > 0);
        assertEquals(MyGoFootsteps.Cue.NONE, steps.update(30, 0, 3.6, 0, false, false, false, false, false));
    }
}
