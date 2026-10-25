package com.example.pvp.mygo;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 升级必须改用外部下载，同时保留管理员自己的 URL 和后续显式启用的局域网 HTTP。 */
final class MyGoResourcePackConfigTest {
    private static MyGoResourcePack.Config read(String json) {
        MyGoResourcePack.Config config = new Gson().fromJson(json, MyGoResourcePack.Config.class);
        config.validate();
        return config;
    }

    @Test void newConfigurationUsesExternalDownload() {
        MyGoResourcePack.Config config = new MyGoResourcePack.Config();
        config.validate();
        assertTrue(config.migrate());
        assertEquals("https://qfmaker.com/upload/files/lf192hqd.zip", config.packUrl);
        assertEquals(5, config.configVersion);
    }

    @Test void oldEmptyWhitespaceAndNullAddressesMigrateOnce() {
        for (String value : new String[]{"\"\"", "\"  \"", "null"}) {
            MyGoResourcePack.Config config = read("{\"packUrl\":" + value + "}");
            assertTrue(config.migrate());
            assertEquals(MyGoResourcePack.DEFAULT_PACK_URL, config.packUrl);
            assertEquals(5, config.configVersion);
            assertFalse(config.migrate());
        }
    }

    @Test void existingCustomAddressIsPreserved() {
        for (int version : new int[]{0, 1, 2, 3, 4}) {
            MyGoResourcePack.Config config = read("{\"configVersion\":" + version
                    + ",\"packUrl\":\"https://cdn.example.com/mygo.zip\"}");
            assertTrue(config.migrate());
            assertEquals("https://cdn.example.com/mygo.zip", config.packUrl);
            assertEquals(5, config.configVersion);
            assertFalse(config.migrate());
        }
    }

    @Test void existingVersionsCanExplicitlyKeepLocalHttp() {
        for (int version : new int[]{1, 2, 3, 4}) {
            MyGoResourcePack.Config config = read("{\"configVersion\":" + version + ",\"packUrl\":\"\"}");
            assertTrue(config.migrate());
            assertEquals("", config.packUrl);
            assertEquals(5, config.configVersion);
            assertFalse(config.migrate());
        }
    }

    @Test void newerConfigurationIsNotDowngraded() {
        MyGoResourcePack.Config config = read("{\"configVersion\":6,\"packUrl\":\"https://cdn.example.com/mygo.zip\"}");
        assertFalse(config.migrate());
        assertEquals(6, config.configVersion);
        assertEquals("https://cdn.example.com/mygo.zip", config.packUrl);
    }

    @Test void currentVersionDoesNotRewriteAnExplicitAddress() {
        MyGoResourcePack.Config config = read("{\"configVersion\":5,\"packUrl\":\"https://qfmaker.com/upload/files/5df4ab7h.zip\"}");
        assertFalse(config.migrate());
        assertEquals(5, config.configVersion);
        assertEquals("https://qfmaker.com/upload/files/5df4ab7h.zip", config.packUrl);
    }

    @Test void versionThreeTextureFixCdnUpgradesToClassicWeapons() {
        MyGoResourcePack.Config config = read("{\"configVersion\":3,\"packUrl\":\"https://qfmaker.com/upload/files/zevadwr3.zip\"}");
        assertTrue(config.migrate());
        assertEquals(MyGoResourcePack.DEFAULT_PACK_URL, config.packUrl);
        assertEquals(5, config.configVersion);
        assertFalse(config.migrate());
    }

    @Test void previousDefaultCdnUpgradesWithTheNewAssets() {
        MyGoResourcePack.Config config = read("{\"configVersion\":1,\"packUrl\":\"https://qfmaker.com/upload/files/wmcvlnck.zip\"}");
        assertTrue(config.migrate());
        assertEquals(MyGoResourcePack.DEFAULT_PACK_URL, config.packUrl);
        assertEquals(5, config.configVersion);
        assertFalse(config.migrate());
    }

    @Test void versionTwoDefaultCdnUpgradesToFixedTextures() {
        MyGoResourcePack.Config config = read("{\"configVersion\":2,\"packUrl\":\"https://qfmaker.com/upload/files/5df4ab7h.zip\"}");
        assertTrue(config.migrate());
        assertEquals(MyGoResourcePack.DEFAULT_PACK_URL, config.packUrl);
        assertEquals(5, config.configVersion);
        assertFalse(config.migrate());
    }

    @Test void classicGunPackUpgradesToTheMatchingFootstepAndUtilityAssets() {
        MyGoResourcePack.Config config = read("{\"configVersion\":4,\"packUrl\":\"https://qfmaker.com/upload/files/uxv5wsbc.zip\"}");
        assertTrue(config.migrate());
        assertEquals(MyGoResourcePack.DEFAULT_PACK_URL, config.packUrl);
        assertEquals(5, config.configVersion);
        assertFalse(config.migrate());
    }
}
