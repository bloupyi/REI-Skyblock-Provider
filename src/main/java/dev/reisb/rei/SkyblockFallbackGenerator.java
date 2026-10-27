package dev.reisb.rei;

import dev.reisb.ReiSkyblockProvider;
import dev.reisb.item.SkyblockIds;
import java.util.List;
import java.util.Optional;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryStack;
import net.minecraft.world.item.ItemStack;

/**
 * REI skips its wildcard search as soon as ANY display (including item info) exists.
 * Recover registered recipes by SkyBlock identity rather than dropping metadata and
 * matching every item of the same vanilla material. Return the original displays;
 * REI still applies their category filters, visibility predicates and normal merging.
 */
public final class SkyblockFallbackGenerator implements DynamicDisplayGenerator<Display> {
    @Override
    public Optional<List<Display>> getRecipeFor(EntryStack<?> entry) { return find(entry, false); }

    @Override
    public Optional<List<Display>> getUsageFor(EntryStack<?> entry) { return find(entry, true); }

    private Optional<List<Display>> find(EntryStack<?> entry, boolean usage) {
        if (!ReiSkyblockProvider.active() || !(entry.getValue() instanceof ItemStack stack)
                || SkyblockIds.of(stack).isEmpty()) return Optional.empty();
        var source = ReiSkyblockProvider.repository().source();
        String id = SkyblockIds.resolve(stack, source);
        var categories = CategoryRegistry.getInstance();
        var result = DisplayRegistry.getInstance().getAll().entrySet().stream()
                .filter(category -> categories.tryGet(category.getKey())
                        .filter(configuration -> !categories.isCategoryInvisible(configuration.getCategory())).isPresent())
                .flatMap(category -> category.getValue().stream())
                .filter(display -> !(display instanceof SkyblockDisplay))
                .filter(display -> (usage ? display.getInputEntries() : display.getOutputEntries()).stream()
                        .flatMap(java.util.Collection::stream)
                        .anyMatch(candidate -> candidate.getValue() instanceof ItemStack item
                                && matches(id, stack, item, source)))
                .toList();
        ReiDiagnostics.log("Identity fallback: id={}, usages={}, registered displays recovered={}", id, usage, result.size());
        return Optional.of(result);
    }

    static boolean matches(ItemStack query, ItemStack candidate, dev.reisb.data.RepositorySnapshot source) {
        return matches(SkyblockIds.of(query), query, candidate, source);
    }

    private static boolean matches(String id, ItemStack query, ItemStack candidate, dev.reisb.data.RepositorySnapshot source) {
        return !id.isEmpty() && candidate.is(query.getItem()) && id.equals(SkyblockIds.resolve(candidate, source));
    }
}
