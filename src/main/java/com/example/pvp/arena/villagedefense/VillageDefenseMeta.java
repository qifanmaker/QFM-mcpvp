package com.example.pvp.arena.villagedefense;

import com.mojang.logging.LogUtils;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Village Defense 地图的"世界坐标游戏数据"，来自插件预配置的 arenas.yml
 * （坐标位于地图自身的世界坐标系，导入竞技场时整体平移）。
 * 字段与 VD 插件 arenas.yml 对应：villagerspawns / zombiespawns / shop / doors。
 */
public final class VillageDefenseMeta {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 村庄"地表"所在的世界 Y（地表方块 Y）。用于把世界 y 平移对齐到竞技场 PLATFORM_Y。 */
    public int groundWorldY = 79;
    /** 想让村庄中心对齐的竞技场世界坐标（对应世界坐标 (0,?,0)），通常=竞技场区域中心。 */
    public BlockPos centerArena = null; // 由调用方填

    public final List<double[]> villagerSpawns = new ArrayList<>(); // {x,y,z}
    public final List<double[]> zombieSpawns = new ArrayList<>();
    public final List<double[]> doors = new ArrayList<>();
    public double[] shop;

    /** 从一个目录读取 arenas.yml（若存在），否则置空列表（将由调试命令/默认补）。 */
    public static VillageDefenseMeta load(Path mapFolder) {
        VillageDefenseMeta meta = new VillageDefenseMeta();
        Path f = mapFolder.resolve("arenas.yml");
        if (!Files.isRegularFile(f)) {
            LOGGER.info("[VD] 未找到 {}，将仅导入建筑、不出生村民/僵尸", f);
            return meta;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[VD] 读取 arenas.yml 失败: {}", e.toString());
            return meta;
        }
        String section = "";
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!line.startsWith("- ") && line.endsWith(":") && !line.startsWith("  ")) {
                section = line.substring(0, line.length() - 1).trim();
                continue;
            }
            if (!line.startsWith("- ") || !line.contains(",")) {
                continue;
            }
            String[] parts = line.substring(2).split(",");
            if (parts.length < 4) {
                continue;
            }
            // 形式: 世界名,x,y,z,yaw,pitch （首段是世界名，跳过）
            try {
                double x = Double.parseDouble(parts[1].trim());
                double y = Double.parseDouble(parts[2].trim());
                double z = Double.parseDouble(parts[3].trim());
                double[] p = {x, y, z};
                if (section.equals("villagerspawns") || section.endsWith("villagerspawns")) {
                    meta.villagerSpawns.add(p);
                } else if (section.equals("zombiespawns") || section.endsWith("zombiespawns")) {
                    meta.zombieSpawns.add(p);
                } else if (section.equals("shop") || section.endsWith("shop")) {
                    meta.shop = p;
                } else if (section.equals("doors")) {
                    meta.doors.add(p);
                }
            } catch (NumberFormatException ignored) {
                // 非坐标行忽略
            }
        }
        LOGGER.info("[VD] arenas.yml: 村民 {} 僵尸 {} 门 {} 商店 {}",
                meta.villagerSpawns.size(), meta.zombieSpawns.size(), meta.doors.size(), meta.shop != null);
        return meta;
    }

    /** 世界坐标点（feet/位置）在导入后的竞技场坐标。 */
    public BlockPos translate(double[] worldPos, BlockPos center) {
        int dy = center == null ? 0 : PLATFORM_DY(center.getY(), this.groundWorldY);
        int dx = center == null ? 0 : center.getX();
        int dz = center == null ? 0 : center.getZ();
        return new BlockPos(dx + (int) Math.floor(worldPos[0]),
                (int) Math.floor(worldPos[1]) + dy,
                dz + (int) Math.floor(worldPos[2]));
    }

    /** 竞技场 y 平移量：世界地表方块 groundWorldY → 竞技场 PLATFORM_Y。 */
    public static int PLATFORM_DY(int arenaFloorBlockY, int groundWorldY) {
        return arenaFloorBlockY - groundWorldY;
    }
}
