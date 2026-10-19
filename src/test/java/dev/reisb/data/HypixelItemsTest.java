package dev.reisb.data;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class HypixelItemsTest {
    private RepositorySnapshot base() throws Exception {
        var parser = new RepositoryParser();
        var ingredient = parser.parseItem(com.google.gson.JsonParser.parseString(RepositoryParserTest.simpleItem("INGREDIENT")).getAsJsonObject());
        var outputJson = com.google.gson.JsonParser.parseString(SyncFixture.craft(4, "Old name")).getAsJsonObject();
        return new RepositorySnapshot(Map.of("INGREDIENT", ingredient, "OUTPUT", parser.parseItem(outputJson)), parser.parseRecipes(outputJson), List.of(), Map.of());
    }

    @Test
    void officialMetadataOverridesNeuAndNewItemsNeedNoNewMod() throws Exception {
        String json = "{\"success\":true,\"lastUpdated\":123,\"items\":["
                + "{\"id\":\"OUTPUT\",\"material\":\"DIAMOND\",\"name\":\"Official name\",\"tier\":\"EPIC\",\"description\":\"Official description\",\"stats\":{\"DAMAGE\":99}},"
                + "{\"id\":\"NEW\",\"material\":\"DIAMOND\",\"name\":\"New item\"}]}";
        var snapshot = new HypixelItems().merge(base(), json);
        assertEquals("§5Official name", snapshot.items().get("OUTPUT").name());
        assertTrue(snapshot.items().get("OUTPUT").officialMetadata().contains("Official description"));
        assertTrue(snapshot.items().containsKey("NEW"));
        assertTrue(snapshot.items().containsKey("INGREDIENT"));
        assertEquals(4, snapshot.recipes().getFirst().inputs().get(4).count());
        var removed = new HypixelItems().merge(base(), json.replace(",{\"id\":\"NEW\",\"material\":\"DIAMOND\",\"name\":\"New item\"}", ""));
        assertFalse(removed.items().containsKey("NEW"));
    }

    @Test
    void officialRecipesAreAuthoritativeOnlyWhenExposed() throws Exception {
        String json = "{\"success\":true,\"lastUpdated\":123,\"items\":[{\"id\":\"OUTPUT\",\"material\":\"DIAMOND\",\"name\":\"Output\",\"recipes\":[{\"output\":{\"item_id\":\"OUTPUT\"},\"ingredient_symbols\":{\"A\":\"INGREDIENT\"},\"matrix\":[\" \",\" \",\" \",\" \",\"A\",\" \",\" \",\" \",\" \"]}]}]}";
        var merged = new HypixelItems().merge(base(), json);
        assertEquals(1, merged.recipes().size());
        assertEquals(1, merged.recipes().getFirst().inputs().get(4).count());
        assertEquals("Hypixel API", merged.recipes().getFirst().extra());
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        root.getAsJsonArray("items").get(0).getAsJsonObject().add("recipes", new com.google.gson.JsonArray());
        assertTrue(new HypixelItems().merge(base(), root.toString()).recipes().isEmpty());
        root.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("recipes", "incompatible");
        assertThrows(RuntimeException.class, () -> new HypixelItems().merge(base(), root.toString()));
    }

    @Test
    void failedResponsesAndDuplicateIdsAreRejected() {
        assertThrows(RuntimeException.class, () -> new HypixelItems().parse("{\"success\":false}"));
        String item = "{\"id\":\"X\",\"material\":\"DIAMOND\",\"name\":\"X\"}";
        assertThrows(RuntimeException.class, () -> new HypixelItems().parse("{\"success\":true,\"lastUpdated\":1,\"items\":[" + item + "," + item + "]}"));
    }
}
