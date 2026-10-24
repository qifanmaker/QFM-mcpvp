package com.example.pvp.arena.skywars;

import com.example.pvp.config.PvPConfig;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖人数、主题、随机种子和配置边界；失败时打印可重现的种子。 */
class SkyWarsLayoutTest {
    private int previousIslandRadius;
    private int previousMiddleRadius;
    private static final BlockPos CENTER = new BlockPos(384 + 88, 101, 88);

    @BeforeEach
    void useDefaults() {
        this.previousIslandRadius = PvPConfig.INSTANCE.skywarsIslandRadius;
        this.previousMiddleRadius = PvPConfig.INSTANCE.skywarsMiddleRadius;
        PvPConfig.INSTANCE.skywarsIslandRadius = 5;
        PvPConfig.INSTANCE.skywarsMiddleRadius = 80;
    }

    @AfterEach
    void restoreConfig() {
        PvPConfig.INSTANCE.skywarsIslandRadius = this.previousIslandRadius;
        PvPConfig.INSTANCE.skywarsMiddleRadius = this.previousMiddleRadius;
    }

    @Test
    void allThemesHaveFairPlayableRoutesAcrossSeedsAndPlayerCounts() {
        for (int sample = 0; sample < 300; sample++) {
            for (SkyWarsTheme theme : SkyWarsTheme.values()) {
                int seed = sample * SkyWarsTheme.values().length + theme.ordinal();
                for (int players = 2; players <= 8; players++) {
                    SkyWarsLayout layout = SkyWarsLayout.compute(CENTER, seed, players);
                    String context = "seed=" + seed + ", players=" + players + ", theme=" + theme;
                    assertEquals(theme, SkyWarsTheme.pick(seed), context);
                    assertEquals(List.of(), SkyWarsBetaLayoutValidator.validate(layout, players), context);
                    assertEquals(players <= 4, layout.compact(), context);
                    assertEquals(players <= 4 ? 25 : 35, layout.suggestedFinalRadius(), context);
                    for (int i = 0; i < players; i++) {
                        SkyWarsLayout.Island spawn = layout.spawnIslands().get(i);
                        SkyWarsLayout.Island transit = layout.midIslands().get(i);
                        double gap = distance(spawn.center(), transit.center()) - spawn.radius() - transit.radius();
                        assertTrue(gap >= (players <= 4 ? 10 : 14), context + ", gap=" + gap);
                        assertTrue(gap <= (players <= 4 ? 14 : 20), context + ", gap=" + gap);
                        assertEquals(spawn.chests().size(), new HashSet<>(spawn.chests()).size(), context);
                    }
                    assertFootprint(layout, context);
                }
            }
        }
    }

    @Test
    void centralDiameterIsAboutTwoAndAHalfTimesWhileOuterIslandSizesStayOriginal() {
        int[][] original = {{12,16},{14,18},{12,16},{12,16},{13,17},{14,18},{28,40}};
        for (int seed = 0; seed < 100; seed++) {
            for (int players : new int[]{2,4,5,8}) {
                SkyWarsLayout layout = SkyWarsLayout.compute(CENTER, seed, players);
                int before = original[layout.mapStyle().ordinal()][layout.compact() ? 0 : 1];
                assertEquals(SkyWarsLayout.scaleCentral(before), layout.middle().radius());
                double ratio = layout.middle().radius() / (double) before;
                assertTrue(ratio >= 2 && ratio <= 3);
                assertEquals(5, layout.spawnIslands().get(0).radius());
                assertEquals(layout.compact() ? 6 : 8, layout.midIslands().get(0).radius());
                assertTrue(layout.maxRadius() <= 176, "地图必须完整落在暂存区域内");
            }
        }
    }

    @Test
    void layoutIsDeterministicAndDecorationsDoNotLockToOneTopology() {
        for (SkyWarsTheme theme : SkyWarsTheme.values()) {
            Set<Integer> variants = new HashSet<>();
            Set<Integer> styles = new HashSet<>();
            for (int sample = 0; sample < 300; sample++) {
                int seed = sample * 5 + theme.ordinal();
                SkyWarsLayout first = SkyWarsLayout.compute(CENTER, seed, 8);
                SkyWarsLayout repeated = SkyWarsLayout.compute(CENTER, seed, 8);
                assertEquals(first.spawns(), repeated.spawns());
                assertEquals(first.maxRadius(), repeated.maxRadius());
                assertEquals(first.middleVariant(), repeated.middleVariant());
                assertEquals(first.styleSeed(), repeated.styleSeed());
                assertEquals(first.mapStyle(), repeated.mapStyle());
                variants.add(first.middleVariant());
                styles.add(new Random(first.styleSeed()).nextInt(6));
            }
            assertEquals(3, variants.size(), theme.name());
            assertEquals(6, styles.size(), theme.name());
        }
    }

    @Test
    void eachMaterialThemeCanDrawEveryStructureWithoutLockingToTopologyOrPlayerCount() {
        Set<SkyWarsMapStyle> expected = EnumSet.allOf(SkyWarsMapStyle.class);
        expected.remove(SkyWarsMapStyle.VILLAGE);
        for (SkyWarsTheme theme : SkyWarsTheme.values()) {
            EnumMap<SkyWarsMapStyle, Integer> frequencies = new EnumMap<>(SkyWarsMapStyle.class);
            Set<String> styleTopologyPairs = new HashSet<>();
            for (int sample = 0; sample < 1200; sample++) {
                int seed = sample * SkyWarsTheme.values().length + theme.ordinal();
                SkyWarsLayout small = SkyWarsLayout.compute(CENTER, seed, 2);
                SkyWarsLayout large = SkyWarsLayout.compute(CENTER, seed, 8);
                assertEquals(small.mapStyle(), large.mapStyle(), "人数只改变尺度，不重新抽结构");
                assertEquals(SkyWarsMapStyle.pick(seed, theme), large.mapStyle());
                frequencies.merge(large.mapStyle(), 1, Integer::sum);
                styleTopologyPairs.add(large.mapStyle().name() + ":" + large.middleVariant());
            }
            if (theme == SkyWarsTheme.VILLAGE) {
                assertEquals(Set.of(SkyWarsMapStyle.VILLAGE), frequencies.keySet());
            } else {
                assertEquals(expected, frequencies.keySet(), theme.name());
                // 独立抽签应覆盖6结构×3拓扑，而不是材质/拓扑把地图锁成固定搭配。
                assertEquals(18, styleTopologyPairs.size(), theme.name());
                for (int frequency : frequencies.values()) {
                    assertTrue(frequency >= 120 && frequency <= 280,
                            theme + "结构抽样明显偏斜: " + frequencies);
                }
            }
        }
    }

    @Test
    void structuresChangeCentralGeometryButPreserveEveryPlayersApproachAndLootBudget() {
        Set<SkyWarsMapStyle> covered = EnumSet.noneOf(SkyWarsMapStyle.class);
        Set<String> compactProfiles = new HashSet<>();
        for (int seed = 0; seed < 300; seed++) {
            if (SkyWarsTheme.pick(seed) == SkyWarsTheme.VILLAGE) continue;
            for (int players : new int[]{2, 4, 5, 8}) {
                SkyWarsLayout layout = SkyWarsLayout.compute(CENTER, seed, players);
                SkyWarsMapStyle style = layout.mapStyle();
                covered.add(style);
                SkyWarsLayout.Island first = layout.middleIslands().get(1);
                double smallestGap = Double.POSITIVE_INFINITY;
                double largestGap = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < players; i++) {
                    SkyWarsLayout.Island satellite = layout.middleIslands().get(i + 1);
                    SkyWarsLayout.Island transit = layout.midIslands().get(i);
                    assertEquals(first.radius(), satellite.radius(), style.name());
                    assertEquals(first.center().getY(), satellite.center().getY(), style.name());
                    assertEquals(first.chests().size(), satellite.chests().size(), style.name());
                    double gap = distance(transit.center(), satellite.center())
                            - transit.radius() - satellite.radius();
                    double targetGap = layout.compact() ? 6 : 8;
                    assertTrue(Math.abs(gap - targetGap) <= 1.5, style + "入口间距=" + gap);
                    smallestGap = Math.min(smallestGap, gap);
                    largestGap = Math.max(largestGap, gap);
                }
                assertTrue(largestGap - smallestGap <= 3.0, "各方向只允许方块取整误差: " + style);
                int chests = layout.middleIslands().stream().mapToInt(island -> island.chests().size()).sum();
                assertEquals(Math.min(16, Math.max(2, PvPConfig.INSTANCE.skywarsMiddleChests - players)) + players,
                        chests, "结构和无奖励支路不能增加物资: " + style);
                if (layout.compact()) {
                    compactProfiles.add(style.centralRadius(true) + ":" + first.radius() + ":"
                            + style.satelliteHeight(0) + ":" + style.branchHeight(0));
                }
            }
        }
        assertEquals(6, covered.size());
        assertEquals(6, compactProfiles.size(), "六种结构必须有不同的几何轮廓");
    }

    @Test
    void customStructureClearingDoesNotReachSatelliteIslandsEvenWithExtremeConfiguration() {
        Set<SkyWarsMapStyle> covered = EnumSet.noneOf(SkyWarsMapStyle.class);
        for (int configuredRadius : new int[]{1, 80, 200}) {
            PvPConfig.INSTANCE.skywarsMiddleRadius = configuredRadius;
            for (int seed = -120; seed < 120; seed++) {
                for (int players : new int[]{2, 4, 5, 8}) {
                    SkyWarsLayout layout = SkyWarsLayout.compute(CENTER, seed, players);
                    if (!layout.mapStyle().usesCustomStructure()) continue;
                    covered.add(layout.mapStyle());
                    int clearingRadius = layout.centralClearanceRadius();
                    for (int i = 1; i < layout.middleIslands().size(); i++) {
                        SkyWarsLayout.Island island = layout.middleIslands().get(i);
                        assertTrue(distance(layout.middle().center(), island.center()) - island.radius() > clearingRadius,
                                "中央清区侵入卫星/支路: seed=" + seed + ", style=" + layout.mapStyle());
                    }
                    assertFootprint(layout, "seed=" + seed + ", style=" + layout.mapStyle());
                }
            }
        }
        assertEquals(5, covered.size());
    }

    @Test
    void generatedCentralChestPositionsReplaceOldRegistrationsAndCannotBeMutatedByCallers() {
        SkyWarsLayout layout = SkyWarsLayout.compute(CENTER, 0, 4);
        BlockPos.Mutable mutable = new BlockPos.Mutable(470, 104, 85);
        BlockPos first = mutable.toImmutable();
        BlockPos second = first.add(3, 2, 0);
        layout.setCentralStructureChests(List.of(mutable, first, second));
        mutable.set(0, 0, 0);
        List<BlockPos> stored = layout.centralStructureChests();
        assertEquals(List.of(first, second), stored);
        assertThrows(UnsupportedOperationException.class, () -> stored.add(BlockPos.ORIGIN));
        layout.setCentralStructureChests(List.of(CENTER.up()));
        assertEquals(List.of(CENTER.up()), layout.centralStructureChests());
        assertEquals(List.of(first, second), stored, "旧快照不能随重登记变化");
        layout.setCentralStructureChests(List.of());
        assertTrue(layout.centralStructureChests().isEmpty());
    }

    @Test
    void compactGamesAreSmallerAndFallbackCleanupCoversExtremeConfigs() {
        for (int islandRadius : new int[]{-5, 4, 5, 8, 40}) {
            for (int middleRadius : new int[]{1, 80, 200}) {
                PvPConfig.INSTANCE.skywarsIslandRadius = islandRadius;
                PvPConfig.INSTANCE.skywarsMiddleRadius = middleRadius;
                int cleanupRadius = SkyWarsLayout.computeMaxRadius();
                for (int seed = 0; seed < 100; seed++) {
                    SkyWarsLayout small = SkyWarsLayout.compute(CENTER, seed, 2);
                    SkyWarsLayout large = SkyWarsLayout.compute(CENTER, seed, 8);
                    assertTrue(small.maxRadius() < large.maxRadius());
                    assertTrue(large.maxRadius() <= cleanupRadius);
                    assertTrue(large.maxRadius() < 192, "must not reach adjacent match region");
                    assertEquals(List.of(), SkyWarsBetaLayoutValidator.validate(large, 8));
                    assertFootprint(small, "small extreme config");
                    assertFootprint(large, "large extreme config");
                }
            }
        }
    }

    private static void assertFootprint(SkyWarsLayout layout, String context) {
        for (List<SkyWarsLayout.Island> group : List.of(layout.spawnIslands(), layout.midIslands(), layout.middleIslands())) {
            for (SkyWarsLayout.Island island : group) {
                assertTrue(distance(CENTER, island.center()) + island.radius() <= layout.maxRadius(), context);
            }
        }
        // 出生侧台半径2、中途侧台半径3。三角不等式的保守界已包含桥和坐标取整。
        for (SkyWarsLayout.Island island : layout.spawnIslands()) {
            assertTrue(distance(CENTER, island.center()) + island.radius() + 6 <= layout.maxRadius(), context);
        }
        for (SkyWarsLayout.Island island : layout.midIslands()) {
            assertTrue(distance(CENTER, island.center()) + island.radius() + 8 <= layout.maxRadius(), context);
        }
    }

    @Test
    void narrowCustomIslandsNeverNeedTwoBlockJumpsToReachHighPlatforms() {
        PvPConfig.INSTANCE.skywarsIslandRadius = 4;
        for (int seed = 0; seed < 300; seed++) {
            SkyWarsLayout layout = SkyWarsLayout.compute(CENTER, seed, 4);
            int climb = SkyWarsBetaMapGenerator.transitHeightOffset(layout, 2);
            assertTrue(climb > 0);
            for (SkyWarsLayout.Island island : layout.midIslands()) {
                double angle = Math.atan2(island.center().getZ() - CENTER.getZ(),
                        island.center().getX() - CENTER.getX());
                for (int sign : new int[]{-1, 1}) {
                    int x = (int) Math.round(Math.cos(angle + sign * Math.PI / 2) * (island.radius() + 4));
                    int z = (int) Math.round(Math.sin(angle + sign * Math.PI / 2) * (island.radius() + 4));
                    int alternateX = (int) Math.round(Math.cos(angle) * (island.radius() - 1));
                    int alternateZ = (int) Math.round(Math.sin(angle) * (island.radius() - 1));
                    assertTrue(climb <= Math.max(Math.abs(x), Math.abs(z)), "main ramp seed=" + seed);
                    assertTrue(climb <= Math.max(Math.abs(x - alternateX), Math.abs(z - alternateZ)),
                            "alternate ramp seed=" + seed);
                }
            }
        }
    }

    private static double distance(BlockPos left, BlockPos right) {
        return Math.hypot(left.getX() - right.getX(), left.getZ() - right.getZ());
    }
}
