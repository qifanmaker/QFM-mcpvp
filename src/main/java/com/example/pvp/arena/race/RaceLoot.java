package com.example.pvp.arena.race;

/**
 * 道具抽签策略（纯逻辑，不依赖任何 Minecraft 类型，便于离线做分布检验）。
 *
 * <p>权重数组的下标与 {@link RaceItem#values()} 一一对应：
 * {@code [0]=NITRO 氮气}、{@code [1]=TRAP 速冻胶}、{@code [2]=INK 墨水弹}、{@code [3]=SHIELD 鱼鳞护盾}。
 * 之所以不直接写 {@code RaceItem.values()}，是因为 {@code RaceItem} 依赖 Minecraft 的物品注册表，
 * 一旦引用它，这段纯策略就没法离线跑了。
 */
final class RaceLoot {
    private RaceLoot() {
    }

    /**
     * 名次加权权重。
     *
     * <p>{@code place} 从 1 开始（1 = 第一名），{@code total} 是本场人数。
     * 落后者拿攻击类（速冻胶/墨水弹）的权重随名次线性上升；<b>领先者只出防御类</b>
     * （攻击权重直接归零）—— 目的是抑制"第一名越跑越远"的滚雪球，
     * 让道具战成为追回来的手段，而不是扩大差距的工具。
     *
     * @return 长度为 4 的权重数组，顺序见类注释；保证全为正且和大于 0
     */
    static double[] weights(int place, int total) {
        if (total <= 1) {
            return new double[]{1.0, 1.0, 1.0, 1.0};
        }
        int clampedPlace = Math.max(1, Math.min(total, place));
        // r = 0（第一名）… 1（最后一名）
        double r = (clampedPlace - 1) / (double) (total - 1);
        double nitro = 40.0;
        double trap = 15.0 + 35.0 * r;
        double ink = 10.0 + 25.0 * r;
        double shield = 20.0 + 30.0 * (1.0 - r);
        if (clampedPlace <= 1) {
            trap = 0.0;
            ink = 0.0;
        }
        return new double[]{nitro, trap, ink, shield};
    }

    /** 按权重抽一个下标；{@code roll} 由调用方给出（0 ~ 总权重），保证可复现。 */
    static int pick(double[] weights, double roll) {
        double totalWeight = 0.0;
        for (double weight : weights) {
            totalWeight += weight;
        }
        double remaining = roll * totalWeight;
        for (int i = 0; i < weights.length; i++) {
            if ((remaining -= weights[i]) < 0.0) {
                return i;
            }
        }
        return 0;
    }
}
