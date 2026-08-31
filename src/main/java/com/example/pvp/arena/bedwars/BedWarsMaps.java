package com.example.pvp.arena.bedwars;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Bed Wars 地图目录管理：扫描 config/pvp/bedwars/maps/ 下的可用地图（含 region/ 的文件夹）。
 */
public final class BedWarsMaps {
    private static final Logger LOGGER = LogUtils.getLogger();

    private BedWarsMaps() {
    }

    /** 地图根目录。 */
    public static Path mapsDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvp/bedwars/maps");
    }

    /** 列出所有可用地图文件夹（含 region/*.mca 的目录，按名称排序）。 */
    public static List<Path> listMaps() {
        List<Path> maps = new ArrayList<>();
        Path dir = mapsDir();
        if (!Files.isDirectory(dir)) {
            return maps;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(Files::isDirectory).toList()) {
                if (Files.isDirectory(p.resolve("region"))) {
                    maps.add(p);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[PvP] BedWars 扫描地图目录失败: {}", e.toString());
        }
        maps.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return maps;
    }

    /** 随机选一张地图。 */
    public static Path randomMap(Random random) {
        List<Path> maps = listMaps();
        if (maps.isEmpty()) {
            return null;
        }
        return maps.get(random.nextInt(maps.size()));
    }
}
