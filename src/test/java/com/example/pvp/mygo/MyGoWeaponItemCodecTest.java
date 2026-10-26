package com.example.pvp.mygo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证枪械物品协议在未启动 Minecraft 世界时也能安全处理空值和弹药边界。 */
final class MyGoWeaponItemCodecTest {
    @Test
    void gunStateClampsToWeaponMagazineAndReserveLimits() {
        var state = new MyGoWeaponItemCodec.GunData(MyGoWeapon.AK47, 999, 999);
        assertEquals(MyGoWeapon.AK47.magazine, state.ammo());
        assertEquals(MyGoWeapon.AK47.reserve, state.reserve());

        var empty = new MyGoWeaponItemCodec.GunData(MyGoWeapon.AK47, -5, -8);
        assertEquals(0, empty.ammo());
        assertEquals(0, empty.reserve());
    }

    @Test
    void recordsRejectMissingTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> new MyGoWeaponItemCodec.GunData(null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MyGoWeaponItemCodec.AmmoData("", 1));
    }
}
