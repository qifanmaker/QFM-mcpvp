package com.example.pvp.arena.skywars;

import com.example.pvp.config.PvPConfig;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** 确定性的空岛布局：主路线按玩家旋转对称，装饰与地形风格使用独立随机源。 */
public final class SkyWarsLayout {
    public static final int MAX_RADIUS_MARGIN = 4;

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
    private final BlockPos mapCenter;
    private final int maxRadius;
    private final List<Island> spawnIslands;
    private final List<Island> midIslands;
    private final List<Island> middleIslands;
    private final List<BlockPos> spawns;
    private final int middleVariant;
    private final boolean compact;
    private final long styleSeed;
    private final SkyWarsLootPolicy lootPolicy;

    private SkyWarsLayout(BlockPos mapCenter, int maxRadius, List<Island> spawnIslands,
                          List<Island> midIslands, List<Island> middleIslands, List<BlockPos> spawns,
                          int middleVariant, boolean compact, long styleSeed, int seed) {
        this.mapCenter = mapCenter;
        this.maxRadius = maxRadius;
        this.spawnIslands = List.copyOf(spawnIslands);
        this.midIslands = List.copyOf(midIslands);
        this.middleIslands = List.copyOf(middleIslands);
        this.spawns = List.copyOf(spawns);
        this.middleVariant = middleVariant;
        this.compact = compact;
        this.styleSeed = styleSeed;
        this.lootPolicy = new SkyWarsLootPolicy(seed);
    }

    public List<ExtraChest> extraChests() { return this.extraChests; }
    public void addExtraChest(ExtraChest chest) { this.extraChests.add(chest); }
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
    public SkyWarsLootPolicy lootPolicy() { return this.lootPolicy; }

    /** 上下层都有实心落脚面，末地/地狱也能安全缩到中央决战区域。 */
    public int suggestedFinalRadius() { return this.compact ? 10 : 14; }

    /** 清场兜底包含出生侧台、观察台和高层桥，不依赖旧版 skywarsSize 方框。 */
    public static int computeMaxRadius() {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int spawnRadius = Math.max(4, Math.min(8, cfg.skywarsIslandRadius));
        int transitRadius = spawnRadius + 3;
        return middleExtent(cfg, false) + 2 * transitRadius + 2 * spawnRadius + 9 + 19 + 12;
    }

    public static SkyWarsLayout compute(BlockPos mapCenter, int seed, int playerCount) {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int count = Math.max(2, Math.min(8, playerCount));
        boolean compact = count <= 4;
        Random topology = new Random(mixSeed(seed, 0x48a7d31bL));
        long styleSeed = mixSeed(seed, 0x61c88647L);
        int variant = topology.nextInt(3);
        double rotation = topology.nextDouble() * Math.PI * 2.0;
        int spawnRadius = Math.max(4, Math.min(8, cfg.skywarsIslandRadius));
        int transitRadius = spawnRadius + (compact ? 1 : 3);
        int extent = middleExtent(cfg, compact);
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
        if (SkyWarsTheme.pick(seed) == SkyWarsTheme.VILLAGE) {
            // 房屋保留生活物资；村庄生成之后另加固定 PvP 军械箱。
            middleIslands.add(buildIsland(topology, mapCenter, extent, 0, 0));
        } else {
            int centralRadius = compact ? 12 : 16;
            int satelliteRadius = compact ? 5 : 7;
            int satelliteDistance = extent - satelliteRadius;
            int centerChests = Math.max(2, cfg.skywarsMiddleChests - count);
            middleIslands.add(buildIsland(topology, mapCenter, centralRadius, centerChests, rotation));
            for (int i = 0; i < count; i++) {
                double angle = rotation + i * Math.PI * 2.0 / count;
                middleIslands.add(buildIsland(new Random(chestSeed ^ 0x31faL),
                        offset(mapCenter, angle, satelliteDistance, variant - 1), satelliteRadius, 1, angle));
            }
            if (variant != 0) {
                // 支路没有额外物资，提供绕行/掩体；每个扇区都得到同样一座。
                for (int i = 0; i < count; i++) {
                    double angle = rotation + (i + 0.5) * Math.PI * 2.0 / count;
                    middleIslands.add(buildIsland(topology,
                            offset(mapCenter, angle, satelliteDistance, variant == 1 ? -3 : 3),
                            3, 0, angle));
                }
            }
        }

        // 侧台向切线伸出 radius+3，外缘再加3；用三角形上界覆盖所有取整和装饰。
        int maxRadius = Math.max(spawnDistance + spawnRadius + 9,
                transitDistance + transitRadius + 10) + MAX_RADIUS_MARGIN;
        return new SkyWarsLayout(mapCenter, maxRadius, spawnIslands, midIslands, middleIslands,
                spawns, variant, compact, styleSeed, seed);
    }

    private static int middleExtent(PvPConfig cfg, boolean compact) {
        // 保留旧配置的尺度作用，默认80对应28/40；限制极端配置避免岛屿重叠或越过比赛区域。
        return Math.max(compact ? 28 : 40,
                Math.min(compact ? 36 : 50, (int) Math.round(cfg.skywarsMiddleRadius * (compact ? 0.35 : 0.5))));
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
