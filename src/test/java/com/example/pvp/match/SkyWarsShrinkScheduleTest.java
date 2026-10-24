package com.example.pvp.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkyWarsShrinkScheduleTest {
    @Test
    void bothMapSizesReachTheDuelAreaAtSixMinutes() {
        for (int initial : new int[] {80, 104, 147}) {
            var schedule = new SkyWarsShrinkSchedule(initial, 14, 180, 30, 360);
            assertEquals(0, schedule.stageAt(179));
            int previous = initial;
            for (int second = 0; second <= 600; second++) {
                int radius = schedule.radiusForStage(schedule.stageAt(second));
                assertTrue(radius <= previous && radius >= 14);
                previous = radius;
            }
            assertEquals(14, schedule.radiusForStage(schedule.stageAt(360)));
            assertEquals(14, schedule.radiusForStage(schedule.stageAt(600)));
            assertEquals(15, schedule.warningSeconds());
        }
    }

    @Test
    void nonAlignedFinalTimeDoesNotWaitForAnotherInterval() {
        var schedule = new SkyWarsShrinkSchedule(80, 10, 180, 30, 305);
        assertEquals(schedule.stages(), schedule.stageAt(305));
        assertEquals(305, schedule.secondsForStage(schedule.stages()));
        assertEquals(10, schedule.radiusForStage(schedule.stageAt(305)));
        assertTrue(schedule.radiusForStage(schedule.stageAt(304)) > 10);
    }

    @Test
    void invalidConfigurationCannotDivideByZeroOrExpandTheArena() {
        var schedule = new SkyWarsShrinkSchedule(10, 80, -1, 0, -5);
        assertEquals(10, schedule.finalRadius());
        assertEquals(15, schedule.startSeconds());
        assertEquals(10, schedule.radiusForStage(schedule.stageAt(15)));
    }
}
