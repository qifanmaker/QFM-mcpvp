package com.example.pvp.arena.luckypillar;

import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;

/**
 * 幸运之柱随机物品：加权随机表，每隔一段时间发给存活玩家一件。
 * 以搭桥方块为主力，武器/防具/食物/捣乱道具梯度随机。
 */
public final class LuckyPillarLoot {

    private LuckyPillarLoot() {
    }

    /** 一条加权战利品条目：weight 权重，factory(random, _) 生成物品。 */
    private record LootEntry(int weight, BiFunction<Random, Integer, ItemStack> factory) {
    }

    /** 随机羊毛（搭桥主力）的 16 色物品。 */
    private static final Item[] WOOL_ITEMS = {
            Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL, Items.LIGHT_BLUE_WOOL,
            Items.YELLOW_WOOL, Items.LIME_WOOL, Items.PINK_WOOL, Items.GRAY_WOOL,
            Items.LIGHT_GRAY_WOOL, Items.CYAN_WOOL, Items.PURPLE_WOOL, Items.BLUE_WOOL,
            Items.BROWN_WOOL, Items.GREEN_WOOL, Items.RED_WOOL, Items.BLACK_WOOL
    };

    private static final List<LootEntry> TABLE = List.of(
            // 搭桥方块（主力）
            new LootEntry(20, (r, c) -> stack(WOOL_ITEMS[r.nextInt(WOOL_ITEMS.length)], 32)),
            new LootEntry(12, (r, c) -> stack(Items.OAK_PLANKS, 16)),
            new LootEntry(8, (r, c) -> stack(Items.COBBLESTONE, 8)),
            // 武器
            new LootEntry(10, (r, c) -> stack(Items.WOODEN_SWORD, 1)),
            new LootEntry(8, (r, c) -> stack(Items.STONE_SWORD, 1)),
            new LootEntry(6, (r, c) -> stack(Items.IRON_SWORD, 1)),
            new LootEntry(1, (r, c) -> stack(Items.DIAMOND_SWORD, 1)),
            new LootEntry(6, (r, c) -> stack(Items.WOODEN_AXE, 1)),
            new LootEntry(5, (r, c) -> stack(Items.STONE_AXE, 1)),
            new LootEntry(3, (r, c) -> stack(Items.IRON_AXE, 1)),
            new LootEntry(6, (r, c) -> stack(Items.BOW, 1)),
            // 防具
            new LootEntry(12, (r, c) -> armor(r, false)),
            new LootEntry(6, (r, c) -> armor(r, true)),
            // 食物
            new LootEntry(8, (r, c) -> stack(Items.BREAD, 4)),
            new LootEntry(5, (r, c) -> stack(Items.COOKED_BEEF, 4)),
            new LootEntry(6, (r, c) -> stack(Items.GOLDEN_APPLE, 1)),
            new LootEntry(1, (r, c) -> stack(Items.ENCHANTED_GOLDEN_APPLE, 1)),
            // 捣乱/机动力
            new LootEntry(3, (r, c) -> stack(Items.TNT, 2)),
            new LootEntry(3, (r, c) -> stack(Items.FLINT_AND_STEEL, 1)),
            new LootEntry(3, (r, c) -> stack(Items.FIRE_CHARGE, 4)),
            new LootEntry(5, (r, c) -> stack(Items.SNOWBALL, 16)),
            new LootEntry(5, (r, c) -> stack(Items.EGG, 16)),
            new LootEntry(1, (r, c) -> stack(Items.ENDER_PEARL, 1)),
            new LootEntry(3, (r, c) -> stack(Items.WATER_BUCKET, 1)),
            new LootEntry(2, (r, c) -> stack(Items.LAVA_BUCKET, 1)),
            new LootEntry(3, (r, c) -> stack(Items.COBWEB, 4)),
            new LootEntry(1, (r, c) -> stack(Items.TOTEM_OF_UNDYING, 1))
    );

    /** 发放 1 件随机物品到玩家背包（背包满则落为实体），并聊天提示。 */
    public static void giveRandomItem(ServerPlayerEntity player, Random random) {
        giveRandomItems(player, random, 1);
    }

    /** 发放 count 件随机物品（补给潮用）。 */
    public static void giveRandomItems(ServerPlayerEntity player, Random random, int count) {
        for (int i = 0; i < count; i++) {
            ItemStack stack = roll(TABLE, random);
            if (stack.isEmpty()) {
                continue;
            }
            giveStack(player, stack);
            if (stack.isOf(Items.BOW)) {
                // 弓配箭：一发随弓补 8 支箭
                giveStack(player, new ItemStack(Items.ARROW, 8));
            }
        }
    }

    private static void giveStack(ServerPlayerEntity player, ItemStack stack) {
        if (!player.getInventory().insertStack(stack)) {
            // 背包已满：掉落为实体（避免物品凭空消失）
            ItemEntity entity = new ItemEntity(player.getWorld(), player.getX(), player.getEyeY(), player.getZ(), stack);
            player.getWorld().spawnEntity(entity);
        }
        player.sendMessage(Text.literal("§e[幸运之柱] §f你获得了 §b" + stack.getName().getString()), false);
    }

    private static ItemStack roll(List<LootEntry> table, Random random) {
        int total = 0;
        for (LootEntry entry : table) {
            total += entry.weight();
        }
        int roll = random.nextInt(total);
        for (LootEntry entry : table) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry.factory().apply(random, 0);
            }
        }
        return ItemStack.EMPTY;
    }

    /** 随机一件护甲（随机部位）。 */
    private static ItemStack armor(Random random, boolean diamond) {
        Item[] pieces = diamond
                ? new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}
                : new Item[]{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
        return new ItemStack(pieces[random.nextInt(pieces.length)]);
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }
}
