package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The new utility families are server-side rules and must not depend on a client resource pack. */
final class MyGoUtilityTypesTest {
    @Test void fireGrenadesAreFactionRestrictedAndShareOneHotkeySlot() {
        assertTrue(MyGoUtilities.Type.MOLOTOV.availableTo(true));
        assertFalse(MyGoUtilities.Type.MOLOTOV.availableTo(false));
        assertTrue(MyGoUtilities.Type.INCENDIARY.availableTo(false));
        assertFalse(MyGoUtilities.Type.INCENDIARY.availableTo(true));
        assertTrue(MyGoUtilities.Type.MOLOTOV.fire());
        assertTrue(MyGoUtilities.Type.INCENDIARY.fire());
        assertEquals(MyGoUtilities.Type.MOLOTOV.slot, MyGoUtilities.Type.INCENDIARY.slot);
    }

    @Test void decoyIsSharedAndUsesItsOwnInventorySlotAndMaximum() {
        assertTrue(MyGoUtilities.Type.DECOY.availableTo(true));
        assertTrue(MyGoUtilities.Type.DECOY.availableTo(false));
        assertTrue(MyGoUtilities.Type.DECOY.decoy());
        assertEquals(2, MyGoUtilities.Type.DECOY.maximum);
        assertEquals(MyGoUtilities.Type.DECOY,
                MyGoUtilities.Type.heldAt(Map.of(MyGoUtilities.Type.DECOY, 1), MyGoUtilities.Type.DECOY.slot));
        assertNull(MyGoUtilities.Type.heldAt(Map.of(MyGoUtilities.Type.DECOY, 1), MyGoUtilities.Type.HE.slot));
    }

    @Test void middleThrowIsBetweenPrimaryAndUnderhandSpeed() {
        double strong = MyGoGrenadePhysics.launchVelocity(0, -90, 1, net.minecraft.util.math.Vec3d.ZERO).length();
        double middle = MyGoGrenadePhysics.launchVelocity(0, -90, .6, net.minecraft.util.math.Vec3d.ZERO).length();
        double weak = MyGoGrenadePhysics.launchVelocity(0, -90, .3, net.minecraft.util.math.Vec3d.ZERO).length();
        assertTrue(strong > middle && middle > weak);
    }
}
