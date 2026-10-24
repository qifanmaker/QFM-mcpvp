package com.example.pvp.arena.skywars;

import com.example.pvp.arena.ArenaWorld;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.JigsawBlockEntity;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.util.math.random.Random;

/**
 * 村庄地图（{@link SkyWarsTheme#VILLAGE}）的中岛村庄生成器。
 *
 * <p><b>为什么不自己盖房子</b>：直接复用原版村庄的 NBT 结构模板
 * （{@code data/minecraft/structure/village/plains/**}），房子/商店/农田/喷泉都是原版造型；
 * 放置后清理结构/拼图标记，并把所有箱子纳入空岛中岛物资与补货策略。
 *
 * <p><b>布局</b>：中岛被放大成一整座岛（见 {@link SkyWarsLayout}），这里在岛面上铺
 * 广场 + 放射道路，然后沿"两条路之间"的位置成排摆房子，
 * 用包围盒做碰撞检测，保证不互相压住、不压到路、不出岛。密度靠候选点数量保证。
 *
 * <p>房屋箱和分区散布的露天箱统一登记为 CENTER，不保留原版 LootTable 或 Items。
 */
public final class SkyWarsVillageGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 原版平原村庄的房屋模板（按"出现频率"排：小房子重复几次，神庙/大房子更稀有）。 */
    private static final List<Identifier> HOUSES = List.of(
            house("plains_small_house_1"), house("plains_small_house_2"), house("plains_small_house_3"),
            house("plains_small_house_4"), house("plains_small_house_5"), house("plains_small_house_6"),
            house("plains_small_house_7"), house("plains_small_house_8"),
            // 自带箱子的 7 种房子（原版平原村庄里只有这 7 种带箱）加重权重：
            // 保留搜屋收益，露天箱和中央军械箱再补充开放区域的争夺点
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
        int houseChests = layout.extraChests().size() - chestsBefore;
        addOutdoorChests(world, layout, center, radius, occupied, seed);
        LOGGER.info("[PvP] 村庄地图已生成: 半径 {}，房屋 {} 座，道路 {} 条放射 + {} 圈环路，房屋战斗箱 {} 个",
                radius, houses, ROAD_RAYS, RING_ROADS.length, houseChests);

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

        // 原版模板直接放置不会执行完整拼图生成流程，必须先替换标记再处理地基/物资。
        cleanTemplateMarkers(world, box, rotation);
        // 4) 架空的地基补成圆石（房子自带地基通常 1 层，门廊/台阶会悬空）
        fillGaps(world, box, y);
        // 5) 所有房屋箱统一交给空岛初始物资与补货策略，包括模板中没有LootTable的空箱。
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

    /**
     * 模板自身携带的生成标记不应成为竞技场方块。拼图按final_state恢复真实建筑材料，
     * 缺失/非法/递归标记均退化为空气，避免保留可见的编辑方块。
     */
    private static void cleanTemplateMarkers(ArenaWorld world, BlockBox box, BlockRotation rotation) {
        for (int x = box.getMinX(); x <= box.getMaxX(); x++) {
            for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) {
                for (int y = box.getMinY(); y <= box.getMaxY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isOf(Blocks.STRUCTURE_BLOCK)) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                    } else if (state.isOf(Blocks.JIGSAW)) {
                        BlockState replacement = Blocks.AIR.getDefaultState();
                        BlockEntity entity = world.getBlockEntity(pos);
                        String finalState = entity instanceof JigsawBlockEntity jigsaw ? jigsaw.getFinalState() : "";
                        if (finalState != null && !finalState.isBlank()) {
                            try {
                                StringReader reader = new StringReader(finalState);
                                BlockArgumentParser.BlockResult result = BlockArgumentParser.block(
                                        world.createCommandRegistryWrapper(RegistryKeys.BLOCK), reader, true);
                                reader.skipWhitespace();
                                if (reader.canRead()) throw new IllegalArgumentException("Unexpected final_state suffix");
                                replacement = result.blockState().rotate(rotation);
                            } catch (CommandSyntaxException | IllegalArgumentException exception) {
                                LOGGER.warn("[PvP] 村庄拼图终态无效，已清为空气: {}，{}", pos, finalState);
                            }
                        }
                        if (replacement.isOf(Blocks.JIGSAW) || replacement.isOf(Blocks.STRUCTURE_BLOCK)
                                || replacement.isOf(Blocks.STRUCTURE_VOID)) replacement = Blocks.AIR.getDefaultState();
                        world.setBlockState(pos, replacement, Block.NOTIFY_ALL);
                        if (replacement.getBlock() instanceof BlockEntityProvider provider) {
                            // staging的原位置仍可能缓存Jigsaw BE；显式登记新类型，不能把旧实体留给最终方块。
                            BlockEntity replacementEntity = provider.createBlockEntity(pos, replacement);
                            if (replacementEntity != null) world.addBlockEntity(replacementEntity);
                        }
                    }
                }
            }
        }
    }

    /** 扫描所有模板箱，清掉原版延迟战利品表及内容，统一登记空岛中岛预算。 */
    private static void collectChests(ArenaWorld world, SkyWarsLayout layout, BlockBox box) {
        Set<BlockPos> registered = new HashSet<>();
        layout.extraChests().forEach(chest -> registered.add(chest.pos()));
        for (int x = box.getMinX(); x <= box.getMaxX(); x++) {
            for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) {
                for (int y = box.getMinY(); y <= box.getMaxY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if ((!state.isOf(Blocks.CHEST) && !state.isOf(Blocks.TRAPPED_CHEST))
                            || !(world.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
                        continue;
                    }
                    chest.setLootTable(null);
                    chest.setLootTableSeed(0L);
                    chest.clear();
                    chest.markDirty();
                    // 只清箱盖紧邻的一格，不拆整片屋顶；双箱的两半都各自保留可开启空间。
                    world.setBlockState(pos.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                    if (registered.add(pos)) {
                        layout.addExtraChest(new SkyWarsLayout.ExtraChest(pos, SkyWarsLootPolicy.Tier.CENTER, -1));
                    }
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
        cleanTemplateMarkers(world, box, rotation);
        if (registerChests) {
            collectChests(world, layout, box);
        }
    }

    /** 空地箱分区选点，给屋外路线稳定收益，同时保留房门、道路、中央军械箱的通行空间。 */
    private static void addOutdoorChests(ArenaWorld world, SkyWarsLayout layout, BlockPos center, int radius,
                                          List<Rect> occupied, int seed) {
        int target = layout.compact() ? 4 : 8;
        Random random = Random.create(seed * 0x9E3779B97F4A7C15L ^ 0x3c6ef372fe94f82bL);
        double phase = random.nextDouble() * Math.PI * 2.0;
        List<List<BlockPos>> sectors = new ArrayList<>();
        for (int i = 0; i < target; i++) sectors.add(new ArrayList<>());
        int limit = radius - ISLAND_MARGIN - 1;
        for (int dx = -limit; dx <= limit; dx++) {
            for (int dz = -limit; dz <= limit; dz++) {
                double distance = Math.hypot(dx, dz);
                if (distance < PLAZA_RADIUS + 5 || distance > limit) continue;
                BlockPos pos = center.add(dx, 1, dz);
                if (!outdoorSiteAvailable(world, layout, center, occupied, pos)) continue;
                double angle = Math.atan2(dz, dx) - phase;
                angle = (angle % (Math.PI * 2.0) + Math.PI * 2.0) % (Math.PI * 2.0);
                int sector = Math.min(target - 1, (int) (angle * target / (Math.PI * 2.0)));
                sectors.get(sector).add(pos);
            }
        }
        List<BlockPos> placed = new ArrayList<>();
        List<Integer> emptySectors = new ArrayList<>();
        for (int i = 0; i < target; i++) {
            BlockPos selected = chooseOutdoorSite(world, layout, center, occupied, sectors.get(i), random);
            if (selected == null) emptySectors.add(i);
            else placeOutdoorChest(world, layout, selected, placed);
        }
        // 极少数扇区被大房屋占满时，向邻近扇区找空位；保留所有距离与通行约束。
        for (int emptySector : emptySectors) {
            BlockPos selected = null;
            for (int step = 1; step < target && selected == null; step++) {
                selected = chooseOutdoorSite(world, layout, center, occupied,
                        sectors.get((emptySector + step) % target), random);
            }
            if (selected != null) placeOutdoorChest(world, layout, selected, placed);
        }
        LOGGER.info("[PvP] 村庄露天补给箱: {}/{}，位置 {}", placed.size(), target, placed);
        if (placed.size() < target) LOGGER.warn("[PvP] 村庄露天箱可用空地不足，保留道路和房门净空: {}/{}", placed.size(), target);
    }

    private static BlockPos chooseOutdoorSite(ArenaWorld world, SkyWarsLayout layout, BlockPos center,
                                               List<Rect> occupied, List<BlockPos> candidates, Random random) {
        while (!candidates.isEmpty()) {
            BlockPos candidate = candidates.remove(random.nextInt(candidates.size()));
            if (outdoorSiteAvailable(world, layout, center, occupied, candidate)) return candidate;
        }
        return null;
    }

    private static boolean outdoorSiteAvailable(ArenaWorld world, SkyWarsLayout layout, BlockPos center,
                                                 List<Rect> occupied, BlockPos pos) {
        if (tooCloseToRoad(center, pos.getX(), pos.getZ())) return false;
        Rect point = new Rect(pos.getX(), pos.getZ(), pos.getX(), pos.getZ());
        for (Rect house : occupied) {
            if (point.intersects(house, 3)) return false;
        }
        for (SkyWarsLayout.ExtraChest chest : layout.extraChests()) {
            if (Math.hypot(pos.getX() - chest.pos().getX(), pos.getZ() - chest.pos().getZ()) < 4.0) return false;
        }
        if (!world.getBlockState(pos).isAir() || !world.getBlockState(pos.up()).isAir()
                || !world.getBlockState(pos.down()).isSolidBlock(world, pos.down())) return false;
        // 至少一个相邻落脚位有完整地面与两格净空，保证玩家可以走到箱前开启。
        for (BlockPos access : List.of(pos.north(), pos.south(), pos.east(), pos.west())) {
            if (world.getBlockState(access).isAir() && world.getBlockState(access.up()).isAir()
                    && world.getBlockState(access.down()).isSolidBlock(world, access.down())) return true;
        }
        return false;
    }

    private static void placeOutdoorChest(ArenaWorld world, SkyWarsLayout layout, BlockPos pos, List<BlockPos> placed) {
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        if (world.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            chest.setLootTable(null);
            chest.setLootTableSeed(0L);
            chest.clear();
            chest.markDirty();
        }
        layout.addExtraChest(new SkyWarsLayout.ExtraChest(pos, SkyWarsLootPolicy.Tier.CENTER, -1));
        placed.add(pos);
    }
}
