package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖真实输入顺序边界；不用时间封锁，否则开镜后立即左键会被误吞。 */
final class MyGoInputGuardTest {
    @Test void predictedUseConsumesExactlyOneSwingBeforeAnImmediateAirAttack() {
        MyGoInputGuard guard = new MyGoInputGuard();
        guard.blockUse(true);
        assertTrue(guard.consumePredictedSwing(true));
        assertFalse(guard.consumePredictedSwing(true));
    }

    @Test void nonPredictingBlockOrAirUseCannotSuppressTheFollowingAttack() {
        MyGoInputGuard guard = new MyGoInputGuard();
        guard.blockUse(false);
        assertFalse(guard.consumePredictedSwing(true));
        guard.clear();
        assertFalse(guard.consumePredictedSwing(true));
    }

    @Test void explicitEntityAttackDigFallbackItemOrSlotInputClearsAnUnconsumedPrediction() {
        MyGoInputGuard guard = new MyGoInputGuard();
        guard.blockUse(true);
        guard.clear();
        assertFalse(guard.consumePredictedSwing(true));
    }

    @Test void anOffhandAnimationDoesNotConsumeThePendingMainHandAnimation() {
        MyGoInputGuard guard = new MyGoInputGuard();
        guard.blockUse(true);
        assertFalse(guard.consumePredictedSwing(false));
        assertTrue(guard.consumePredictedSwing(true));
        assertFalse(guard.consumePredictedSwing(true));
    }

    @Test void repeatedRightClicksEachConsumeTheirOwnAnimationAndAChangedTargetClearsIt() {
        MyGoInputGuard guard = new MyGoInputGuard();
        for (int i = 0; i < 3; i++) {
            guard.blockUse(true);
            assertTrue(guard.consumePredictedSwing(true));
        }
        guard.blockUse(true);
        guard.blockUse(false);
        assertFalse(guard.consumePredictedSwing(true));
    }
}
