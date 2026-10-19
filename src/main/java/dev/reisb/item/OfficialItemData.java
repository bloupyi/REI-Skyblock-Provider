package dev.reisb.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.reisb.data.SkyblockItem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

public final class OfficialItemData {
    private OfficialItemData() {}

    public static void apply(CompoundTag tag, JsonObject item) {
        if (item.has("item_model")) tag.putString("ItemModel", item.get("item_model").getAsString());
        if (item.has("skin")) {
            var skin = item.getAsJsonObject("skin");
            if (skin.has("value")) {
                var owner = new CompoundTag();
                owner.putString("Id", UUID.nameUUIDFromBytes(item.get("id").getAsString().getBytes(StandardCharsets.UTF_8)).toString());
                var texture = new CompoundTag();
                texture.putString("Value", skin.get("value").getAsString());
                if (skin.has("signature")) texture.putString("Signature", skin.get("signature").getAsString());
                var textures = new ListTag();
                textures.add(texture);
                var properties = new CompoundTag();
                properties.put("textures", textures);
                owner.put("Properties", properties);
                tag.put("SkullOwner", owner);
            }
        }
        if (item.has("color")) {
            String[] channels = item.get("color").getAsString().split(",");
            if (channels.length != 3) throw new IllegalArgumentException("Invalid official color");
            int color = 0;
            for (String channel : channels) {
                int value = Integer.parseInt(channel.strip());
                if (value < 0 || value > 255) throw new IllegalArgumentException("Invalid official color channel");
                color = color * 256 + value;
            }
            var display = tag.getCompoundOrEmpty("display");
            display.putInt("color", color);
            tag.put("display", display);
        }
    }

    public static List<String> lore(SkyblockItem item) {
        if (item.officialMetadata() == null || item.officialMetadata().isEmpty()) return item.lore();
        var data = JsonParser.parseString(item.officialMetadata()).getAsJsonObject();
        if (!item.lore().isEmpty()) {
            var stats = data.has("stats") ? data.getAsJsonObject("stats") : new JsonObject();
            return item.lore().stream().map(line -> {
                String plain = line.replaceAll("§.", "");
                for (var stat : stats.entrySet()) {
                    String label = statLabel(stat.getKey());
                    if (plain.startsWith(label + ": ") && stat.getValue().isJsonPrimitive()
                            && stat.getValue().getAsJsonPrimitive().isNumber()) {
                        String number = stat.getValue().getAsBigDecimal().stripTrailingZeros().toPlainString();
                        String suffix = plain.endsWith("%") ? "%" : "";
                        int colon = line.indexOf(": ") + 2;
                        String color = line.substring(colon).startsWith("§") ? line.substring(colon, colon + 2) : "§a";
                        return line.substring(0, colon) + color + (number.startsWith("-") ? "" : "+") + number + suffix;
                    }
                }
                if (data.has("tier") && plain.matches("(?:VERY SPECIAL|UNCOMMON|LEGENDARY|COMMON|MYTHIC|DIVINE|SPECIAL|EPIC|RARE)(?: [A-Z ]+)?")) {
                    String type = plain.replaceFirst("^(?:VERY SPECIAL|UNCOMMON|LEGENDARY|COMMON|MYTHIC|DIVINE|SPECIAL|EPIC|RARE)", "");
                    return (item.name().startsWith("§") ? item.name().substring(0, 2) : "§f") + "§l"
                            + data.get("tier").getAsString().replace('_', ' ') + type;
                }
                return line;
            }).toList();
        }
        var lines = new ArrayList<String>();
        if (data.has("stats")) data.getAsJsonObject("stats").entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                        String value = entry.getValue().getAsBigDecimal().stripTrailingZeros().toPlainString();
                        lines.add("§7" + statLabel(entry.getKey()) + ": §a" + (value.startsWith("-") ? "" : "+") + value);
                    }
                });
        if (data.has("description")) {
            if (!lines.isEmpty()) lines.add("");
            for (var line : data.get("description").getAsString().split("\n")) lines.add(format(line));
        }
        if (data.has("tier")) {
            if (!lines.isEmpty()) lines.add("");
            String tier = data.get("tier").getAsString();
            String color = item.name().startsWith("§") ? item.name().substring(0, 2) : "§f";
            lines.add(color + "§l" + tier.replace('_', ' ')
                    + (data.has("category") ? " " + data.get("category").getAsString().replace('_', ' ') : ""));
        }
        return List.copyOf(lines);
    }

    private static String statLabel(String key) {
        return switch (key) {
            case "CRITICAL_DAMAGE" -> "Crit Damage";
            case "CRITICAL_CHANCE" -> "Crit Chance";
            case "ATTACK_SPEED" -> "Attack Speed";
            default -> java.util.Arrays.stream(key.toLowerCase(Locale.ROOT).split("_"))
                    .map(word -> word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1))
                    .collect(java.util.stream.Collectors.joining(" "));
        };
    }

    private static String format(String text) {
        var matcher = java.util.regex.Pattern.compile("%%([a-z_]+)%%").matcher(text);
        var output = new StringBuilder();
        while (matcher.find()) {
            String replacement = matcher.group();
            try { replacement = ChatFormatting.valueOf(matcher.group(1).toUpperCase(Locale.ROOT)).toString(); }
            catch (IllegalArgumentException ignored) {}
            matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
