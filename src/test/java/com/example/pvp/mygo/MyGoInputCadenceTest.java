package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for input expiry and fractional CS:GO firing intervals. */
final class MyGoInputCadenceTest {
    @Test void clickLeaseExpiresAtItsFixedBoundaryInsteadOfWeaponCycle() {
        MyGoInputLease lease = new MyGoInputLease(4);
        lease.refresh(20);
        assertTrue(lease.active(23));
        assertFalse(lease.active(24));
        lease.refresh(24);
        assertTrue(lease.active(27));
        assertFalse(lease.active(28));
    }

    @Test void clearCancelsAStillActiveClickLease() {
        MyGoInputLease lease = new MyGoInputLease();
        lease.refresh(10);
        lease.clear();
        assertFalse(lease.active(10));
    }

    @Test void fractionalIntervalKeepsMp9NearItsNativeRate() {
        MyGoPreciseCadence cadence = new MyGoPreciseCadence();
        double interval = MyGoWeapon.MP9.cycleTickInterval();
        cadence.armNow(0);
        int shots = 0;
        for (int tick = 0; tick <= 140; tick++) {
            if (cadence.ready(tick)) {
                shots++;
                cadence.fired(tick, interval);
            }
        }
        // 140 ticks / 1.4 ticks per shot = 100 shots (rounding at endpoints).
        assertEquals(101, shots);
        assertEquals(141.4, cadence.nextAt(), 1e-9);
    }

    @Test void delayedServerTickDoesNotEmitBackloggedShots() {
        MyGoPreciseCadence cadence = new MyGoPreciseCadence();
        cadence.armNow(0);
        cadence.fired(0, 1.4);
        assertTrue(cadence.ready(2));
        cadence.fired(20, 1.4);
        assertFalse(cadence.ready(20));
        assertTrue(cadence.nextAt() > 20);
    }

    @Test void resettingCadenceRemovesAnOldDeadline() {
        MyGoPreciseCadence cadence = new MyGoPreciseCadence();
        cadence.armNow(5);
        cadence.fired(5, 2.0);
        cadence.reset();
        assertFalse(cadence.ready(100));
        assertEquals(Double.POSITIVE_INFINITY, cadence.nextAt());
    }
}
