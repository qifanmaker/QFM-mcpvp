package com.example.pvp.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MapWorkBudgetTest {
    private static final long MS = 1_000_000;

    @Test void idleServerCanUseTwentyMillisecondsWithoutGrantingAnotherPoolForRepeatedCalls() {
        MapWorkBudget budget = new MapWorkBudget();
        Object queue = new Object();
        budget.beginTick(1, 0);
        assertEquals(20 * MS, budget.allowance(queue, MS));
        budget.spent(queue, 6 * MS);
        assertEquals(14 * MS, budget.allowance(queue, 7 * MS));
        budget.spent(queue, 14 * MS);
        assertEquals(0, budget.allowance(queue, 21 * MS));
        budget.beginTick(1, 21 * MS); // Duplicate capture cannot reset this tick.
        assertEquals(0, budget.allowance(queue, 21 * MS));
        budget.beginTick(2, 50 * MS);
        assertEquals(20 * MS, budget.allowance(queue, 51 * MS));
    }

    @Test void busyWorldTickLeavesOnlyItsRealHeadroomAndNoGuaranteedWorkAfterDeadline() {
        MapWorkBudget budget = new MapWorkBudget();
        Object queue = new Object();
        assertEquals(0, budget.allowance(queue, 0)); // No captured tick yet.
        budget.beginTick(1, 0);
        assertEquals(3 * MS, budget.allowance(queue, 37 * MS));
        budget.spent(queue, MS);
        assertEquals(MS, budget.allowance(queue, 39 * MS));
        assertEquals(0, budget.allowance(queue, 40 * MS));
        assertEquals(0, budget.allowance(queue, 41 * MS));
    }

    @Test void newlyDiscoveredQueueGetsItsShareNextTickAndNeitherCanConsumePeersShare() {
        MapWorkBudget budget = new MapWorkBudget();
        Object first = new Object(), second = new Object();
        budget.beginTick(1, 0);
        assertEquals(20 * MS, budget.allowance(first, 0));
        budget.spent(first, 20 * MS);
        assertEquals(0, budget.allowance(second, 20 * MS));
        budget.beginTick(2, 50 * MS);
        assertEquals(10 * MS, budget.allowance(first, 51 * MS));
        budget.spent(first, 10 * MS);
        assertEquals(0, budget.allowance(first, 61 * MS));
        assertEquals(10 * MS, budget.allowance(second, 61 * MS));
        budget.spent(second, 10 * MS);
        assertEquals(0, budget.allowance(second, 71 * MS));
    }

    @Test void multipleQueuesAlsoShareShortBusyTickHeadroom() {
        MapWorkBudget budget = new MapWorkBudget();
        Object first = new Object(), second = new Object();
        budget.beginTick(1, 0);
        budget.allowance(first, 0);
        budget.allowance(second, 0);
        budget.beginTick(2, 50 * MS);
        assertEquals(2 * MS, budget.allowance(first, 86 * MS));
        budget.spent(first, 2 * MS);
        assertEquals(2 * MS, budget.allowance(second, 88 * MS));
        budget.spent(second, 2 * MS);
        assertEquals(0, budget.allowance(first, 90 * MS));
        assertEquals(0, budget.allowance(second, 90 * MS));
    }

    @Test void nativeOperationOverrunsAreChargedToTheWholePool() {
        MapWorkBudget budget = new MapWorkBudget();
        Object first = new Object(), second = new Object();
        budget.beginTick(1, 0);
        budget.allowance(first, 0);
        budget.spent(first, 23 * MS);
        assertEquals(0, budget.allowance(second, 23 * MS));
    }

    @Test void completedAndAbandonedQueuesDoNotKeepReducingAnotherQueuesShare() {
        MapWorkBudget budget = new MapWorkBudget();
        Object first = new Object(), second = new Object();
        budget.beginTick(1, 0);
        budget.allowance(first, 0);
        budget.allowance(second, 0);
        budget.completed(first);
        budget.beginTick(2, 50 * MS);
        assertEquals(20 * MS, budget.allowance(second, 51 * MS));
        budget.beginTick(3, 100 * MS); // second was active in tick 2.
        budget.beginTick(4, 150 * MS); // second was abandoned in tick 3.
        assertEquals(20 * MS, budget.allowance(first, 151 * MS));
    }

    @Test void queueIdentityAndDurationValidationKeepAccountingIndependent() {
        MapWorkBudget budget = new MapWorkBudget();
        Object first = new String("same"), second = new String("same");
        budget.beginTick(1, 0);
        budget.allowance(first, 0);
        budget.allowance(second, 0);
        budget.beginTick(2, 50 * MS);
        assertEquals(10 * MS, budget.allowance(first, 51 * MS));
        assertThrows(IllegalArgumentException.class, () -> budget.spent(first, -1));
        assertThrows(IllegalStateException.class, () -> budget.spent(new Object(), MS));
    }
}
