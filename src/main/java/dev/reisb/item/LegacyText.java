package dev.reisb.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

public final class LegacyText {
    private LegacyText() {}

    public static MutableComponent parse(String text) {
        MutableComponent output = Component.empty();
        Style style = Style.EMPTY.withItalic(false);
        int start = 0;
        for (int index = 0; index < text.length() - 1; index++) {
            if (text.charAt(index) != '\u00a7') continue;
            ChatFormatting format = ChatFormatting.getByCode(text.charAt(index + 1));
            if (format == null) continue;
            if (index > start) output.append(Component.literal(text.substring(start, index)).setStyle(style));
            if (format == ChatFormatting.RESET) style = Style.EMPTY.withItalic(false);
            else if (net.minecraft.network.chat.TextColor.fromLegacyFormat(format) != null) style = Style.EMPTY.withItalic(false).applyFormat(format);
            else style = style.applyFormat(format);
            index++;
            start = index + 1;
        }
        if (start < text.length()) output.append(Component.literal(text.substring(start)).setStyle(style));
        return output;
    }
}
