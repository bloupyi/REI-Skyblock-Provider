package dev.reisb.data;

import java.util.List;
import java.util.Map;

public record RepositorySnapshot(Map<String, SkyblockItem> items, List<SkyblockRecipe> recipes,
                                  List<String> warnings, Map<String, Integer> unsupported) {
    public static final RepositorySnapshot EMPTY = new RepositorySnapshot(Map.of(), List.of(), List.of(), Map.of());

    public RepositorySnapshot {
        items = Map.copyOf(items);
        recipes = List.copyOf(recipes);
        warnings = List.copyOf(warnings);
        unsupported = Map.copyOf(unsupported);
    }
}
