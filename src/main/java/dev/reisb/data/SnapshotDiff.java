package dev.reisb.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public record SnapshotDiff(int itemsAdded, int itemsModified, int itemsRemoved,
                           int recipesAdded, int recipesModified, int recipesRemoved) {
    public static final SnapshotDiff EMPTY = new SnapshotDiff(0, 0, 0, 0, 0, 0);

    public static SnapshotDiff between(RepositorySnapshot before, RepositorySnapshot after) {
        int added = 0, changed = 0, removed = 0;
        for (var entry : after.items().entrySet()) {
            var old = before.items().get(entry.getKey());
            if (old == null) added++;
            else if (!old.equals(entry.getValue())) changed++;
        }
        for (var key : before.items().keySet()) if (!after.items().containsKey(key)) removed++;
        var oldGroups = groups(before.recipes());
        var newGroups = groups(after.recipes());
        var keys = new HashSet<>(oldGroups.keySet());
        keys.addAll(newGroups.keySet());
        int recipeAdded = 0, recipeModified = 0, recipeRemoved = 0;
        for (var key : keys) {
            var old = new HashSet<>(oldGroups.getOrDefault(key, List.of()));
            var next = new HashSet<>(newGroups.getOrDefault(key, List.of()));
            var common = new HashSet<>(old);
            common.retainAll(next);
            old.removeAll(common);
            next.removeAll(common);
            int modified = Math.min(old.size(), next.size());
            recipeModified += modified;
            recipeAdded += next.size() - modified;
            recipeRemoved += old.size() - modified;
        }
        return new SnapshotDiff(added, changed, removed, recipeAdded, recipeModified, recipeRemoved);
    }

    private static Map<String, List<SkyblockRecipe>> groups(List<SkyblockRecipe> recipes) {
        var groups = new HashMap<String, List<SkyblockRecipe>>();
        for (var recipe : recipes) groups.computeIfAbsent(recipe.kind() + "\n" + recipe.output().id() + "\n" + recipe.station(),
                key -> new ArrayList<>()).add(recipe);
        return groups;
    }

    public boolean changed() { return !equals(EMPTY); }
    public String summary() {
        return "items +" + itemsAdded + " ~" + itemsModified + " -" + itemsRemoved
                + "; recipes +" + recipesAdded + " ~" + recipesModified + " -" + recipesRemoved;
    }
}
