package com.example.pvp.arena.bedwars;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bed Wars 地图配置（map.json）：存储每队的商店/铁/金生成点 + 中央岛钻石/绿宝石生成点。
 * 坐标使用地图原始坐标（level.dat 世界坐标），运行时由 {@link BedWarsMapPaster} 平移。
 * 由 /pvp bedwars edit 标记模式生成，非手写。
 */
public final class BedWarsMapConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String name = "";
    public int teams = 8;
    public List<Pos> shops = new ArrayList<>();
    public List<Pos> upgradeShops = new ArrayList<>();
    public List<Pos> irons = new ArrayList<>();
    public List<Pos> golds = new ArrayList<>();
    public List<Pos> diamonds = new ArrayList<>();
    public List<Pos> emeralds = new ArrayList<>();
    /** 每队颜色（red/blue/yellow/green/aqua/white/pink/black），按队伍顺序。 */
    public List<String> colors = new ArrayList<>();

    /** 坐标对象（Gson 序列化用）。 */
    public static final class Pos {
        public int x;
        public int y;
        public int z;

        public Pos() {
        }

        public Pos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Pos(BlockPos p) {
            this(p.getX(), p.getY(), p.getZ());
        }

        public BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Pos pos)) {
                return false;
            }
            return x == pos.x && y == pos.y && z == pos.z;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }
    }

    private BedWarsMapConfig() {
    }

    /** 读取地图目录下的 map.json，无则返回 null。 */
    public static BedWarsMapConfig load(Path mapDir) {
        Path cfg = mapDir.resolve("map.json");
        if (!Files.exists(cfg)) {
            return null;
        }
        try {
            BedWarsMapConfig parsed = GSON.fromJson(Files.readString(cfg), BedWarsMapConfig.class);
            if (parsed != null) {
                return parsed;
            }
        } catch (Exception e) {
            LOGGER.warn("[PvP] 解析 map.json 失败 {}: {}", cfg, e.toString());
        }
        return null;
    }

    /** 保存配置到地图目录下的 map.json。 */
    public void save(Path mapDir) {
        Path cfg = mapDir.resolve("map.json");
        try {
            Files.writeString(cfg, GSON.toJson(this));
            LOGGER.info("[PvP] 已保存床战地图配置 {}", cfg);
        } catch (IOException e) {
            LOGGER.warn("[PvP] 保存 map.json 失败 {}: {}", cfg, e.toString());
        }
    }

    /** 创建一个空配置。 */
    public static BedWarsMapConfig create() {
        return new BedWarsMapConfig();
    }
}
