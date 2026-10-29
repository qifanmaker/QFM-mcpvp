package com.example.pvp.mygo.practice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Bound map traversal to a real player's jump and a controlled, survivable drop. */
final class MyGoNavigatorTest {
    @Test void halfBlocksAndNormalJumpsAreAllowedButTallWallsAndUnsafeDropsAreNot() {
        assertTrue(MyGoNavigator.traversableHeight(0));
        assertTrue(MyGoNavigator.traversableHeight(.5));
        assertTrue(MyGoNavigator.traversableHeight(1));
        assertTrue(MyGoNavigator.traversableHeight(1.25));
        assertTrue(MyGoNavigator.traversableHeight(-3));
        assertFalse(MyGoNavigator.traversableHeight(1.251));
        assertFalse(MyGoNavigator.traversableHeight(-3.001));
        assertFalse(MyGoNavigator.traversableHeight(Double.NaN));
        assertFalse(MyGoNavigator.traversableHeight(Double.POSITIVE_INFINITY));
    }
}
