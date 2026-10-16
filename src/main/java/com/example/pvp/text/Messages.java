package com.example.pvp.text;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
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

    /** 每局结束后显示的反馈入口，链接可直接点击打开。 */
    public static MutableText feedbackHint() {
        String url = "https://qfmaker.com/blackboard/mcserverfb";
        MutableText link = Text.literal(url).formatted(Formatting.AQUA, Formatting.UNDERLINE);
        link.setStyle(link.getStyle().withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
        return Text.literal("")
                .append(Text.literal("[Hint] ").formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal("Please submit your suggestions or bug feedback at ")
                        .formatted(Formatting.GRAY))
                .append(link)
                .append(Text.literal(" , we'll fix them in soon.").formatted(Formatting.GRAY));
    }
}
