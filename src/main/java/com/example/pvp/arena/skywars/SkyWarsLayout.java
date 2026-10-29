package com.example.pvp.arena.skywars;

import com.example.pvp.config.PvPConfig;
import com.example.pvp.arena.ArenaTemplate;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** 确定性的空岛布局：主路线按玩家旋转对称，装饰与地形风格使用独立随机源。 */
public final class SkyWarsLayout {
    public static final int MAX_RADIUS_MARGIN = 4;
    /** 按要求只扩展中央岛的水平尺度；高度保持可正常跳跃，外围按净距顺移。 */
    public static final double CENTRAL_SCALE = 2.5;

    public static int scaleCentral(int blocks) {
        return (int) Math.copySign(Math.round(Math.abs(blocks) * CENTRAL_SCALE), blocks);
    }

    public static final class Island {
        public final BlockPos center;
        public final int radius;
        public final List<BlockPos> chests;

        Island(BlockPos center, int radius, List<BlockPos> chests) {
            this.center = center;
            this.radius = radius;
            this.chests = List.copyOf(chests);
        }

        public BlockPos center() { return this.center; }
        public int radius() { return this.radius; }
        public List<BlockPos> chests() { return this.chests; }
    }

    /** 所有附加箱都按空岛档位分配物资；村庄房内和露天箱同属中岛，补货规则一致。 */
    public record ExtraChest(BlockPos pos, SkyWarsLootPolicy.Tier tier, int handicapIndex) { }

    private final List<ExtraChest> extraChests = new ArrayList<>();
    /** 新结构生成后登记实际中央箱位，替代经典圆盘预先计算的占位坐标。 */
    private final List<BlockPos> centralStructureChests = new ArrayList<>();
    private final BlockPos mapCenter;
    private final int maxRadius;
    private final List<Island> spawnIslands;
    private final List<Island> midIslands;
    private final List<Island> middleIslands;
    private final List<BlockPos> spawns;
    private final int middleVariant;
    private final boolean compact;
    private final long styleSeed;
    private final SkyWarsMapStyle mapStyle;
    private final SkyWarsLootPolicy lootPolicy;
    private SkyWarsImportedMap importedMap;

    private SkyWarsLayout(BlockPos mapCenter, int maxRadius, List<Island> spawnIslands,
                          List<Island> midIslands, List<Island> middleIslands, List<BlockPos> spawns,
                          int middleVariant, boolean compact, long styleSeed, SkyWarsMapStyle mapStyle, long lootSeed) {
        this.mapCenter = mapCenter;
        this.maxRadius = maxRadius;
        this.spawnIslands = List.copyOf(spawnIslands);
        this.midIslands = List.copyOf(midIslands);
        this.middleIslands = List.copyOf(middleIslands);
        this.spawns = List.copyOf(spawns);
        this.middleVariant = middleVariant;
        this.compact = compact;
        this.styleSeed = styleSeed;
        this.mapStyle = mapStyle;
        this.lootPolicy = new SkyWarsLootPolicy(lootSeed);
    }

    public List<ExtraChest> extraChests() { return this.extraChests; }
    public void addExtraChest(ExtraChest chest) { this.extraChests.add(chest); }
    public List<BlockPos> centralStructureChests() { return List.copyOf(this.centralStructureChests); }
    public void setCentralStructureChests(List<BlockPos> chests) {
        this.centralStructureChests.clear();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos pos : chests) {
            BlockPos immutable = pos.toImmutable();
            if (seen.add(immutable)) this.centralStructureChests.add(immutable);
        }
    }
    public BlockPos mapCenter() { return this.mapCenter; }
    public int maxRadius() { return this.maxRadius; }
    public List<Island> spawnIslands() { return this.spawnIslands; }
    public List<Island> midIslands() { return this.midIslands; }
    public Island middle() { return this.middleIslands.isEmpty() ? null : this.middleIslands.get(0); }
    public List<Island> middleIslands() { return this.middleIslands; }
    public List<BlockPos> spawns() { return this.spawns; }
    public int middleVariant() { return this.middleVariant; }
    public boolean compact() { return this.compact; }
    public long styleSeed() { return this.styleSeed; }
    public SkyWarsMapStyle mapStyle() { return this.mapStyle; }
    public SkyWarsLootPolicy lootPolicy() { return this.lootPolicy; }
    public SkyWarsImportedMap importedMap() { return this.importedMap; }
    public String displayName() { return this.importedMap == null ? this.mapStyle.getDisplayName() : this.importedMap.name(); }
    public String mapId() { return this.importedMap == null ? this.mapStyle.name() : this.importedMap.id(); }

    /** 上下层都有实心落脚面，末地/地狱也能安全缩到中央决战区域。 */
    public int suggestedFinalRadius() {
        return this.importedMap == null ? scaleCentral(this.compact ? 10 : 14) : this.importedMap.finalRadius();
    }

    public int centralClearanceRadius() { return this.middle().radius() + scaleCentral(3); }

    /** 清场兜底包含出生侧台、观察台和高层桥，不依赖旧版 skywarsSize 方框。 */
    public static int computeMaxRadius() {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int spawnRadius = Math.max(4, Math.min(8, cfg.skywarsIslandRadius));
        int transitRadius = spawnRadius + 3;
        int maximum = 0;
        for (SkyWarsMapStyle style : SkyWarsMapStyle.values()) {
            int extent = middleExtent(cfg, false, style, spawnRadius, transitRadius);
            maximum = Math.max(maximum, extent + 8 + 2 * transitRadius + 2 * spawnRadius
                    + 18 + 9 + MAX_RADIUS_MARGIN);
        }
        return maximum;
    }

    public static SkyWarsLayout compute(BlockPos mapCenter, int seed, int playerCount) {
        // 修改原因：地图种子重复时不应复刻箱子装备。普通/枪械空岛共用本局独立的物资随机源，
        // 初次装填和补货继续消费它；地图、出生点及箱位仍只由地图 seed 决定。
        return compute(mapCenter, seed, playerCount, ThreadLocalRandom.current().nextLong());
    }

    /** 外部地图的出生、箱位和边界必须登记在同一布局中，确保开局与补货使用实际地图坐标。 */
    public static SkyWarsLayout imported(BlockPos center, int seed, int playerCount, SkyWarsImportedMap map) {
        if (map == null || playerCount < 1 || playerCount > map.spawns().size()) {
            throw new IllegalArgumentException("导入地图出生点不足或参赛人数无效");
        }
        int capacity = map.spawns().size();
        long styleSeed = mixSeed(seed, 0x3c6ef372L ^ map.id().hashCode());
        int start = new Random(styleSeed).nextInt(capacity);
        List<Integer> sourceOrder = new ArrayList<>(capacity);
        boolean[] selected = new boolean[capacity];
        for (int i = 0; i < playerCount; i++) {
            int sourceIndex = (start + i * capacity / playerCount) % capacity;
            sourceOrder.add(sourceIndex);
            selected[sourceIndex] = true;
        }
        // 已分配槽位排在前面，与 players/handicaps 的索引一致；空置岛仍参加箱子补货。
        for (int i = 0; i < capacity; i++) {
            int sourceIndex = (start + i) % capacity;
            if (!selected[sourceIndex]) sourceOrder.add(sourceIndex);
        }
        List<Island> spawnIslands = new ArrayList<>(capacity);
        List<BlockPos> spawns = new ArrayList<>(playerCount);
        int[] reorderedIndex = new int[capacity];
        for (int i = 0; i < sourceOrder.size(); i++) {
            int sourceIndex = sourceOrder.get(i);
            BlockPos spawn = center.add(map.spawns().get(sourceIndex));
            spawnIslands.add(new Island(spawn.down(), 6, List.of()));
            if (i < playerCount) spawns.add(spawn);
            reorderedIndex[sourceIndex] = i;
        }
        SkyWarsLayout layout = new SkyWarsLayout(center, map.maxRadius(), spawnIslands, List.of(),
                List.of(new Island(center, map.finalRadius(), List.of())), spawns, 0, playerCount <= 4,
                styleSeed, SkyWarsMapStyle.TERRACES, ThreadLocalRandom.current().nextLong());
        layout.importedMap = map;
        for (SkyWarsImportedMap.Chest chest : map.chests()) {
            if (chest.tier() == SkyWarsLootPolicy.Tier.SPAWN) {
                if (chest.spawnIndex() < 0 || chest.spawnIndex() >= capacity) {
                    throw new IllegalArgumentException("导入地图出生岛箱子缺少有效出生槽位: " + chest.pos());
                }
                layout.addExtraChest(new ExtraChest(center.add(chest.pos()), chest.tier(),
                        reorderedIndex[chest.spawnIndex()]));
            } else {
                // 导入图未单列中途岛，公共路线箱和中央箱共用中岛补货入口。
                layout.addExtraChest(new ExtraChest(center.add(chest.pos()), SkyWarsLootPolicy.Tier.CENTER, -1));
            }
        }
        return layout;
    }

    /** 显式物资种子用于复现与测试，不参与地形和箱位生成。 */
    public static SkyWarsLayout compute(BlockPos mapCenter, int seed, int playerCount, long lootSeed) {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int count = Math.max(2, Math.min(8, playerCount));
        boolean compact = count <= 4;
        Random topology = new Random(mixSeed(seed, 0x48a7d31bL));
        long styleSeed = mixSeed(seed, 0x61c88647L);
        SkyWarsMapStyle mapStyle = SkyWarsMapStyle.pick(seed, SkyWarsTheme.pick(seed));
        int variant = topology.nextInt(3);
        double rotation = topology.nextDouble() * Math.PI * 2.0;
        int spawnRadius = Math.max(4, Math.min(8, cfg.skywarsIslandRadius));
        int transitRadius = spawnRadius + (compact ? 1 : 3);
        int extent = middleExtent(cfg, compact, mapStyle, spawnRadius, transitRadius);
        // 圆周坐标取整最多带来约1.4格偏差，目标留在区间内部，实际桥距仍为10~14/14~20。
        int firstGap = compact ? 12 : 16 + topology.nextInt(3);
        int innerGap = compact ? 6 : 8;
        int transitDistance = extent + innerGap + transitRadius;
        int spawnDistance = transitDistance + transitRadius + spawnRadius + firstGap;
        int spawnHeight = topology.nextInt(3) - 1;
        int transitHeight = topology.nextInt(3) - 1;
        long chestSeed = topology.nextLong();

        List<Island> spawnIslands = new ArrayList<>();
        List<Island> midIslands = new ArrayList<>();
        List<BlockPos> spawns = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = rotation + i * Math.PI * 2.0 / count;
            // 同局共享高度、岛面和箱子相对位置，只旋转整条路线，避免某个出生位更近或装备更多。
            Island spawn = buildIsland(new Random(chestSeed), offset(mapCenter, angle, spawnDistance, spawnHeight),
                    spawnRadius, Math.max(1, cfg.skywarsChestsPerIsland), angle);
            spawnIslands.add(spawn);
            spawns.add(spawn.center.up());
            midIslands.add(buildIsland(new Random(chestSeed ^ 0x51a9L),
                    offset(mapCenter, angle, transitDistance, transitHeight), transitRadius,
                    Math.max(1, cfg.skywarsMidIslandChests), angle));
        }

        List<Island> middleIslands = new ArrayList<>();
        if (mapStyle == SkyWarsMapStyle.VILLAGE) {
            // 村庄房内箱与露天军械箱统一使用中岛物资，生成后登记实际箱位。
            middleIslands.add(buildIsland(topology, mapCenter, extent, 0, 0));
        } else {
            int centralRadius = mapStyle.centralRadius(compact);
            int satelliteRadius = mapStyle.satelliteRadius(compact);
            int satelliteDistance = extent - satelliteRadius;
            int centerChests = Math.max(2, cfg.skywarsMiddleChests - count);
            middleIslands.add(buildIsland(topology, mapCenter, centralRadius, centerChests, rotation));
            for (int i = 0; i < count; i++) {
                double angle = rotation + i * Math.PI * 2.0 / count;
                middleIslands.add(buildIsland(new Random(chestSeed ^ 0x31faL),
                        offset(mapCenter, angle, satelliteDistance, mapStyle.satelliteHeight(variant)),
                        satelliteRadius, 1, angle));
            }
            if (mapStyle.hasBranchIslands(variant)) {
                // 支路没有额外物资，提供绕行/掩体；每个扇区都得到同样一座。
                for (int i = 0; i < count; i++) {
                    double angle = rotation + (i + 0.5) * Math.PI * 2.0 / count;
                    middleIslands.add(buildIsland(topology,
                            offset(mapCenter, angle, satelliteDistance, mapStyle.branchHeight(variant)),
                            3, 0, angle));
                }
            }
        }

        // 侧台向切线伸出 radius+3，外缘再加3；用三角形上界覆盖所有取整和装饰。
        int maxRadius = Math.max(spawnDistance + spawnRadius + 9,
                transitDistance + transitRadius + 10) + MAX_RADIUS_MARGIN;
        return new SkyWarsLayout(mapCenter, maxRadius, spawnIslands, midIslands, middleIslands,
                spawns, variant, compact, styleSeed, mapStyle, lootSeed);
    }

    private static int middleExtent(PvPConfig cfg, boolean compact, SkyWarsMapStyle style,
                                    int spawnRadius, int transitRadius) {
        int base = Math.max(compact ? 28 : 40,
                Math.min(compact ? 36 : 50, (int) Math.round(cfg.skywarsMiddleRadius * (compact ? 0.35 : 0.5))));
        int wanted = style == SkyWarsMapStyle.VILLAGE ? scaleCentral(base)
                : Math.max(base, style.centralRadius(compact) + scaleCentral(3)
                        + 2 * style.satelliteRadius(compact) + 4);
        // 给扩大后的中岛让位，同时保留外围岛尺寸/桥距，并限制在本局暂存区域内。
        int outerAllowance = (compact ? 6 : 8) + 2 * transitRadius + 2 * spawnRadius
                + (compact ? 12 : 18) + 9 + MAX_RADIUS_MARGIN;
        return Math.min(wanted, ArenaTemplate.REGION_SPACING / 2 - 16 - outerAllowance);
    }

    private static Island buildIsland(Random random, BlockPos center, int radius, int chestCount, double rotation) {
        List<BlockPos> chests = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        int wanted = Math.min(16, chestCount);
        double phase = random.nextDouble() * Math.PI * 2.0;
        for (int attempt = 0; attempt < Math.max(64, wanted * 24) && chests.size() < wanted; attempt++) {
            double angle = rotation + phase + attempt * Math.PI * (3.0 - Math.sqrt(5.0));
            int distance = 2 + random.nextInt(Math.max(1, radius - 3));
            BlockPos pos = offset(center, angle, distance, 1);
            if (used.add(pos.asLong())) chests.add(pos);
        }
        return new Island(center, radius, chests);
    }

    private static BlockPos offset(BlockPos center, double angle, int distance, int height) {
        return center.add((int) Math.round(Math.cos(angle) * distance), height,
                (int) Math.round(Math.sin(angle) * distance));
    }

    private static long mixSeed(int seed, long salt) {
        long value = seed ^ salt;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
