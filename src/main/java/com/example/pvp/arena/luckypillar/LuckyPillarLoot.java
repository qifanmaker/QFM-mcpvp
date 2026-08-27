package com.example.pvp.arena.luckypillar;

import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 幸运之柱随机物品：纯随机——每件从全物品注册表均匀抽取（不限定制表），每件只掉 1 个。
 * 排除空气/创造专用/刷怪蛋等不能正常使用的物品；弓会顺带补 1 支箭。
 */
public final class LuckyPillarLoot {

    private LuckyPillarLoot() {
    }

    /** 不能正常发放的物品（空气/创造专用/无意义方块）。 */
    private static final Set<Identifier> BLACKLIST = Set.of(
            Identifier.of("minecraft", "air"),
            Identifier.of("minecraft", "barrier"),
            Identifier.of("minecraft", "light"),
            Identifier.of("minecraft", "structure_block"),
            Identifier.of("minecraft", "structure_void"),
            Identifier.of("minecraft", "jigsaw"),
            Identifier.of("minecraft", "command_block"),
            Identifier.of("minecraft", "chain_command_block"),
            Identifier.of("minecraft", "repeating_command_block"),
            Identifier.of("minecraft", "debug_stick"),
            Identifier.of("minecraft", "knowledge_book"),
            Identifier.of("minecraft", "spawner")
    );

    /** 候选物品列表（注册表就绪后懒加载缓存，供均匀随机抽取）。 */
    private static List<Item> candidates;

    private static List<Item> candidates() {
        if (candidates == null) {
            List<Item> list = new ArrayList<>();
            for (Item item : Registries.ITEM) {
                if (item == Items.AIR) {
                    continue;
                }
                Identifier id = Registries.ITEM.getId(item);
                if (id == null || BLACKLIST.contains(id)) {
                    continue;
                }
                if (id.getPath().endsWith("_spawn_egg")) {
                    continue; // 刷怪蛋排除：避免竞技场里刷出敌对生物
                }
                list.add(item);
            }
            candidates = List.copyOf(list);
        }
        return candidates;
    }

    /** 发放 1 件纯随机物品（每件 1 个）到玩家背包（背包满则落为实体），并动作栏提示。 */
    public static void giveRandomItem(ServerPlayerEntity player, Random random) {
        giveRandomItems(player, random, 1);
    }

    /** 发放 count 件纯随机物品（补给潮用）。 */
    public static void giveRandomItems(ServerPlayerEntity player, Random random, int count) {
        List<Item> pool = candidates();
        if (pool.isEmpty()) {
            return;
        }
        for (int i = 0; i < count; i++) {
            Item item = pool.get(random.nextInt(pool.size()));
            giveStack(player, new ItemStack(item, 1));
            if (item == Items.BOW) {
                // 弓配箭：随弓补 1 支箭，否则拿到弓没箭用
                giveStack(player, new ItemStack(Items.ARROW, 1));
            }
        }
    }

    private static void giveStack(ServerPlayerEntity player, ItemStack stack) {
        if (!player.getInventory().insertStack(stack)) {
            // 背包已满：掉落为实体（避免物品凭空消失）
            ItemEntity entity = new ItemEntity(player.getWorld(), player.getX(), player.getEyeY(), player.getZ(), stack);
            player.getWorld().spawnEntity(entity);
        }
        // 间隔短，用动作栏提示（不刷屏聊天）
        player.sendMessage(Text.literal("§e§l+ §b" + stack.getName().getString()), true);
    }
}
