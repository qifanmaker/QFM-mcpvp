package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Behaviour regressions: fire recovery, velocity accuracy and per-weapon identity. */
final class MyGoGunAccuracyTest {
    private static MyGoGunAccuracy state(MyGoWeapon weapon, boolean alternate, boolean crouched,
                                         boolean grounded, double speed, double vertical) {
        MyGoGunAccuracy result = new MyGoGunAccuracy(weapon);
        result.update(0, alternate, crouched, grounded, false, speed, vertical);
        return result;
    }

    @Test void originalProfilesCoverEveryGunAndKeepArmourAndWallPenetrationSeparate() {
        for (MyGoWeapon weapon : MyGoWeapon.values()) {
            MyGoWeaponProfile profile = weapon.profile();
            assertTrue(profile.attributes().size() >= 50, weapon.name());
            profile.attributes().forEach((key, value) -> assertTrue(Double.isFinite(value), weapon.name() + key));
            assertTrue(profile.maxSpeed(false) > 0);
            assertTrue(profile.recoverySeconds(false, 0) > 0);
        }
        assertEquals(215, MyGoWeapon.AK47.profile().maxSpeed(false));
        assertEquals(200, MyGoWeapon.AWP.profile().maxSpeed(false));
        assertEquals(100, MyGoWeapon.AWP.profile().maxSpeed(true));
        assertEquals(230, MyGoWeapon.SSG08.profile().maxSpeed(true));
        assertEquals(2.5, MyGoWeapon.AWP.profile().wallPenetration());
        assertEquals(.975, MyGoWeapon.AWP.penetration);
    }

    @Test void automaticFireUsesItemAttributesInsteadOfBroadWeaponCategories() {
        for (MyGoWeapon weapon : EnumSet.of(MyGoWeapon.CZ75, MyGoWeapon.XM1014,
                MyGoWeapon.G3SG1, MyGoWeapon.SCAR20, MyGoWeapon.REVOLVER, MyGoWeapon.AK47))
            assertTrue(weapon.automatic(), weapon.name());
        for (MyGoWeapon weapon : EnumSet.of(MyGoWeapon.GLOCK, MyGoWeapon.DEAGLE,
                MyGoWeapon.AWP, MyGoWeapon.SSG08, MyGoWeapon.NOVA, MyGoWeapon.MAG7, MyGoWeapon.SAWEDOFF))
            assertFalse(weapon.automatic(), weapon.name());
    }

    @Test void highRpmAndBurstTimingsKeepTheirFractionalTickIntervals() {
        assertEquals(1.4, MyGoWeapon.MP9.cycleTickInterval(), 1e-12);
        assertEquals(1.5, MyGoWeapon.MAC10.cycleTickInterval(), 1e-12);
        assertEquals(1.8, MyGoWeapon.M4A4.cycleTickInterval(), 1e-12);
        assertEquals(2.2, MyGoWeapon.SG553.cycleTickInterval(), 1e-12);
        assertArrayEquals(new double[]{0, 1, 2}, MyGoWeapon.GLOCK.burstTickOffsets(true));
        assertArrayEquals(new double[]{0, 1.5, 3}, MyGoWeapon.FAMAS.burstTickOffsets(true));
        assertEquals(10, MyGoWeapon.GLOCK.cycleTickInterval(true, false));
        assertEquals(11, MyGoWeapon.FAMAS.cycleTickInterval(true, false));
        assertArrayEquals(new double[]{0}, MyGoWeapon.AK47.burstTickOffsets(true));
    }

    @Test void scopedSnipersUseTheirOwnStandingCrouchingAndMovementCones() {
        MyGoGunAccuracy hip = state(MyGoWeapon.AWP, false, false, true, 0, 0);
        MyGoGunAccuracy aimed = state(MyGoWeapon.AWP, true, false, true, 0, 0);
        MyGoGunAccuracy crouched = state(MyGoWeapon.AWP, true, true, true, 0, 0);
        MyGoGunAccuracy moving = state(MyGoWeapon.AWP, true, false, true, 1, 0);
        assertEquals(.080800003, hip.inaccuracy(), 1e-9);
        assertEquals(.002, aimed.inaccuracy(), 1e-12);
        assertEquals(.0015, crouched.inaccuracy(), 1e-12);
        assertTrue(moving.inaccuracy() > aimed.inaccuracy() * 50,
                "Walking AWP must not remain almost as accurate as stationary AWP");
    }

    @Test void pistolsHaveTheirOwnMobilityPenaltyAndCrouchingImprovesStandingAccuracy() {
        MyGoGunAccuracy pistol = state(MyGoWeapon.GLOCK, false, false, true, 1, 0);
        MyGoGunAccuracy rifle = state(MyGoWeapon.AK47, false, false, true, 1, 0);
        assertTrue(rifle.inaccuracy() > pistol.inaccuracy() * 5);
        assertTrue(state(MyGoWeapon.AK47, false, true, true, 0, 0).inaccuracy()
                < state(MyGoWeapon.AK47, false, false, true, 0, 0).inaccuracy());
        assertEquals(state(MyGoWeapon.AK47, false, false, true, 0, 0).inaccuracy(),
                state(MyGoWeapon.AK47, false, false, true, .3, 0).inaccuracy(), 1e-12);
    }

    @Test void fastDeagleTapsAccumulatePenaltyAndWaitingRecoversAccuracy() {
        MyGoGunAccuracy accuracy = state(MyGoWeapon.DEAGLE, false, false, true, 0, 0);
        double initial = accuracy.inaccuracy();
        accuracy.fired(0, false);
        accuracy.update(5, false, false, true, false, 0, 0);
        assertTrue(accuracy.firingPenalty() > .001, "Firing penalty must survive a legal Deagle tap interval");
        double second = accuracy.inaccuracy();
        accuracy.fired(5, false);
        accuracy.update(10, false, false, true, false, 0, 0);
        assertTrue(accuracy.inaccuracy() > second);
        accuracy.update(110, false, false, true, false, 0, 0);
        assertEquals(initial, accuracy.inaccuracy(), .00001);
    }

    @Test void shortAndLongBurstsHaveDifferentRecoveryInsteadOfOneGlobalConstant() {
        MyGoWeaponProfile ak = MyGoWeapon.AK47.profile();
        assertTrue(ak.recoverySeconds(false, 6) > ak.recoverySeconds(false, 1));
        assertTrue(ak.recoverySeconds(true, 6) < ak.recoverySeconds(false, 6));
    }

    @Test void airAccuracyDependsOnVerticalVelocityIncludingFallsFromLedges() {
        MyGoGunAccuracy apex = state(MyGoWeapon.SSG08, true, false, false, 0, 0);
        MyGoGunAccuracy ascending = state(MyGoWeapon.SSG08, true, false, false, 0, .42);
        MyGoGunAccuracy falling = state(MyGoWeapon.SSG08, true, false, false, 0, -.42);
        assertTrue(ascending.inaccuracy() > apex.inaccuracy() * 10);
        assertEquals(ascending.inaccuracy(), falling.inaccuracy(), 1e-12);
        assertTrue(apex.inaccuracy() > state(MyGoWeapon.SSG08, true, false, true, 0, 0).inaccuracy());
    }

    @Test void radialSpreadSamplesStayBoundedAndAreNotGaussianOutliers() {
        MyGoGunAccuracy accuracy = state(MyGoWeapon.M4A4, false, false, true, 0, 0);
        Random random = new Random(71);
        double radius = accuracy.inaccuracy() + accuracy.spread();
        for (int i = 0; i < 10000; i++) {
            MyGoGunAccuracy.Offset sample = accuracy.sample(random);
            double x = Math.tan(Math.toRadians(sample.yawDegrees()));
            double y = Math.tan(Math.toRadians(sample.pitchDegrees()));
            assertTrue(x * x + y * y <= radius * radius + 1e-12);
        }
    }

    @Test void landingPenaltyDependsOnFallSeverityRatherThanAnySupportChange() {
        MyGoGunAccuracy tiny = state(MyGoWeapon.AK47, false, false, false, 0, -.01);
        MyGoGunAccuracy fall = state(MyGoWeapon.AK47, false, false, false, 0, -.6);
        tiny.update(1, false, false, true, false, 0, 0);
        fall.update(1, false, false, true, false, 0, 0);
        assertTrue(fall.inaccuracy() > tiny.inaccuracy() * 10);
        assertEquals(state(MyGoWeapon.AK47, false, false, true, 0, 0).inaccuracy(), tiny.inaccuracy(), 1e-12);
        fall.update(50, false, false, true, false, 0, 0);
        assertEquals(tiny.inaccuracy(), fall.inaccuracy(), .00001);
    }

    @Test void recoilPatternIsDeterministicPerGunAndNotTheSharedSineWave() {
        for (int shot = 0; shot < 30; shot++)
            assertEquals(MyGoGunAccuracy.recoil(MyGoWeapon.AK47, shot, false),
                    MyGoGunAccuracy.recoil(MyGoWeapon.AK47, shot, false));
        assertNotEquals(MyGoGunAccuracy.recoil(MyGoWeapon.AK47, 10, false),
                MyGoGunAccuracy.recoil(MyGoWeapon.M4A4, 10, false));
        assertTrue(MyGoGunAccuracy.recoil(MyGoWeapon.USP_S, 0, true).pitchDegrees()
                < MyGoGunAccuracy.recoil(MyGoWeapon.USP_S, 0, false).pitchDegrees());
    }

    @Test void negevConvergesDuringSustainedFireAndResetsAfterStopping() {
        MyGoGunAccuracy accuracy = state(MyGoWeapon.NEGEV, false, false, true, 0, 0);
        double initial = accuracy.inaccuracy();
        for (int shot = 0; shot < 30; shot++) {
            int tick = (int) Math.ceil(shot * MyGoWeapon.NEGEV.cycleTickInterval());
            accuracy.update(tick, false, false, true, false, 0, 0);
            accuracy.fired(tick, false);
        }
        accuracy.update(45, false, false, true, false, 0, 0);
        assertTrue(accuracy.inaccuracy() < initial / 3);
        assertEquals(.5, MyGoWeapon.NEGEV.profile().attackMoveFactor());
        accuracy.update(100, false, false, true, false, 0, 0);
        assertEquals(0, accuracy.shots());
        assertTrue(accuracy.inaccuracy() >= initial);
    }

    @Test void magazineShotgunAndSingleShellShotgunsRemainDistinct() {
        assertFalse(MyGoWeapon.MAG7.shellReload());
        assertTrue(MyGoWeapon.NOVA.shellReload());
        assertTrue(MyGoWeapon.XM1014.shellReload());
        assertTrue(MyGoWeapon.SAWEDOFF.shellReload());
    }

    @Test void cameraRecoveryRemovesOnlyRecordedKickAndDoesNotReplaceManualAim() {
        MyGoGunAccuracy accuracy = state(MyGoWeapon.AK47, false, false, true, 0, 0);
        MyGoGunAccuracy.Kick kick = accuracy.fired(0, false);
        accuracy.recordCameraKick(0, kick.yawDegrees(), kick.pitchDegrees());
        double yaw = 30 + kick.yawDegrees(), pitch = 12 - kick.pitchDegrees();
        // Manual aim changes independently of the remembered recoil offset.
        yaw -= 7; pitch += 3;
        assertEquals(new MyGoGunAccuracy.Kick(0, 0), accuracy.recoverCamera(2));
        for (int tick = 3; tick <= 200; tick++) {
            MyGoGunAccuracy.Kick recovery = accuracy.recoverCamera(tick);
            yaw += recovery.yawDegrees(); pitch -= recovery.pitchDegrees();
        }
        assertEquals(23, yaw, 1e-9);
        assertEquals(15, pitch, 1e-9);
    }

    @Test void clampedCameraKickDoesNotRecoverAnOffsetWhichWasNeverApplied() {
        MyGoGunAccuracy accuracy = state(MyGoWeapon.AK47, false, false, true, 0, 0);
        accuracy.fired(0, false);
        accuracy.recordCameraKick(0, 0, .05);
        MyGoGunAccuracy.Kick recovery = accuracy.recoverCamera(200);
        assertEquals(0, recovery.yawDegrees());
        assertEquals(-.05, recovery.pitchDegrees(), 1e-9);
        accuracy.reset();
        assertEquals(new MyGoGunAccuracy.Kick(0, 0), accuracy.recoverCamera(201));
    }
}
