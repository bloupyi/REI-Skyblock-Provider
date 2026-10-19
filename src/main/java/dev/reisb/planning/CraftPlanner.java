package dev.reisb.planning;

import dev.reisb.data.Ingredient;
import dev.reisb.data.SkyblockRecipe;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CraftPlanner {
    public record Stock(Map<String, BigDecimal> inventory, Map<String, BigDecimal> sacks) {
        public Stock { inventory = Map.copyOf(inventory); sacks = Map.copyOf(sacks); }
        public static final Stock EMPTY = new Stock(Map.of(), Map.of());
    }
    public record Node(String id, BigDecimal needed, BigDecimal inventory, BigDecimal sacks,
                       BigDecimal reused, BigDecimal batches, BigDecimal surplus,
                       SkyblockRecipe recipe, String reason, List<Node> children) {}
    public record Material(String id, BigDecimal needed, BigDecimal inventory, BigDecimal sacks, BigDecimal missing) {}
    public record Result(List<Node> roots, List<Material> materials, List<String> warnings) {}
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final Map<String, List<SkyblockRecipe>> recipes = new LinkedHashMap<>();

    public CraftPlanner(Iterable<SkyblockRecipe> source) {
        for (var recipe : source) {
            if (recipe.kind() != SkyblockRecipe.Kind.DROPS && recipe.kind() != SkyblockRecipe.Kind.INFO) {
                recipes.computeIfAbsent(recipe.output().id(), key -> new ArrayList<>()).add(recipe);
            }
        }
        recipes.values().forEach(values -> values.sort(java.util.Comparator.comparing(SkyblockRecipe::kind)));
    }

    public List<SkyblockRecipe> methods(String id) { return List.copyOf(recipes.getOrDefault(id, List.of())); }

    public Result plan(Map<String, BigDecimal> goals, Stock stock, Map<String, Integer> choices, Set<String> stopped) {
        var context = new Context(stock, choices, stopped);
        var roots = new ArrayList<Node>();
        goals.forEach((id, amount) -> {
            if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0 || amount.precision() > 20) {
                throw new IllegalArgumentException("Invalid target quantity");
            }
            roots.add(context.expand(id, amount, new LinkedHashSet<>()));
        });
        return new Result(List.copyOf(roots), context.materials.values().stream()
                .sorted(java.util.Comparator.comparing(Material::id)).toList(), List.copyOf(context.warnings));
    }

    private final class Context {
        private final Map<String, BigDecimal> inventory;
        private final Map<String, BigDecimal> sacks;
        private final Map<String, BigDecimal> surplus = new LinkedHashMap<>();
        private final Map<String, Material> materials = new LinkedHashMap<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private final Map<String, Integer> choices;
        private final Set<String> stopped;
        private int nodes;

        private Context(Stock stock, Map<String, Integer> choices, Set<String> stopped) {
            inventory = new LinkedHashMap<>(stock.inventory());
            sacks = new LinkedHashMap<>(stock.sacks());
            this.choices = choices;
            this.stopped = stopped;
        }

        private boolean returnsTo(String root, SkyblockRecipe recipe, BigDecimal required, Set<String> visited) {
            if (visited.size() > 48 || required.precision() > 100) return false;
            BigDecimal batches = required.divide(recipe.output().amount(), 0, RoundingMode.CEILING);
            var inputs = new LinkedHashMap<String, BigDecimal>();
            for (var input : recipe.inputs()) if (!input.empty()) inputs.merge(input.id(), input.amount().multiply(batches), BigDecimal::add);
            for (var input : inputs.entrySet()) {
                String id = input.getKey();
                BigDecimal available = inventory.getOrDefault(id, ZERO).add(sacks.getOrDefault(id, ZERO)).add(surplus.getOrDefault(id, ZERO));
                BigDecimal missing = input.getValue().subtract(available);
                if (missing.signum() <= 0 || stopped.contains(id)) continue;
                if (id.equals(root)) return true;
                if (visited.contains(id)) continue;
                var methods = methods(id);
                if (methods.isEmpty()) continue;
                int index = choices.getOrDefault(id, 0);
                if (index < 0 || index >= methods.size()) index = 0;
                var next = methods.get(index);
                if (next.kind() == SkyblockRecipe.Kind.NPC && !choices.containsKey(id)) continue;
                visited.add(id);
                boolean cycle = returnsTo(root, next, missing, visited);
                visited.remove(id);
                if (cycle) return true;
            }
            return false;
        }

        private Node expand(String id, BigDecimal needed, Set<String> path) {
            BigDecimal inv = take(inventory, id, needed);
            BigDecimal sack = take(sacks, id, needed.subtract(inv));
            BigDecimal reused = take(surplus, id, needed.subtract(inv).subtract(sack));
            BigDecimal remaining = needed.subtract(inv).subtract(sack).subtract(reused);
            String reason = "";
            SkyblockRecipe recipe = null;
            var methods = methods(id);
            if (remaining.signum() == 0) reason = "stock";
            else if (stopped.contains(id)) reason = "stopped";
            else if (path.contains(id)) reason = "cycle";
            else if (++nodes > 4096 || path.size() >= 48 || needed.precision() > 100) reason = "limit";
            else if (methods.isEmpty()) reason = "source";
            else {
                int choice = choices.getOrDefault(id, 0);
                if (choice < 0 || choice >= methods.size()) choice = 0;
                recipe = methods.get(choice);
                if (recipe.kind() == SkyblockRecipe.Kind.NPC && !choices.containsKey(id)) { recipe = null; reason = "source"; }
            }
            if (recipe != null && returnsTo(id, recipe, remaining, new LinkedHashSet<>(Set.of(id)))) {
                recipe = null;
                reason = "cycle";
            }
            if (recipe == null) {
                if (reason.equals("cycle") || reason.equals("limit")) warnings.add(reason + ": " + id);
                var previous = materials.getOrDefault(id, new Material(id, ZERO, ZERO, ZERO, ZERO));
                if (needed.compareTo(reused) > 0) materials.put(id, new Material(id, previous.needed.add(needed.subtract(reused)),
                        previous.inventory.add(inv), previous.sacks.add(sack), previous.missing.add(remaining)));
                return new Node(id, needed, inv, sack, reused, ZERO, ZERO, null, reason, List.of());
            }
            BigDecimal batches = remaining.divide(recipe.output().amount(), 0, RoundingMode.CEILING);
            BigDecimal extra = batches.multiply(recipe.output().amount()).subtract(remaining);
            var inputs = new LinkedHashMap<String, BigDecimal>();
            for (Ingredient input : recipe.inputs()) if (!input.empty()) inputs.merge(input.id(), input.amount().multiply(batches), BigDecimal::add);
            path.add(id);
            var children = new ArrayList<Node>();
            for (var input : inputs.entrySet()) children.add(expand(input.getKey(), input.getValue(), path));
            path.remove(id);
            if (extra.signum() > 0) surplus.merge(id, extra, BigDecimal::add);
            return new Node(id, needed, inv, sack, reused, batches, extra, recipe, "craft", List.copyOf(children));
        }
    }

    private static BigDecimal take(Map<String, BigDecimal> stock, String id, BigDecimal requested) {
        BigDecimal amount = stock.getOrDefault(id, ZERO).max(ZERO).min(requested);
        stock.put(id, stock.getOrDefault(id, ZERO).subtract(amount));
        return amount;
    }
}
