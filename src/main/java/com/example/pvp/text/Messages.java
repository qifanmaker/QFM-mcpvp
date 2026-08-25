package com.example.pvp.text;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 中文消息文案集中管理。
 */
public final class Messages {
    public static final String PREFIX = "[PvP]";

    private Messages() {
    }

    public static MutableText prefix(MutableText inner) {
        return Text.literal("").append(Text.literal(PREFIX + " ").formatted(Formatting.GOLD)).append(inner);
    }

    public static MutableText info(String message) {
        return prefix(Text.literal(message).formatted(Formatting.GREEN));
    }

    public static MutableText error(String message) {
        return prefix(Text.literal(message).formatted(Formatting.RED));
    }

    public static MutableText warn(String message) {
        return prefix(Text.literal(message).formatted(Formatting.YELLOW));
    }

    public static MutableText gold(String message) {
        return prefix(Text.literal(message).formatted(Formatting.GOLD));
    }

    public static MutableText playerName(String name) {
        return Text.literal(name).formatted(Formatting.AQUA);
    }
}
