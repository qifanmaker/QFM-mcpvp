package com.example.pvp.arena.skywars;

import com.example.pvp.arena.ArenaWorld;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Beta 地图的几何安全检查。
 *
 * <p>这只检查布局输入，不参与战斗逻辑。生成器遇到异常时继续生成并记录警告，
 * 避免因为一个极端 seed 让整场对局无法开始。</p>
 */
public final class SkyWarsBetaLayoutValidator {
    private SkyWarsBetaLayoutValidator() {
    }

    public static List<String> validate(SkyWarsLayout layout, int expectedPlayers) {
        List<String> errors = new ArrayList<>();
        if (layout == null) {
            errors.add("布局为空");
            return errors;
        }
        if (layout.spawnIslands().size() != expectedPlayers) {
            errors.add("出生岛数量 " + layout.spawnIslands().size() + " != 玩家数 " + expectedPlayers);
        }
        if (layout.middle() == null) {
            errors.add("缺少中央主岛");
        }
        checkIslands(errors, "出生岛", layout.spawnIslands());
        checkIslands(errors, "中途岛", layout.midIslands());
        checkPairwiseSpacing(errors, "出生岛", layout.spawnIslands(), 1);
        checkPairwiseSpacing(errors, "中途岛", layout.midIslands(), 1);
        checkFairRoutes(errors, layout);
        if (layout.middle() != null && layout.middle().radius() < 6) {
            errors.add("中央主岛半径过小: " + layout.middle().radius());
        }
        return List.copyOf(errors);
    }

    /** 几何铺完后检查实际方块，兼容 ArenaWorld 的暂存叠加层。 */
    public static List<String> validateGenerated(ArenaWorld world, SkyWarsLayout layout, SkyWarsTheme theme) {
        List<String> errors = new ArrayList<>();
        for (BlockPos spawn : layout.spawns()) {
            if (world.getBlockState(spawn.down()).isAir() || !world.getBlockState(spawn).isAir()
                    || !world.getBlockState(spawn.up()).isAir()) errors.add("出生点未留净空: " + spawn);
        }
        for (SkyWarsLayout.Island island : layout.spawnIslands()) checkChests(errors, world, island, false, false, theme);
        for (SkyWarsLayout.Island island : layout.midIslands()) checkChests(errors, world, island, false, false, theme);
        for (int i = 0; i < layout.middleIslands().size(); i++) {
            checkChests(errors, world, layout.middleIslands().get(i), true, i == 0, theme);
        }
        for (SkyWarsLayout.ExtraChest extra : layout.extraChests()) {
            if (extra.lootTable() == null) checkChest(errors, world, extra.pos());
        }
        if (theme != SkyWarsTheme.VILLAGE && layout.middle() != null) {
            BlockPos lower = layout.middle().center().down(SkyWarsBetaMapGenerator.LOWER_LAYER_DROP);
            if (world.getBlockState(lower).isAir()) errors.add("下层缺少中央安全地板");
            for (int dy = 1; dy <= 3; dy++) {
                if (!world.getBlockState(lower.up(dy)).isAir()) errors.add("下层中央净空不足");
            }
        }
        return List.copyOf(errors);
    }

    private static void checkChests(List<String> errors, ArenaWorld world, SkyWarsLayout.Island island,
                                    boolean middle, boolean central, SkyWarsTheme theme) {
        for (int i = 0; i < island.chests().size(); i++) {
            checkChest(errors, world, SkyWarsMapGenerator.islandChestPos(island, i, middle, central, theme));
        }
    }

    private static void checkChest(List<String> errors, ArenaWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isOf(Blocks.CHEST)
                || !(world.getBlockEntity(pos) instanceof ChestBlockEntity)) errors.add("战斗箱丢失: " + pos);
        else if (!world.getBlockState(pos.up()).isAir()) errors.add("战斗箱顶部被遮挡: " + pos);
    }

    private static void checkFairRoutes(List<String> errors, SkyWarsLayout layout) {
        if (layout.spawnIslands().size() != layout.midIslands().size()) {
            errors.add("出生岛和中途岛未一一对应");
            return;
        }
        if (layout.spawnIslands().isEmpty()) return;
        SkyWarsLayout.Island firstSpawn = layout.spawnIslands().get(0);
        SkyWarsLayout.Island firstTransit = layout.midIslands().get(0);
        double minGap = layout.compact() ? 10 : 14;
        double maxGap = layout.compact() ? 14 : 20;
        for (int i = 0; i < layout.spawnIslands().size(); i++) {
            SkyWarsLayout.Island spawn = layout.spawnIslands().get(i);
            SkyWarsLayout.Island transit = layout.midIslands().get(i);
            double gap = horizontalDistance(spawn.center(), transit.center()) - spawn.radius() - transit.radius();
            if (gap < minGap - 0.5 || gap > maxGap + 0.5) errors.add("出生主路桥距超出范围: " + i + "=" + gap);
            if (spawn.radius() != firstSpawn.radius() || transit.radius() != firstTransit.radius()
                    || spawn.center().getY() != firstSpawn.center().getY()
                    || transit.center().getY() != firstTransit.center().getY()
                    || spawn.chests().size() != firstSpawn.chests().size()
                    || transit.chests().size() != firstTransit.chests().size()) errors.add("出生主路预算不一致: " + i);
        }
    }

    private static void checkIslands(List<String> errors, String label, List<SkyWarsLayout.Island> islands) {
        for (int i = 0; i < islands.size(); i++) {
            SkyWarsLayout.Island island = islands.get(i);
            if (island.radius() < 3) {
                errors.add(label + " " + i + " 半径过小: " + island.radius());
            }
            if (island.chests().stream().anyMatch(chest -> chest.getY() <= island.center().getY())) {
                errors.add(label + " " + i + " 箱子低于岛面");
            }
        }
    }

    private static void checkPairwiseSpacing(List<String> errors, String label,
                                             List<SkyWarsLayout.Island> islands, int gap) {
        for (int i = 0; i < islands.size(); i++) {
            for (int j = i + 1; j < islands.size(); j++) {
                SkyWarsLayout.Island left = islands.get(i);
                SkyWarsLayout.Island right = islands.get(j);
                double distance = horizontalDistance(left.center(), right.center());
                double required = left.radius() + right.radius() + gap;
                if (distance < required) {
                    errors.add(label + " " + i + "/" + j + " 水平间距不足: "
                            + String.format(java.util.Locale.ROOT, "%.1f < %.1f", distance, required));
                }
            }
        }
    }

    private static double horizontalDistance(BlockPos left, BlockPos right) {
        int dx = left.getX() - right.getX();
        int dz = left.getZ() - right.getZ();
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }
}
