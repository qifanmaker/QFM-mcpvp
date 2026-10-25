package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 经典经济必须覆盖首轮败补、胜后降档、半场边界、残甲和安拆压线，不能仅验证常量本身。 */
final class MyGoRulesTest {
    @Test void helmetsPreserveAkAndM4OneShotThresholdsAndLegsIgnoreArmor() {
        assertEquals(111, MyGoRules.damage(MyGoWeapon.AK47, 0, MyGoRules.Part.HEAD, true, true));
        assertEquals(92, MyGoRules.damage(MyGoWeapon.M4A4, 0, MyGoRules.Part.HEAD, true, true));
        assertEquals(112, MyGoRules.damage(MyGoWeapon.AWP, 0, MyGoRules.Part.BODY, true, true));
        assertEquals(86, MyGoRules.damage(MyGoWeapon.AWP, 0, MyGoRules.Part.LEGS, true, true));
        assertEquals(86, MyGoRules.damage(MyGoWeapon.AWP, 0, MyGoRules.Part.LEGS, false, false));
        assertEquals(92, MyGoRules.damage(MyGoWeapon.M4A1S, 0, MyGoRules.Part.HEAD, true, true));
    }

    @Test void stomachHasItsOwnMultiplierAndIsProtectedByTheVest() {
        assertEquals(45, MyGoRules.damage(MyGoWeapon.AK47, 0, MyGoRules.Part.STOMACH, false, false));
        assertEquals(34, MyGoRules.damage(MyGoWeapon.AK47, 0, MyGoRules.Part.STOMACH, true, false));
        assertEquals(41, MyGoRules.damage(MyGoWeapon.M4A4, 0, MyGoRules.Part.STOMACH, false, false));
        assertEquals(28, MyGoRules.damage(MyGoWeapon.M4A4, 0, MyGoRules.Part.STOMACH, true, true));
        assertEquals(140, MyGoRules.damage(MyGoWeapon.AWP, 0, MyGoRules.Part.STOMACH, true, true));
    }

    @Test void hitRegionsIncludeTheBellyForBothStandingAndCrouchedBounds() {
        for (double height : new double[]{1.8, 1.5}) {
            assertEquals(MyGoRules.Part.LEGS, MyGoRules.hitPart((height * .25) / height));
            assertEquals(MyGoRules.Part.STOMACH, MyGoRules.hitPart((height * .47) / height));
            assertEquals(MyGoRules.Part.BODY, MyGoRules.hitPart((height * .65) / height));
            assertEquals(MyGoRules.Part.HEAD, MyGoRules.hitPart((height * .9) / height));
        }
        assertEquals(MyGoRules.Part.LEGS, MyGoRules.hitPart(.38));
        assertEquals(MyGoRules.Part.BODY, MyGoRules.hitPart(.55));
        assertEquals(MyGoRules.Part.HEAD, MyGoRules.hitPart(.75));
    }

    @Test void bulletHealthRoundsDownButArmorLossRoundsUpAfterRangeAndHitgroup() {
        var ak = MyGoRules.roundBulletDamage(MyGoRules.damageWithArmor(MyGoWeapon.AK47, 0, MyGoRules.Part.BODY, 100, true));
        assertEquals(new MyGoRules.Damage(27, 5), ak);
        var glock = MyGoRules.roundBulletDamage(MyGoRules.damageWithArmor(MyGoWeapon.GLOCK,
                4000 / MyGoWeapon.SOURCE_UNITS_PER_BLOCK, MyGoRules.Part.BODY, 100, true));
        assertEquals(new MyGoRules.Damage(3, 3), glock);
        assertEquals(33, MyGoRules.damage(MyGoWeapon.AK47,
                700 / MyGoWeapon.SOURCE_UNITS_PER_BLOCK, MyGoRules.Part.STOMACH, true, true));
    }

    @Test void fractionalPelletsKeepIntermediatePrecisionAndExhaustArmorBeforeRounding() {
        var first = MyGoRules.damageWithArmor(MyGoWeapon.NOVA, 0, MyGoRules.Part.BODY, 7, true);
        var second = MyGoRules.damageWithArmor(MyGoWeapon.NOVA, 0, MyGoRules.Part.BODY, 7 - first.armor(), true);
        assertEquals(new MyGoRules.Damage(38, 7), MyGoRules.roundBulletDamage(
                new MyGoRules.Damage(first.health() + second.health(), first.armor() + second.armor())));
        // 1.8 + 1.8 必须先累加为 3.6 再取 3，不能两次取整而变成 2。
        assertEquals(new MyGoRules.Damage(3, 1), MyGoRules.roundBulletDamage(new MyGoRules.Damage(1.8 + 1.8, .4 + .4)));
    }

    @Test void armorIsConsumedAndLowArmorCannotGrantAnInfiniteReduction() {
        var hit = MyGoRules.damageWithArmor(MyGoWeapon.AK47, 0, MyGoRules.Part.BODY, 100, true);
        assertEquals(27.9, hit.health(), 1e-9);
        assertEquals(4.05, hit.armor(), 1e-9);
        var exhausted = MyGoRules.damageWithArmor(MyGoWeapon.AK47, 0, MyGoRules.Part.BODY, 1, true);
        assertEquals(34, exhausted.health(), 1e-9);
        assertEquals(1, exhausted.armor());
        assertEquals(36, MyGoRules.damageWithArmor(MyGoWeapon.AK47, 0, MyGoRules.Part.BODY, 0, true).health());
        assertEquals(144, MyGoRules.damageWithArmor(MyGoWeapon.AK47, 0, MyGoRules.Part.HEAD, 100, false).health());
    }

    @Test void armorRepairsAndHelmetUpgradesUseTheExistingEquipment() {
        assertEquals(1000, MyGoRules.armorCost(0, false, true));
        assertEquals(350, MyGoRules.armorCost(100, false, true));
        assertEquals(1000, MyGoRules.armorCost(70, false, true));
        assertEquals(650, MyGoRules.armorCost(70, true, true));
        assertEquals(0, MyGoRules.armorCost(100, true, true));
        assertEquals(650, MyGoRules.armorCost(50, false, false));
    }

    @Test void aPistolLossStartsAt1900AndAWinOnlyStepsDownOneTier() {
        int level = MyGoRules.START_LOSS_LEVEL;
        assertEquals(1900, MyGoRules.lossReward(level));
        level = MyGoRules.afterLoss(level);
        assertEquals(2400, MyGoRules.lossReward(level));
        level = MyGoRules.afterLoss(MyGoRules.afterLoss(level));
        assertEquals(3400, MyGoRules.lossReward(level));
        for (int i = 0; i < 20; i++) level = MyGoRules.afterLoss(level);
        assertEquals(3400, MyGoRules.lossReward(level));
        assertEquals(2900, MyGoRules.lossReward(MyGoRules.afterWin(level)));
        assertEquals(1400, MyGoRules.lossReward(MyGoRules.afterWin(MyGoRules.START_LOSS_LEVEL)));
    }

    @Test void objectiveWinsAndMoneyCapsAreDistinctFromElimination() {
        assertEquals(3500, MyGoRules.roundReward(MyGoRules.WinCause.BOMB_DEFUSED));
        assertEquals(3500, MyGoRules.roundReward(MyGoRules.WinCause.BOMB_EXPLODED));
        assertEquals(3250, MyGoRules.roundReward(MyGoRules.WinCause.ELIMINATION));
        assertEquals(3250, MyGoRules.roundReward(MyGoRules.WinCause.TIMEOUT));
        assertEquals(16000, MyGoRules.addMoney(15900, 3500));
        assertEquals(0, MyGoRules.addMoney(100, -200));
    }

    @Test void fifteenthRoundSwitchesSidesAndThirtyRoundsCanDraw() {
        assertFalse(MyGoRules.switchedSides(15));
        assertTrue(MyGoRules.switchedSides(16));
        assertFalse(MyGoRules.finished(15, 14));
        assertTrue(MyGoRules.finished(16, 0));
        assertTrue(MyGoRules.finished(15, 15));
        assertFalse(MyGoRules.finished(7, 0));
    }

    @Test void exactDeadlineWinsOverDefuseAndPlantProgress() {
        assertNull(MyGoRules.timedOutcome(false, MyGoRules.ROUND_TICKS - 1, MyGoRules.ROUND_TICKS));
        assertEquals(MyGoRules.Outcome.DEFENDERS, MyGoRules.timedOutcome(false, MyGoRules.ROUND_TICKS, MyGoRules.ROUND_TICKS));
        assertEquals(MyGoRules.Outcome.ATTACKERS, MyGoRules.timedOutcome(true, 800, 800));
        assertFalse(MyGoRules.beforeDeadline(800, 800));
        assertTrue(MyGoRules.beforeDeadline(799, 800));
        assertEquals(64, MyGoRules.PLANT_TICKS);
        assertEquals(200, MyGoRules.DEFUSE_TICKS);
        assertEquals(100, MyGoRules.KIT_DEFUSE_TICKS);
    }

    @Test void plantedBombSurvivesAttackerElimination() {
        assertNull(MyGoRules.eliminationOutcome(true, 0, 2));
        assertEquals(MyGoRules.Outcome.ATTACKERS, MyGoRules.eliminationOutcome(true, 0, 0));
        assertEquals(MyGoRules.Outcome.DEFENDERS, MyGoRules.eliminationOutcome(false, 0, 2));
    }

    @Test void rangeDecayIsMonotonicAndCannotHitBeyondTheWeaponLimit() {
        for (MyGoWeapon weapon : MyGoWeapon.values()) {
            assertEquals(1, weapon.distanceMultiplier(0));
            assertTrue(weapon.distanceMultiplier(10) <= 1);
            assertTrue(weapon.distanceMultiplier(30) <= weapon.distanceMultiplier(10));
            assertEquals(weapon.farMultiplier, weapon.distanceMultiplier(weapon.maxRange), 1e-9);
            assertEquals(0, weapon.distanceMultiplier(weapon.maxRange + .01));
            int[] offsets = weapon.burstOffsets(); offsets[0] = 999;
            assertEquals(0, weapon.burstOffsets()[0]);
        }
    }
}
