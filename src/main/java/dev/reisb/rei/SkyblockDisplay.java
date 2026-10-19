package dev.reisb.rei;

import dev.reisb.ReiSkyblockProvider;
import dev.reisb.data.Ingredient;
import dev.reisb.data.SkyblockRecipe;
import dev.reisb.item.PreparedRepository;
import java.util.List;
import java.util.Locale;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.world.item.ItemStack;

public final class SkyblockDisplay implements Display {
    private final SkyblockRecipe recipe;
    private final PreparedRepository repository;

    public SkyblockDisplay(SkyblockRecipe recipe, PreparedRepository repository) {
        this.recipe = recipe;
        this.repository = repository;
    }

    public static CategoryIdentifier<SkyblockDisplay> category(SkyblockRecipe.Kind kind) {
        return CategoryIdentifier.of(ReiSkyblockProvider.ID, kind.name().toLowerCase(Locale.ROOT));
    }

    public EntryStack<ItemStack> entry(Ingredient ingredient) {
        return EntryStacks.of(repository.stack(ingredient));
    }

    public SkyblockRecipe recipe() { return recipe; }
    public PreparedRepository repository() { return repository; }

    @Override
    public boolean equals(Object other) { return other instanceof SkyblockDisplay display && recipe.equals(display.recipe); }

    @Override
    public int hashCode() { return recipe.hashCode(); }

    @Override
    public List<EntryIngredient> getInputEntries() {
        return recipe.inputs().stream().map(this::entry).map(EntryIngredient::of).toList();
    }

    @Override
    public List<EntryIngredient> getOutputEntries() { return List.of(EntryIngredient.of(entry(recipe.output()))); }

    @Override
    public CategoryIdentifier<?> getCategoryIdentifier() { return category(recipe.kind()); }

    @Override
    public DisplaySerializer<? extends Display> getSerializer() { return null; }

    @Override
    public java.util.Optional<net.minecraft.resources.Identifier> getDisplayLocation() { return java.util.Optional.empty(); }
}
