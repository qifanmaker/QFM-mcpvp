package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression cases exercise survivability and depleted armor, not just configured constants. */
final class MyGoExplosiveRulesTest {
    @Test void aSingleArmorPointCannotTurnAnAlmostLethalHeInto57Damage() {
        assertEquals(new MyGoRules.Damage(100, 0), MyGoRules.heDamage(100, 0));
        assertEquals(new MyGoRules.Damage(98, 1), MyGoRules.heDamage(100, 1));
        assertEquals(new MyGoRules.Damage(60, 20), MyGoRules.heDamage(100, 100));
    }

    @Test void heConsumesOnlyTheArmorActuallyNeeded() {
        assertEquals(new MyGoRules.Damage(54, 18), MyGoRules.heDamage(90, 18));
        assertEquals(new MyGoRules.Damage(56, 17), MyGoRules.heDamage(90, 17));
        assertEquals(new MyGoRules.Damage(0, 0), MyGoRules.heDamage(Double.NaN, 100));
        assertEquals(new MyGoRules.Damage(0, 0), MyGoRules.heDamage(-1, 100));
    }

    @Test void c4CanWoundPlayersBeyondTheOld12BlockHardBoundary() {
        double unarmored = MyGoBombDamage.damage(21, 0).health();
        double armored = MyGoBombDamage.damage(21, 100).health();
        assertTrue(unarmored > 100);
        assertTrue(armored > 0 && armored < 100);
        assertTrue(MyGoBombDamage.damage(12.1, 100).health() > 0);
        assertTrue(MyGoBombDamage.damage(12.1, 100).armor() > 0);
    }

    @Test void c4DistanceAndRemainingArmorChangeSurvival() {
        double units = MyGoWeapon.SOURCE_UNITS_PER_BLOCK;
        assertTrue(MyGoBombDamage.damage(1000 / units, 0).health() >= 100);
        assertTrue(MyGoBombDamage.damage(1050 / units, 0).health() < 100);
        assertTrue(MyGoBombDamage.damage(750 / units, 100).health() >= 100);
        assertTrue(MyGoBombDamage.damage(800 / units, 100).health() < 100);
        assertTrue(MyGoBombDamage.damage(800 / units, 1).health() >= 100);
        assertEquals(new MyGoRules.Damage(300, 100), MyGoBombDamage.damage(0, 100));
    }

    @Test void c4DamageFallsWithDistanceAndDoesNotAffectPlayersOutsideItsRadius() {
        double previous = Double.POSITIVE_INFINITY;
        for (int distance = 0; distance <= 45; distance++) {
            double raw = MyGoBombDamage.rawDamage(distance);
            assertTrue(raw <= previous);
            previous = raw;
        }
        assertEquals(new MyGoRules.Damage(0, 0), MyGoBombDamage.damage(MyGoBombDamage.radiusBlocks() + .01, 100));
        assertEquals(0, MyGoBombDamage.rawDamage(Double.NaN));
        assertEquals(0, MyGoBombDamage.rawDamage(Double.POSITIVE_INFINITY));
    }
}
