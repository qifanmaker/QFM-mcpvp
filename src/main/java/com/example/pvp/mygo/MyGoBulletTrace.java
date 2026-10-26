package com.example.pvp.mygo;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Server-side Source-style trace adapter. It supports bounded, material-aware
 * wall penetration and multiple players on one line without changing blocks.
 * The original profile's wall penetration power is deliberately separate from
 * its armour ratio. Minecraft block resistance is an explicit compatibility
 * mapping, because a Fabric server has no Source surface prop table.
 */
public final class MyGoBulletTrace {
    public record Hit(ServerPlayerEntity player, Vec3d point, MyGoRules.Part part,
                      double damageScale, double distance) { }
    public record Result(List<Hit> hits, Vec3d end, double damageScale, int penetrations) {
        public Result { hits = List.copyOf(hits); }
    }
    private record BlockExit(BlockPos pos, Vec3d point, double thickness, double loss) { }
    private record Candidate(ServerPlayerEntity player, MyGoHitboxes.Hit hit, double distance) { }
    private record Surface(double resistance, double maxThickness) { }
    private static final double EPSILON = .035;
    private static final int MAX_PENETRATIONS = 4;

    private MyGoBulletTrace() { }

    /**
     * Trace from the server eye position. Candidates should already exclude
     * dead and friendly players; the method still excludes the shooter itself.
     */
    public static Result trace(ServerPlayerEntity shooter, MyGoWeapon weapon, Vec3d from, Vec3d direction,
                               Collection<ServerPlayerEntity> candidates, MyGoLagHistory history, int tick) {
        if (direction.lengthSquared() < 1e-12 || !finite(direction))
            return new Result(List.of(), from, 0, 0);
        Vec3d ray = direction.normalize();
        double maxDistance = weapon.maxRange;
        Vec3d origin = from;
        double remaining = maxDistance, scale = 1;
        int penetrations = 0;
        List<Hit> hits = new ArrayList<>();
        Set<ServerPlayerEntity> struck = new HashSet<>();
        Vec3d end = from.add(ray.multiply(maxDistance));
        while (remaining > EPSILON && scale > .015) {
            Vec3d finish = origin.add(ray.multiply(remaining));
            BlockHitResult block = shooter.getServerWorld().raycast(new RaycastContext(origin, finish,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, shooter));
            Vec3d blockPoint = block.getType() == HitResult.Type.MISS ? finish : block.getPos();
            double blockDistance = origin.distanceTo(blockPoint);
            Candidate candidate = nearestCandidate(shooter, weapon, origin, blockPoint, candidates, history, tick, struck);
            if (candidate != null && candidate.distance() <= blockDistance + EPSILON) {
                hits.add(new Hit(candidate.player(), candidate.hit().point(), candidate.hit().part(), scale,
                        maxDistance - remaining + candidate.distance()));
                struck.add(candidate.player());
                double advance = Math.min(remaining, candidate.distance() + EPSILON);
                origin = origin.add(ray.multiply(advance));
                remaining -= advance;
                scale *= .72; // body penetration is always possible but loses energy.
                end = candidate.hit().point();
                continue;
            }
            end = blockPoint;
            if (block.getType() == HitResult.Type.MISS || penetrations >= MAX_PENETRATIONS) break;
            BlockExit exit = exitBlock(shooter, weapon, block.getBlockPos(), blockPoint, ray, remaining);
            if (exit == null || exit.loss() >= scale) break;
            penetrations++;
            scale -= exit.loss();
            double advance = Math.min(remaining, origin.distanceTo(exit.point()) + EPSILON);
            origin = origin.add(ray.multiply(advance));
            remaining -= advance;
        }
        return new Result(hits, end, scale, penetrations);
    }

    private static Candidate nearestCandidate(ServerPlayerEntity shooter, MyGoWeapon weapon, Vec3d from, Vec3d to,
                                              Collection<ServerPlayerEntity> candidates, MyGoLagHistory history,
                                              int tick, Set<ServerPlayerEntity> struck) {
        Candidate nearest = null;
        for (ServerPlayerEntity player : candidates) {
            if (player == shooter || struck.contains(player) || player.getWorld() != shooter.getWorld()) continue;
            MyGoHitboxes.Pose pose = history == null ? MyGoHitboxes.snapshot(player) : history.poseFor(player, shooter, tick);
            Optional<MyGoHitboxes.Hit> hit = MyGoHitboxes.intersect(pose, from, to);
            if (hit.isEmpty()) continue;
            double distance = from.distanceTo(hit.get().point());
            if (nearest == null || distance < nearest.distance()) nearest = new Candidate(player, hit.get(), distance);
        }
        return nearest;
    }

    private static BlockExit exitBlock(ServerPlayerEntity shooter, MyGoWeapon weapon, BlockPos pos, Vec3d hit, Vec3d ray, double remaining) {
        BlockState state = shooter.getServerWorld().getBlockState(pos);
        Surface surface = surface(state);
        if (surface == null) return null;
        double step = EPSILON, walked = 0;
        boolean inside = true;
        Vec3d cursor = hit;
        while (walked < Math.min(remaining, surface.maxThickness() + 2)) {
            cursor = cursor.add(ray.multiply(step)); walked += step;
            inside = containsCollision(state, shooter, pos, cursor);
            if (!inside) {
                double thickness = Math.max(step, walked);
                double loss = thickness * surface.resistance() /
                        Math.max(.25, weapon.profile().wallPenetration());
                return new BlockExit(pos, cursor, thickness, loss);
            }
        }
        return null;
    }

    private static boolean containsCollision(BlockState state, ServerPlayerEntity shooter, BlockPos pos, Vec3d point) {
        double x = point.x - pos.getX(), y = point.y - pos.getY(), z = point.z - pos.getZ();
        for (Box box : state.getCollisionShape(shooter.getServerWorld(), pos).getBoundingBoxes())
            if (x >= box.minX - .002 && x <= box.maxX + .002 && y >= box.minY - .002 && y <= box.maxY + .002
                    && z >= box.minZ - .002 && z <= box.maxZ + .002) return true;
        return false;
    }

    private static Surface surface(BlockState state) {
        if (state.isAir() || state.getFluidState().isStill()) return null;
        String id = state.getBlock().getTranslationKey().toLowerCase();
        if (id.contains("glass") || id.contains("pane") || id.contains("ice")) return new Surface(.08, 1.5);
        if (id.contains("wood") || id.contains("plank") || id.contains("door") || id.contains("trapdoor")
                || id.contains("fence") || id.contains("barrel") || id.contains("chest")) return new Surface(.28, 3);
        if (id.contains("iron") || id.contains("anvil") || id.contains("chain") || id.contains("metal")) return new Surface(.85, 2);
        if (id.contains("leaves") || id.contains("wool") || id.contains("carpet")) return new Surface(.16, 2);
        // Stone, deepslate, brick and arbitrary map construction are deliberately
        // expensive. One block can be penetrated by a high-power rifle; several
        // blocks stop it, unlike the previous all-or-nothing raycast.
        return new Surface(1.15, 2.5);
    }

    private static boolean finite(Vec3d value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
