package com.example.pvp.arena.skywars;

import com.example.pvp.arena.ArenaWorld;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.RegistryKey;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.util.math.random.Random;

/**
 * 村庄地图（{@link SkyWarsTheme#VILLAGE}）的中岛村庄生成器。
 *
 * <p><b>为什么不自己盖房子</b>：直接复用原版村庄的 NBT 结构模板
 * （{@code data/minecraft/structure/village/plains/**}），房子/商店/农田/喷泉都是原版造型；
 * 房子里的箱子自带原版村庄战利品表（{@code chests/village/...}），
 * 也就是"物资箱子就用原版村庄刷的箱子"——不需要我们再塞战利品。
 *
 * <p><b>布局</b>：中岛被放大成一整座岛（见 {@link SkyWarsLayout}），这里在岛面上铺
 * 广场 + 8 条放射道路 + 3 圈环路，然后沿"两条路之间"的位置成排摆房子，
 * 用包围盒做碰撞检测，保证不互相压住、不压到路、不出岛。密度靠候选点数量保证。
 *
 * <p>箱子会被登记进 {@link SkyWarsLayout#extraChests()}（带原版战利品表），
 * 所以 3 分钟后的「物资刷新」会把它们清空并重新掷原版战利品。
 */
public final class SkyWarsVillageGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 原版平原村庄的房屋模板（按"出现频率"排：小房子重复几次，神庙/大房子更稀有）。 */
    private static final List<Identifier> HOUSES = List.of(
            house("plains_small_house_1"), house("plains_small_house_2"), house("plains_small_house_3"),
            house("plains_small_house_4"), house("plains_small_house_5"), house("plains_small_house_6"),
            house("plains_small_house_7"), house("plains_small_house_8"),
            // 自带箱子的 7 种房子（原版平原村庄里只有这 7 种带箱）加重权重：
            // 村庄地图的物资全靠这些箱子，比例太低会没东西开
            house("plains_small_house_7"), house("plains_medium_house_2"), house("plains_big_house_1"),
            house("plains_cartographer_1"), house("plains_fisher_cottage_1"), house("plains_tannery_1"),
            house("plains_weaponsmith_1"),
            house("plains_small_house_7"), house("plains_medium_house_2"),
            house("plains_cartographer_1"), house("plains_fisher_cottage_1"), house("plains_tannery_1"),
            house("plains_weaponsmith_1"),
            house("plains_small_house_7"), house("plains_medium_house_2"),
            house("plains_tannery_1"), house("plains_weaponsmith_1"),
            house("plains_big_house_1"), house("plains_cartographer_1"), house("plains_fisher_cottage_1"),
            // 其余房屋/农场/畜栏：只提供掩体与村庄外观
            house("plains_small_house_1"), house("plains_small_house_2"), house("plains_small_house_3"),
            house("plains_small_house_4"), house("plains_small_house_5"), house("plains_small_house_6"),
            house("plains_small_house_8"), house("plains_medium_house_1"),
            house("plains_armorer_house_1"), house("plains_butcher_shop_1"), house("plains_butcher_shop_2"),
            house("plains_fletcher_house_1"), house("plains_library_1"), house("plains_library_2"),
            house("plains_masons_house_1"), house("plains_shepherds_house_1"), house("plains_tool_smith_1"),
            house("plains_temple_3"), house("plains_temple_4"),
            house("plains_small_farm_1"), house("plains_large_farm_1"),
            house("plains_stable_1"), house("plains_stable_2"),
            house("plains_animal_pen_1"), house("plains_animal_pen_2"), house("plains_animal_pen_3"),
            house("plains_accessory_1"));

    /** 中央广场的喷泉（原版村庄 meeting point）。 */
    private static final Identifier FOUNTAIN = Identifier.of("minecraft",
            "village/plains/town_centers/plains_fountain_01");

    /** 放射道路条数 / 环路半径 / 广场半径。 */
    private static final int ROAD_RAYS = 4;
    private static final int[] RING_ROADS = {};
    private static final int PLAZA_RADIUS = 9;

    /** 房屋候选的栅格间距（格）：越小越密，实际能放多少由碰撞/道路检测决定。 */
    private static final int HOUSE_GRID = 12;

    /** 房子/道路/岛缘之间保留的空隙（格）。 */
    private static final int HOUSE_GAP = 1;
    private static final int ROAD_CLEARANCE = 1;
    private static final int ISLAND_MARGIN = 3;

    /** 已占用的水平包围盒（含道路空隙），用于碰撞检测。 */
    private record Rect(int minX, int minZ, int maxX, int maxZ) {
        boolean intersects(Rect other, int gap) {
            return this.minX - gap <= other.maxX && this.maxX + gap >= other.minX
                    && this.minZ - gap <= other.maxZ && this.maxZ + gap >= other.minZ;
        }
    }

    private SkyWarsVillageGenerator() {
    }

    private static Identifier house(String name) {
        return Identifier.of("minecraft", "village/plains/houses/" + name);
    }

    /**
     * 在中岛（{@code layout.middle()}）上盖一座村庄。
     *
     * @param world  竞技场世界（暂存模式也能用：方块/方块实体都会走暂存队列）
     * @param layout 本场布局，用来登记村庄箱子（物资刷新要按这份登记补货）
     * @param theme  当前主题（取填充方块）
     * @param seed   比赛种子，决定房子组合与摆放顺序
     */
    public static void build(ArenaWorld world, SkyWarsLayout layout, SkyWarsTheme theme, int seed) {
        SkyWarsLayout.Island island = layout.middle();
        if (island == null) {
            return;
        }
        BlockPos center = island.center;
        int radius = island.radius;
        StructureTemplateManager templates = world.getStructureTemplateManager();
        Random random = Random.create(seed * 6364136223846793005L + 1442695040888963407L);

        pave(world, center, radius);
        placeTemplate(world, layout, templates, FOUNTAIN, center, BlockRotation.NONE, random, true);

        List<Rect> occupied = new ArrayList<>();
        int houses = 0;
        int chestsBefore = layout.extraChests().size();
        // 栅格铺房：从广场外沿一直铺到岛缘，逐格尝试，碰撞/道路检测决定放不放得下。
        // 用一个稳定的遍历顺序（由内向外）保证同一 seed 结果一致。
        int limit = radius - 8;
        List<int[]> candidates = new ArrayList<>();
        // 栅格整体偏半格，避免整排房子正好压在放射道路上（道路在 x=0 / z=0 线上）
        for (int x = -limit + HOUSE_GRID * 3 / 4; x <= limit; x += HOUSE_GRID) {
            for (int z = -limit + HOUSE_GRID * 3 / 4; z <= limit; z += HOUSE_GRID) {
                if (Math.hypot(x, z) <= limit) {
                    candidates.add(new int[]{center.getX() + x, center.getZ() + z});
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(
                p -> Math.hypot(p[0] - center.getX(), p[1] - center.getZ())));
        for (int[] candidate : candidates) {
            double angle = Math.atan2(candidate[1] - center.getZ(), candidate[0] - center.getX());
            Identifier id = HOUSES.get(random.nextInt(HOUSES.size()));
            if (tryPlaceHouse(world, layout, templates, id, center, radius,
                    candidate[0], candidate[1], angle, random, occupied)) {
                houses++;
            }
        }
        LOGGER.info("[PvP] 村庄地图已生成: 半径 {}，房屋 {} 座，道路 {} 条放射 + {} 圈环路，村庄箱子 {} 个",
                radius, houses, ROAD_RAYS, RING_ROADS.length, layout.extraChests().size() - chestsBefore);

    }

    /** 铺广场 + 放射道路 + 环路（都是土径，和原版村庄路面一致）。 */
    private static void pave(ArenaWorld world, BlockPos center, int radius) {
        int limit = radius - 4;
        double rayStep = Math.PI / (ROAD_RAYS / 2.0);
        for (int dx = -limit; dx <= limit; dx++) {
            for (int dz = -limit; dz <= limit; dz++) {
                double dist = Math.hypot(dx, dz);
                if (dist > limit) {
                    continue;
                }
                boolean road = false;
                if (dist <= PLAZA_RADIUS) {
                    road = true; // 广场
                } else {
                    for (int ring : RING_ROADS) {
                        if (Math.abs(dist - ring) <= 1.5) {
                            road = true;
                            break;
                        }
                    }
                }
                if (!road) {
                    double angle = Math.atan2(dz, dx);
                    for (int k = 0; k < ROAD_RAYS; k++) {
                        double diff = Math.atan2(Math.sin(angle - k * rayStep), Math.cos(angle - k * rayStep));
                        if (Math.abs(dist * Math.sin(diff)) <= 1.5) {
                            road = true; // 放射路（到中心线的垂直距离 ≤1.5 → 3 格宽）
                            break;
                        }
                    }
                }
                if (road) {
                    world.setBlockState(center.add(dx, 0, dz), Blocks.DIRT_PATH.getDefaultState(), 3);
                }
            }
        }
    }

    /**
     * 尝试把一座房子的模板放到 (x, z)，成功返回 true。
     *
     * <p>旋转后的实际占位一律用 {@link StructureTemplate#calculateBoundingBox} 算，
     * 不自己推（旋转 90°/180° 时模板会往反方向长，自己推必然算错，房子就会互相压住/出岛）。
     * 于是先算"放在原点时的包围盒"，再平移让包围盒中心落在 (x, z)。
     */
    private static boolean tryPlaceHouse(ArenaWorld world, SkyWarsLayout layout, StructureTemplateManager templates,
                                         Identifier id, BlockPos center, int islandRadius, int x, int z,
                                         double angle, Random random, List<Rect> occupied) {
        Optional<StructureTemplate> loaded = templates.getTemplate(id);
        if (loaded.isEmpty()) {
            return false; // 数据包缺少模板（理论上不会）——跳过这座
        }
        StructureTemplate template = loaded.get();
        BlockRotation rotation = rotationFacing(center, x, z);
        StructurePlacementData data = new StructurePlacementData()
                .setRotation(rotation)
                .setIgnoreEntities(true) // 不刷村民/动物，竞技场里不允许额外实体
                .setUpdateNeighbors(false);
        BlockBox originBox = template.calculateBoundingBox(data, BlockPos.ORIGIN);
        int y = center.getY() + 1;
        BlockPos origin = new BlockPos(
                x - (originBox.getMinX() + originBox.getMaxX()) / 2,
                y,
                z - (originBox.getMinZ() + originBox.getMaxZ()) / 2);
        BlockBox box = template.calculateBoundingBox(data, origin);

        // 1) 整座房子留在岛内（四角都在岛缘以内）
        for (int cx : new int[]{box.getMinX(), box.getMaxX()}) {
            for (int cz : new int[]{box.getMinZ(), box.getMaxZ()}) {
                if (Math.hypot(cx - center.getX(), cz - center.getZ()) > islandRadius - ISLAND_MARGIN) {
                    return false;
                }
            }
        }
        // 2) 不压道路：四角到放射路中心线的垂直距离、到每条环路的距离都要够
        for (int cx : new int[]{box.getMinX(), box.getMaxX()}) {
            for (int cz : new int[]{box.getMinZ(), box.getMaxZ()}) {
                if (tooCloseToRoad(center, cx, cz)) {
                    return false;
                }
            }
        }
        // 3) 不与已放置的房子/喷泉重叠（含空隙）
        Rect rect = new Rect(box.getMinX(), box.getMinZ(), box.getMaxX(), box.getMaxZ());
        for (Rect other : occupied) {
            if (rect.intersects(other, HOUSE_GAP)) {
                return false;
            }
        }

        if (!template.place(world, origin, origin, data, random, Block.NOTIFY_ALL)) {
            return false;
        }
        occupied.add(rect);

        // 4) 架空的地基补成圆石（房子自带地基通常 1 层，门廊/台阶会悬空）
        fillGaps(world, box, y);
        // 5) 收集房子自带的箱子（原版村庄战利品表），登记给"物资刷新"
        collectChests(world, layout, box);
        return true;
    }

    /** 让房子的正面朝向岛心（原版房屋默认朝南，按所在象限转 90° 的倍数）。 */
    private static BlockRotation rotationFacing(BlockPos center, int x, int z) {
        int dx = x - center.getX();
        int dz = z - center.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? BlockRotation.CLOCKWISE_90 : BlockRotation.COUNTERCLOCKWISE_90;
        }
        return dz >= 0 ? BlockRotation.CLOCKWISE_180 : BlockRotation.NONE;
    }

    /** 该点是否离任何道路太近（道路 3 格宽，再留 ROAD_CLEARANCE 的空隙）。 */
    private static boolean tooCloseToRoad(BlockPos center, int x, int z) {
        double dx = x - center.getX();
        double dz = z - center.getZ();
        double dist = Math.hypot(dx, dz);
        double clearance = ROAD_CLEARANCE + 1.5;
        if (dist <= PLAZA_RADIUS + clearance) {
            return true;
        }
        for (int ring : RING_ROADS) {
            if (Math.abs(dist - ring) <= clearance) {
                return true;
            }
        }
        double angle = Math.atan2(dz, dx);
        double rayStep = Math.PI / (ROAD_RAYS / 2.0);
        for (int k = 0; k < ROAD_RAYS; k++) {
            double diff = Math.atan2(Math.sin(angle - k * rayStep), Math.cos(angle - k * rayStep));
            if (Math.abs(dist * Math.sin(diff)) <= clearance) {
                return true;
            }
        }
        return false;
    }

    /** 把房子下面架空的部分填成圆石，避免门廊/台阶悬空。 */
    private static void fillGaps(ArenaWorld world, BlockBox box, int floorY) {
        for (int x = box.getMinX(); x <= box.getMaxX(); x++) {
            for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) {
                int lowest = Integer.MAX_VALUE;
                for (int y = box.getMinY(); y <= box.getMaxY(); y++) {
                    if (!world.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        lowest = y;
                        break;
                    }
                }
                if (lowest == Integer.MAX_VALUE) {
                    continue;
                }
                for (int y = floorY; y < lowest; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, Blocks.COBBLESTONE.getDefaultState(), 3);
                    }
                }
            }
        }
    }

    /** 扫描房子范围内的箱子，把它们的原版战利品表登记给"物资刷新"。 */
    private static void collectChests(ArenaWorld world, SkyWarsLayout layout, BlockBox box) {
        for (int x = box.getMinX(); x <= box.getMaxX(); x++) {
            for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) {
                for (int y = box.getMinY(); y <= box.getMaxY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!(world.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
                        continue;
                    }
                    RegistryKey<LootTable> table = chest.getLootTable();
                    if (table == null) {
                        continue; // 模板里没配战利品表的箱子（极少见）不管
                    }
                    layout.addExtraChest(new SkyWarsLayout.ExtraChest(pos, true, -1, table));
                }
            }
        }
    }

    /** 在指定位置放一个模板（用于中央喷泉）；同样登记它自带的箱子。 */
    private static void placeTemplate(ArenaWorld world, SkyWarsLayout layout, StructureTemplateManager templates,
                                      Identifier id, BlockPos center, BlockRotation rotation, Random random,
                                      boolean registerChests) {
        Optional<StructureTemplate> loaded = templates.getTemplate(id);
        if (loaded.isEmpty()) {
            LOGGER.warn("[PvP] 村庄模板缺失: {}", id);
            return;
        }
        StructureTemplate template = loaded.get();
        StructurePlacementData data = new StructurePlacementData()
                .setRotation(rotation)
                .setIgnoreEntities(true)
                .setUpdateNeighbors(false);
        BlockBox originBox = template.calculateBoundingBox(data, BlockPos.ORIGIN);
        BlockPos origin = new BlockPos(
                center.getX() - (originBox.getMinX() + originBox.getMaxX()) / 2,
                center.getY() + 1,
                center.getZ() - (originBox.getMinZ() + originBox.getMaxZ()) / 2);
        if (!template.place(world, origin, origin, data, random, Block.NOTIFY_ALL)) {
            return;
        }
        BlockBox box = template.calculateBoundingBox(data, origin);
        if (registerChests) {
            collectChests(world, layout, box);
        }
    }
}
