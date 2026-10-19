package dev.reisb.planning;

import dev.reisb.ReiSkyblockProvider;
import dev.reisb.item.SkyblockIds;
import dev.reisb.util.SkyblockDetector;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;

public final class PlanningService {
    private static PlanningStore store = new PlanningStore();
    private static Path path;
    private static boolean writable = true;
    private static boolean dirty;
    private static boolean saveFailed;
    private static Map<String, BigDecimal> lastInventory = Map.of();
    private static int ticks;
    private static String scope = "";
    private static String profileName = "";
    private static long revision;
    private static dev.reisb.data.RepositorySnapshot indexedRepository;
    private static Map<String, String> resourceNames = Map.of();
    private static Map<String, String> collectionKeys = Map.of();
    private static net.minecraft.client.gui.screens.Screen blockedMenu;
    private PlanningService() {}

    public static void initialize(Path directory) {
        path = directory.resolve("planning-v1.json");
        ProfileApiSync.initialize(directory);
        try { store = PlanningStore.load(path); }
        catch (java.io.IOException error) {
            writable = false;
            ReiSkyblockProvider.LOGGER.warn("Cannot load planning cache; keeping the original file unchanged", error);
        }
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            refreshScope();
            if (!scope.isEmpty()) ObservedMenus.slayer(message.getString()).ifPresent(PlanningService::recordLevel);
        });
    }

    public static PlanningStore.Account account() {
        return store.account(Minecraft.getInstance().getUser().getProfileId().toString());
    }

    public static Map<String, PlanningStore.Observation> sackObservations() {
        return scope.isEmpty() ? Map.of() : Map.copyOf(store.profile(scope).sacks);
    }

    public static Map<String, PlanningStore.Observation> progress() {
        return scope.isEmpty() ? Map.of() : Map.copyOf(store.profile(scope).progress);
    }

    public static String profileName() { return profileName; }
    public static long revision() { return revision; }
    public static boolean persistenceHealthy() { return writable && !saveFailed; }

    public static CraftPlanner.Stock stock(boolean useSacks) {
        var client = Minecraft.getInstance();
        var inventory = new LinkedHashMap<String, BigDecimal>();
        var sacks = new LinkedHashMap<String, BigDecimal>();
        if (client.player != null) {
            for (var stack : client.player.getInventory().getNonEquipmentItems()) {
                if (stack.isEmpty() || SkyblockIds.owned(stack)) continue;
                String id = SkyblockIds.resolve(stack, ReiSkyblockProvider.repository().source());
                if (!id.isEmpty() && !account().excluded.contains(id)) inventory.merge(id, BigDecimal.valueOf(stack.getCount()), BigDecimal::add);
            }
        }
        if (useSacks) sackObservations().forEach((id, observation) -> {
            long age = System.currentTimeMillis() - observation.time();
            if (!account().excluded.contains(id) && age >= -60_000 && age <= 86_400_000) sacks.put(id, observation.amount());
        });
        return new CraftPlanner.Stock(inventory, sacks);
    }

    public static void addGoal(String id, long amount) {
        if (!ReiSkyblockProvider.repository().source().items().containsKey(id) || amount <= 0 || amount > 1_000_000) throw new IllegalArgumentException("Invalid planning goal");
        var goals = account().goals;
        if (!goals.containsKey(id) && goals.size() >= 100) throw new IllegalArgumentException("Too many goals");
        BigDecimal next = goals.getOrDefault(id, BigDecimal.ZERO).add(BigDecimal.valueOf(amount));
        if (next.compareTo(BigDecimal.valueOf(1_000_000)) > 0) throw new IllegalArgumentException("Target quantity exceeds 1000000");
        goals.put(id, next);
        changed();
    }

    public static void removeGoal(String id) { account().goals.remove(id); changed(); }
    public static void choose(String id, int index) { account().choices.put(id, index); account().stopped.remove(id); changed(); }
    public static void toggleStop(String id) { toggle(account().stopped, id); changed(); }
    public static void toggleReserve(String id) { toggle(account().excluded, id); changed(); }

    private static void toggle(java.util.Set<String> values, String id) { if (!values.remove(id)) values.add(id); }

    private static void changed() { revision++; dirty = true; save(); }

    public static void tick() {
        if (++ticks % 5 != 0) return;
        refreshScope();
        ProfileApiSync.tick(scope, profileName);
        var client = Minecraft.getInstance();
        var repository = ReiSkyblockProvider.repository().source();
        if (indexedRepository != repository) {
            resourceNames = ObservedMenus.resourceNames(repository.items().values());
            collectionKeys = resourceNames.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    entry -> "COLLECTION:" + entry.getKey(), entry -> "COLLECTION:" + ObservedMenus.key(entry.getValue())));
            indexedRepository = repository;
            if (!scope.isEmpty() && aliasCollections(store.profile(scope).progress, collectionKeys)) { revision++; dirty = true; }
        }
        if (!scope.isEmpty() && client.gui.screen() != blockedMenu && client.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            String title = screen.getTitle().getString();
            for (var slot : screen.getMenu().slots) {
                if (slot.container == client.player.getInventory()) continue;
                var stack = slot.getItem();
                if (stack.isEmpty() || SkyblockIds.owned(stack)) continue;
                String id = SkyblockIds.resolve(stack, ReiSkyblockProvider.repository().source());
                String name = stack.getHoverName().getString();
                List<String> lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream().map(line -> line.getString()).toList();
                String resource = ObservedMenus.resourceId(name, id, resourceNames);
                if (!resource.isEmpty()) ObservedMenus.stored(title, resource, name, lore).ifPresent(amount -> {
                    var previous = store.profile(scope).sacks.put(resource, new PlanningStore.Observation(amount, System.currentTimeMillis()));
                    if (previous == null || previous.amount().compareTo(amount) != 0) revision++;
                    dirty = true;
                });
                if (ObservedMenus.clean(title).contains("Slayer")) {
                    ObservedMenus.slayer(name).ifPresent(PlanningService::recordLevel);
                    lore.forEach(line -> ObservedMenus.slayer(line).ifPresent(PlanningService::recordLevel));
                }
                ObservedMenus.level(title, name).ifPresent(level -> {
                    recordLevel(level);
                    if (level.key().startsWith("COLLECTION:") && !resource.isEmpty()) recordLevel(new ObservedMenus.Level("COLLECTION:" + ObservedMenus.key(resource), level.value()));
                });
            }
        }
        var inventory = stock(false).inventory();
        if (!inventory.equals(lastInventory)) { lastInventory = inventory; revision++; }
        if (ticks % 100 == 0 && dirty) save();
    }

    private static void refreshScope() {
        var client = Minecraft.getInstance();
        String name = "";
        if (SkyblockDetector.isSkyblock(client)) {
            name = client.getConnection().getOnlinePlayers().stream().map(player -> player.getTabListDisplayName())
                    .filter(java.util.Objects::nonNull).map(line -> ObservedMenus.clean(line.getString()))
                    .filter(line -> line.startsWith("Profile: ")).map(line -> line.substring(9).strip()).findFirst().orElse("");
        }
        profileName = name;
        String next = name.isEmpty() ? "" : client.getUser().getProfileId() + ":" + name;
        boolean changedScope = !next.equals(scope);
        if (changedScope) { blockedMenu = scope.isEmpty() ? null : client.gui.screen(); revision++; }
        scope = next;
        if (changedScope && !scope.isEmpty() && aliasCollections(store.profile(scope).progress, collectionKeys)) dirty = true;
    }

    private static void recordLevel(ObservedMenus.Level level) {
        if (scope.isEmpty()) return;
        var progress = store.profile(scope).progress;
        BigDecimal value = BigDecimal.valueOf(level.value());
        var previous = progress.put(level.key(), new PlanningStore.Observation(value, System.currentTimeMillis()));
        if (previous == null || previous.amount().compareTo(value) != 0 || System.currentTimeMillis() - previous.time() > 86_400_000) revision++;
        dirty = true;
    }

    static void apiChanged() { revision++; }

    public static void applyApi(String requestedScope, ProfileApiData data, long started) {
        if (!scope.equals(requestedScope) || scope.isEmpty()) return;
        var profile = store.profile(scope);
        mergeApi(profile.sacks, data.sacks(), started);
        var levels = new LinkedHashMap<>(data.progress());
        profile.progress.forEach((key, previous) -> {
            if (levels.containsKey(key) && previous.amount().compareTo(levels.get(key)) > 0) levels.remove(key);
        });
        mergeApi(profile.progress, levels, started);
        aliasCollections(profile.progress, collectionKeys);
        changed();
    }

    static boolean aliasCollections(Map<String, PlanningStore.Observation> progress, Map<String, String> aliases) {
        boolean changed = false;
        for (var entry : aliases.entrySet()) {
            var source = progress.get(entry.getValue());
            var previous = progress.get(entry.getKey());
            if (source != null && (previous == null || source.time() > previous.time())) {
                progress.put(entry.getKey(), source);
                changed = true;
            }
        }
        return changed;
    }

    static void mergeApi(Map<String, PlanningStore.Observation> target, Map<String, BigDecimal> values, long started) {
        values.forEach((key, amount) -> {
            var previous = target.get(key);
            if (previous == null || previous.time() < started - 300000)
                target.put(key, new PlanningStore.Observation(amount, started));
        });
    }

    private static void save() {
        if (path == null || !writable) return;
        try { store.save(path); dirty = false; saveFailed = false; }
        catch (java.io.IOException error) { saveFailed = true; ReiSkyblockProvider.LOGGER.warn("Cannot save planning cache", error); }
    }
}
