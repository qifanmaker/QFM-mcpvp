package com.example.pvp.match;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

/** 用服务端标记区分职业/战利品道具，避免拆粘液平台合成普通球也获得稀有击退。 */
public final class SkyWarsItems {
    private static final String KEY = "pvp.skywars_item";

    private SkyWarsItems() { }

    public static ItemStack rescuePlatform(int count) {
        ItemStack stack = new ItemStack(Items.BLAZE_ROD, count);
        mark(stack, "rescue_platform");
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e自救平台"));
        return stack;
    }

    public static boolean isRescuePlatform(ItemStack stack) {
        return stack.isOf(Items.BLAZE_ROD) && hasMark(stack, "rescue_platform");
    }

    public static ItemStack markKnockbackBall(ItemStack stack) {
        mark(stack, "knockback_ball");
        return stack;
    }

    public static boolean isKnockbackBall(ItemStack stack) {
        return stack.isOf(Items.SLIME_BALL) && hasMark(stack, "knockback_ball");
    }

    private static void mark(ItemStack stack, String value) {
        NbtCompound data = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        data.putString(KEY, value);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
    }

    private static boolean hasMark(ItemStack stack, String value) {
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data != null && value.equals(data.copyNbt().getString(KEY));
    }
}
