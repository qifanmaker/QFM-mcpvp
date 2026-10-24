package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 首版枪战必须证明击杀阈值、点射实际击杀时间和爆破压线规则没有被取整或顺序改变。 */
final class MyGoRulesTest {
    @Test void helmetThresholdsRemainExactAndLegsIgnoreArmor() {
        assertEquals(115.2, MyGoRules.damage(MyGoWeapon.AK, 10, MyGoRules.Part.HEAD, true, true), 1e-9);
        assertEquals(99, MyGoRules.damage(MyGoWeapon.M4, 10, MyGoRules.Part.HEAD, true, true), 1e-9);
        assertTrue(MyGoRules.damage(MyGoWeapon.M4, 10, MyGoRules.Part.HEAD, true, true) < 100);
        assertEquals(52.5, MyGoRules.damage(MyGoWeapon.AW, 10, MyGoRules.Part.LEGS, true, true), 1e-9);
        assertEquals(102.9, MyGoRules.damage(MyGoWeapon.AW, 10, MyGoRules.Part.BODY, true, true), 1e-9);
        assertEquals(16.5, MyGoRules.damage(MyGoWeapon.M4, 10, MyGoRules.Part.LEGS, true, true), 1e-9);
    }

    @Test void actualBurstScheduleProducesAdvertisedBodyKillTime() {
        assertEquals(20, killTick(MyGoWeapon.P9));
        assertEquals(12, killTick(MyGoWeapon.MP5));
        assertEquals(12, killTick(MyGoWeapon.AK));
        assertEquals(10, killTick(MyGoWeapon.M4));
        assertEquals(0, killTick(MyGoWeapon.AW));
    }

    private static int killTick(MyGoWeapon weapon) {
        double hp = 100;
        for (int cycle = 0; cycle < 20; cycle++) {
            for (int offset : weapon.burstOffsets()) {
                hp -= MyGoRules.damage(weapon, 5, MyGoRules.Part.BODY, true, true);
                if (hp <= 1e-9) return cycle * weapon.cycleTicks + offset;
            }
        }
        fail("No kill within 20 bursts"); return -1;
    }

    @Test void deadlineTickWinsOverPlantAndDefuseProgress() {
        assertNull(MyGoRules.timedOutcome(false, 1799, 1800));
        assertEquals(MyGoRules.Outcome.DEFENDERS, MyGoRules.timedOutcome(false, 1800, 1800));
        assertEquals(MyGoRules.Outcome.ATTACKERS, MyGoRules.timedOutcome(true, 800, 800));
        assertFalse(MyGoRules.beforeDeadline(800, 800));
        assertTrue(MyGoRules.beforeDeadline(799, 800));
    }

    @Test void plantedBombSurvivesAttackerEliminationAndDefenderWipeWinsForAttackers() {
        assertNull(MyGoRules.eliminationOutcome(true, 0, 2));
        assertEquals(MyGoRules.Outcome.ATTACKERS, MyGoRules.eliminationOutcome(true, 0, 0));
        assertEquals(MyGoRules.Outcome.DEFENDERS, MyGoRules.eliminationOutcome(false, 0, 2));
        assertEquals(MyGoRules.Outcome.ATTACKERS, MyGoRules.eliminationOutcome(true, 2, 0));
    }

    @Test void fastEconomyAllowsRecoveryAndCapsRewards() {
        assertEquals(2200, MyGoRules.lossReward(1));
        assertEquals(3700, MyGoRules.lossReward(4));
        assertEquals(3700, MyGoRules.lossReward(20));
        int afterVestAndTwoLosses = MyGoRules.addMoney(MyGoRules.addMoney(1600 - 650, 2200), 2700);
        assertTrue(afterVestAndTwoLosses >= MyGoWeapon.AK.price + 650 + 350);
        assertEquals(16000, MyGoRules.addMoney(15800, 3250));
        assertEquals(0, MyGoRules.addMoney(100, -200));
    }

    @Test void sixthRoundSwitchesSidesAndTwelveRoundsCanDraw() {
        assertFalse(MyGoRules.switchedSides(6));
        assertTrue(MyGoRules.switchedSides(7));
        assertFalse(MyGoRules.finished(6, 5));
        assertTrue(MyGoRules.finished(7, 0));
        assertTrue(MyGoRules.finished(6, 6));
    }

    @Test void damageRangeDecaysContinuouslyAndCannotHitBeyondLimit() {
        for (MyGoWeapon weapon : MyGoWeapon.values()) {
            assertEquals(1, weapon.distanceMultiplier(weapon.fullDamageRange), 1e-9);
            assertEquals(weapon.farMultiplier, weapon.distanceMultiplier(weapon.maxRange), 1e-9);
            assertEquals(0, weapon.distanceMultiplier(weapon.maxRange + .01));
            int[] offsets = weapon.burstOffsets(); offsets[0] = 999;
            assertEquals(0, weapon.burstOffsets()[0]);
        }
    }
}
