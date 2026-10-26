package com.example.pvp.mygo;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Geometry-only checks; server integration supplies real player poses later. */
final class MyGoHitboxesTest {
    @Test void finiteRaySlabIntersectionKeepsHeadAndLegBoxesSeparate() {
        Box head = new Box(-.22, 1.25, -.22, .22, 1.75, .22);
        Box leg = new Box(-.22, 0, -.12, -.02, .75, .12);
        assertEquals(4.78 / 10, MyGoHitboxes.rayInterval(head,
                new Vec3d(-5, 1.25, 0), new Vec3d(5, 1.25, 0)).orElseThrow().enter(), 1e-9);
        assertTrue(MyGoHitboxes.rayInterval(leg,
                new Vec3d(-5, 1, 0), new Vec3d(5, 1, 0)).isEmpty());
    }

    @Test void parallelRayOutsideASectionDoesNotBecomeAHit() {
        Box shoulder = new Box(-.45, .7, -.12, -.235, 1.5, .12);
        assertEquals(Optional.empty(), MyGoHitboxes.rayInterval(shoulder,
                new Vec3d(-1, 1.6, 0), new Vec3d(1, 1.6, 0)));
        assertTrue(MyGoHitboxes.rayInterval(shoulder,
                new Vec3d(-1, 1, 0), new Vec3d(1, 1, 0)).isPresent());
    }

    @Test void rotatedPoseStillUsesFiniteLocalBoxes() {
        MyGoHitboxes.Pose pose = new MyGoHitboxes.Pose(new Box(0, 0, 0, 1, 1.8, 1), 90, 90, false);
        assertTrue(MyGoHitboxes.intersect(pose, new Vec3d(-2, .95, .5), new Vec3d(2, .95, .5)).isPresent());
        assertEquals(MyGoRules.Part.STOMACH, MyGoHitboxes.intersect(pose,
                new Vec3d(.5, .9, -2), new Vec3d(.5, .9, 2)).orElseThrow().part());
    }

    @Test void crouchedPoseRetainsAVisibleHeadSection() {
        MyGoHitboxes.Pose crouched = new MyGoHitboxes.Pose(new Box(0, 0, 0, 1, 1.5, 1), 0, 0, true);
        MyGoHitboxes.Hit hit = MyGoHitboxes.intersect(crouched,
                new Vec3d(-2, 1.25, .5), new Vec3d(2, 1.25, .5)).orElseThrow();
        assertEquals(MyGoRules.Part.HEAD, hit.part());
    }

    @Test void intersectionContainsExitPointForSubsequentTracing() {
        MyGoHitboxes.Pose pose = new MyGoHitboxes.Pose(new Box(0, 0, 0, 1, 1.8, 1), 0, 0, false);
        MyGoHitboxes.Hit hit = MyGoHitboxes.intersect(pose,
                new Vec3d(-2, .9, .5), new Vec3d(2, .9, .5)).orElseThrow();
        assertTrue(hit.exitPoint().x > hit.point().x);
        assertTrue(hit.exitPoint().x - hit.point().x > .3);
    }
}
