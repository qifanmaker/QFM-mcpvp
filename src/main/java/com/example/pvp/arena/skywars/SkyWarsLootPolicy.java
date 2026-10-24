package com.example.pvp.arena.skywars;

import java.util.Arrays;
import java.util.Random;

/**
 * 一局空岛的物资预算。原先按箱子抽神器会让箱子越多的地图越容易产出多把秒人斧，
 * 所以资格只在创建对局时抽一次，且同一份策略会一直保留到补货结束。
 * 数量、附魔分布和补偿计算不依赖 Minecraft，便于独立抽样核对。
 */
public final class SkyWarsLootPolicy {
    public enum Tier {
        SPAWN, TRANSIT, CENTER
    }

    private final Random random;
    private final boolean rareAxeEligible;
    private boolean rareAxeIssued;

    public SkyWarsLootPolicy(int seed) {
        this.random = new Random(seed * 0x9E3779B97F4A7C15L ^ 0x6A09E667F3BCC909L);
        this.rareAxeEligible = this.random.nextInt(100) < 12;
    }

    Random random() {
        return this.random;
    }

    /** 仅初次填充中心箱时领取，补货及重复调用均不能额外生成。 */
    public boolean claimRareAxe(boolean refill) {
        if (refill || !this.rareAxeEligible || this.rareAxeIssued) {
            return false;
        }
        this.rareAxeIssued = true;
        return true;
    }

    /** 出生岛总预算，箱子数量仅影响分散位置。 */
    public static int spawnBlockCount(Random random) {
        return 48 + random.nextInt(17);
    }

    /** 所有等级均保留；外圈偏向低级，中岛偏向高级。 */
    public static int bowPower(Random random, Tier tier) {
        int roll = random.nextInt(100);
        if (tier == Tier.CENTER) {
            return roll < 15 ? 1 : roll < 45 ? 2 : roll < 80 ? 3 : 4;
        }
        return roll < 65 ? 1 : roll < 90 ? 2 : roll < 98 ? 3 : 4;
    }

    /** 同分得到同样待遇；只给明显落后于本局中位数的玩家少量基础消耗品。 */
    public static int[] supportLevels(double[] ratings) {
        int[] result = new int[ratings.length];
        double[] sorted = Arrays.stream(ratings).filter(Double::isFinite).sorted().toArray();
        if (sorted.length < 2) {
            return result;
        }
        double median = sorted[sorted.length / 2];
        if (sorted.length % 2 == 0) {
            median = (median + sorted[sorted.length / 2 - 1]) / 2.0;
        }
        for (int i = 0; i < ratings.length; i++) {
            if (Double.isFinite(ratings[i])) {
                double gap = median - ratings[i];
                result[i] = gap >= 300.0 ? 2 : gap >= 150.0 ? 1 : 0;
            }
        }
        return result;
    }
}
