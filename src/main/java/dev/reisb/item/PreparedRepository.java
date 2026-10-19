package dev.reisb.item;

import dev.reisb.data.Ingredient;
import dev.reisb.data.RepositorySnapshot;
import dev.reisb.data.SkyblockRecipe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;

public record PreparedRepository(RepositorySnapshot source, Map<String, ItemStack> stacks,
                                  Map<String, List<SkyblockRecipe>> recipes, Map<String, List<SkyblockRecipe>> usages) {
    public static final PreparedRepository EMPTY = new PreparedRepository(RepositorySnapshot.EMPTY, Map.of(), Map.of(), Map.of());

    public static PreparedRepository prepare(RepositorySnapshot source, Logger logger) {
        return prepare(source, logger, false);
    }

    public static PreparedRepository prepare(RepositorySnapshot source, Logger logger, boolean strict) {
        var factory = new ItemStackFactory(logger);
        var stacks = new HashMap<String, ItemStack>();
        source.items().values().forEach(item -> stacks.put(item.id(), factory.create(item, strict)));
        var recipes = new HashMap<String, List<SkyblockRecipe>>();
        var usages = new HashMap<String, List<SkyblockRecipe>>();
        for (var recipe : source.recipes()) {
            recipes.computeIfAbsent(recipe.output().id(), key -> new ArrayList<>()).add(recipe);
            recipe.inputs().stream().filter(i -> !i.empty()).map(Ingredient::id).distinct()
                    .forEach(id -> usages.computeIfAbsent(id, key -> new ArrayList<>()).add(recipe));
        }
        recipes.replaceAll((key, values) -> List.copyOf(values));
        usages.replaceAll((key, values) -> List.copyOf(values));
        return new PreparedRepository(source, Map.copyOf(stacks), Map.copyOf(recipes), Map.copyOf(usages));
    }

    public ItemStack stack(Ingredient ingredient) {
        if (ingredient.empty()) return ItemStack.EMPTY;
        ItemStack stack = stacks.get(ingredient.id());
        if (ingredient.id().equals("SKYBLOCK_COIN")) {
            stack = new ItemStack(Items.GOLD_NUGGET);
            var tag = new CompoundTag();
            tag.putString(SkyblockIds.MARKER + "_id", "SKYBLOCK_COIN");
            tag.putBoolean(SkyblockIds.MARKER, true);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            stack.set(DataComponents.CUSTOM_NAME, LegacyText.parse("§6" + dev.reisb.rei.DisplayText.coins(ingredient.amount())));
        }
        if (stack == null) return ItemStack.EMPTY;
        ItemStack copy = stack.copy();
        copy.setCount(ingredient.id().equals("SKYBLOCK_COIN") ? 1 : (int) Math.min(Integer.MAX_VALUE, ingredient.count()));
        return copy;
    }
}
