package com.example.pvp.mygo;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 用飞行结果验证强弱抛物线、动量和反弹，避免只验证配置常数。 */
final class MyGoGrenadePhysicsTest {
    @Test void strongThrowTravelsFartherAndHigherThanLowUnderhandThrow() {
        Vec3d strong = MyGoGrenadePhysics.launchVelocity(0, -90, true, Vec3d.ZERO);
        Vec3d weak = MyGoGrenadePhysics.launchVelocity(0, -90, false, Vec3d.ZERO);
        double[] strongFlight = freeFlight(strong, 1.62), weakFlight = freeFlight(weak, 1.32);
        assertTrue(strongFlight[0] > weakFlight[0] * 3, "高抛应有明显射程优势");
        assertTrue(strongFlight[1] > weakFlight[1] + .5, "平视高抛应经过更高的顶点");
        assertEquals(.3, weak.length() / strong.length(), 1.0e-6);
    }

    @Test void aimingUpStillChangesBothThrowsAndRealMovementIsInherited() {
        Vec3d aimUp = MyGoGrenadePhysics.launchVelocity(-45, 0, true, Vec3d.ZERO);
        Vec3d horizontal = MyGoGrenadePhysics.launchVelocity(0, 0, true, Vec3d.ZERO);
        assertTrue(aimUp.y > horizontal.y);
        Vec3d movement = new Vec3d(.2, .3, -.1);
        Vec3d moving = MyGoGrenadePhysics.launchVelocity(-45, 0, true, movement);
        assertEquals(aimUp.x + .25, moving.x, 1.0e-9);
        assertEquals(aimUp.y + .375, moving.y, 1.0e-9);
        assertEquals(aimUp.z - .125, moving.z, 1.0e-9);
    }

    @Test void wallAndFloorReflectionsLoseEnergyWithoutReversingTheWrongAxis() {
        Vec3d incoming = new Vec3d(.6, -.4, .2);
        Vec3d floor = MyGoGrenadePhysics.reflect(incoming, new Vec3d(0, 1, 0));
        assertTrue(floor.y > 0 && floor.x > 0 && floor.z > 0);
        assertEquals(incoming.length() * .45, floor.length(), 1.0e-9);
        Vec3d wall = MyGoGrenadePhysics.reflect(incoming, new Vec3d(-1, 0, 0));
        assertTrue(wall.x < 0 && wall.y < 0 && wall.z > 0);
    }

    @Test void heDamageDecaysWithoutLongRangeOrInvalidDistanceDamage() {
        double peak = 0;
        assertEquals(99, MyGoGrenadePhysics.heDamage(peak), 1.0e-9);
        assertTrue(MyGoGrenadePhysics.heDamage(3) < MyGoGrenadePhysics.heDamage(1));
        assertTrue(MyGoGrenadePhysics.heDamage(6) < MyGoGrenadePhysics.heDamage(3));
        assertEquals(0, MyGoGrenadePhysics.heDamage(MyGoGrenadePhysics.HE_RADIUS + .01));
        assertEquals(0, MyGoGrenadePhysics.heDamage(Double.NaN));
    }

    @Test void turningAwayAndDistanceBothShortenTheFlash() {
        int front = MyGoGrenadePhysics.flashDurationTicks(1, 1);
        int back = MyGoGrenadePhysics.flashDurationTicks(1, -1);
        assertTrue(front >= 90 && front <= 100);
        assertTrue(back < 20 && back > 0);
        assertTrue(MyGoGrenadePhysics.flashDurationTicks(20, 1) < front);
        assertEquals(0, MyGoGrenadePhysics.flashDurationTicks(MyGoGrenadePhysics.FLASH_RADIUS, 1));
        assertEquals(0, MyGoGrenadePhysics.flashDurationTicks(Double.NaN, 1));
    }

    private static double[] freeFlight(Vec3d velocity, double height) {
        Vec3d point = new Vec3d(0, height, 0);
        double highest = height;
        for (int tick = 0; tick < 200; tick++) {
            Vec3d nextVelocity = velocity.add(0, -MyGoGrenadePhysics.GRAVITY, 0);
            point = point.add(velocity.add(nextVelocity).multiply(.5));
            velocity = nextVelocity;
            highest = Math.max(highest, point.y);
            if (point.y <= 0) return new double[]{Math.hypot(point.x, point.z), highest};
        }
        fail("手雷应落地，不能无限飞行");
        return new double[2];
    }
}
