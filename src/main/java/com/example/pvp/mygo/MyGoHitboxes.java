package com.example.pvp.mygo;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;

/**
 * Reason: treating the top quarter of the entire collision box as a head made
 * shoulder/empty-space shots count as headshots. These separate, yaw-oriented
 * boxes approximate the visible Minecraft head, torso, arms and separated legs.
 * They are explicitly not Source's animated capsules. Review: rotate the ray
 * rather than expand boxes, retain leg gaps, and preserve the same head size
 * while crouching instead of uniformly shrinking every body part.
 */
public final class MyGoHitboxes {
    public record Pose(Box bounds, float bodyYaw, float headYaw, boolean crouched) { }
    public record Hit(Vec3d point, Vec3d exitPoint, MyGoRules.Part part) { }
    public record Interval(double enter, double exit) { }
    private record Section(MyGoRules.Part part, Box box) { }

    private MyGoHitboxes() { }

    public static Pose snapshot(ServerPlayerEntity player) {
        return new Pose(player.getBoundingBox(), player.bodyYaw, player.getYaw(), player.isSneaking());
    }

    public static Optional<Hit> intersect(Pose pose, Vec3d from, Vec3d to) {
        Vec3d origin = new Vec3d(pose.bounds().getCenter().x, pose.bounds().minY, pose.bounds().getCenter().z);
        Vec3d delta = to.subtract(from);
        Hit best = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (Section section : sections(pose)) {
            float yaw = section.part() == MyGoRules.Part.HEAD ? pose.headYaw() : pose.bodyYaw();
            Optional<Interval> interval = rayInterval(section.box(), toLocal(from, origin, yaw), toLocal(to, origin, yaw));
            if (interval.isEmpty() || interval.get().enter() >= nearest) continue;
            nearest = interval.get().enter();
            best = new Hit(from.add(delta.multiply(nearest)), from.add(delta.multiply(interval.get().exit())), section.part());
        }
        return Optional.ofNullable(best);
    }

    private static List<Section> sections(Pose pose) {
        double headTop = pose.bounds().getLengthY() + .05;
        double neck = headTop - .45;
        double hip = pose.crouched() ? .55 : .72;
        double chest = (hip + neck) / 2;
        double lean = pose.crouched() ? .08 : 0;
        return List.of(
                new Section(MyGoRules.Part.HEAD, new Box(-.225, neck, lean - .225, .225, headTop, lean + .225)),
                new Section(MyGoRules.Part.BODY, new Box(-.23, chest, lean - .12, .23, neck, lean + .12)),
                new Section(MyGoRules.Part.STOMACH, new Box(-.23, hip, lean - .12, .23, chest, lean + .12)),
                new Section(MyGoRules.Part.LEGS, new Box(-.225, 0, -.12, -.02, hip, .12)),
                new Section(MyGoRules.Part.LEGS, new Box(.02, 0, -.12, .225, hip, .12)),
                new Section(MyGoRules.Part.BODY, new Box(-.445, Math.max(hip, chest - .1), lean - .12, -.235, neck, lean + .12)),
                new Section(MyGoRules.Part.BODY, new Box(.235, Math.max(hip, chest - .1), lean - .12, .445, neck, lean + .12)));
    }

    private static Vec3d toLocal(Vec3d point, Vec3d origin, float yaw) {
        Vec3d delta = point.subtract(origin);
        double angle = Math.toRadians(yaw), c = Math.cos(angle), s = Math.sin(angle);
        return new Vec3d(delta.x * c + delta.z * s, delta.y, -delta.x * s + delta.z * c);
    }

    /** Finite segment/slab intersection, including rays that begin inside. */
    public static Optional<Interval> rayInterval(Box box, Vec3d from, Vec3d to) {
        double[] start = {from.x, from.y, from.z}, end = {to.x, to.y, to.z};
        double[] low = {box.minX, box.minY, box.minZ}, high = {box.maxX, box.maxY, box.maxZ};
        double enter = 0, exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(start[axis]) || !Double.isFinite(end[axis])) return Optional.empty();
            double velocity = end[axis] - start[axis];
            if (Math.abs(velocity) < 1e-12) {
                if (start[axis] < low[axis] || start[axis] > high[axis]) return Optional.empty();
                continue;
            }
            double a = (low[axis] - start[axis]) / velocity, b = (high[axis] - start[axis]) / velocity;
            enter = Math.max(enter, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
            if (enter > exit) return Optional.empty();
        }
        return Optional.of(new Interval(enter, exit));
    }
}
