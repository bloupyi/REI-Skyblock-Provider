package dev.reisb.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class HypixelItems {
    private static final Gson GSON = new Gson();
    private static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("COMMON", "f"), Map.entry("UNCOMMON", "a"), Map.entry("RARE", "9"),
            Map.entry("EPIC", "5"), Map.entry("LEGENDARY", "6"), Map.entry("MYTHIC", "d"),
            Map.entry("DIVINE", "b"), Map.entry("SPECIAL", "c"), Map.entry("VERY_SPECIAL", "c"));

    public JsonObject parse(String text) {
        var root = JsonParser.parseString(text).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()
                || !root.has("lastUpdated") || root.get("lastUpdated").getAsLong() < 0
                || !root.has("items") || !root.get("items").isJsonArray() || root.getAsJsonArray("items").isEmpty()) {
            throw new IllegalArgumentException("Invalid Hypixel resource response");
        }
        var ids = new java.util.HashSet<String>();
        for (var element : root.getAsJsonArray("items")) {
            var item = element.getAsJsonObject();
            String id = required(item, "id");
            required(item, "name");
            required(item, "material");
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate Hypixel item " + id);
            if (item.has("stats") && !item.get("stats").isJsonObject()) throw new IllegalArgumentException("Invalid stats " + id);
            if (item.has("description") && !item.get("description").isJsonPrimitive()) throw new IllegalArgumentException("Invalid description " + id);
        }
        return root;
    }

    public RepositorySnapshot merge(RepositorySnapshot neu, String officialJson) {
        if (officialJson.isEmpty()) {
            validateRecipes(neu.items(), neu.recipes());
            return neu;
        }
        var root = parse(officialJson);
        var items = new LinkedHashMap<>(neu.items());
        var recipes = new LinkedHashSet<>(neu.recipes());
        var materials = new HashMap<String, String>();
        for (var element : root.getAsJsonArray("items")) {
            var item = element.getAsJsonObject();
            var known = neu.items().get(normalize(required(item, "id")));
            if (known != null) materials.putIfAbsent(required(item, "material"), known.minecraftId());
        }
        for (var element : root.getAsJsonArray("items")) {
            var item = element.getAsJsonObject();
            String id = normalize(required(item, "id"));
            var old = neu.items().get(id);
            String material = required(item, "material");
            String base = materials.getOrDefault(material, "minecraft:" + material.toLowerCase(java.util.Locale.ROOT));
            if (material.equals("SKULL_ITEM")) base = "minecraft:skull";
            int damage = item.has("durability") ? item.get("durability").getAsBigDecimal().intValueExact() : old == null ? 0 : old.damage();
            if (material.equals("SKULL_ITEM")) damage = 3;
            String color = item.has("tier") ? COLORS.getOrDefault(item.get("tier").getAsString(), "f") : "f";
            String name = "\u00a7" + color + required(item, "name");
            items.put(id, new SkyblockItem(id, base, damage, old == null ? "{}" : old.nbt(), name,
                    old == null ? List.of() : old.lore(), old == null ? List.of() : old.info(), canonical(item), old == null ? "" : old.levelData()));
        }
        for (var element : root.getAsJsonArray("items")) {
            var item = element.getAsJsonObject();
            if (!item.has("recipes")) continue;
            var officialRecipes = new ArrayList<SkyblockRecipe>();
            for (var recipe : item.getAsJsonArray("recipes")) officialRecipes.add(crafting(recipe.getAsJsonObject()));
            String owner = normalize(required(item, "id"));
            recipes.removeIf(recipe -> recipe.kind() == SkyblockRecipe.Kind.CRAFTING && recipe.output().id().equals(owner));
            recipes.addAll(officialRecipes);
        }
        validateRecipes(items, recipes);
        return new RepositorySnapshot(items, List.copyOf(recipes), neu.warnings(), neu.unsupported());
    }

    private static void validateRecipes(Map<String, SkyblockItem> items, Iterable<SkyblockRecipe> recipes) {
        for (var recipe : recipes) {
            if (!known(items, recipe.output()) || recipe.inputs().stream().anyMatch(input -> !known(items, input))) {
                throw new IllegalArgumentException("Unresolved combined recipe: " + recipe.output().id());
            }
        }
    }

    private static SkyblockRecipe crafting(JsonObject recipe) {
        if (!recipe.has("matrix") || !recipe.has("ingredient_symbols") || !recipe.has("output")
                || recipe.getAsJsonArray("matrix").size() != 9) throw new IllegalArgumentException("Unsupported official recipe format");
        var symbols = recipe.getAsJsonObject("ingredient_symbols");
        var inputs = new ArrayList<Ingredient>();
        for (var value : recipe.getAsJsonArray("matrix")) {
            String symbol = value.getAsString();
            if (symbol.isBlank()) inputs.add(Ingredient.EMPTY);
            else {
                if (!symbols.has(symbol) || !symbols.get(symbol).isJsonPrimitive()) throw new IllegalArgumentException("Unknown official ingredient symbol");
                var ingredient = Ingredient.parse(symbols.get(symbol).getAsString());
                inputs.add(new Ingredient(normalize(ingredient.id()), ingredient.amount()));
            }
        }
        var output = recipe.getAsJsonObject("output");
        long amount = output.has("count") ? output.get("count").getAsBigDecimal().longValueExact() : 1;
        if (amount <= 0 || inputs.stream().allMatch(Ingredient::empty)) throw new IllegalArgumentException("Invalid official recipe quantity/grid");
        return new SkyblockRecipe(SkyblockRecipe.Kind.CRAFTING, inputs,
                new Ingredient(normalize(required(output, "item_id")), amount), 0, "Hypixel API", "");
    }

    private static boolean known(Map<String, SkyblockItem> items, Ingredient input) {
        return input.empty() || input.id().equals("SKYBLOCK_COIN") || items.containsKey(input.id());
    }

    public long version(String text) { return text.isEmpty() ? 0 : parse(text).get("lastUpdated").getAsLong(); }
    public static String normalize(String id) { return id.replace(':', '-'); }

    public static String canonical(JsonElement element) {
        if (element.isJsonObject()) {
            var sorted = new JsonObject();
            element.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.add(entry.getKey(), JsonParser.parseString(canonical(entry.getValue()))));
            return GSON.toJson(sorted);
        }
        if (element.isJsonArray()) {
            var array = new com.google.gson.JsonArray();
            element.getAsJsonArray().forEach(value -> array.add(JsonParser.parseString(canonical(value))));
            return GSON.toJson(array);
        }
        return GSON.toJson(element);
    }

    private static String required(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() || object.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("Missing official " + key);
        }
        return object.get(key).getAsString();
    }
}
