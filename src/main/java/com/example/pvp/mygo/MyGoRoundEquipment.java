package com.example.pvp.mygo;

import java.util.EnumMap;
import java.util.Map;

/**
 * 修改原因：持有上限不是购买额度，投出手雷不应让本回合购买次数恢复。
 * 预审：每回合以保留手雷初始化额度，仅成功购买才记账，拾取/投出不改购买计数。
 * Valve 2019-04-22 明确规定上局保留手雷也占本回合各类购买额度。
 * https://blog.counter-strike.net/2019/04/23870/
 */
public final class MyGoRoundEquipment {
    private final EnumMap<MyGoUtilities.Type, Integer> purchased = new EnumMap<>(MyGoUtilities.Type.class);

    public void beginRound(Map<MyGoUtilities.Type, Integer> carried) {
        this.purchased.clear();
        for (MyGoUtilities.Type type : MyGoUtilities.Type.values())
            this.purchased.put(type, Math.max(0, carried.getOrDefault(type, 0)));
    }

    public int purchased(MyGoUtilities.Type type) { return this.purchased.getOrDefault(type, 0); }
    public int remainingPurchases(MyGoUtilities.Type type) {
        int used = type.fire() ? this.purchased.entrySet().stream().filter(e -> e.getKey().fire())
                .mapToInt(Map.Entry::getValue).sum() : purchased(type);
        return Math.max(0, type.maximum - used);
    }
    public boolean canBuy(MyGoUtilities.Type type) { return remainingPurchases(type) > 0; }

    /** Only call after affordability and current carrying limits have been checked. */
    public boolean recordPurchase(MyGoUtilities.Type type) {
        if (!canBuy(type)) return false;
        this.purchased.merge(type, 1, Integer::sum);
        return true;
    }

    /**
     * 经典 mp_death_drop_grenade=2 只掉当前/优先一颗，不是清空后生成全部手雷。
     * 当前三种的原始 weapon weight 为 HE=2、SMOKE=2、FLASH=1，同权时稳定选 HE。
     */
    public static MyGoUtilities.Type deathGrenade(Map<MyGoUtilities.Type, Integer> carried, int selectedSlot) {
        for (MyGoUtilities.Type type : MyGoUtilities.Type.values())
            if (type.slot == selectedSlot && carried.getOrDefault(type, 0) > 0) return type;
        for (MyGoUtilities.Type type : MyGoUtilities.Type.values())
            if (type != MyGoUtilities.Type.FLASH && type != MyGoUtilities.Type.DECOY
                    && carried.getOrDefault(type, 0) > 0) return type;
        for (MyGoUtilities.Type type : new MyGoUtilities.Type[]{MyGoUtilities.Type.FLASH, MyGoUtilities.Type.DECOY})
            if (carried.getOrDefault(type, 0) > 0) return type;
        return null;
    }
}
