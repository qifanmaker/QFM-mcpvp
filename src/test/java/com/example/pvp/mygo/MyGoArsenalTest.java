package com.example.pvp.mygo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 用公开游戏物品属性的独立事实表核对军械，防止把旧脚本或 CS2 数据误当成末期 CS:GO。 */
final class MyGoArsenalTest {
    @Test void completeArsenalHasNoDuplicateModelsOrUnusableCommandNames() {
        assertEquals(34, MyGoWeapon.values().length);
        assertEquals(34, Arrays.stream(MyGoWeapon.values()).map(g -> g.model).distinct().count());
        assertEquals(10, Arrays.stream(MyGoWeapon.values()).filter(MyGoWeapon::pistol).count());
        assertEquals(7, Arrays.stream(MyGoWeapon.values()).filter(MyGoWeapon::smg).count());
        assertEquals(4, Arrays.stream(MyGoWeapon.values()).filter(MyGoWeapon::sniper).count());
        assertEquals(4, Arrays.stream(MyGoWeapon.values()).filter(MyGoWeapon::shotgun).count());
        for (MyGoWeapon gun : MyGoWeapon.values()) {
            assertSame(gun, MyGoWeapon.parse(gun.name()), gun.name());
            assertSame(gun, MyGoWeapon.parse(gun.label), gun.label);
            assertTrue(gun.model >= 71001 && gun.model <= 71036);
            assertNotEquals(71006, gun.model, "刀模型不得被枪覆盖");
            assertNotEquals(71010, gun.model, "瞄准镜图标不得被枪覆盖");
        }
        assertSame(MyGoWeapon.UMP45, MyGoWeapon.parse("ump45"));
        assertSame(MyGoWeapon.AWP, MyGoWeapon.parse("awm"));
        assertSame(MyGoWeapon.FAMAS, MyGoWeapon.parse("m16a4"));
        assertNull(MyGoWeapon.parse("invalid"));
        assertNull(MyGoWeapon.parse(null));
    }

    @Test void allWeaponFactsMatchResolvedValveItemAttributesAndFinalBalancePatches() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mygo/csgo-weapon-facts.json")) {
            assertNotNull(stream);
            JsonObject weapons = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("weapons");
            assertEquals(MyGoWeapon.values().length, weapons.size());
            for (MyGoWeapon gun : MyGoWeapon.values()) {
                JsonObject fact = weapons.getAsJsonObject(gun.name());
                assertNotNull(fact, gun.name());
                assertAll(gun.name(),
                        () -> assertEquals(fact.get("in game price").getAsInt(), gun.price),
                        () -> assertEquals(fact.get("primary clip size").getAsInt(), gun.magazine),
                        () -> assertEquals(fact.get("primary reserve ammo max").getAsInt(), gun.reserve),
                        () -> assertEquals(fact.get("damage").getAsDouble(), gun.damage),
                        () -> assertEquals(fact.get("headshot multiplier").getAsDouble(), gun.headshotMultiplier),
                        () -> assertEquals(fact.get("armor ratio").getAsDouble() / 2, gun.penetration),
                        () -> assertEquals(fact.get("cycletime").getAsDouble(), gun.cycleSeconds),
                        () -> assertEquals(fact.get("kill award").getAsInt(), gun.killReward),
                        () -> assertEquals(fact.get("bullets").getAsInt(), gun.pellets()),
                        () -> assertEquals(fact.get("range modifier").getAsDouble(), gun.rangeModifier),
                        () -> assertEquals(fact.get("range").getAsDouble(),
                                gun.maxRange * MyGoWeapon.SOURCE_UNITS_PER_BLOCK, 1e-8),
                        () -> assertEquals(fact.get("zoom levels").getAsInt(), gun.scopeLevels()));
                String side = fact.get("team").getAsString();
                assertEquals(!side.equals("CT"), gun.availableTo(true), gun.name() + " T");
                assertEquals(!side.equals("T"), gun.availableTo(false), gun.name() + " CT");
                for (int level = 1; level <= gun.scopeLevels(); level++)
                    assertEquals(fact.get("zoom fov " + level).getAsInt(), gun.scopeFov(level), gun.name());
            }
        }
    }

    @Test void bothSidesStartWithTheirOwnPistolAndCannotBuyTheOtherSideArsenal() {
        assertSame(MyGoWeapon.GLOCK, MyGoWeapon.defaultPistol(true));
        assertSame(MyGoWeapon.USP_S, MyGoWeapon.defaultPistol(false));
        Set<MyGoWeapon> terrorists = EnumSet.of(MyGoWeapon.GLOCK, MyGoWeapon.TEC9, MyGoWeapon.MAC10,
                MyGoWeapon.GALIL, MyGoWeapon.AK47, MyGoWeapon.SG553, MyGoWeapon.G3SG1, MyGoWeapon.SAWEDOFF);
        Set<MyGoWeapon> counterTerrorists = EnumSet.of(MyGoWeapon.USP_S, MyGoWeapon.P2000, MyGoWeapon.FIVESEVEN,
                MyGoWeapon.MP9, MyGoWeapon.FAMAS, MyGoWeapon.M4A4, MyGoWeapon.M4A1S, MyGoWeapon.AUG,
                MyGoWeapon.SCAR20, MyGoWeapon.MAG7);
        for (MyGoWeapon gun : MyGoWeapon.values()) {
            assertEquals(!counterTerrorists.contains(gun), gun.availableTo(true), gun.name());
            assertEquals(!terrorists.contains(gun), gun.availableTo(false), gun.name());
        }
    }

    @Test void ordinaryRiflesHaveNoScopeAndOnlyOpticalWeaponsExposeOriginalZoomStages() {
        for (MyGoWeapon gun : MyGoWeapon.values()) {
            if (gun == MyGoWeapon.AUG || gun == MyGoWeapon.SG553) {
                assertEquals(1, gun.scopeLevels());
                assertEquals(45, gun.scopeFov(1));
            } else if (gun.sniper()) {
                assertEquals(2, gun.scopeLevels());
                assertEquals(40, gun.scopeFov(1));
                assertEquals(gun == MyGoWeapon.AWP ? 10 : 15, gun.scopeFov(2));
            } else assertEquals(0, gun.scopeLevels(), gun.name());
            assertEquals(90, gun.scopeFov(0));
            assertEquals(90, gun.scopeFov(gun.scopeLevels() + 1));
        }
    }

    @Test void onlyBoltActionSnipersUnzoomAfterEachShot() {
        for (MyGoWeapon gun : MyGoWeapon.values())
            assertEquals(gun == MyGoWeapon.AWP || gun == MyGoWeapon.SSG08, gun.unzoomAfterShot(), gun.name());
    }

    @Test void originalRangeFalloffUsesSourceUnitsWithoutCuttingOffDustTwoLongSightlines() {
        double metersPer500SourceUnits = 500 / MyGoWeapon.SOURCE_UNITS_PER_BLOCK;
        for (MyGoWeapon gun : MyGoWeapon.values()) {
            assertEquals(gun.rangeModifier, gun.distanceMultiplier(metersPer500SourceUnits), 1e-9, gun.name());
            assertEquals(gun.rangeModifier * gun.rangeModifier,
                    gun.distanceMultiplier(2 * metersPer500SourceUnits), 1e-9, gun.name());
            assertEquals(1, gun.distanceMultiplier(0));
            assertEquals(0, gun.distanceMultiplier(gun.maxRange + .01));
            assertTrue(gun.cycleTicks / 20.0 >= gun.cycleSeconds - 1e-9);
            assertTrue(gun.cycleTicks / 20.0 < gun.cycleSeconds + .05);
            assertTrue(gun.reloadTicks > 0);
            assertArrayEquals(new int[]{0}, gun.burstOffsets(), "左键一次不应强制三连发: " + gun.name());
        }
        assertTrue(MyGoWeapon.AK47.maxRange > 200);
        assertTrue(MyGoWeapon.AWP.maxRange > 200);
    }

    @Test void killRewardsAndShotgunPelletsRetainEconomyTradeoffs() {
        assertEquals(100, MyGoWeapon.AWP.killReward);
        assertEquals(100, MyGoWeapon.CZ75.killReward);
        assertEquals(300, MyGoWeapon.P90.killReward);
        for (MyGoWeapon gun : MyGoWeapon.values()) {
            if (gun.shotgun()) assertEquals(900, gun.killReward, gun.name());
            if (gun.smg() && gun != MyGoWeapon.P90) assertEquals(600, gun.killReward, gun.name());
        }
        assertEquals(9, MyGoWeapon.NOVA.pellets());
        assertEquals(6, MyGoWeapon.XM1014.pellets());
        assertEquals(8, MyGoWeapon.MAG7.pellets());
        assertEquals(8, MyGoWeapon.SAWEDOFF.pellets());
        assertEquals(5, MyGoWeapon.AWP.magazine);
        assertEquals(20, MyGoWeapon.M4A1S.magazine);
        assertEquals(80, MyGoWeapon.M4A1S.reserve);
    }

    @Test void utilityLimitsAllowFourItemsWithoutUnlimitedFlashPurchases() {
        assertEquals(1, MyGoUtilities.Type.HE.maximum); assertEquals(2, MyGoUtilities.Type.FLASH.maximum);
        assertEquals(1, MyGoUtilities.Type.SMOKE.maximum);
        assertEquals(3, Arrays.stream(MyGoUtilities.Type.values()).map(t -> t.slot).distinct().count());
    }
}
