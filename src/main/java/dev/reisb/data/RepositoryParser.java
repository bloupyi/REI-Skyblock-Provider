package dev.reisb.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import static dev.reisb.data.SkyblockRecipe.Kind.*;

public final class RepositoryParser {
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Integer> unsupported = new HashMap<>();

    public RepositorySnapshot parse(Path archive) throws IOException {
        return parse(archive, false);
    }

    public RepositorySnapshot parse(Path archive, boolean deferReferences) throws IOException {
        try (var directory = new java.util.zip.ZipFile(archive.toFile())) {
            if (directory.size() > 100_000) throw new IOException("Too many repository entries");
        }
        warnings.clear();
        unsupported.clear();
        var items = new LinkedHashMap<String, SkyblockItem>();
        var recipes = new LinkedHashSet<SkyblockRecipe>();
        var petNumbers = new JsonObject();
        try (InputStream stream = Files.newInputStream(archive); var zip = new ZipInputStream(stream)) {
            int entries = 0;
            long total = 0;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > 100_000) throw new IOException("Too many repository entries");
                if (entry.getName().matches("[^/]+/constants/petnums\\.json")) {
                    byte[] bytes = zip.readNBytes(2_097_153);
                    if (bytes.length > 2_097_152) throw new IOException("Pet constants exceed size limit");
                    petNumbers = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    continue;
                }
                if (entry.isDirectory() || !entry.getName().matches("[^/]+/items/[^/]+\\.json")) continue;
                byte[] bytes = zip.readNBytes(1_048_577);
                total += bytes.length;
                if (total > 256L * 1024 * 1024) throw new IOException("Repository exceeds size limit");
                if (bytes.length > 1_048_576) { warn(entry.getName() + ": item exceeds 1 MiB"); continue; }
                try {
                    JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    SkyblockItem item = parseItem(json);
                    if (items.putIfAbsent(item.id(), item) != null) warn("Duplicate item " + item.id());
                    recipes.addAll(parseRecipes(json));
                } catch (RuntimeException e) {
                    warn(entry.getName() + ": " + e.getMessage());
                }
            }
        }
        if (items.isEmpty()) throw new IOException("Repository contains no valid items");
        final var levels = petNumbers;
        items.replaceAll((id, item) -> {
            String profile = dev.reisb.item.PetLevels.profile(id, levels);
            return profile.isEmpty() ? item : new SkyblockItem(item.id(), item.minecraftId(), item.damage(), item.nbt(),
                    item.name(), item.lore(), item.info(), item.officialMetadata(), profile);
        });
        if (deferReferences) return new RepositorySnapshot(items, List.copyOf(recipes), warnings, unsupported);
        var valid = recipes.stream().filter(recipe -> {
            boolean known = (items.containsKey(recipe.output().id()) || recipe.output().id().equals("SKYBLOCK_COIN")) && recipe.inputs().stream()
                    .allMatch(i -> i.empty() || i.id().equals("SKYBLOCK_COIN") || items.containsKey(i.id()));
            if (!known) warn("Unresolved ingredient/output: " + recipe);
            return known;
        }).toList();
        return new RepositorySnapshot(items, valid, warnings, unsupported);
    }

    public SkyblockItem parseItem(JsonObject json) {
        return new SkyblockItem(required(json, "internalname"), required(json, "itemid"),
                Math.toIntExact(number(json, "damage", 0)), string(json, "nbttag", "{}"),
                required(json, "displayname"), strings(json, "lore"), strings(json, "info"));
    }

    public List<SkyblockRecipe> parseRecipes(JsonObject item) {
        String owner = required(item, "internalname");
        var result = new ArrayList<SkyblockRecipe>();
        if (item.has("recipe")) {
            var recipe = item.getAsJsonObject("recipe").deepCopy();
            recipe.addProperty("count", number(item, "count", 1));
            addRecipe(result, recipe, owner, string(item, "crafttext", ""));
        }
        if (item.has("recipes")) {
            for (JsonElement recipe : item.getAsJsonArray("recipes")) {
                try { addRecipe(result, recipe.getAsJsonObject(), owner, string(item, "crafttext", "")); }
                catch (RuntimeException e) { warn(owner + ": " + e.getMessage()); }
            }
        }
        return result;
    }

    private void addRecipe(List<SkyblockRecipe> target, JsonObject json, String owner, String extra) {
        try {
            String type = string(json, "type", "crafting");
            switch (type) {
                case "crafting" -> {
                    var grid = new ArrayList<Ingredient>(9);
                    for (char row = 'A'; row <= 'C'; row++) {
                        for (int column = 1; column <= 3; column++) {
                            grid.add(Ingredient.parse(string(json, "" + row + column, "")));
                        }
                    }
                    if (grid.stream().allMatch(Ingredient::empty)) throw new IllegalArgumentException("Empty crafting grid");
                    target.add(new SkyblockRecipe(CRAFTING, grid,
                            new Ingredient(string(json, "overrideOutputId", owner), positive(json, "count", 1)), 0, extra, ""));
                }
                case "forge" -> target.add(new SkyblockRecipe(FORGE, ingredients(json, "inputs"),
                        new Ingredient(string(json, "overrideOutputId", owner), positive(json, "count", 1)),
                        number(json, "duration", 0), extra, ""));
                case "katgrade" -> {
                    var inputs = new ArrayList<Ingredient>();
                    inputs.add(new Ingredient(required(json, "input"), 1));
                    inputs.addAll(ingredients(json, "items"));
                    var coins = json.has("coins") ? json.get("coins").getAsBigDecimal() : java.math.BigDecimal.ZERO;
                    if (coins.signum() < 0) throw new IllegalArgumentException("Negative coin cost");
                    if (coins.signum() > 0) inputs.add(new Ingredient("SKYBLOCK_COIN", coins));
                    target.add(new SkyblockRecipe(KAT, inputs, new Ingredient(required(json, "output"), 1),
                            number(json, "time", 0), extra, "KAT_NPC"));
                }
                case "npc_shop" -> target.add(new SkyblockRecipe(NPC, ingredients(json, "cost"),
                        Ingredient.parse(required(json, "result")), 0, extra, owner));
                case "trade" -> target.add(new SkyblockRecipe(NPC, List.of(Ingredient.parse(required(json, "cost"))),
                        new Ingredient(required(json, "result"), 1), 0, extra, "SkyBlock Trades"));
                case "drops" -> {
                    for (var element : json.getAsJsonArray("drops")) {
                        try {
                            var drop = element.getAsJsonObject();
                            String id = required(drop, "id");
                            var output = id.contains(":") ? Ingredient.parse(id) : new Ingredient(id, 1);
                            String description = "Chance: " + string(drop, "chance", "unspecified");
                            if (drop.has("extra")) description += " | " + String.join(" | ", strings(drop, "extra"));
                            target.add(new SkyblockRecipe(DROPS, List.of(new Ingredient(owner, 1)), output,
                                    0, description, owner));
                        } catch (RuntimeException e) { warn(owner + ": invalid drop: " + e.getMessage()); }
                    }
                }
                default -> unsupported.merge(type, 1, Integer::sum);
            }
        } catch (RuntimeException e) { warn(owner + ": invalid recipe: " + e.getMessage()); }
    }

    private static List<Ingredient> ingredients(JsonObject json, String key) {
        if (!json.has(key)) return List.of();
        return json.getAsJsonArray(key).asList().stream().map(e -> Ingredient.parse(e.getAsString()))
                .filter(i -> !i.empty()).toList();
    }

    private static List<String> strings(JsonObject json, String key) {
        JsonArray array = json.has(key) ? json.getAsJsonArray(key) : new JsonArray();
        return array.asList().stream().map(JsonElement::getAsString).toList();
    }

    private static String required(JsonObject json, String key) {
        String value = string(json, key, "");
        if (value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    private static long number(JsonObject json, String key, long fallback) {
        return json.has(key) ? json.get(key).getAsBigDecimal().longValueExact() : fallback;
    }

    private static long positive(JsonObject json, String key, long fallback) {
        long value = number(json, key, fallback);
        if (value <= 0) throw new IllegalArgumentException("Non-positive " + key);
        return value;
    }

    private void warn(String text) { warnings.add(text); }
}
