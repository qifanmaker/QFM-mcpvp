package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class MyGoRoundEquipmentTest {
    @Test void throwingAndPickingUpDoNotRefreshTheRoundPurchaseBudget() {
        MyGoRoundEquipment budget = new MyGoRoundEquipment();
        Map<MyGoUtilities.Type, Integer> held = new EnumMap<>(MyGoUtilities.Type.class);
        budget.beginRound(held);
        assertTrue(budget.recordPurchase(MyGoUtilities.Type.HE));
        held.put(MyGoUtilities.Type.HE, 0);
        assertFalse(budget.canBuy(MyGoUtilities.Type.HE));
        assertFalse(budget.recordPurchase(MyGoUtilities.Type.HE));
        assertEquals(1, budget.purchased(MyGoUtilities.Type.HE));
        assertEquals(0, budget.remainingPurchases(MyGoUtilities.Type.HE));
        assertTrue(budget.canBuy(MyGoUtilities.Type.SMOKE));
    }

    @Test void carriedGrenadesConsumeNewRoundBudgetEvenAfterBeingThrown() {
        MyGoRoundEquipment budget = new MyGoRoundEquipment();
        Map<MyGoUtilities.Type, Integer> held = new EnumMap<>(MyGoUtilities.Type.class);
        held.put(MyGoUtilities.Type.HE, 1);
        held.put(MyGoUtilities.Type.FLASH, 1);
        budget.beginRound(held);
        held.clear();
        assertFalse(budget.canBuy(MyGoUtilities.Type.HE));
        assertEquals(1, budget.remainingPurchases(MyGoUtilities.Type.FLASH));
        assertTrue(budget.recordPurchase(MyGoUtilities.Type.FLASH));
        assertFalse(budget.recordPurchase(MyGoUtilities.Type.FLASH));
        budget.beginRound(Map.of());
        assertTrue(budget.canBuy(MyGoUtilities.Type.HE));
        assertEquals(2, budget.remainingPurchases(MyGoUtilities.Type.FLASH));
    }

    @Test void deathDropsOnlyTheCurrentlySelectedOrOneHighestWeightGrenade() {
        Map<MyGoUtilities.Type, Integer> held = Map.of(MyGoUtilities.Type.HE, 1,
                MyGoUtilities.Type.SMOKE, 1, MyGoUtilities.Type.FLASH, 2);
        assertEquals(MyGoUtilities.Type.FLASH, MyGoRoundEquipment.deathGrenade(held, MyGoUtilities.Type.FLASH.slot));
        assertEquals(MyGoUtilities.Type.HE, MyGoRoundEquipment.deathGrenade(held, 0));
        assertEquals(MyGoUtilities.Type.SMOKE, MyGoRoundEquipment.deathGrenade(
                Map.of(MyGoUtilities.Type.SMOKE, 1, MyGoUtilities.Type.FLASH, 2), 0));
        assertNull(MyGoRoundEquipment.deathGrenade(Map.of(), 0));
        assertEquals(2, held.get(MyGoUtilities.Type.FLASH));
    }
}
