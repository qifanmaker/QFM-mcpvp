package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MyGoKnifeRulesTest {
    @Test void armoredTwoSlashesAndAStabKeepTheClassic100HpCombo() {
        double first = MyGoKnifeRules.damage(false, false, true, 100).health();
        double second = MyGoKnifeRules.damage(false, false, false, 100).health();
        double stab = MyGoKnifeRules.damage(true, false, true, 100).health();
        assertEquals(34, first);
        assertEquals(21, second);
        assertEquals(55, stab);
        assertTrue(first + stab < 100);
        assertTrue(first + second + stab >= 100);
    }

    @Test void backstabsKillButBackSlashesDoNotAlwaysKill() {
        assertEquals(76, MyGoKnifeRules.damage(false, true, true, 100).health());
        assertEquals(153, MyGoKnifeRules.damage(true, true, false, 100).health());
        assertEquals(90, MyGoKnifeRules.damage(false, true, false, 0).health());
        assertEquals(180, MyGoKnifeRules.damage(true, true, false, 0).health());
        assertEquals(new MyGoRules.Damage(63, 1), MyGoKnifeRules.damage(true, false, false, 1));
    }

    @Test void backstabRequiresTheAttackerToBeBehindTheVictim() {
        assertTrue(MyGoKnifeRules.isBackstab(0, 1, 0, 1));
        assertFalse(MyGoKnifeRules.isBackstab(0, 1, 0, -1));
        assertFalse(MyGoKnifeRules.isBackstab(0, 1, 1, 0));
        assertFalse(MyGoKnifeRules.isBackstab(0, 1, 0, 0));
        assertTrue(MyGoKnifeRules.isBackstab(0, 4, .5, 1));
    }

    @Test void missesAndHitsHaveDifferentRecoveryAndStabsHaveShorterTraceReach() {
        assertEquals(8, MyGoKnifeRules.cooldownTicks(false, false));
        assertEquals(10, MyGoKnifeRules.cooldownTicks(false, true));
        assertEquals(20, MyGoKnifeRules.cooldownTicks(true, false));
        assertEquals(22, MyGoKnifeRules.cooldownTicks(true, true));
        assertTrue(MyGoKnifeRules.rangeBlocks(true) < MyGoKnifeRules.rangeBlocks(false));
        assertFalse(MyGoKnifeRules.firstSlash(18, 10));
        assertTrue(MyGoKnifeRules.firstSlash(19, 10));
    }
}
