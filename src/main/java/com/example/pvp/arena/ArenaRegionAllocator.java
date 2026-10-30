package com.example.pvp.arena;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 珍珠投手必须保留真实远端末地岛，单个512格槽位无法容纳；整场五轮持有相邻七槽。
 * 同时避开两个练习管理器的固定区域，构造失败和延迟清场均通过同一入口完整释放。
 */
public final class ArenaRegionAllocator {
    private final Set<Integer> occupied = new HashSet<>();
    private final Map<Integer, Integer> reservations = new HashMap<>();

    public int allocate(boolean wideParty) {
        int radius = wideParty ? 3 : 0;
        for (int anchor = 0; anchor < 50_000; anchor++) {
            boolean free = true;
            for (int slot = anchor - radius; slot <= anchor + radius; slot++) {
                if (occupied.contains(slot) || practice(slot)) { free = false; break; }
            }
            if (!free) continue;
            for (int slot = anchor - radius; slot <= anchor + radius; slot++) occupied.add(slot);
            reservations.put(anchor, radius);
            return anchor;
        }
        throw new IllegalStateException("No arena region within the Minecraft world border");
    }

    public void release(int anchor) {
        Integer radius = reservations.remove(anchor);
        if (radius == null) return;
        for (int slot = anchor - radius; slot <= anchor + radius; slot++) occupied.remove(slot);
    }

    private static boolean practice(int slot) {
        return slot >= 100 && slot < 132 || slot >= 200 && slot < 209;
    }
}
