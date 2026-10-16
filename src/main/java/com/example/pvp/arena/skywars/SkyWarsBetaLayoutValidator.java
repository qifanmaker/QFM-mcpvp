package com.example.pvp.arena.skywars;

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
        if (layout.middle() != null && layout.middle().radius() < 6) {
            errors.add("中央主岛半径过小: " + layout.middle().radius());
        }
        return List.copyOf(errors);
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
