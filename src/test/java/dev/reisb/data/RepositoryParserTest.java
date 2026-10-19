package dev.reisb.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class RepositoryParserTest {
    @TempDir Path temporary;

    public static JsonObject fixture(String name) throws Exception {
        try (var stream = RepositoryParserTest.class.getResourceAsStream("/neu/" + name + ".json")) {
            assertNotNull(stream);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void ingredientIdsAndExactQuantities() {
        assertEquals(new Ingredient("ARMADILLO;1", 2), Ingredient.parse("ARMADILLO;1:2"));
        assertEquals(new Ingredient("SKYBLOCK_COIN", 10), Ingredient.parse("SKYBLOCK_COIN:10.0"));
        assertEquals(new Ingredient("minecraft:diamond", 32), Ingredient.parse("minecraft:diamond:32"));
        assertEquals(Ingredient.EMPTY, Ingredient.parse(""));
        assertEquals(new Ingredient("DIAMOND", 1), Ingredient.parse("DIAMOND"));
        assertEquals("0.5", Ingredient.parse("SKYBLOCK_COIN:0.5").quantityText());
        for (String value : List.of("DIAMOND:0", "DIAMOND:-1", "COIN:1.5", "DIAMOND:x", "X:9223372036854775808")) {
            assertThrows(RuntimeException.class, () -> Ingredient.parse(value), value);
        }
    }

    @Test
    void hyperionHasOriginalNineSlotLayout() throws Exception {
        var parser = new RepositoryParser();
        var json = fixture("HYPERION");
        assertEquals("HYPERION", parser.parseItem(json).id());
        var recipes = parser.parseRecipes(json);
        assertEquals(1, recipes.size());
        var recipe = recipes.getFirst();
        assertEquals(SkyblockRecipe.Kind.CRAFTING, recipe.kind());
        assertEquals(9, recipe.inputs().size());
        assertEquals("NECRON_BLADE", recipe.inputs().get(4).id());
        assertEquals(8, recipe.inputs().stream().filter(i -> i.id().equals("GIANT_FRAGMENT_LASER")).count());
        assertEquals(new Ingredient("HYPERION", 1), recipe.output());
    }

    @Test
    void enchantedDiamondPreservesEmptySlotsAndAlternateYield() throws Exception {
        var recipes = new RepositoryParser().parseRecipes(fixture("ENCHANTED_DIAMOND"));
        assertEquals(2, recipes.size());
        for (var recipe : recipes) {
            for (int position : List.of(0, 2, 6, 8)) assertTrue(recipe.inputs().get(position).empty());
            assertEquals(160, recipe.inputs().stream().mapToLong(Ingredient::count).sum());
        }
        assertEquals(1, recipes.get(0).output().count());
        assertEquals(9, recipes.get(1).output().count());
    }

    @Test
    void forgeKatNpcAndTradesUseRealData() throws Exception {
        var parser = new RepositoryParser();
        var forge = parser.parseRecipes(fixture("REFINED_DIAMOND")).getFirst();
        assertEquals(28800, forge.seconds());
        assertEquals(new Ingredient("ENCHANTED_DIAMOND_BLOCK", 2), forge.inputs().getFirst());
        var kat = parser.parseRecipes(fixture("ARMADILLO;1")).getFirst();
        assertEquals(10800, kat.seconds());
        assertEquals(List.of(new Ingredient("ARMADILLO;0", 1), new Ingredient("SKYBLOCK_COIN", 10000)), kat.inputs());
        var fractional = fixture("ARMADILLO;1");
        fractional.getAsJsonArray("recipes").get(0).getAsJsonObject().addProperty("coins", new java.math.BigDecimal("0.5"));
        assertEquals("0.5", parser.parseRecipes(fractional).getFirst().inputs().getLast().quantityText());
        var npc = parser.parseRecipes(fixture("ADVENTURER_NPC")).getFirst();
        assertEquals(new Ingredient("ROTTEN_FLESH", 1), npc.output());
        assertEquals(new Ingredient("SKYBLOCK_COIN", 8), npc.inputs().getFirst());
        var trade = parser.parseRecipes(fixture("ANVIL")).stream().filter(r -> r.kind() == SkyblockRecipe.Kind.NPC).findFirst().orElseThrow();
        assertEquals(new Ingredient("EMERALD", 25), trade.inputs().getFirst());
    }

    @Test
    void invalidRecipeDoesNotDiscardValidNeighbour() throws Exception {
        var json = fixture("ENCHANTED_DIAMOND");
        json.getAsJsonArray("recipes").get(0).getAsJsonObject().addProperty("A1", "DIAMOND:-2");
        var recipes = new RepositoryParser().parseRecipes(json);
        assertEquals(1, recipes.size());
        assertEquals(9, recipes.getFirst().output().count());
        assertThrows(IllegalArgumentException.class, () -> new RepositoryParser().parseItem(new JsonObject()));
    }

    @Test
    void petConstantsAreStoredWithTheSnapshotEvenAfterItemEntries() throws Exception {
        Path archive = temporary.resolve("pet-constants.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("NEU/items/pet.json"));
            zip.write(simpleItem("ENDER_DRAGON;3").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("NEU/constants/petnums.json"));
            zip.write("{\"ENDER_DRAGON\":{\"EPIC\":{\"1\":{\"statNums\":{\"STRENGTH\":1}},\"100\":{\"statNums\":{\"STRENGTH\":99}}}}}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var item = new RepositoryParser().parse(archive).items().get("ENDER_DRAGON;3");
        assertTrue(item.levelData().contains("99"));
        assertEquals(java.util.List.of("1 → 99"), dev.reisb.item.PetLevels.lore(item, java.util.List.of("{STRENGTH}")));
    }

    @Test
    void archivesDeduplicateValidateReferencesAndIgnoreBrokenJson() throws Exception {
        var item = fixture("ENCHANTED_DIAMOND");
        var recipe = item.getAsJsonArray("recipes").get(0).deepCopy();
        item.getAsJsonArray("recipes").add(recipe);
        var diamond = simpleItem("DIAMOND");
        var block = simpleItem("DIAMOND_BLOCK");
        Path archive = temporary.resolve("repo.zip");
        archive(archive, List.of(item.toString(), diamond, block, "{bad json"));
        var result = new RepositoryParser().parse(archive);
        assertEquals(3, result.items().size());
        assertEquals(2, result.recipes().size());
        assertFalse(result.warnings().isEmpty());
        Path missing = temporary.resolve("missing.zip");
        archive(missing, List.of(item.toString()));
        assertTrue(new RepositoryParser().parse(missing).recipes().isEmpty());
        assertFalse(new RepositoryParser().parse(missing).warnings().isEmpty());
    }

    public static String simpleItem(String id) {
        return "{\"internalname\":\"" + id + "\",\"itemid\":\"minecraft:diamond\",\"displayname\":\"" + id + "\"}";
    }

    public static void archive(Path path, List<String> json) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            int index = 0;
            for (String text : json) {
                zip.putNextEntry(new ZipEntry("NEU/items/" + index++ + ".json"));
                zip.write(text.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
