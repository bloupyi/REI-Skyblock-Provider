package dev.reisb.rei;

import dev.reisb.ReiSkyblockProvider;
import dev.reisb.config.ProviderConfig;
import dev.reisb.data.Ingredient;
import dev.reisb.data.RepositorySnapshot;
import dev.reisb.data.SkyblockItem;
import dev.reisb.data.SkyblockRecipe;
import dev.reisb.item.PreparedRepository;
import dev.reisb.item.SkyblockIds;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import me.shedaniel.rei.api.common.entry.EntryStack;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkyblockLookupTest {
    private ProviderConfig previousConfig;
    private PreparedRepository previousRepository;
    private final SkyblockDisplayGenerator generator = new SkyblockDisplayGenerator();
    private final SkyblockRecipe craft = new SkyblockRecipe(SkyblockRecipe.Kind.CRAFTING,
            Collections.nCopies(9, new Ingredient("DIAMOND", 1)), new Ingredient("HYPERION", 1), 0, "", "");
    private final SkyblockRecipe forge = new SkyblockRecipe(SkyblockRecipe.Kind.FORGE,
            List.of(new Ingredient("DIAMOND", 64)), new Ingredient("HYPERION", 1), 600, "", "");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.ITEM.forEach(item ->
                item.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY));
    }

    @BeforeEach
    void setup() throws Exception {
        previousConfig = ReiSkyblockProvider.config();
        previousRepository = ReiSkyblockProvider.repository();
        var config = new ProviderConfig();
        config.onlyOnHypixelSkyblock = false;
        set("config", config);
        publish(List.of(craft, craft, forge));
    }

    @AfterEach
    void restore() throws Exception {
        set("config", previousConfig);
        set("repository", previousRepository);
    }

    @Test
    void recipesRemainAlongsideInfoForProviderModernAndLegacyStacks() {
        for (var stack : List.of(tagged("HYPERION", false), tagged("HYPERION", true), ReiSkyblockProvider.repository().stacks().get("HYPERION"))) {
            var displays = generator.getRecipeFor(entry(stack)).orElseThrow();
            assertEquals(List.of(SkyblockRecipe.Kind.CRAFTING, SkyblockRecipe.Kind.FORGE, SkyblockRecipe.Kind.INFO),
                    displays.stream().map(display -> display.recipe().kind()).toList());
            assertEquals(64, displays.get(1).recipe().inputs().getFirst().count());
            assertEquals(600, displays.get(1).recipe().seconds());
        }
    }

    @Test
    void usagesContainRealRecipesWithoutInfoOrDuplicates() {
        var usages = generator.getUsageFor(entry(tagged("DIAMOND", false))).orElseThrow();
        assertEquals(List.of(craft, forge), usages.stream().map(SkyblockDisplay::recipe).toList());
    }

    @Test
    void noKnownRecipeProducesOnlyInformationAndUnknownItemProducesNothing() throws Exception {
        publish(List.of());
        var result = generator.getRecipeFor(entry(tagged("HYPERION", false))).orElseThrow();
        assertEquals(List.of(SkyblockRecipe.Kind.INFO), result.stream().map(display -> display.recipe().kind()).toList());
        assertTrue(generator.getUsageFor(entry(tagged("HYPERION", false))).orElseThrow().isEmpty());
        assertTrue(generator.getRecipeFor(entry(tagged("UNKNOWN", false))).isEmpty());
        assertTrue(generator.getRecipeFor(entry(new ItemStack(Items.IRON_SWORD))).isEmpty());
    }

    @Test
    void sameGeneratorReadsNewSnapshotAfterIncompleteLoadingAndDataReload() throws Exception {
        set("repository", PreparedRepository.EMPTY);
        var query = entry(tagged("HYPERION", false));
        assertTrue(generator.getRecipeFor(query).isEmpty());
        publish(List.of());
        assertEquals(1, generator.getRecipeFor(query).orElseThrow().size());
        publish(List.of(craft));
        assertEquals(List.of(craft), generator.getRecipeFor(query).orElseThrow().stream()
                .map(SkyblockDisplay::recipe).filter(recipe -> recipe.kind() != SkyblockRecipe.Kind.INFO).toList());
        publish(List.of(forge));
        assertEquals(List.of(forge), generator.getRecipeFor(query).orElseThrow().stream()
                .map(SkyblockDisplay::recipe).filter(recipe -> recipe.kind() != SkyblockRecipe.Kind.INFO).toList());
    }

    @Test
    void disablingInfoDoesNotDisableRecipes() {
        ReiSkyblockProvider.config().enableItemInfo = false;
        assertEquals(List.of(craft, forge), generator.getRecipeFor(entry(tagged("HYPERION", false))).orElseThrow()
                .stream().map(SkyblockDisplay::recipe).toList());
    }

    @Test
    void fallbackMatchesIdentityWithoutBroadeningToUnrelatedMaterialsOrSkyblockIds() {
        var source = ReiSkyblockProvider.repository().source();
        assertTrue(SkyblockFallbackGenerator.matches(tagged("HYPERION", false), tagged("HYPERION", true), source));
        assertTrue(SkyblockFallbackGenerator.matches(tagged("DIAMOND", false), new ItemStack(Items.DIAMOND), source));
        assertFalse(SkyblockFallbackGenerator.matches(tagged("HYPERION", false), new ItemStack(Items.IRON_SWORD), source));
        assertFalse(SkyblockFallbackGenerator.matches(tagged("HYPERION", false), tagged("OTHER_SWORD", false), source));
        assertFalse(SkyblockFallbackGenerator.matches(new ItemStack(Items.IRON_SWORD), tagged("HYPERION", false), source));
        var wrongMaterial = new ItemStack(Items.DIAMOND);
        wrongMaterial.set(DataComponents.CUSTOM_DATA, tagged("HYPERION", false).get(DataComponents.CUSTOM_DATA));
        assertFalse(SkyblockFallbackGenerator.matches(tagged("HYPERION", false), wrongMaterial, source));
    }

    private void publish(List<SkyblockRecipe> recipes) throws Exception {
        var items = Map.of("HYPERION", new SkyblockItem("HYPERION", "minecraft:iron_sword", 0, "{}", "Hyperion", List.of(), List.of()),
                "DIAMOND", new SkyblockItem("DIAMOND", "minecraft:diamond", 0, "{}", "Diamond", List.of(), List.of()));
        var stack = tagged("HYPERION", false);
        var tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        tag.putBoolean(SkyblockIds.MARKER, true);
        tag.putString(SkyblockIds.MARKER + "_id", "HYPERION");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        set("repository", new PreparedRepository(new RepositorySnapshot(items, recipes, List.of(), Map.of()),
                Map.of("HYPERION", stack, "DIAMOND", tagged("DIAMOND", false)),
                recipes.isEmpty() ? Map.of() : Map.of("HYPERION", recipes),
                recipes.isEmpty() ? Map.of() : Map.of("DIAMOND", recipes)));
    }

    private static ItemStack tagged(String id, boolean legacy) {
        var stack = new ItemStack(id.equals("DIAMOND") ? Items.DIAMOND : Items.IRON_SWORD);
        var tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("uuid", "real-inventory-uuid");
        if (legacy) { var nested = new CompoundTag(); nested.put("ExtraAttributes", tag); tag = nested; }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    @SuppressWarnings("unchecked")
    private static EntryStack<ItemStack> entry(ItemStack stack) {
        return (EntryStack<ItemStack>) Proxy.newProxyInstance(EntryStack.class.getClassLoader(), new Class<?>[]{EntryStack.class},
                (proxy, method, args) -> method.getName().equals("getValue") ? stack : null);
    }

    private static void set(String name, Object value) throws Exception {
        Field field = ReiSkyblockProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
