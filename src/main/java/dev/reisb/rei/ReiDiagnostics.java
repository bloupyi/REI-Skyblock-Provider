package dev.reisb.rei;

import dev.reisb.ReiSkyblockProvider;
import dev.reisb.item.SkyblockIds;
import java.util.List;
import java.util.stream.Collectors;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.comparison.ComparisonContext;
import me.shedaniel.rei.api.common.entry.comparison.ItemComparatorRegistry;
import me.shedaniel.rei.api.common.plugins.PluginManager;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.world.item.ItemStack;

public final class ReiDiagnostics {
    private ReiDiagnostics() {}

    public static boolean enabled() { return ReiSkyblockProvider.config().debugLogging || ReiSkyblockProvider.LOGGER.isDebugEnabled(); }

    public static void log(String message, Object... arguments) {
        if (ReiSkyblockProvider.config().debugLogging) ReiSkyblockProvider.LOGGER.info("[REI debug] " + message, arguments);
        else ReiSkyblockProvider.LOGGER.debug(message, arguments);
    }

    public static String status() {
        return "Skyblocker installed=" + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("skyblocker")
                + "; " + dev.reisb.compat.SkyblockerReiIsolation.status() + "; " + ReiSkyblockProvider.loadingStatus();
    }

    public static void registries() {
        if (!enabled()) return;
        log("REI categories={}; global generators={}; category generators={}; comparators={}; {}",
                CategoryRegistry.getInstance().stream().map(config -> config.getCategory().getCategoryIdentifier()).toList(),
                DisplayRegistry.getInstance().getGlobalDisplayGenerators().stream().map(generator -> generator.getClass().getName()).toList(),
                DisplayRegistry.getInstance().getCategoryDisplayGenerators().entrySet().stream()
                        .collect(Collectors.toMap(java.util.Map.Entry::getKey,
                                entry -> entry.getValue().stream().map(generator -> generator.getClass().getName()).toList())),
                ItemComparatorRegistry.getInstance().comparatorSize(), ReiSkyblockProvider.loadingStatus());
    }

    public static void comparator(ItemStack query, String id) {
        var canonical = ReiSkyblockProvider.repository().stacks().get(id);
        if (canonical == null) return;
        var registry = ItemComparatorRegistry.getInstance();
        long queryHash = registry.hashOf(ComparisonContext.FUZZY, query);
        long canonicalHash = registry.hashOf(ComparisonContext.FUZZY, canonical);
        log("Comparator: id={}, queryHash={}, repositoryHash={}, conflict={} (provider lookup uses the ID directly)",
                id, queryHash, canonicalHash, queryHash != canonicalHash || !query.is(canonical.getItem()));
    }

    /** Explicit end-to-end probe, after all generators, filters and merging have run. */
    public static void inspect(String id) {
        if (PluginManager.areAnyReloading()) {
            ReiSkyblockProvider.LOGGER.info("REI diagnostic postponed: plugins are reloading");
            return;
        }
        var stack = ReiSkyblockProvider.repository().stacks().get(id);
        if (stack == null) {
            ReiSkyblockProvider.LOGGER.info("REI diagnostic: no prepared item for {}; {}", id, ReiSkyblockProvider.loadingStatus());
            return;
        }
        registries();
        inspect(EntryStacks.of(stack), false);
        inspect(EntryStacks.of(stack), true);
    }

    private static void inspect(EntryStack<ItemStack> entry, boolean usage) {
        var generated = (usage ? new SkyblockDisplayGenerator().getUsageFor(entry)
                : new SkyblockDisplayGenerator().getRecipeFor(entry)).orElse(List.of());
        var registry = DisplayRegistry.getInstance();
        var missing = generated.stream().filter(display -> CategoryRegistry.getInstance()
                .tryGet(display.getCategoryIdentifier()).isEmpty()).toList();
        var hidden = generated.stream().filter(display -> !missing.contains(display))
                .filter(display -> !registry.isDisplayVisible(display)).toList();
        var builder = ViewSearchBuilder.builder();
        if (usage) builder.addUsagesFor(entry); else builder.addRecipesFor(entry);
        var displayed = builder.streamDisplays().map(spec -> spec.provideInternalDisplay()).toList();
        var lost = generated.stream().filter(display -> !displayed.contains(display)).toList();
        ReiSkyblockProvider.LOGGER.info("REI diagnostic: id={}, usages={}, generated={}, missing category={}, hidden={}, "
                        + "final provider={}, final categories={}, lost={}; {}",
                SkyblockIds.of(entry.getValue()), usage, generated.size(), missing.size(), hidden.size(),
                displayed.stream().filter(SkyblockDisplay.class::isInstance).count(),
                displayed.stream().map(display -> display.getCategoryIdentifier().toString()).distinct().toList(),
                lost.stream().map(SkyblockDisplay::recipe).toList(), ReiSkyblockProvider.loadingStatus());
    }
}
