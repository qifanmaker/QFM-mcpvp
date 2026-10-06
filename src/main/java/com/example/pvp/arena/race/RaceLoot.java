package com.example.pvp.arena.race;

/**
 * 道具抽签策略（纯逻辑，不依赖任何 Minecraft 类型，便于离线做分布检验）。
 *
 * <p>权重数组的下标与 {@link RaceItem#values()} 一一对应：
 * {@code [0]=NITRO 氮气}、{@code [1]=TRAP 速冻胶}、{@code [2]=INK 墨水弹}、
 * {@code [3]=SHIELD 鱼鳞护盾}（权重由配置传入，默认极低 —— 它是稀有保命道具）。
 * 之所以不直接写 {@code RaceItem.values()}，是因为 {@code RaceItem} 依赖 Minecraft 的物品注册表，
 * 一旦引用它，这段纯策略就没法离线跑了。
 */
final class RaceLoot {
    /**
     * 抽取权重参数（都来自配置）。
     *
     * @param trapLeader   速冻胶权重（第一名侧）
     * @param trapLast     速冻胶权重（最后一名侧）
     * @param shieldWeight 鱼鳞护盾权重（对所有名次相同，刻意很低）
     */
    record WeightConfig(double trapLeader, double trapLast, double shieldWeight) {
    }

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
    static double[] weights(int place, int total, WeightConfig cfg) {
        double shield = Math.max(0.0, cfg.shieldWeight());
        if (total <= 1) {
            return new double[]{1.0, 1.0, 1.0, shield};
        }
        int clampedPlace = Math.max(1, Math.min(total, place));
        // r = 0（第一名）… 1（最后一名）
        double r = (clampedPlace - 1) / (double) (total - 1);
        double nitro = 40.0;
        // 速冻胶与墨水弹是按名次加权的攻击类；两者的权重都由配置给（默认速冻胶更低：
        // 它是最难躲的一件，压上去几乎停住，出场太多会让比赛变成"排雷"）。
        double trap = cfg.trapLeader() + (cfg.trapLast() - cfg.trapLeader()) * r;
        double ink = 10.0 + 25.0 * r;
        if (clampedPlace <= 1) {
            // 领先者拿不到攻击类：道具战是给后面的人追回来的手段，不是扩大差距的工具
            trap = 0.0;
            ink = 0.0;
        }
        // 护盾对所有名次都是同一个（很低的）权重：它是保命道具，不该按名次倾斜
        return new double[]{nitro, trap, ink, shield};
    }

    /**
     * 「缓冲区结冰」援助的触发概率（每 tick）：名次越靠后越高。
     *
     * <p>为什么用"概率"而不是"换成更慢的方块"：原版方块只有 0.98（冰，40 格/秒）与
     * 0.6（雪，2 格/秒）两档，没有中间值。所以"比正常路面慢一点"只能靠<b>触发概率</b>做 ——
     * 没触发时船还压在雪上（2 格/秒），触发后雪冻成冰面（40 格/秒），
     * 平均速度就落在两者之间，而且落后的人概率更高 → 平均更快。
     *
     * @param leaderChance 第一名的每 tick 触发概率
     * @param lastChance   最后一名的每 tick 触发概率（中间名次线性插值）
     */
    /**
     * 缓冲区结冰的<b>充能速率</b>（每秒充满的比例），按名次在领头/最后一名之间线性插值。
     *
     * <p>为什么从"每 tick 掷概率"改成"确定性充能"：概率 + 续期等于"只要在缓冲带里待够
     * 期望时间，冰就永久不化" —— 领头 1%/tick 的期望等待只有 100 tick（5 秒），
     * 而缓冲带本身比冰面宽 5 格，于是援助变成了"领跑者第一圈之后白嫖一条外道"。
     * 充能版把代价摊开：充能只在缓冲带里累加（那里只有 2 格/秒），
     * 领头要待满 1/{@code leaderRate} 秒才换 5 秒冰，净收益为负，没人会故意去刷。
     */
    static double gripChargeRate(double leaderRate, double lastRate, int place, int total) {
        double min = Math.max(0.0, leaderRate);
        double max = Math.max(0.0, lastRate);
        if (total <= 1) {
            return min;
        }
        int clamped = Math.max(1, Math.min(total, place));
        double r = (clamped - 1) / (double) (total - 1);
        return min + (max - min) * r;
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
