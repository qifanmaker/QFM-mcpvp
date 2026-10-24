package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** 军械库扩充后验证阵营限制、资产编号与近距离霰弹规则，避免新枪绕过购买或伤害边界。 */
final class MyGoArsenalTest {
    @Test void fifteenDistinctGunsRetainLegacyCommandsAndExactAssetIds() {
        assertEquals(15, MyGoWeapon.values().length);
        assertEquals(15, Arrays.stream(MyGoWeapon.values()).map(g -> g.model).distinct().count());
        assertSame(MyGoWeapon.MP5, MyGoWeapon.parse("ump45"));
        assertSame(MyGoWeapon.AW, MyGoWeapon.parse("awp"));
        assertSame(MyGoWeapon.M16, MyGoWeapon.parse("m16a4"));
        assertNull(MyGoWeapon.parse("invalid"));
    }
    @Test void SideSpecificRiflesCannotBeBoughtByTheOppositeSide() {
        assertTrue(MyGoWeapon.AK.availableTo(true)); assertFalse(MyGoWeapon.AK.availableTo(false));
        assertFalse(MyGoWeapon.M4.availableTo(true)); assertTrue(MyGoWeapon.M4.availableTo(false));
        for (MyGoWeapon gun : MyGoWeapon.values()) if (gun != MyGoWeapon.AK && gun != MyGoWeapon.M4)
            assertTrue(gun.availableTo(true) && gun.availableTo(false));
    }
    @Test void shotgunUsesOneShellForEightShortRangePellets() {
        assertEquals(8, MyGoWeapon.S686.pellets());
        assertEquals(2, MyGoWeapon.S686.magazine);
        assertEquals(52.8, MyGoRules.damage(MyGoWeapon.S686, 3, MyGoRules.Part.BODY, true, true) * 8, 1e-8);
        assertEquals(0, MyGoRules.damage(MyGoWeapon.S686, 19, MyGoRules.Part.BODY, false, false));
        for (MyGoWeapon gun : MyGoWeapon.values()) assertTrue(gun.price <= MyGoRules.MAX_MONEY && gun.reloadTicks > 0);
    }
    @Test void utilityLimitsAllowFourItemsWithoutUnlimitedFlashPurchases() {
        assertEquals(1, MyGoUtilities.Type.HE.maximum); assertEquals(2, MyGoUtilities.Type.FLASH.maximum);
        assertEquals(1, MyGoUtilities.Type.SMOKE.maximum);
        assertEquals(3, Arrays.stream(MyGoUtilities.Type.values()).map(t -> t.slot).distinct().count());
    }
}
