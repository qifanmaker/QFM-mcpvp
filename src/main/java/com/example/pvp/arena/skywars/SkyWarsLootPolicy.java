package com.example.pvp.arena.skywars;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

/**
 * 空岛物资共用的随机源及旧版每箱抽取规则。
 * 按要求恢复每箱独立抽取，开局和补货不再受整局神器配额限制。
 */
public final class SkyWarsLootPolicy {
    public enum Tier {
        SPAWN, TRANSIT, CENTER
    }

    public enum RareDrop {
        NONE, AXE, TOTEM, ELYTRA, ENCHANTED_GOLDEN_APPLE
    }

    private final Random random;

    /** seed 属于本局物资，不与可重复使用的地图种子绑定。 */
    public SkyWarsLootPolicy(long seed) {
        this.random = new Random(seed * 0x9E3779B97F4A7C15L ^ 0x6A09E667F3BCC909L);
    }

    Random random() {
        return this.random;
    }

    /** 旧表每箱额外抽取的物品组数，外圈保底装备和方块另计。 */
    public static int randomStackCount(Random random, Tier tier) {
        return tier == Tier.CENTER ? 5 + random.nextInt(3) : 4 + random.nextInt(2);
    }

    /** 每只箱子独立抽取，补货再次调用即可；同一次抽取只会命中一种稀有物品。 */
    public static RareDrop rareDrop(Random random, Tier tier) {
        int roll = random.nextInt(100);
        if (tier == Tier.CENTER) {
            return switch (roll) {
                case 0 -> RareDrop.ELYTRA;
                case 1 -> RareDrop.ENCHANTED_GOLDEN_APPLE;
                case 2 -> RareDrop.TOTEM;
                default -> RareDrop.NONE;
            };
        }
        return switch (roll) {
            case 0 -> RareDrop.AXE;
            case 1 -> RareDrop.TOTEM;
            default -> RareDrop.NONE;
        };
    }

    /** 弓触发附魔后，力量I～IV恢复为等概率。 */
    public static int bowPower(Random random) {
        return 1 + random.nextInt(4);
    }

    /** 恢复原规则：最低分2档，三人以上局次低分1档；同分时按原玩家顺序。 */
    public static int[] supportLevels(double[] ratings) {
        int[] result = new int[ratings.length];
        if (ratings.length == 0) return result;
        Integer[] order = new Integer[ratings.length];
        for (int i = 0; i < ratings.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(index -> ratings[index]));
        result[order[0]] = 2;
        if (ratings.length > 2) result[order[1]] = 1;
        return result;
    }
}
