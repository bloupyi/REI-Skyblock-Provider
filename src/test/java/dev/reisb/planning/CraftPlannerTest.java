package dev.reisb.planning;

import dev.reisb.data.Ingredient;
import dev.reisb.data.SkyblockRecipe;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CraftPlannerTest {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static BigDecimal amount(long amount) { return BigDecimal.valueOf(amount); }
    private static SkyblockRecipe recipe(String output, long yield, Ingredient... inputs) {
        return new SkyblockRecipe(SkyblockRecipe.Kind.FORGE, List.of(inputs), new Ingredient(output, yield), 10, "", "");
    }

    @Test
    void batchesReuseSurplusAndDoNotConsumeSharedStockTwice() {
        var planner = new CraftPlanner(List.of(recipe("A", 1, new Ingredient("PART", 5)), recipe("B", 1, new Ingredient("PART", 3)),
                recipe("PART", 8, new Ingredient("RAW", 16))));
        var goals = new LinkedHashMap<String, BigDecimal>();
        goals.put("A", amount(1)); goals.put("B", amount(1));
        var result = planner.plan(goals, new CraftPlanner.Stock(Map.of("RAW", amount(6)), Map.of("RAW", amount(4))), Map.of(), Set.of());
        var raw = result.materials().stream().filter(row -> row.id().equals("RAW")).findFirst().orElseThrow();
        assertEquals(0, amount(16).compareTo(raw.needed()));
        assertEquals(0, amount(6).compareTo(raw.inventory()));
        assertEquals(0, amount(4).compareTo(raw.sacks()));
        assertEquals(0, amount(6).compareTo(raw.missing()));
        assertEquals(0, amount(3).compareTo(result.roots().get(1).children().getFirst().reused()));
    }

    @Test
    void ownedIntermediateAvoidsItsRawMaterialCost() {
        var planner = new CraftPlanner(List.of(recipe("FINAL", 1, new Ingredient("PART", 2)), recipe("PART", 1, new Ingredient("RAW", 160))));
        var result = planner.plan(Map.of("FINAL", new BigDecimal("1.0")), new CraftPlanner.Stock(Map.of("PART", amount(1)), Map.of()), Map.of(), Set.of());
        assertEquals(0, amount(160).compareTo(result.materials().stream().filter(row -> row.id().equals("RAW")).findFirst().orElseThrow().missing()));
        var covered = planner.plan(Map.of("FINAL", amount(1)), new CraftPlanner.Stock(Map.of("PART", amount(2)), Map.of()), Map.of(), Set.of());
        assertTrue(covered.materials().stream().noneMatch(row -> row.id().equals("RAW")));
        assertTrue(covered.materials().stream().allMatch(row -> row.missing().signum() == 0));
    }

    @Test
    void reversibleCompressionStopsBeforeRoundingInflatesRawDemand() {
        var planner = new CraftPlanner(List.of(recipe("ENCHANTED", 1, new Ingredient("DIAMOND", 160)),
                recipe("DIAMOND", 9, new Ingredient("BLOCK", 1)), recipe("BLOCK", 1, new Ingredient("DIAMOND", 9))));
        var result = planner.plan(Map.of("ENCHANTED", amount(1)), CraftPlanner.Stock.EMPTY, Map.of(), Set.of());
        assertEquals(0, amount(160).compareTo(result.materials().getFirst().missing()));
        assertEquals("DIAMOND", result.materials().getFirst().id());
        assertEquals(List.of("cycle: DIAMOND"), result.warnings());
        var fromBlocks = planner.plan(Map.of("DIAMOND", amount(10)), new CraftPlanner.Stock(Map.of("BLOCK", amount(2)), Map.of()), Map.of(), Set.of());
        assertTrue(fromBlocks.materials().stream().allMatch(row -> row.missing().signum() == 0));
        assertEquals(0, amount(8).compareTo(fromBlocks.roots().getFirst().surplus()));
    }

    @Test
    void stopsAlternativeMethodsNpcDecimalCostsAndDrops() {
        var npc = new SkyblockRecipe(SkyblockRecipe.Kind.NPC, List.of(new Ingredient("SKYBLOCK_COIN", new BigDecimal("0.5"))), new Ingredient("PART", 1), 0, "", "NPC");
        var drop = new SkyblockRecipe(SkyblockRecipe.Kind.DROPS, List.of(new Ingredient("MONSTER", 1)), new Ingredient("DROP", 1), 0, "", "");
        var planner = new CraftPlanner(List.of(recipe("PART", 1, new Ingredient("RAW", 10)), npc, drop));
        var stopped = planner.plan(Map.of("PART", amount(3)), CraftPlanner.Stock.EMPTY, Map.of(), Set.of("PART"));
        assertEquals("PART", stopped.materials().getFirst().id());
        var bought = planner.plan(Map.of("PART", amount(3)), CraftPlanner.Stock.EMPTY, Map.of("PART", 1), Set.of());
        assertEquals(0, new BigDecimal("1.5").compareTo(bought.materials().getFirst().missing()));
        var loot = planner.plan(Map.of("DROP", amount(2)), CraftPlanner.Stock.EMPTY, Map.of(), Set.of());
        assertEquals("DROP", loot.materials().getFirst().id());
        assertTrue(planner.methods("DROP").isEmpty());
    }

    @Test
    void largeTotalsRemainExactAndInvalidGoalsAreRejected() {
        var planner = new CraftPlanner(List.of(recipe("A", 1, new Ingredient("RAW", Long.MAX_VALUE))));
        var result = planner.plan(Map.of("A", amount(1_000_000)), CraftPlanner.Stock.EMPTY, Map.of(), Set.of());
        assertEquals(0, amount(Long.MAX_VALUE).multiply(amount(1_000_000)).compareTo(result.materials().getFirst().missing()));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(Map.of("A", ZERO), CraftPlanner.Stock.EMPTY, Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(Map.of("A", new BigDecimal("0.5")), CraftPlanner.Stock.EMPTY, Map.of(), Set.of()));
    }
}
