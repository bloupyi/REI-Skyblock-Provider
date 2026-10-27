package dev.reisb.rei;

import dev.reisb.ReiSkyblockProvider;
import dev.reisb.data.Ingredient;
import dev.reisb.data.SkyblockRecipe;
import dev.reisb.item.SkyblockIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.common.entry.EntryStack;
import net.minecraft.world.item.ItemStack;

public final class SkyblockDisplayGenerator implements DynamicDisplayGenerator<SkyblockDisplay> {
    @Override
    public Optional<List<SkyblockDisplay>> generate(me.shedaniel.rei.api.client.view.ViewSearchBuilder builder) {
        preferSkyblockMethod(builder);
        if (!ReiSkyblockProvider.active() || builder.getCategories().isEmpty()
                || !builder.getRecipesFor().isEmpty() || !builder.getUsagesFor().isEmpty()) return Optional.empty();
        var repository = ReiSkyblockProvider.repository();
        return Optional.of(repository.source().recipes().stream()
                .filter(r -> ReiSkyblockProvider.enabled(r.kind()) && builder.getCategories().contains(SkyblockDisplay.category(r.kind())))
                .distinct()
                .map(r -> new SkyblockDisplay(r, repository)).toList());
    }

    private static void preferSkyblockMethod(me.shedaniel.rei.api.client.view.ViewSearchBuilder builder) {
        if (!ReiSkyblockProvider.active() || !builder.getCategories().isEmpty() || !builder.getFilteringCategories().isEmpty()) return;
        var repository = ReiSkyblockProvider.repository();
        boolean usage = builder.getRecipesFor().isEmpty();
        for (var entry : usage ? builder.getUsagesFor() : builder.getRecipesFor()) {
            if (!(entry.getValue() instanceof ItemStack stack)) continue;
            String id = SkyblockIds.resolve(stack, repository.source());
            if (id.isEmpty()) continue;
            var recipes = (usage ? repository.usages() : repository.recipes()).getOrDefault(id, List.of());
            for (var kind : new SkyblockRecipe.Kind[]{SkyblockRecipe.Kind.CRAFTING, SkyblockRecipe.Kind.FORGE,
                    SkyblockRecipe.Kind.KAT, SkyblockRecipe.Kind.NPC, SkyblockRecipe.Kind.DROPS}) {
                if (ReiSkyblockProvider.enabled(kind) && recipes.stream().anyMatch(recipe -> recipe.kind() == kind)) {
                    builder.setPreferredOpenedCategory(SkyblockDisplay.category(kind));
                    return;
                }
            }
        }
    }

    @Override
    public Optional<List<SkyblockDisplay>> getRecipeFor(EntryStack<?> entry) { return find(entry, false); }

    @Override
    public Optional<List<SkyblockDisplay>> getUsageFor(EntryStack<?> entry) { return find(entry, true); }

    private Optional<List<SkyblockDisplay>> find(EntryStack<?> entry, boolean usage) {
        if (!(entry.getValue() instanceof ItemStack stack)) return Optional.empty();
        var repository = ReiSkyblockProvider.repository();
        String id = SkyblockIds.resolve(stack, repository.source());
        if (ReiDiagnostics.enabled()) ReiDiagnostics.log("Lookup: id={}, rawId={}, usages={}, active={}, recipes={}, usagesFound={}; {}", id, SkyblockIds.of(stack), usage,
                ReiSkyblockProvider.active(), repository.recipes().getOrDefault(id, List.of()).size(),
                repository.usages().getOrDefault(id, List.of()).size(), ReiSkyblockProvider.loadingStatus());
        if (!ReiSkyblockProvider.active()) return Optional.empty();
        if (id.isEmpty() || (!id.equals("SKYBLOCK_COIN") && !repository.stacks().containsKey(id))) return Optional.empty();
        var recipes = (usage ? repository.usages() : repository.recipes()).getOrDefault(id, List.of());
        var result = new ArrayList<>(recipes.stream().distinct().filter(r -> ReiSkyblockProvider.enabled(r.kind()))
                .map(r -> new SkyblockDisplay(r, repository)).toList());
        if (!usage && repository.source().items().containsKey(id) && ReiSkyblockProvider.enabled(SkyblockRecipe.Kind.INFO)) {
            var info = new SkyblockRecipe(SkyblockRecipe.Kind.INFO, List.of(), new Ingredient(id, 1), 0, "", "");
            result.add(new SkyblockDisplay(info, repository));
        }
        if (ReiDiagnostics.enabled()) ReiDiagnostics.log("Generated: id={}, usages={}, indexed={}, emitted={}, categories={}", id, usage, recipes.size(), result.size(),
                result.stream().map(SkyblockDisplay::getCategoryIdentifier).distinct().toList());
        if (ReiSkyblockProvider.config().debugLogging) ReiDiagnostics.comparator(stack, id);
        return Optional.of(result);
    }
}
