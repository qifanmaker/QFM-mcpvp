package com.example.pvp.arena.skywars;

import com.example.pvp.match.MatchType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 修改原因：枪械空岛箱子物品是后续运行时识别枪械的协议，模型和弹药标签必须稳定。
 * 这里只验证物品编码和独立模式身份，不启动 Minecraft 世界。
 */
final class SkyWarsGunLootTest {
    @Test
    void gunItemFactoryRejectsMissingWeaponBeforeWorldBootstrap() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SkyWarsGunLoot.createGunItem(null, 0));
    }

    @Test
    void gunSkywarsIsIndependentLastManStandingMode() {
        assertEquals(MatchType.SKYWARS_GUNS, MatchType.byId("skywarsguns"));
        assertTrue(MatchType.SKYWARS_GUNS.isSkyWars());
        assertTrue(MatchType.SKYWARS_GUNS.isLastManStanding());
        assertEquals("枪械空岛", MatchType.SKYWARS_GUNS.getDisplayName());
    }
}
