package dev.reisb.planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public record ProfileApiData(String profileId, Map<String, BigDecimal> sacks, Map<String, BigDecimal> progress) {
    public ProfileApiData { sacks = Map.copyOf(sacks); progress = Map.copyOf(progress); }

    public static ProfileApiData parse(JsonObject response, String uuid, String profileName, JsonObject leveling) {
        if (!response.has("success") || !response.get("success").getAsBoolean() || !response.has("profiles")
                || !response.get("profiles").isJsonArray()) throw new IllegalArgumentException("Invalid profiles response");
        JsonObject selected = null;
        for (var entry : response.getAsJsonArray("profiles")) {
            if (!entry.isJsonObject()) continue;
            var profile = entry.getAsJsonObject();
            if (!profile.has("cute_name") || !ObservedMenus.key(profile.get("cute_name").getAsString()).equals(ObservedMenus.key(profileName))) continue;
            if (selected != null) throw new IllegalArgumentException("Ambiguous profile");
            selected = profile;
        }
        if (selected == null) throw new IllegalArgumentException("Active profile not returned");
        var members = object(selected, "members");
        JsonObject member = null;
        for (var entry : members.entrySet()) if (entry.getKey().replace("-", "").equalsIgnoreCase(uuid.replace("-", ""))) member = entry.getValue().getAsJsonObject();
        if (member == null) throw new IllegalArgumentException("Player not returned");
        var sacks = new LinkedHashMap<String, BigDecimal>();
        var progress = new LinkedHashMap<String, BigDecimal>();
        var inventory = object(member, "inventory");
        var counts = inventory.has("sacks_counts") ? object(inventory, "sacks_counts") : object(member, "sacks_counts");
        for (var entry : counts.entrySet()) {
            String id = entry.getKey().replace(':', '-');
            BigDecimal amount = amount(entry.getValue());
            if (id.matches("[A-Z0-9_]+(?:-[0-9]+)?") && amount != null && amount.stripTrailingZeros().scale() <= 0) sacks.put(id, amount);
        }
        var player = object(member, "player_data");
        var tiers = player.has("unlocked_coll_tiers") ? player.get("unlocked_coll_tiers") : member.get("unlocked_coll_tiers");
        if (tiers != null && tiers.isJsonArray()) for (var tier : tiers.getAsJsonArray()) {
            var match = java.util.regex.Pattern.compile("(.+)_([0-9]{1,3})").matcher(tier.getAsString());
            if (match.matches()) progress.merge("COLLECTION:" + ObservedMenus.key(match.group(1)), new BigDecimal(match.group(2)), BigDecimal::max);
        }
        var experience = object(player, "experience");
        for (String skill : java.util.List.of("ALCHEMY", "CARPENTRY", "COMBAT", "ENCHANTING", "FARMING", "FISHING", "FORAGING", "HUNTING", "MINING", "RUNECRAFTING", "SOCIAL", "TAMING")) {
            JsonElement xp = experience.get("SKILL_" + skill);
            if (xp == null) xp = member.get("experience_skill_" + skill.toLowerCase(java.util.Locale.ROOT));
            String chart = switch (skill) { case "RUNECRAFTING" -> "runecrafting_xp"; case "SOCIAL" -> "social"; default -> "leveling_xp"; };
            int level = level(xp, leveling.getAsJsonArray(chart));
            if (level < 0) continue;
            int cap = object(leveling, "leveling_caps").has(skill.toLowerCase(java.util.Locale.ROOT))
                    ? object(leveling, "leveling_caps").get(skill.toLowerCase(java.util.Locale.ROOT)).getAsInt() : 50;
            if (skill.equals("FARMING")) {
                BigDecimal increase = amount(object(object(member, "jacobs_contest"), "perks").get("farming_level_cap"));
                if (increase != null) cap += increase.min(BigDecimal.TEN).intValue();
            }
            if (skill.equals("TAMING")) {
                var care = object(object(member, "pets_data"), "pet_care").get("pet_types_sacrificed");
                if (care != null && care.isJsonArray()) cap += Math.min(10, care.getAsJsonArray().size());
            }
            progress.put("SKILL:" + skill, BigDecimal.valueOf(Math.min(cap, level)));
        }
        var bosses = object(object(member, "slayer"), "slayer_bosses");
        if (bosses.isEmpty()) bosses = object(member, "slayer_bosses");
        for (var entry : bosses.entrySet()) {
            var boss = entry.getValue().getAsJsonObject();
            if (!boss.has("claimed_levels") || !boss.get("claimed_levels").isJsonObject()) continue;
            int highest = 0;
            for (var claim : object(boss, "claimed_levels").entrySet()) {
                var match = java.util.regex.Pattern.compile("level_([0-9]{1,2})").matcher(claim.getKey());
                if (match.matches() && claim.getValue().isJsonPrimitive() && claim.getValue().getAsBoolean()) highest = Math.max(highest, Integer.parseInt(match.group(1)));
            }
            progress.put("SLAYER:" + ObservedMenus.key(entry.getKey()), BigDecimal.valueOf(highest));
        }
        var catacombs = object(object(object(member, "dungeons"), "dungeon_types"), "catacombs");
        int cata = level(catacombs.get("experience"), leveling.getAsJsonArray("catacombs"));
        if (cata >= 0) progress.put("DUNGEON_SKILL:CATACOMBS", BigDecimal.valueOf(cata));
        if (catacombs.has("tier_completions") && catacombs.get("tier_completions").isJsonObject()) {
            int floor = 0;
            for (var entry : object(catacombs, "tier_completions").entrySet()) {
                BigDecimal completed = amount(entry.getValue());
                if (entry.getKey().matches("[0-7]") && completed != null && completed.signum() > 0) floor = Math.max(floor, Integer.parseInt(entry.getKey()));
            }
            progress.put("DUNGEON_TIER:CATACOMBS", BigDecimal.valueOf(floor));
        }
        if (sacks.size() > 10000 || progress.size() > 10000) throw new IllegalArgumentException("Profile too large");
        return new ProfileApiData(selected.get("profile_id").getAsString(), sacks, progress);
    }

    public static JsonObject leveling(java.nio.file.Path archive) throws java.io.IOException {
        if (java.nio.file.Files.isRegularFile(archive)) try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("[^/]+/constants/leveling\\.json")) continue;
                try (var stream = zip.getInputStream(entry)) {
                    byte[] bytes = stream.readNBytes(131073);
                    if (bytes.length > 131072) throw new java.io.IOException("Leveling constants too large");
                    return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                }
            }
        }
        try (var stream = ProfileApiData.class.getResourceAsStream("/assets/rei_skyblock_provider/leveling.json")) {
            if (stream == null) throw new java.io.IOException("Missing leveling constants");
            return JsonParser.parseReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    static int level(JsonElement experience, JsonArray chart) {
        BigDecimal remaining = amount(experience);
        if (remaining == null || chart == null || chart.isEmpty()) return -1;
        int level = 0;
        for (var value : chart) {
            BigDecimal required = amount(value);
            if (required == null || required.signum() == 0) return -1;
            if (remaining.compareTo(required) < 0) break;
            remaining = remaining.subtract(required);
            level++;
        }
        return level;
    }

    private static JsonObject object(JsonObject parent, String field) {
        var value = parent.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static BigDecimal amount(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            BigDecimal amount = value.getAsBigDecimal();
            return amount.signum() >= 0 && amount.precision() <= 20 && amount.scale() <= 6 ? amount : null;
        } catch (RuntimeException error) { return null; }
    }
}
