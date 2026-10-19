package dev.reisb.planning;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.reisb.data.SkyblockItem;
import dev.reisb.data.SkyblockRecipe;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class Requirements {
    public enum Status { MET, UNMET, UNKNOWN }
    public record Condition(String scope, String label, String key, int threshold,
                            List<Condition> alternatives, String source) {
        public Status status(Map<String, PlanningStore.Observation> progress, long now) {
            if (!alternatives.isEmpty()) {
                var states = alternatives.stream().map(value -> value.status(progress, now)).toList();
                if (states.contains(Status.MET)) return Status.MET;
                return states.contains(Status.UNKNOWN) ? Status.UNKNOWN : Status.UNMET;
            }
            var observed = progress.get(key);
            if (key.isEmpty() || observed == null || observed.time() > now + 60_000) return Status.UNKNOWN;
            return observed.amount().compareTo(BigDecimal.valueOf(threshold)) >= 0 ? Status.MET : Status.UNMET;
        }
    }
    private static final Pattern SLAYER = Pattern.compile("(Zombie|Spider|Wolf|Enderman|Blaze|Vampire) Slayer ([0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOOR = Pattern.compile("(?:The )?Catacombs Floor ([IVXLCDM]+|[0-9]+) Completion", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEVEL = Pattern.compile("(.+?) ([IVXLCDM]+|[0-9]+)$");
    private Requirements() {}

    public static List<Condition> read(SkyblockItem item, List<SkyblockRecipe> recipes) {
        var found = new LinkedHashMap<String, Condition>();
        if (item == null) return List.of();
        if (item.officialMetadata() != null && !item.officialMetadata().isBlank()) {
            try {
                var json = JsonParser.parseString(item.officialMetadata()).getAsJsonObject();
                for (String field : List.of("requirements", "catacombs_requirements")) {
                    if (!json.has(field) || !json.get(field).isJsonArray()) continue;
                    String scope = field.equals("catacombs_requirements") ? "dungeonized" : "use";
                    for (var element : json.getAsJsonArray(field)) {
                        try { add(found, official(element.getAsJsonObject(), scope)); }
                        catch (RuntimeException ignored) { add(found, new Condition(scope, "Requirement unavailable", "", 0, List.of(), "Hypixel API")); }
                    }
                }
            } catch (RuntimeException ignored) {}
        }
        for (var recipe : recipes) {
            var matcher = Pattern.compile("Requires:?\\s+(.+)", Pattern.CASE_INSENSITIVE).matcher(ObservedMenus.clean(recipe.extra()));
            if (matcher.find()) add(found, text(matcher.group(1), "craft"));
        }
        for (String lore : item.lore()) {
            var matcher = Pattern.compile("Requires\\s+(.+)", Pattern.CASE_INSENSITIVE).matcher(ObservedMenus.clean(lore));
            if (matcher.find()) {
                var condition = text(matcher.group(1).replaceFirst("\\.$", ""), "use");
                boolean structured = !condition.key.isEmpty() && found.values().stream().anyMatch(value -> value.scope.equals("use")
                        && value.key.equals(condition.key) && value.threshold == condition.threshold);
                if (!structured) add(found, condition);
            }
        }
        return List.copyOf(found.values());
    }

    private static void add(Map<String, Condition> found, Condition condition) {
        found.putIfAbsent(condition.scope + ":" + (condition.key.isEmpty() ? condition.label : condition.key + ":" + condition.threshold), condition);
    }

    private static Condition official(JsonObject json, String scope) {
        String type = value(json, "type", "UNKNOWN");
        if (type.equals("ANY_OF") || type.equals("ONE_OF")) {
            var children = new ArrayList<Condition>();
            for (var child : json.getAsJsonArray("requirements")) children.add(official(child.getAsJsonObject(), scope));
            return new Condition(scope, String.join(" OR ", children.stream().map(Condition::label).toList()), "", 0, List.copyOf(children), "Hypixel API");
        }
        String name = switch (type) {
            case "SLAYER" -> value(json, "slayer_boss_type", "?");
            case "COLLECTION" -> value(json, "collection", "?");
            case "DUNGEON_SKILL", "DUNGEON_TIER" -> value(json, "dungeon_type", "?");
            case "SKILL" -> value(json, "skill", "?");
            default -> "";
        };
        int threshold = json.has("level") ? json.get("level").getAsInt() : json.has("tier") ? json.get("tier").getAsInt() : 0;
        String key = name.isEmpty() || threshold < 0 ? "" : type + ":" + ObservedMenus.key(name);
        String label = switch (type) {
            case "SLAYER" -> pretty(name) + " Slayer " + threshold;
            case "COLLECTION" -> pretty(name) + " Collection " + threshold;
            case "DUNGEON_SKILL", "SKILL" -> pretty(name) + " level " + threshold;
            case "DUNGEON_TIER" -> pretty(name) + " floor " + threshold + " completion";
            default -> pretty(type) + ": " + String.join(", ", json.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals("type") && !entry.getKey().equals("lore_index"))
                    .map(entry -> pretty(entry.getKey()) + " " + (entry.getValue().isJsonPrimitive()
                            ? pretty(entry.getValue().getAsString()) : "(additional conditions)")).toList());
        };
        return new Condition(scope, label, key, threshold, List.of(), "Hypixel API");
    }

    private static Condition text(String label, String scope) {
        String key = "";
        int threshold = 0;
        var slayer = SLAYER.matcher(label);
        var floor = FLOOR.matcher(label);
        if (slayer.matches()) { key = "SLAYER:" + ObservedMenus.key(slayer.group(1)); threshold = Integer.parseInt(slayer.group(2)); }
        else if (floor.matches()) { key = "DUNGEON_TIER:CATACOMBS"; threshold = ObservedMenus.number(floor.group(1)); }
        else if (scope.equals("craft")) {
            var level = LEVEL.matcher(label);
            if (level.matches()) { key = "COLLECTION:" + ObservedMenus.key(level.group(1)); threshold = ObservedMenus.number(level.group(2)); }
        }
        return new Condition(scope, label, key, threshold, List.of(), "NEU");
    }

    private static String pretty(String text) {
        String words = text.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String value(JsonObject object, String key, String fallback) { return object.has(key) ? object.get(key).getAsString() : fallback; }
}
