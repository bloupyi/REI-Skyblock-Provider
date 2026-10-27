package dev.reisb.rei;

import dev.reisb.data.Ingredient;
import dev.reisb.data.SkyblockRecipe;
import dev.reisb.item.PreparedRepository;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DisplayIntegrationTest {
    @Test
    void repeatedReiReloadsKeepOneGlobalGenerator() {
        var generators = new ArrayList<DynamicDisplayGenerator<?>>();
        var foreign = new DynamicDisplayGenerator<me.shedaniel.rei.api.common.display.Display>() {};
        generators.add(foreign);
        var registry = (DisplayRegistry) Proxy.newProxyInstance(DisplayRegistry.class.getClassLoader(), new Class<?>[]{DisplayRegistry.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getGlobalDisplayGenerators" -> generators;
                    case "registerGlobalDisplayGenerator" -> { generators.add((DynamicDisplayGenerator<?>) args[0]); yield null; }
                    default -> null;
                });
        for (int i = 0; i < 5; i++) new SkyblockClientPlugin().registerDisplays(registry);
        assertEquals(1, generators.stream().filter(SkyblockDisplayGenerator.class::isInstance).count());
        assertEquals(1, generators.stream().filter(SkyblockFallbackGenerator.class::isInstance).count());
        assertEquals(3, generators.size());
        assertSame(foreign, generators.getFirst());
    }

    @Test
    void identicalDisplaysDeduplicateWithoutLosingDifferentMethods() {
        var recipe = new SkyblockRecipe(SkyblockRecipe.Kind.NPC, List.of(new Ingredient("SKYBLOCK_COIN", 8)), new Ingredient("ROTTEN_FLESH", 1), 0, "", "NPC");
        var displays = new HashSet<SkyblockDisplay>();
        displays.add(new SkyblockDisplay(recipe, PreparedRepository.EMPTY));
        displays.add(new SkyblockDisplay(recipe, PreparedRepository.EMPTY));
        assertEquals(1, displays.size());
        displays.add(new SkyblockDisplay(new SkyblockRecipe(recipe.kind(), recipe.inputs(), recipe.output(), 0, "", "OTHER_NPC"), PreparedRepository.EMPTY));
        assertEquals(2, displays.size());
    }

    @Test
    void coinAmountsStayExactAndWikiLinksAreWebLinks() {
        assertEquals("1,234,567.125 coins", DisplayText.coins(new BigDecimal("1234567.125")));
        assertEquals("0.5 coins", DisplayText.coins(new BigDecimal("0.5")));
        assertEquals("320", DisplayText.quantity(new BigDecimal("320")));
        assertEquals("1,234,567.125", DisplayText.quantity(new BigDecimal("1234567.125")));
        assertEquals(1, DisplayText.wikiLinks(List.of("https://wiki.hypixel.net/Terminator", "javascript:alert(1)", "file:///tmp/test", "not a link", "https://wiki.hypixel.net/Terminator")).size());
    }
}
