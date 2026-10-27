package dev.reisb;

import dev.reisb.data.SkyblockRecipe;
import dev.reisb.item.SkyblockIds;
import dev.reisb.rei.SkyblockClientPlugin;
import dev.reisb.rei.SkyblockDisplay;
import dev.reisb.rei.SkyblockDisplayGenerator;
import dev.reisb.rei.SkyblockFallbackGenerator;
import java.util.List;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.comparison.ItemComparatorRegistry;
import me.shedaniel.rei.api.common.plugins.PluginManager;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

final class CoexistenceAssertions {
    private CoexistenceAssertions() {}

    static void run(ClientGameTestContext context) {
        for (int mode = 0; mode < 3; mode++) {
            int currentMode = mode;
            context.runOnClient(client -> {
                ReiSkyblockProvider.config().disableSkyblockerReiIntegration = false;
                CompatibilityFixture.skyblockerPresent = currentMode != 0;
                CompatibilityFixture.skyblockerEnabled = currentMode != 2;
                me.shedaniel.rei.RoughlyEnoughItemsCoreClient.reloadPlugins(null, null);
            });
            context.waitTicks(5);
            context.waitFor(client -> !PluginManager.areAnyReloading() && SkyblockClientPlugin.isSynchronized(), 2000);
            context.runOnClient(client -> {
                var registry = DisplayRegistry.getInstance();
                check(registry.getGlobalDisplayGenerators().stream().filter(SkyblockDisplayGenerator.class::isInstance).count() == 1, "Provider generator count");
                check(registry.getGlobalDisplayGenerators().stream().filter(SkyblockFallbackGenerator.class::isInstance).count() == 1, "Fallback generator count");
                var infoGenerators = registry.getGlobalDisplayGenerators().stream().filter(SkyblockerInfoFixture.class::isInstance).toList();
                check(infoGenerators.size() <= 1, "Duplicate upstream generators after REI reload");
                if (!infoGenerators.isEmpty()) check(!infoGenerators.getFirst()
                        .getRecipeFor(EntryStacks.of(ReiSkyblockProvider.repository().stacks().get("HYPERION")))
                        .orElseThrow().isEmpty() == CompatibilityFixture.integrationEnabled(), "Stale generator returned an orphaned category");
                else check(!CompatibilityFixture.integrationEnabled(), "Enabled upstream generator missing");
                verifyLookups();
                verifyForeignRecipes();
                if (currentMode == 0) {
                    verifyOrphanFailure();
                    ReiSkyblockProvider.config().debugLogging = true;
                    dev.reisb.rei.ReiDiagnostics.inspect("HYPERION");
                    ReiSkyblockProvider.config().debugLogging = false;
                }
                // A config toggle does not retroactively unregister upstream generators.
                if (CompatibilityFixture.integrationEnabled()) {
                    CompatibilityFixture.skyblockerEnabled = false;
                    check(registry.getGlobalDisplayGenerators().stream().anyMatch(SkyblockerInfoFixture.class::isInstance), "Fixture did not reproduce stale registration");
                    verifyLookups();
                    CompatibilityFixture.skyblockerEnabled = true;
                }
                ReiSkyblockProvider.LOGGER.info("REI coexistence fixture mode {} passed", currentMode);
            });
            verifyKeys(context);
        }
        context.runOnClient(client -> {
            CompatibilityFixture.skyblockerPresent = true;
            CompatibilityFixture.skyblockerEnabled = true;
            me.shedaniel.rei.RoughlyEnoughItemsCoreClient.reloadPlugins(null, null);
        });
        context.waitTicks(5);
        context.waitFor(client -> !PluginManager.areAnyReloading() && SkyblockClientPlugin.isSynchronized(), 2000);
        verifyIsolation(context);
    }

    private static void verifyIsolation(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ReiSkyblockProvider.config().disableSkyblockerReiIntegration = true;
            CompatibilityFixture.retained = false;
            de.hysky.skyblocker.compatibility.rei.SkyblockerREIClientPlugin.registered = false;
            de.hysky.skyblocker.compatibility.rei.SkyblockerREICommonPlugin.registered = false;
            // The old global generator still exists in REI, but its body must stop immediately.
            var query = EntryStacks.of(ReiSkyblockProvider.repository().stacks().get("HYPERION"));
            for (var generator : DisplayRegistry.getInstance().getGlobalDisplayGenerators()) {
                if (generator instanceof SkyblockerInfoFixture) {
                    check(generator.getRecipeFor(query).isEmpty(), "Isolated legacy generator still produced recipes");
                    check(generator.getUsageFor(query).isEmpty(), "Isolated legacy generator still produced usages");
                }
            }
        });
        // No explicit reload call: changing the provider option must schedule a full REI reload.
        context.waitTicks(5);
        context.waitFor(client -> !PluginManager.areAnyReloading() && SkyblockClientPlugin.isSynchronized(), 2000);
        context.runOnClient(client -> {
            check(!de.hysky.skyblocker.compatibility.rei.SkyblockerREIClientPlugin.registered, "Isolated client hooks ran");
            check(!de.hysky.skyblocker.compatibility.rei.SkyblockerREICommonPlugin.registered, "Isolated comparator hooks ran");
            check(CompatibilityFixture.retained, "Isolation removed an unrelated plugin from the mixed provider");
            check(CategoryRegistry.getInstance().tryGet(SkyblockerInfoFixture.CATEGORY).isEmpty(), "Isolated category remained registered");
            verifyLookups();
        });
        verifyKeys(context);
        ReiSkyblockProvider.LOGGER.info("Skyblocker REI isolation assertions passed: startup, runtime switch, stale generators, unrelated providers and actual R/U keys");
    }

    private static void verifyOrphanFailure() {
        var registry = DisplayRegistry.getInstance();
        var unprotected = new SkyblockerInfoFixture();
        registry.registerGlobalDisplayGenerator(unprotected);
        boolean failed = false;
        try {
            ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(ReiSkyblockProvider.repository().stacks().get("HYPERION")))
                    .streamDisplays().count();
        } catch (NullPointerException | IllegalArgumentException expected) {
            failed = true;
        } finally {
            ((me.shedaniel.rei.impl.client.registry.display.DisplayGeneratorsRegistryImpl) registry)
                    .globalDisplayGenerators().remove(unprotected);
        }
        check(failed, "An unprotected orphaned generator must reproduce the whole-search failure");
        ReiSkyblockProvider.LOGGER.info("Reproduced orphaned-category failure with provider recipes loaded; protected lookup succeeds");
    }

    private static void verifyKeys(ClientGameTestContext context) {
        var previous = context.computeOnClient(client -> client.player.getInventory().getItem(0).copy());
        try {
            context.runOnClient(client -> {
                client.player.getInventory().setItem(0, live(ReiSkyblockProvider.repository().stacks().get("HYPERION")));
                client.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player));
            });
            var position = context.computeOnClient(client -> {
                var screen = (net.minecraft.client.gui.screens.inventory.InventoryScreen) client.gui.screen();
                var accessor = (dev.reisb.mixin.InventoryScreenAccessor) screen;
                var slot = screen.getMenu().getSlot(36);
                var window = client.getWindow();
                return new double[]{(accessor.reisb$left() + slot.x + 8.0) * window.getScreenWidth() / window.getGuiScaledWidth(),
                        (accessor.reisb$top() + slot.y + 8.0) * window.getScreenHeight() / window.getGuiScaledHeight()};
            });
            context.getInput().setCursorPos(position[0], position[1]);
            context.waitTicks(2);
            context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_R);
            context.waitFor(client -> client.gui.screen() instanceof me.shedaniel.rei.api.client.gui.screen.DisplayScreen);
            context.runOnClient(client -> check(((me.shedaniel.rei.api.client.gui.screen.DisplayScreen) client.gui.screen())
                    .getCurrentCategoryId().equals(SkyblockDisplay.category(SkyblockRecipe.Kind.CRAFTING)), "R key opened information instead of crafting"));
            context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);
            context.waitForScreen(net.minecraft.client.gui.screens.inventory.InventoryScreen.class);
            context.runOnClient(client -> client.player.getInventory().setItem(0,
                    live(ReiSkyblockProvider.repository().stacks().get("ENCHANTED_DIAMOND"))));
            context.getInput().setCursorPos(position[0], position[1]);
            context.waitTicks(2);
            context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_U);
            context.waitFor(client -> client.gui.screen() instanceof me.shedaniel.rei.api.client.gui.screen.DisplayScreen);
            context.runOnClient(client -> check(((me.shedaniel.rei.api.client.gui.screen.DisplayScreen) client.gui.screen())
                    .getCurrentCategoryId().equals(SkyblockDisplay.category(SkyblockRecipe.Kind.CRAFTING)), "U key opened information instead of usages"));
            ReiSkyblockProvider.LOGGER.info("Actual R/U keyboard assertions passed for fixture present={}, enabled={}",
                    CompatibilityFixture.skyblockerPresent, CompatibilityFixture.skyblockerEnabled);
        } finally {
            context.runOnClient(client -> {
                client.player.getInventory().setItem(0, previous);
                client.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player));
            });
        }
    }

    private static void verifyLookups() {
        var repository = ReiSkyblockProvider.repository();
        var category = new SkyblockDisplayGenerator().generate(ViewSearchBuilder.builder()
                .addCategory(SkyblockDisplay.category(SkyblockRecipe.Kind.CRAFTING))).orElseThrow();
        check(category.size() == new java.util.HashSet<>(category).size(), "Duplicate provider recipes while browsing category");
        for (String id : List.of("HYPERION", "ASPECT_OF_THE_END", "REFINED_DIAMOND")) {
            var entry = EntryStacks.of(live(repository.stacks().get(id)));
            var expected = repository.recipes().get(id).stream().filter(recipe -> recipe.kind() != SkyblockRecipe.Kind.INFO).distinct().toList();
            var result = ViewSearchBuilder.builder().addRecipesFor(entry).streamDisplays().map(spec -> spec.provideInternalDisplay()).toList();
            for (var recipe : expected) check(result.contains(new SkyblockDisplay(recipe, repository)), "R lost " + id + ": " + recipe);
            check(!expected.isEmpty(), "Test requires known recipes for " + id);
            check(ViewSearchBuilder.builder().addRecipesFor(entry).open(), "R screen did not open for " + id);
            check(((me.shedaniel.rei.api.client.gui.screen.DisplayScreen) net.minecraft.client.Minecraft.getInstance().gui.screen())
                    .getCurrentCategoryId().equals(SkyblockDisplay.category(expected.getFirst().kind())), "Info category selected over recipe for " + id);
        }
        var ingredient = EntryStacks.of(live(repository.stacks().get("ENCHANTED_DIAMOND")));
        var usages = ViewSearchBuilder.builder().addUsagesFor(ingredient).streamDisplays().map(spec -> spec.provideInternalDisplay()).toList();
        for (var recipe : repository.usages().get("ENCHANTED_DIAMOND").stream().distinct().toList())
            check(usages.contains(new SkyblockDisplay(recipe, repository)), "U lost ingredient usage: " + recipe);
        String noRecipe = repository.source().items().keySet().stream().filter(id -> !repository.recipes().containsKey(id)).findFirst().orElseThrow();
        var unknown = EntryStacks.of(live(repository.stacks().get(noRecipe)));
        var result = ViewSearchBuilder.builder().addRecipesFor(unknown).streamDisplays().map(spec -> spec.provideInternalDisplay()).toList();
        check(result.stream().anyMatch(display -> display instanceof SkyblockDisplay own && own.recipe().kind() == SkyblockRecipe.Kind.INFO), "Missing no-recipe information");
        check(result.stream().noneMatch(display -> display instanceof SkyblockDisplay own && own.recipe().kind() != SkyblockRecipe.Kind.INFO), "Invented a recipe");
        check(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(Items.CRAFTING_TABLE)).streamDisplays().findAny().isPresent(), "Vanilla recipes lost");
    }

    private static void verifyForeignRecipes() {
        var registry = DisplayRegistry.getInstance();
        CategoryRegistry.getInstance().add(new ForeignCategory());
        var stack = ReiSkyblockProvider.repository().stacks().get("HYPERION");
        var foreign = new ForeignDisplay(stack);
        registry.add(foreign);
        // A third party compares metadata in addition to the SkyBlock ID.
        ItemComparatorRegistry.getInstance().registerGlobal((comparison, item) -> SkyblockIds.owned(item) ? 1 : 2);
        var query = EntryStacks.of(live(stack));
        check(!me.shedaniel.rei.impl.client.view.ViewsImpl.isRecipesFor(null, List.of(query), foreign), "Comparator conflict was not reproduced");
        // Reproduce the old REI result, then restore the provider's fallback immediately.
        var generators = ((me.shedaniel.rei.impl.client.registry.display.DisplayGeneratorsRegistryImpl) registry).globalDisplayGenerators();
        var fallback = generators.stream().filter(SkyblockFallbackGenerator.class::isInstance).findFirst().orElseThrow();
        verifyWildcardSuppression(registry, generators, fallback);
        generators.remove(fallback);
        try {
            var before = ViewSearchBuilder.builder().addRecipesFor(query).streamDisplays().map(spec -> spec.provideInternalDisplay()).toList();
            check(before.stream().anyMatch(display -> display instanceof SkyblockDisplay own && own.recipe().kind() == SkyblockRecipe.Kind.INFO), "Info result missing from reproduction");
            check(!before.contains(foreign), "Expected REI to skip fallback when info exists");
        } finally { generators.add(fallback); }
        check(ViewSearchBuilder.builder().addRecipesFor(query).streamDisplays().anyMatch(spec -> spec.provideInternalDisplay() == foreign), "Foreign recipe was masked by info");
        check(ViewSearchBuilder.builder().addUsagesFor(query).streamDisplays().anyMatch(spec -> spec.provideInternalDisplay() == foreign), "Foreign usage was masked by info");
        check(ViewSearchBuilder.builder().addRecipesFor(query).filterCategory(SkyblockDisplay.category(SkyblockRecipe.Kind.INFO))
                .streamDisplays().noneMatch(spec -> spec.provideInternalDisplay() == foreign), "Explicit category filter ignored");
        var different = stack.copy();
        var tag = different.get(DataComponents.CUSTOM_DATA).copyTag();
        tag.putString(SkyblockIds.MARKER + "_id", "DIFFERENT_SKYBLOCK_ITEM");
        different.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        check(new SkyblockFallbackGenerator().getRecipeFor(EntryStacks.of(different)).orElseThrow().isEmpty(), "Matched unrelated item of the same material");
        registry.registerVisibilityPredicate((category, display) -> display == foreign
                ? dev.architectury.event.EventResult.interruptFalse() : dev.architectury.event.EventResult.pass());
        check(ViewSearchBuilder.builder().addRecipesFor(query).streamDisplays().noneMatch(spec -> spec.provideInternalDisplay() == foreign), "Foreign visibility predicate ignored");
        CategoryRegistry.getInstance().registerVisibilityPredicate(category -> category.getCategoryIdentifier().equals(FOREIGN)
                ? dev.architectury.event.EventResult.interruptFalse() : dev.architectury.event.EventResult.pass());
        check(new SkyblockFallbackGenerator().getRecipeFor(query).orElseThrow().stream()
                .noneMatch(display -> display.getCategoryIdentifier().equals(FOREIGN)), "Foreign category visibility predicate ignored");
    }

    private static void verifyWildcardSuppression(DisplayRegistry registry,
            List<me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator<?>> generators,
            me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator<?> fallback) {
        var vanilla = new ForeignDisplay(new ItemStack(Items.DIAMOND));
        registry.add(vanilla);
        var query = EntryStacks.of(ReiSkyblockProvider.repository().stacks().get("DIAMOND"));
        var isolate = new java.util.concurrent.atomic.AtomicBoolean();
        registry.registerVisibilityPredicate((category, display) -> isolate.get() && display != vanilla
                ? dev.architectury.event.EventResult.interruptFalse() : dev.architectury.event.EventResult.pass());
        generators.remove(fallback);
        try {
            var withInfo = ViewSearchBuilder.builder().addRecipesFor(query).streamDisplays().map(spec -> spec.provideInternalDisplay()).toList();
            check(!withInfo.isEmpty() && !withInfo.contains(vanilla), "Info must suppress REI's vanilla wildcard fallback");
            isolate.set(true);
            check(ViewSearchBuilder.builder().addRecipesFor(query).streamDisplays().anyMatch(spec -> spec.provideInternalDisplay() == vanilla),
                    "REI wildcard fallback did not recover the recipe once other results were hidden");
        } finally {
            isolate.set(false);
            generators.add(fallback);
        }
        check(ViewSearchBuilder.builder().addRecipesFor(query).streamDisplays().anyMatch(spec -> spec.provideInternalDisplay() == vanilla),
                "Identity fallback must preserve vanilla recipes alongside info");
    }

    private static ItemStack live(ItemStack source) {
        var stack = source.copy();
        var tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        tag.remove(SkyblockIds.MARKER);
        tag.remove(SkyblockIds.MARKER + "_id");
        tag.putString("uuid", "live-item-test");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static final CategoryIdentifier<ForeignDisplay> FOREIGN = CategoryIdentifier.of("reisb_test", "foreign_recipe");
    private record ForeignDisplay(ItemStack stack) implements Display {
        @Override public List<EntryIngredient> getInputEntries() { return List.of(EntryIngredient.of(EntryStacks.of(stack))); }
        @Override public List<EntryIngredient> getOutputEntries() { return getInputEntries(); }
        @Override public CategoryIdentifier<?> getCategoryIdentifier() { return FOREIGN; }
        @Override public DisplaySerializer<? extends Display> getSerializer() { return null; }
        @Override public java.util.Optional<net.minecraft.resources.Identifier> getDisplayLocation() { return java.util.Optional.empty(); }
    }
    private static final class ForeignCategory implements DisplayCategory<ForeignDisplay> {
        @Override public CategoryIdentifier<ForeignDisplay> getCategoryIdentifier() { return FOREIGN; }
        @Override public Component getTitle() { return Component.literal("Foreign recipe fixture"); }
        @Override public Renderer getIcon() { return EntryStacks.of(Items.CRAFTING_TABLE); }
        @Override public List<Widget> setupDisplay(ForeignDisplay display, Rectangle bounds) { return List.of(); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
