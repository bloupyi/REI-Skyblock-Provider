package dev.reisb.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.reisb.data.SkyblockItem;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PetLevels {
    private static final List<String> TIERS = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL");
    private static final JsonObject FALLBACK = loadFallback();
    private PetLevels() {}

    private static JsonObject loadFallback() {
        try (var input = PetLevels.class.getResourceAsStream("/assets/rei_skyblock_provider/data/petnums.json")) {
            if (input == null) throw new IllegalStateException("Missing pet level constants");
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    public static String profile(String id, JsonObject numbers) {
        String[] parts = id.split(";", -1);
        if (parts.length != 2 || !numbers.has(parts[0])) return "";
        int tier;
        try { tier = Integer.parseInt(parts[1]); }
        catch (NumberFormatException e) { return ""; }
        if (tier < 0 || tier >= TIERS.size()) return "";
        var pet = numbers.getAsJsonObject(parts[0]);
        return pet.has(TIERS.get(tier)) ? pet.getAsJsonObject(TIERS.get(tier)).toString() : "";
    }

    private static JsonObject data(SkyblockItem item) {
        String profile = item.levelData().isEmpty() ? profile(item.id(), FALLBACK) : item.levelData();
        if (!profile.isEmpty()) return JsonParser.parseString(profile).getAsJsonObject();
        if (item.name().contains("{LVL}") && item.lore().stream().anyMatch(line -> line.endsWith(" PET"))) {
            return JsonParser.parseString("{\"1\":{},\"100\":{},\"ranges_unavailable\":true}").getAsJsonObject();
        }
        return null;
    }

    private static Map<String, String> replacements(JsonObject data) {
        var result = new LinkedHashMap<String, String>();
        int maximum = data.has("stats_levelling_curve") ? Integer.parseInt(data.get("stats_levelling_curve").getAsString().split(":")[1]) : 100;
        result.put("LVL", "1 → " + maximum);
        var low = data.getAsJsonObject("1");
        var high = data.getAsJsonObject("100");
        if (low.has("statNums") && high.has("statNums")) low.getAsJsonObject("statNums").entrySet().forEach(entry -> {
            if (high.getAsJsonObject("statNums").has(entry.getKey())) result.put(entry.getKey(), range(entry.getValue(), high.getAsJsonObject("statNums").get(entry.getKey())));
        });
        if (low.has("otherNums") && high.has("otherNums")) {
            var a = low.getAsJsonArray("otherNums");
            var b = high.getAsJsonArray("otherNums");
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) result.put(Integer.toString(i), range(a.get(i), b.get(i)));
        }
        return result;
    }

    private static String range(com.google.gson.JsonElement low, com.google.gson.JsonElement high) {
        String a = low.getAsBigDecimal().stripTrailingZeros().toPlainString();
        String b = high.getAsBigDecimal().stripTrailingZeros().toPlainString();
        return a.equals(b) ? a : a + " → " + b;
    }

    private static String replace(String text, Map<String, String> values) {
        for (var entry : values.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        return text;
    }

    public static String name(SkyblockItem item) {
        var data = data(item);
        return data == null ? item.name() : replace(item.name(), replacements(data));
    }

    public static List<String> lore(SkyblockItem item, List<String> lore) {
        var data = data(item);
        if (data == null) return lore;
        var values = replacements(data);
        var result = new ArrayList<>(lore.stream().map(line -> replace(line, values)).toList());
        if (data.has("ranges_unavailable")) result.add("§8Stat ranges unavailable in source; showing NEU values.");
        if (data.has("stats_levelling_curve")) {
            String[] curve = data.get("stats_levelling_curve").getAsString().split(":");
            result.add("§8Stats / abilities: levels " + curve[0] + " → " + curve[1]);
        }
        return List.copyOf(result);
    }
}
