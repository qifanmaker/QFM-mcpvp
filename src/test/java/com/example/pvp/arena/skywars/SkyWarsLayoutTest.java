package com.example.pvp.arena.skywars;

import com.example.pvp.config.PvPConfig;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
                    assertEquals(players <= 4 ? 10 : 14, layout.suggestedFinalRadius(), context);
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
                variants.add(first.middleVariant());
                styles.add(new Random(first.styleSeed()).nextInt(6));
            }
            assertEquals(3, variants.size(), theme.name());
            assertEquals(6, styles.size(), theme.name());
        }
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
