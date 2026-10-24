package com.example.pvp.arena.skywars;

import com.example.pvp.arena.ArenaWorld;
import com.mojang.logging.LogUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 真正改变中央拓扑的五种结构。外圈沿用布局的公平主路，中央箱数沿用原箱数加两只分层箱，
 * 解决不同地图仅换材质、换装饰而战斗路线始终相同的问题。
 *
 * <p>每种结构在最终圈内保留落脚面及双向上下通路；生成器只放空箱，物资和补货仍由统一物资表处理。
 */
public final class SkyWarsStructureGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FLAGS = 3;

    private SkyWarsStructureGenerator() { }

    public static void generate(ArenaWorld world, SkyWarsLayout layout, SkyWarsTheme theme) {
        if (layout.middle() == null || !layout.mapStyle().usesCustomStructure()) return;
        Builder builder = new Builder(world, layout, theme);
        builder.clearOldCenter();
        switch (layout.mapStyle()) {
            case RING_CITADEL -> builder.ringCitadel();
            case SHATTERED_ARCHIPELAGO -> builder.shatteredArchipelago();
            case CANOPY -> builder.canopy();
            case VIADUCT -> builder.viaduct();
            case EXCAVATION -> builder.excavation();
            default -> throw new IllegalStateException("不支持的中央结构: " + layout.mapStyle());
        }
        builder.placeChests(layout.middle().chests().size() + 2);
        LOGGER.info("空岛中央结构: style={}, radius={}, 箱数={}, 箱位={}",
                layout.mapStyle().name(), layout.middle().radius(), layout.centralStructureChests().size(),
                layout.centralStructureChests());
    }

    private static final class Builder {
        private final ArenaWorld world;
        private final SkyWarsLayout layout;
        private final BlockPos center;
        private final int radius;
        private final BlockState masonry;
        private final BlockState accent;
        private final BlockState timber;
        private final BlockState trunk;
        private final BlockState foliage;
        private final Set<Long> passages = new HashSet<>();
        private final Set<Long> reachableFloors = new HashSet<>();
        private final Set<BlockPos> chestCandidates = new LinkedHashSet<>();

        private Builder(ArenaWorld world, SkyWarsLayout layout, SkyWarsTheme theme) {
            this.world = world;
            this.layout = layout;
            this.center = layout.middle().center();
            this.radius = layout.middle().radius();
            this.masonry = switch (theme) {
                case ICE -> Blocks.PACKED_ICE.getDefaultState();
                case NETHER -> Blocks.POLISHED_BLACKSTONE.getDefaultState();
                case END -> Blocks.PURPUR_BLOCK.getDefaultState();
                case OVERWORLD, VILLAGE -> Blocks.STONE_BRICKS.getDefaultState();
            };
            this.accent = switch (theme) {
                case ICE -> Blocks.BLUE_ICE.getDefaultState();
                case NETHER -> Blocks.NETHER_BRICKS.getDefaultState();
                case END -> Blocks.END_STONE_BRICKS.getDefaultState();
                case OVERWORLD, VILLAGE -> Blocks.MOSSY_STONE_BRICKS.getDefaultState();
            };
            this.timber = switch (theme) {
                case NETHER -> Blocks.CRIMSON_PLANKS.getDefaultState();
                case END -> Blocks.CHERRY_PLANKS.getDefaultState();
                case ICE -> Blocks.SPRUCE_PLANKS.getDefaultState();
                case OVERWORLD, VILLAGE -> Blocks.OAK_PLANKS.getDefaultState();
            };
            this.trunk = switch (theme) {
                case NETHER -> Blocks.CRIMSON_STEM.getDefaultState();
                case END -> Blocks.CHERRY_LOG.getDefaultState();
                case ICE -> Blocks.SPRUCE_LOG.getDefaultState();
                case OVERWORLD, VILLAGE -> Blocks.OAK_LOG.getDefaultState();
            };
            this.foliage = (theme == SkyWarsTheme.ICE ? Blocks.SPRUCE_LEAVES
                    : theme == SkyWarsTheme.END ? Blocks.CHERRY_LEAVES : Blocks.OAK_LEAVES)
                    .getDefaultState().with(LeavesBlock.PERSISTENT, true);
        }

        private void clearOldCenter() {
            int limit = radius + 3;
            for (int x = -limit; x <= limit; x++) {
                for (int z = -limit; z <= limit; z++) {
                    if (x * x + z * z > limit * limit) continue;
                    for (int y = -14; y <= 24; y++) {
                        BlockPos pos = center.add(x, y, z);
                        // 正常生成时中央尚为空；不把整片空气也放进分帧铺图队列。
                        if (!world.getBlockState(pos).isAir()) set(pos, Blocks.AIR.getDefaultState());
                    }
                }
            }
        }

        private void ringCitadel() {
            disk(center, radius, 3, masonry);
            int wallRadius = radius - 2;
            for (int x = -wallRadius; x <= wallRadius; x++) {
                for (int z = -wallRadius; z <= wallRadius; z++) {
                    double distance = Math.hypot(x, z);
                    if (distance <= wallRadius - 2 || distance > wallRadius) continue;
                    // 四个三格宽门洞，城墙顶的回廊仍完整跨过门洞。
                    boolean gate = Math.abs(x) <= 1 || Math.abs(z) <= 1;
                    for (int y = gate ? 4 : 1; y <= 4; y++) set(center.add(x, y, z), accent);
                }
            }
            int towerOffset = (int) Math.floor((radius - 4) / Math.sqrt(2.0));
            for (int sx : new int[]{-1, 1}) {
                for (int sz : new int[]{-1, 1}) {
                    BlockPos tower = center.add(sx * towerOffset, 0, sz * towerOffset);
                    for (int x = -2; x <= 2; x++) {
                        for (int z = -2; z <= 2; z++) {
                            if (Math.abs(x) != 2 && Math.abs(z) != 2) continue;
                            for (int y = 1; y <= 6; y++) set(tower.add(x, y, z), masonry);
                        }
                    }
                    disk(tower.up(4), 2, 0, accent);
                    disk(tower.up(7), 3, 0, accent);
                    // 向中心开的门连接内院；顶部仅为可挖城楼屋顶，不放独占物资。
                    for (int y = 1; y <= 3; y++) {
                        set(tower.add(-sx * 2, y, 0), Blocks.AIR.getDefaultState());
                        set(tower.add(0, y, -sz * 2), Blocks.AIR.getDefaultState());
                    }
                    for (int y = 5; y <= 6; y++) {
                        for (int sign : new int[]{-1, 1}) {
                            set(tower.add(sign * 2, y, 0), Blocks.AIR.getDefaultState());
                            set(tower.add(0, y, sign * 2), Blocks.AIR.getDefaultState());
                        }
                    }
                }
            }
            // 先开地面入口，再建高台坡道，防止开门净空挖掉与其交叉的上行台阶。
            // 出生方向会随机旋转，逐路开门，避免某些玩家正对城墙或封闭塔身。
            for (SkyWarsLayout.Island spawn : layout.spawnIslands()) {
                double angle = Math.atan2(spawn.center().getZ() - center.getZ(),
                        spawn.center().getX() - center.getX());
                walkway(polar(radius, angle, 0), polar(5, angle, 0), 1, masonry);
            }
            BlockPos keep = center.up(4);
            disk(keep, 4, 0, masonry);
            // 终圈会切掉外墙，城楼主层改由圈内斜桥直连中央，避免留下孤立高台。
            for (int sx : new int[]{-1, 1}) {
                for (int sz : new int[]{-1, 1}) {
                    walkway(keep, center.add(sx * towerOffset, 4, sz * towerOffset), 1, masonry);
                }
            }
            walkway(keep.add(0, 0, -4), keep.add(0, 0, -wallRadius), 1, accent);
            walkway(keep.add(0, 0, 4), keep.add(0, 0, wallRadius), 1, accent);
            // 两个坡道完全落在半径10的最终圈内；城墙坍塌后中央高台仍可从两侧进攻。
            walkway(center.add(-9, 0, 0), keep.add(-4, 0, 0), 1, masonry);
            walkway(center.add(9, 0, 0), keep.add(4, 0, 0), 1, masonry);
            candidateRing(center, 6, 12, Math.PI / 12.0);
            candidateRing(center, radius - 4, 16, Math.PI / 16.0);
            candidateCorners(keep, 2);
            oreAt(center.add(3, -1, 3));
            oreAt(center.add(-3, -1, -3));
        }

        private void shatteredArchipelago() {
            int coreRadius = layout.compact() ? 5 : 7;
            disk(center.down(2), coreRadius, 3, accent);
            int players = layout.spawnIslands().size();
            int petals = players <= 4 ? players * 2 : players;
            int distance = radius - 4;
            double phase = routePhase();
            List<BlockPos> islands = new ArrayList<>();
            for (int i = 0; i < petals; i++) {
                double angle = phase + i * Math.PI * 2.0 / petals;
                BlockPos island = polar(distance, angle, 2);
                islands.add(island);
                disk(island, 3, 2, masonry);
                // 短锥形岛底与中间虚空让小岛保持独立轮廓。
                disk(island.down(3), 2, 1, accent);
                candidateRing(island, 2, 8, angle + Math.PI / 8.0);
            }
            for (int i = 0; i < petals; i++) {
                double angle = phase + i * Math.PI * 2.0 / petals;
                BlockPos inner = center.down(2);
                walkway(inner, islands.get(i), 1, masonry);
                // 环向短桥让每个浮岛都有侧面入口，避免唯一窄桥可被完全封锁。
                walkway(islands.get(i), islands.get((i + 1) % petals), 1, timber);
            }
            candidateRing(center.down(2), coreRadius - 1, 16, phase + Math.PI / 16.0);
            oreAt(center.add(0, -3, 3));
            oreAt(center.add(0, -3, -3));
        }

        private void canopy() {
            disk(center, radius, 2, accent);
            int treeOffset = layout.compact() ? 5 : 6;
            int crownRadius = layout.compact() ? 3 : 4;
            List<BlockPos> crowns = new ArrayList<>();
            for (int sx : new int[]{-1, 1}) {
                for (int sz : new int[]{-1, 1}) {
                    BlockPos tree = center.add(sx * treeOffset, 0, sz * treeOffset);
                    for (int x = 0; x <= 1; x++) {
                        for (int z = 0; z <= 1; z++) {
                            for (int y = 1; y <= 5; y++) set(tree.add(x * sx, y, z * sz), trunk);
                        }
                    }
                    BlockPos crown = tree.up(6);
                    crowns.add(crown);
                    // 树冠可见树叶永久保留，实际营地和箱台由完整木板承重。
                    disk(crown.down(), crownRadius, 0, foliage);
                    disk(crown, crownRadius, 0, foliage);
                    disk(crown, crownRadius - 1, 0, timber);
                    candidateRing(crown, crownRadius - 1, 8, Math.PI / 8.0);
                }
            }
            BlockPos camp = center.up(6);
            disk(camp, 4, 0, timber);
            for (BlockPos crown : crowns) walkway(camp, crown, 1, timber);
            walkway(crowns.get(0), crowns.get(1), 1, timber);
            walkway(crowns.get(2), crowns.get(3), 1, timber);
            walkway(crowns.get(0), crowns.get(2), 1, timber);
            walkway(crowns.get(1), crowns.get(3), 1, timber);
            walkway(center.add(-9, 0, 0), camp.add(-3, 0, 0), 1, timber);
            walkway(center.add(9, 0, 0), camp.add(3, 0, 0), 1, timber);
            candidateCorners(camp, 2);
            candidateRing(center, 7, 16, Math.PI / 16.0);
            candidateRing(center, radius - 2, 16, Math.PI / 16.0);
            oreAt(center.add(3, -1, 3));
            oreAt(center.add(-3, -1, -3));
        }

        private void viaduct() {
            BlockPos lower = center.down(3);
            BlockPos upper = center.up(3);
            int end = radius - 3;
            disk(lower, 5, 1, accent);
            // 下层十字桥及上层矩形回廊在空间中交错，桥间保持至少两格身体净空。
            walkway(lower.add(-radius, 0, 0), lower.add(radius, 0, 0), 2, accent);
            walkway(lower.add(0, 0, -radius), lower.add(0, 0, radius), 2, accent);
            for (int sign : new int[]{-1, 1}) {
                walkway(upper.add(-end, 0, sign * 4), upper.add(end, 0, sign * 4), 1, masonry);
                walkway(upper.add(sign * end, 0, -4), upper.add(sign * end, 0, 4), 1, masonry);
                for (int zSign : new int[]{-1, 1}) {
                    BlockPos pier = upper.add(sign * end, 0, zSign * 4);
                    disk(pier, 3, 0, masonry);
                    for (int y = -7; y < 3; y++) set(center.add(sign * end, y, zSign * 4), accent);
                    candidateCorners(pier, 2);
                }
            }
            disk(upper, 3, 0, masonry);
            walkway(upper.add(0, 0, -4), upper.add(0, 0, 4), 1, masonry);
            // 给每一个实际出生方向同高度的接入点，避免只有正对桥轴的玩家容易抢中。
            for (SkyWarsLayout.Island spawn : layout.spawnIslands()) {
                double angle = Math.atan2(spawn.center().getZ() - center.getZ(),
                        spawn.center().getX() - center.getX());
                BlockPos landing = polar(radius - 2, angle, -3);
                disk(landing, 2, 1, accent);
                walkway(polar(3, angle, -3), landing, 1, accent);
                candidateRing(landing, 1, 4, angle + Math.PI / 4.0);
            }
            walkway(lower.add(-8, 0, 0), upper.add(-2, 0, 0), 1, masonry);
            walkway(lower.add(8, 0, 0), upper.add(2, 0, 0), 1, masonry);
            candidateCorners(lower, 2);
            candidateCorners(upper, 2);
            for (int x : new int[]{-6, -3, 3, 6}) {
                chestCandidates.add(upper.add(x, 0, -5));
                chestCandidates.add(upper.add(x, 0, 5));
            }
            oreAt(lower.add(3, -1, 0));
            oreAt(lower.add(-3, -1, 0));
        }

        private void excavation() {
            int basinRadius = layout.compact() ? 5 : 7;
            // 同心台阶形成真正下降八格的盆地；中段宽环道高度-4，内圈地板高度-8。
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    double distance = Math.hypot(x, z);
                    if (distance > radius) continue;
                    int y = excavationHeight((int) Math.ceil(distance), basinRadius);
                    BlockState top = y == -4 || y == 0 ? masonry : accent;
                    BlockPos floor = center.add(x, y, z);
                    for (int depth = 0; depth < 4; depth++) set(floor.down(depth), top);
                }
            }
            // 四个三格宽入口把环道接到坑底；最终圈裁掉外缘时保留的盆地仍处处连通。
            for (int sign : new int[]{-1, 1}) {
                walkway(center.add(sign * radius, 0, 0), center.add(sign * (radius - 8), -8, 0), 1, masonry);
                walkway(center.add(0, 0, sign * radius), center.add(0, -8, sign * (radius - 8)), 1, masonry);
                walkway(center.add(sign * (radius - 8), -8, 0), center.add(sign * basinRadius, -8, 0), 1, masonry);
                walkway(center.add(0, -8, sign * (radius - 8)), center.add(0, -8, sign * basinRadius), 1, masonry);
            }
            candidateRing(center.down(8), basinRadius - 2, 12, Math.PI / 12.0);
            candidateRing(center.down(4), radius - 5, 20, Math.PI / 20.0);
            for (int sx : new int[]{-1, 1}) {
                for (int sz : new int[]{-1, 1}) {
                    int coordinate = layout.compact() ? 5 : 7;
                    int ring = (int) Math.ceil(Math.hypot(coordinate, coordinate));
                    oreAt(center.add(sx * coordinate, excavationHeight(ring, basinRadius) - 1, sz * coordinate));
                }
            }
        }

        private int excavationHeight(int ring, int basinRadius) {
            if (ring <= basinRadius) return -8;
            if (ring <= basinRadius + 4) return -8 + ring - basinRadius;
            if (ring <= radius - 4) return -4;
            return ring - radius;
        }

        private void disk(BlockPos point, int diskRadius, int depth, BlockState block) {
            for (int x = -diskRadius; x <= diskRadius; x++) {
                for (int z = -diskRadius; z <= diskRadius; z++) {
                    if (x * x + z * z > diskRadius * diskRadius) continue;
                    for (int down = 0; down <= depth; down++) set(point.add(x, -down, z), block);
                }
            }
        }

        /** 每一步最多升降一格；全部主通路至少三格宽，并显式清理两格身体净空。 */
        private void walkway(BlockPos from, BlockPos to, int halfWidth, BlockState block) {
            int dx = to.getX() - from.getX();
            int dz = to.getZ() - from.getZ();
            int steps = Math.max(Math.abs(dx), Math.abs(dz));
            if (steps == 0) return;
            if (Math.abs(to.getY() - from.getY()) > steps) {
                throw new IllegalArgumentException("空岛坡道高度超过水平距离: " + from + " -> " + to);
            }
            boolean widenZ = Math.abs(dx) >= Math.abs(dz);
            for (int i = 0; i <= steps; i++) {
                double progress = i / (double) steps;
                int x = from.getX() + (int) Math.round(dx * progress);
                int y = from.getY() + (int) Math.round((to.getY() - from.getY()) * progress);
                int z = from.getZ() + (int) Math.round(dz * progress);
                for (int side = -halfWidth; side <= halfWidth; side++) {
                    BlockPos floor = new BlockPos(widenZ ? x : x + side, y, widenZ ? z + side : z);
                    set(floor, block);
                    passages.add(floor.asLong());
                    // 跳上一格时头部会先升高；坡道要清三格，水平桥保持两格净空。
                    clearAbove(floor, from.getY() == to.getY() ? 2 : 3);
                }
            }
        }

        private void clearAbove(BlockPos floor, int height) {
            for (int y = 1; y <= height; y++) {
                BlockPos pos = floor.up(y);
                if (!world.getBlockState(pos).isAir()) set(pos, Blocks.AIR.getDefaultState());
            }
        }

        private void oreAt(BlockPos point) {
            set(point, Blocks.IRON_ORE.getDefaultState());
            set(point.down(), Blocks.COAL_ORE.getDefaultState());
        }

        private BlockPos polar(int distance, double angle, int y) {
            return center.add((int) Math.round(Math.cos(angle) * distance), y,
                    (int) Math.round(Math.sin(angle) * distance));
        }

        private double routePhase() {
            if (layout.spawnIslands().isEmpty()) return 0;
            BlockPos spawn = layout.spawnIslands().get(0).center();
            return Math.atan2(spawn.getZ() - center.getZ(), spawn.getX() - center.getX());
        }

        private void candidateRing(BlockPos point, int ringRadius, int count, double phase) {
            for (int i = 0; i < count; i++) {
                double angle = phase + Math.PI * 2.0 * i / count;
                chestCandidates.add(point.add((int) Math.round(Math.cos(angle) * ringRadius), 0,
                        (int) Math.round(Math.sin(angle) * ringRadius)));
            }
        }

        private void candidateCorners(BlockPos point, int offset) {
            for (int sx : new int[]{-1, 1}) {
                for (int sz : new int[]{-1, 1}) chestCandidates.add(point.add(sx * offset, 0, sz * offset));
            }
        }

        private void placeChests(int count) {
            collectReachableFloors();
            List<BlockPos> available = new ArrayList<>();
            for (BlockPos floor : chestCandidates) if (safeChestFloor(floor, true)) available.add(floor);
            // 最大自定义预算为18箱；候选台位不足时只使用已有可走宽地面，不额外堆出箱岛。
            if (available.size() < count * 2) {
                for (int y = -8; y <= 6; y++) {
                    for (int x = -radius; x <= radius; x++) {
                        for (int z = -radius; z <= radius; z++) {
                            if (x * x + z * z > radius * radius || x * x + z * z < 9) continue;
                            BlockPos floor = center.add(x, y, z);
                            if (!chestCandidates.contains(floor) && safeChestFloor(floor, true)) available.add(floor);
                        }
                    }
                }
            }
            List<BlockPos> placed = new ArrayList<>();
            List<BlockPos> selectedFloors = new ArrayList<>();
            int initial = available.isEmpty() ? 0 : Math.floorMod(layout.styleSeed(), available.size());
            while (placed.size() < count && !available.isEmpty()) {
                int chosen = -1;
                double bestDistance = -1;
                for (int index = 0; index < available.size(); index++) {
                    BlockPos candidate = available.get(index);
                    if (!safeChestFloor(candidate, true)) continue;
                    double nearest = selectedFloors.isEmpty() ? (index == initial ? 1 : 0) : Double.MAX_VALUE;
                    for (BlockPos selected : selectedFloors) {
                        nearest = Math.min(nearest, candidate.getSquaredDistance(selected));
                    }
                    if ((!selectedFloors.isEmpty() && nearest < 4) || nearest <= bestDistance) continue;
                    chosen = index;
                    bestDistance = nearest;
                }
                if (chosen < 0) break;
                BlockPos floor = available.remove(chosen);
                BlockPos chest = floor.up();
                set(chest, Blocks.CHEST.getDefaultState());
                clearAbove(chest, 1);
                placed.add(chest);
                // 箱子本身也会挡路；试放后重查全部箱位，不能把早先的箱台封成死角。
                collectReachableFloors();
                if (!chestsReachable(placed)) {
                    placed.remove(placed.size() - 1);
                    set(chest, Blocks.AIR.getDefaultState());
                    collectReachableFloors();
                    continue;
                }
                selectedFloors.add(floor);
            }
            layout.setCentralStructureChests(placed);
            if (placed.size() != count) {
                throw new IllegalStateException("空岛中央安全箱位不足: " + layout.mapStyle() + " " + placed.size() + "/" + count);
            }
        }

        private boolean safeChestFloor(BlockPos floor, boolean avoidPassage) {
            if (avoidPassage && passages.contains(floor.asLong())) return false;
            if (!reachableFloors.contains(floor.asLong())) return false;
            if (!world.getBlockState(floor).isSideSolidFullSquare(world, floor, Direction.UP)
                    || !world.getBlockState(floor.up()).isAir() || !world.getBlockState(floor.up(2)).isAir()) return false;
            int approaches = 0;
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos adjacent = floor.offset(direction);
                if (world.getBlockState(adjacent).isSideSolidFullSquare(world, adjacent, Direction.UP)
                        && world.getBlockState(adjacent.up()).isAir()
                        && world.getBlockState(adjacent.up(2)).isAir()) approaches++;
            }
            return approaches >= 2;
        }

        /** 局部能站立不代表能走到：排除封闭城楼及坡桥切出的孤立箱台。 */
        private void collectReachableFloors() {
            reachableFloors.clear();
            BlockPos start = null;
            double nearest = Double.MAX_VALUE;
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    for (int y = -8; y <= 7; y++) {
                        BlockPos floor = center.add(x, y, z);
                        double distance = floor.getSquaredDistance(center);
                        if (distance < nearest && canStandAbove(floor)) {
                            start = floor;
                            nearest = distance;
                        }
                    }
                }
            }
            if (start == null) throw new IllegalStateException("空岛中央结构缺少可站立的地面: " + layout.mapStyle());
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            reachableFloors.add(start.asLong());
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos floor = queue.removeFirst();
                for (Direction direction : Direction.Type.HORIZONTAL) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos next = floor.offset(direction).up(dy);
                        int dx = next.getX() - center.getX();
                        int dz = next.getZ() - center.getZ();
                        int y = next.getY() - center.getY();
                        if (dx * dx + dz * dz > (radius + 3) * (radius + 3) || y < -14 || y > 24
                                || reachableFloors.contains(next.asLong()) || !canStandAbove(next)
                                || (dy > 0 && !world.getBlockState(floor.up(3)).isAir())
                                || (dy < 0 && !world.getBlockState(next.up(3)).isAir())) continue;
                        reachableFloors.add(next.asLong());
                        queue.addLast(next);
                    }
                }
            }
        }

        private boolean canStandAbove(BlockPos floor) {
            return world.getBlockState(floor).isSideSolidFullSquare(world, floor, Direction.UP)
                    && world.getBlockState(floor.up()).isAir()
                    && world.getBlockState(floor.up(2)).isAir();
        }

        private boolean chestsReachable(List<BlockPos> chests) {
            for (BlockPos chest : chests) {
                boolean accessible = false;
                for (Direction direction : Direction.Type.HORIZONTAL) {
                    BlockPos floor = chest.down().offset(direction);
                    if (reachableFloors.contains(floor.asLong()) && canStandAbove(floor)) {
                        accessible = true;
                        break;
                    }
                }
                if (!accessible) return false;
            }
            return true;
        }

        private void set(BlockPos pos, BlockState state) {
            int dx = pos.getX() - center.getX();
            int dz = pos.getZ() - center.getZ();
            // 中央清区之外是其他玩家的卫星岛，任何装饰或桥面都不能侵占。
            if (dx * dx + dz * dz > (radius + 3) * (radius + 3)) return;
            world.setBlockState(pos, state, FLAGS);
        }
    }
}
