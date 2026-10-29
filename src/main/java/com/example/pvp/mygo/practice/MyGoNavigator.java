package com.example.pvp.mygo.practice;

import com.example.pvp.mygo.MyGoArena;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The imported maps have roofs, stairs and passages: steering straight at a
 * site through walls or teleporting on failure would not train real movement.
 * This incremental A* searches actual collision surfaces at 1/16-block height,
 * verifies headroom and transitions, and only returns physical waypoints.
 * Terrain is immutable during MyGO; a bounded cache is shared by the ten bots.
 */
public final class MyGoNavigator {
    private static final int EXPANSIONS_PER_TICK = 100;
    private static final int GLOBAL_EXPANSIONS_PER_TICK = 1_200;
    private static final int MAX_VISITED = 24_000;
    private static final int MAX_TERRAIN_CACHE = 48_000;
    private record TerrainKey(BlockPos center, String map) { }
    private static final Map<ServerWorld, Map<TerrainKey, Terrain>> TERRAIN = new WeakHashMap<>();
    private static final Map<ServerWorld, Budget> BUDGETS = new WeakHashMap<>();
    private static final class Budget { int tick = -1, remaining; }

    private record Node(int x, int z, int y16) {
        double y() { return y16 / 16.0; }
        Vec3d position() { return new Vec3d(x + .5, y(), z + .5); }
    }
    private record Edge(Node node, double cost) { }
    private record Open(Node node, double priority) { }
    private static final class Terrain {
        final Map<BlockPos, List<Double>> surfaces = new LinkedHashMap<>();
        final Map<Node, Boolean> clearance = new LinkedHashMap<>();
    }
    private static final class Search {
        final Node start, goal;
        final PriorityQueue<Open> frontier = new PriorityQueue<>(Comparator.comparingDouble(Open::priority));
        final Map<Node, Double> cost = new HashMap<>();
        final Map<Node, Node> previous = new HashMap<>();
        final Set<Node> closed = new HashSet<>();
        Node nearest;
        Search(Node start, Node goal) {
            this.start = start; this.goal = goal; this.nearest = start;
            frontier.add(new Open(start, heuristic(start, goal))); cost.put(start, 0.0);
        }
    }

    private final ServerWorld world;
    private final MyGoArena arena;
    private final Terrain terrain;
    private Search search;
    private List<Node> path = List.of();
    private int pathIndex, lastSearchTick = -1000, stuckTicks;
    private BlockPos requested;
    private Vec3d previousPosition;
    private String state = "等待路线";

    public MyGoNavigator(ServerWorld world, MyGoArena arena) {
        this.world = world; this.arena = arena;
        terrain = TERRAIN.computeIfAbsent(world, ignored -> new HashMap<>())
                .computeIfAbsent(new TerrainKey(arena.center(), arena.id()), ignored -> new Terrain());
    }

    public static void release(ServerWorld world, MyGoArena arena) {
        Map<TerrainKey, Terrain> cache = TERRAIN.get(world);
        if (cache != null) cache.remove(new TerrainKey(arena.center(), arena.id()));
    }

    public void reset() { search = null; path = List.of(); requested = null; pathIndex = 0; stuckTicks = 0; }
    public String debugState() { return state + " route=" + pathIndex + "/" + path.size(); }
    public boolean searching() { return search != null; }
    public boolean hasPath() { return pathIndex < path.size(); }

    /** Called once per AI tick. Searches never block waiting for a whole map route. */
    public Vec3d next(ServerPlayerEntity actor, BlockPos goal, int tick) {
        if (goal == null || actor.getWorld() != world) return null;
        boolean changed = requested == null || requested.getSquaredDistance(goal) > 4;
        if (changed || search == null && pathIndex >= path.size() && tick - lastSearchTick >= 30) {
            requested = goal.toImmutable(); path = List.of(); pathIndex = 0;
            Node start = nearestStand(actor, actor.getBlockPos(), 1), target = nearestStand(actor, goal, 3);
            lastSearchTick = tick;
            search = start == null || target == null ? null : new Search(start, target);
            state = search == null ? "目标附近无可行地面" : "规划路线";
        }
        if (search != null) advance(actor);
        while (pathIndex < path.size()) {
            Vec3d waypoint = path.get(pathIndex).position();
            if (flatDistanceSquared(actor.getPos(), waypoint) < .20 && Math.abs(actor.getY() - waypoint.y) < .65) pathIndex++;
            else break;
        }
        if (pathIndex >= path.size()) return null;
        Node next = path.get(pathIndex);
        // Skip redundant straight nodes only after checking the entire swept
        // body and support, never cut across a wall, stairwell or floor opening.
        for (int i = pathIndex + 1; i < Math.min(path.size(), pathIndex + 4); i++) {
            Node candidate = path.get(i);
            if (Math.abs(candidate.y() - actor.getY()) > .08 || !straightClear(actor, candidate.position())) break;
            next = candidate; pathIndex = i;
        }
        if (previousPosition != null && actor.isOnGround() && actor.getPos().squaredDistanceTo(previousPosition) < .0003
                && flatDistanceSquared(actor.getPos(), next.position()) > .5) stuckTicks++;
        else stuckTicks = 0;
        previousPosition = actor.getPos();
        if (stuckTicks > 30) { search = null; path = List.of(); pathIndex = 0; lastSearchTick = tick - 30; stuckTicks = 0; }
        state = "沿路线移动";
        return next.position();
    }

    private void advance(ServerPlayerEntity actor) {
        Search current = search;
        Budget global = BUDGETS.computeIfAbsent(world, ignored -> new Budget());
        int tick = world.getServer().getTicks();
        if (global.tick != tick) { global.tick = tick; global.remaining = GLOBAL_EXPANSIONS_PER_TICK; }
        int allowance = Math.min(EXPANSIONS_PER_TICK, global.remaining);
        global.remaining -= allowance;
        for (int budget = 0; budget < allowance && !current.frontier.isEmpty(); budget++) {
            Node node = current.frontier.poll().node();
            if (!current.closed.add(node)) continue;
            if (heuristic(node, current.goal) < heuristic(current.nearest, current.goal)) current.nearest = node;
            if (node.equals(current.goal)) { finish(current, node); return; }
            if (current.closed.size() >= MAX_VISITED) { finish(current, current.nearest); return; }
            double cost = current.cost.get(node);
            for (Edge edge : neighbors(actor, node)) {
                if (current.closed.contains(edge.node())) continue;
                double next = cost + edge.cost();
                if (next >= current.cost.getOrDefault(edge.node(), Double.POSITIVE_INFINITY)) continue;
                current.cost.put(edge.node(), next); current.previous.put(edge.node(), node);
                current.frontier.add(new Open(edge.node(), next + heuristic(edge.node(), current.goal)));
            }
        }
        if (current.frontier.isEmpty()) finish(current, current.nearest);
    }

    private void finish(Search current, Node end) {
        ArrayList<Node> route = new ArrayList<>();
        Node node = end;
        while (node != null && !node.equals(current.start)) { route.add(node); node = current.previous.get(node); }
        Collections.reverse(route); path = List.copyOf(route); pathIndex = 0; search = null;
        state = end.equals(current.goal) ? "路线就绪" : "接近最近可达位置";
    }

    private List<Edge> neighbors(ServerPlayerEntity actor, Node from) {
        ArrayList<Edge> result = new ArrayList<>(8);
        for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int x = from.x + step[0], z = from.z + step[1];
            for (double height : surfaces(x, z, (int) Math.floor(from.y()))) {
                double rise = height - from.y();
                if (!traversableHeight(rise)) continue;
                Node target = new Node(x, z, (int) Math.round(height * 16));
                if (!clear(actor, target)) continue;
                double high = Math.max(height, from.y());
                Box swept = new Box(Math.min(from.x, x) + .21, high + .01, Math.min(from.z, z) + .21,
                        Math.max(from.x, x) + .79, high + 1.79, Math.max(from.z, z) + .79);
                if (world.getBlockCollisions(actor, swept).iterator().hasNext()) continue;
                result.add(new Edge(target, 1 + Math.max(0, rise) * .75 + Math.max(0, -rise - 1) * .8));
            }
        }
        return result;
    }

    private Node nearestStand(ServerPlayerEntity actor, BlockPos target, int radius) {
        Node best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (int x = target.getX() - radius; x <= target.getX() + radius; x++) {
            for (int z = target.getZ() - radius; z <= target.getZ() + radius; z++) {
                for (double height : surfaces(x, z, target.getY())) {
                    double vertical = Math.abs(height - target.getY());
                    if (vertical > 3.1) continue;
                    Node candidate = new Node(x, z, (int) Math.round(height * 16));
                    if (!clear(actor, candidate)) continue;
                    double score = (x - target.getX()) * (x - target.getX()) + (z - target.getZ()) * (z - target.getZ()) + vertical * 3;
                    if (score < bestCost) { bestCost = score; best = candidate; }
                }
            }
        }
        return best;
    }

    private List<Double> surfaces(int x, int z, int aroundY) {
        BlockPos key = new BlockPos(x, aroundY, z);
        List<Double> cached = terrain.surfaces.get(key);
        if (cached != null) return cached;
        ArrayList<Double> result = new ArrayList<>();
        for (int y = aroundY - 4; y <= aroundY + 1; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!arena.inBounds(pos.up()) || !world.getFluidState(pos).isEmpty()) continue;
            BlockState state = world.getBlockState(pos);
            double support = Double.NEGATIVE_INFINITY;
            for (Box box : state.getCollisionShape(world, pos).getBoundingBoxes()) {
                if (box.maxX > .21 && box.minX < .79 && box.maxZ > .21 && box.minZ < .79) support = Math.max(support, y + box.maxY);
            }
            if (Double.isFinite(support) && !result.contains(support)) result.add(support);
        }
        if (terrain.surfaces.size() >= MAX_TERRAIN_CACHE) terrain.surfaces.clear();
        List<Double> immutable = List.copyOf(result); terrain.surfaces.put(key, immutable); return immutable;
    }

    private boolean clear(ServerPlayerEntity actor, Node node) {
        Boolean known = terrain.clearance.get(node);
        if (known != null) return known;
        BlockPos feet = BlockPos.ofFloored(node.x + .5, node.y() + .01, node.z + .5);
        Box body = new Box(node.x + .21, node.y() + .01, node.z + .21, node.x + .79, node.y() + 1.79, node.z + .79);
        boolean pass = arena.inBounds(feet) && world.getFluidState(feet).isEmpty()
                && !world.getBlockCollisions(actor, body).iterator().hasNext();
        if (terrain.clearance.size() >= MAX_TERRAIN_CACHE) terrain.clearance.clear();
        terrain.clearance.put(node, pass); return pass;
    }

    private boolean straightClear(ServerPlayerEntity actor, Vec3d end) {
        Vec3d start = actor.getPos();
        if (flatDistanceSquared(start, end) > 16) return false;
        int steps = Math.max(1, (int) Math.ceil(start.distanceTo(end) * 4));
        for (int i = 1; i <= steps; i++) {
            Vec3d point = start.lerp(end, i / (double) steps);
            Box body = new Box(point.x - .29, point.y + .01, point.z - .29, point.x + .29, point.y + 1.79, point.z + .29);
            if (world.getBlockCollisions(actor, body).iterator().hasNext()
                    || !world.getBlockCollisions(actor, body.offset(0, -.08, 0)).iterator().hasNext()) return false;
        }
        return true;
    }

    private static double heuristic(Node from, Node to) {
        return Math.abs(from.x - to.x) + Math.abs(from.z - to.z) + Math.abs(from.y() - to.y()) * .4;
    }
    static boolean traversableHeight(double rise) { return Double.isFinite(rise) && rise <= 1.25 && rise >= -3; }
    private static double flatDistanceSquared(Vec3d a, Vec3d b) { double x = a.x - b.x, z = a.z - b.z; return x * x + z * z; }
}
