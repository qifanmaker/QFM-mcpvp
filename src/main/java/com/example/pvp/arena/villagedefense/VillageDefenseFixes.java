package com.example.pvp.arena.villagedefense;

import com.example.pvp.arena.ArenaWorld;
import com.mojang.logging.LogUtils;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 手动修图（fixes.json）：记录"世界坐标 → 应显示方块"，导入时每次自动覆盖。
 * 文件每行：{x} {y} {z} {blockId}；blockId 为空或 minecraft:air 表示置空气。
 */
public final class VillageDefenseFixes {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record Fix(int x, int y, int z, String block) {
    }

    private VillageDefenseFixes() {
    }

    private static Path file(Path mapFolder) {
        return mapFolder.resolve("fixes.json");
    }

    public static List<Fix> readAll(Path mapFolder) {
        List<Fix> list = new ArrayList<>();
        Path f = file(mapFolder);
        if (!Files.isRegularFile(f)) {
            return list;
        }
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.split("\\s+");
                if (p.length >= 4) {
                    try {
                        list.add(new Fix(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                                Integer.parseInt(p[2]), p[3]));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[VD] 读取 fixes.json 失败: {}", e.toString());
        }
        return list;
    }

    /** 把一条修复写进 fixes.json（覆盖同名坐标）。 */
    public static void record(Path mapFolder, int x, int y, int z, String block) {
        try {
            Files.createDirectories(mapFolder);
            List<String> lines = new ArrayList<>();
            Path f = file(mapFolder);
            if (Files.isRegularFile(f)) {
                lines.addAll(Files.readAllLines(f, StandardCharsets.UTF_8));
            }
            String prefix = x + " " + y + " " + z + " ";
            lines.removeIf(l -> l.startsWith(prefix));
            lines.add(prefix + (block == null || block.isBlank() ? "minecraft:air" : block));
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[VD] 写入 fixes.json 失败: {}", e.toString());
        }
    }

    /** 整批写入（编辑器保存用）：覆盖同坐标旧条目后写回文件。 */
    public static void writeAll(Path mapFolder, List<Fix> fixes) {
        try {
            Files.createDirectories(mapFolder);
            java.util.Map<String, Fix> merged = new java.util.LinkedHashMap<>();
            for (Fix f : readAll(mapFolder)) {
                merged.put(f.x() + " " + f.y() + " " + f.z(), f);
            }
            for (Fix f : fixes) {
                merged.put(f.x() + " " + f.y() + " " + f.z(), f);
            }
            List<String> lines = new ArrayList<>();
            for (Fix f : merged.values()) {
                lines.add(f.x() + " " + f.y() + " " + f.z() + " "
                        + (f.block() == null || f.block().isBlank() ? "minecraft:air" : f.block()));
            }
            Files.write(file(mapFolder), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[VD] 写入 fixes.json 失败: {}", e.toString());
        }
    }

    /** 导入后应用 fixes：世界坐标经 off 平移后覆盖方块。返回应用条数。 */
    public static int apply(ArenaWorld world, Path mapFolder, VillageWorldImporter.Layout layout) {
        int count = 0;
        for (Fix fix : readAll(mapFolder)) {
            int ax = fix.x() + layout.offX;
            int ay = fix.y() + layout.offY;
            int az = fix.z() + layout.offZ;
            BlockState st = resolve(fix.block());
            world.setBlockState(new net.minecraft.util.math.BlockPos(ax, ay, az), st, 3);
            count++;
        }
        return count;
    }

    public static BlockState resolve(String block) {
        if (block == null || block.isBlank() || block.equals("minecraft:air") || block.equals("air")) {
            return net.minecraft.block.Blocks.AIR.getDefaultState();
        }
        Identifier id = Identifier.tryParse(block.trim());
        if (id != null && Registries.BLOCK.containsId(id)) {
            return Registries.BLOCK.get(id).getDefaultState();
        }
        return net.minecraft.block.Blocks.AIR.getDefaultState();
    }
}
