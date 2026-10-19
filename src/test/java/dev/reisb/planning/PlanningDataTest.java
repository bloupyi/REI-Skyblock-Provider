package dev.reisb.planning;

import dev.reisb.data.Ingredient;
import dev.reisb.data.SkyblockItem;
import dev.reisb.data.SkyblockRecipe;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class PlanningDataTest {
    @TempDir Path temporary;

    @Test
    void sackCountsAreExactAndAmbiguousGemstoneCountsAreIgnored() {
        assertEquals(new BigDecimal("12345"), ObservedMenus.stored("§6Mining Sack", "Diamond", List.of("§7Stored: §e12,345§7/20k")).orElseThrow());
        assertEquals(BigDecimal.ZERO, ObservedMenus.stored("Mining Sack", "Diamond", List.of("Stored: 0/20k")).orElseThrow());
        assertTrue(ObservedMenus.stored("Auction House", "Diamond", List.of("Stored: 123/20k")).isEmpty());
        assertTrue(ObservedMenus.stored("Gemstones Sack", "Ruby Gemstone", List.of("Stored: 100/20k")).isEmpty());
        assertTrue(ObservedMenus.stored("Mining Sack", "Diamond", List.of("Stored: 1.5k/20k")).isEmpty());
        assertEquals(new BigDecimal("1234"), ObservedMenus.stored("Gemstones Sack", "ROUGH_RUBY_GEM", "Ruby Gemstone", List.of(" Amount: 1,234")).orElseThrow());
        assertTrue(ObservedMenus.stored("Gemstones Sack", "RUBY", "Ruby Gemstone", List.of(" Amount: 1,234")).isEmpty());
    }

    @Test
    void menuIconsResolveToTheirNamedResourceInsteadOfTheirVanillaMaterial() {
        var names = ObservedMenus.resourceNames(List.of(
                new SkyblockItem("ENCHANTED_DIAMOND", "minecraft:diamond", 0, "{}", "§aEnchanted Diamond", List.of(), List.of()),
                new SkyblockItem("INK_SACK-3", "minecraft:cocoa_beans", 0, "{}", "Cocoa Beans", List.of(), List.of())));
        assertEquals("ENCHANTED_DIAMOND", ObservedMenus.resourceId("Enchanted Diamond", "DIAMOND", names));
        assertEquals("INK_SACK-3", ObservedMenus.resourceId("Cocoa Beans IV", "", names));
        assertEquals("DIAMOND", ObservedMenus.resourceId("Diamond", "DIAMOND", names));
        assertEquals(new BigDecimal("9876"), ObservedMenus.stored("Enchanted Mining Sack (1/2)", "Enchanted Diamond", List.of("Stored: 9,876/20k")).orElseThrow());
        assertEquals(new ObservedMenus.Level("SKILL:COMBAT", 30), ObservedMenus.level("Your Skills (1/2)", "Combat XXX").orElseThrow());
        assertEquals(new ObservedMenus.Level("DUNGEON_SKILL:CATACOMBS", 25), ObservedMenus.level("Dungeoneering (1/2)", "The Catacombs XXV").orElseThrow());
        assertEquals(new ObservedMenus.Level("COLLECTION:COCOA_BEANS", 4), ObservedMenus.level("Farming Collections (1/2)", "Cocoa Beans IV").orElseThrow());
    }

    @Test
    void onlyRecognizedProgressMenusAndSlayerSummariesAreAccepted() {
        assertEquals(new ObservedMenus.Level("COLLECTION:DIAMOND", 4), ObservedMenus.level("Mining Collections", "Diamond IV").orElseThrow());
        assertEquals(new ObservedMenus.Level("DUNGEON_SKILL:CATACOMBS", 25), ObservedMenus.level("Dungeoneering", "Catacombs XXV").orElseThrow());
        assertEquals(new ObservedMenus.Level("SKILL:COMBAT", 30), ObservedMenus.level("Your Skills", "Combat 30").orElseThrow());
        assertTrue(ObservedMenus.level("Auction House", "Diamond IV").isEmpty());
        assertEquals(new ObservedMenus.Level("SLAYER:ENDERMAN", 7), ObservedMenus.slayer(" Enderman Slayer LVL 7 - Next LVL in 123 XP! ").orElseThrow());
        assertTrue(ObservedMenus.slayer("Requires Enderman Slayer 7").isEmpty());
        assertEquals(new ObservedMenus.Level("SLAYER:WOLF", 6), ObservedMenus.slayer("LVL UP! ➜ Wolf Slayer LVL 6").orElseThrow());
        assertEquals(-1, ObservedMenus.number("IIII"));
    }

    @Test
    void requirementsDistinguishScopesUnknownDataAndAlternatives() {
        var item = new SkyblockItem("TEST", "minecraft:diamond", 0, "{}", "Test", List.of("§cRequires The Catacombs Floor V Completion."), List.of(),
                "{\"requirements\":[{\"type\":\"SLAYER\",\"slayer_boss_type\":\"enderman\",\"level\":7},{\"type\":\"ANY_OF\",\"requirements\":[{\"type\":\"SKILL\",\"skill\":\"COMBAT\",\"level\":20},{\"type\":\"COLLECTION\",\"collection\":\"DIAMOND\",\"tier\":4}]}],\"catacombs_requirements\":[{\"type\":\"DUNGEON_SKILL\",\"dungeon_type\":\"CATACOMBS\",\"level\":25}]}");
        var recipe = new SkyblockRecipe(SkyblockRecipe.Kind.FORGE, List.of(), new Ingredient("TEST", 1), 0, "Requires: Diamond IV", "");
        var requirements = Requirements.read(item, List.of(recipe));
        assertEquals(5, requirements.size());
        assertTrue(requirements.stream().anyMatch(condition -> condition.scope().equals("craft") && condition.key().equals("COLLECTION:DIAMOND")));
        var conditional = requirements.stream().filter(condition -> condition.scope().equals("dungeonized")).findFirst().orElseThrow();
        long now = 1_000_000;
        assertEquals(Requirements.Status.UNKNOWN, conditional.status(Map.of(), now));
        assertEquals(Requirements.Status.UNMET, conditional.status(Map.of(conditional.key(), new PlanningStore.Observation(BigDecimal.TEN, now)), now));
        assertEquals(Requirements.Status.MET, conditional.status(Map.of(conditional.key(), new PlanningStore.Observation(BigDecimal.valueOf(25), now)), now));
        var alternative = requirements.stream().filter(condition -> !condition.alternatives().isEmpty()).findFirst().orElseThrow();
        assertEquals(Requirements.Status.MET, alternative.status(Map.of("COLLECTION:DIAMOND", new PlanningStore.Observation(BigDecimal.valueOf(4), now)), now));
        assertEquals(Requirements.Status.UNKNOWN, alternative.status(Map.of("SKILL:COMBAT", new PlanningStore.Observation(BigDecimal.TEN, now)), now));
        assertEquals(Requirements.Status.UNMET, conditional.status(Map.of(conditional.key(), new PlanningStore.Observation(BigDecimal.TEN, now)), now + 86_400_001));
    }

    @Test
    void persistedPlansAndStocksStaySeparatedByAccountAndProfile() throws Exception {
        Path path = temporary.resolve("planning.json");
        var store = new PlanningStore();
        store.account("account").goals.put("ENCHANTED_DIAMOND", BigDecimal.valueOf(2));
        store.account("account").excluded.add("HYPERION");
        store.profile("account:Apple").sacks.put("DIAMOND", new PlanningStore.Observation(BigDecimal.TEN, 123));
        store.profile("account:Banana").sacks.put("DIAMOND", new PlanningStore.Observation(BigDecimal.ZERO, 456));
        store.save(path);
        String saved = Files.readString(path);
        var loaded = PlanningStore.load(path);
        assertEquals(BigDecimal.valueOf(2), loaded.account("account").goals.get("ENCHANTED_DIAMOND"));
        assertTrue(loaded.account("account").excluded.contains("HYPERION"));
        assertEquals(BigDecimal.TEN, loaded.profile("account:Apple").sacks.get("DIAMOND").amount());
        assertEquals(BigDecimal.ZERO, loaded.profile("account:Banana").sacks.get("DIAMOND").amount());
        assertTrue(loaded.account("other").goals.isEmpty());
        Files.writeString(path, "broken");
        assertThrows(java.io.IOException.class, () -> PlanningStore.load(path));
        assertEquals("broken", Files.readString(path));
        var invalid = com.google.gson.JsonParser.parseString(saved).getAsJsonObject();
        invalid.getAsJsonObject("state").getAsJsonObject("accounts").getAsJsonObject("account")
                .getAsJsonObject("choices").add("DIAMOND", com.google.gson.JsonNull.INSTANCE);
        Files.writeString(path, invalid.toString());
        assertThrows(java.io.IOException.class, () -> PlanningStore.load(path));
        assertEquals(invalid.toString(), Files.readString(path));
    }
}
