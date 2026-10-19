package dev.reisb.planning;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class PlanningStore {
    public record Observation(BigDecimal amount, long time) {}
    public static final class Account {
        public final Map<String, BigDecimal> goals = new LinkedHashMap<>();
        public final Map<String, Integer> choices = new LinkedHashMap<>();
        public final Set<String> stopped = new LinkedHashSet<>();
        public final Set<String> excluded = new LinkedHashSet<>();
    }
    public static final class Profile {
        public final Map<String, Observation> sacks = new LinkedHashMap<>();
        public final Map<String, Observation> progress = new LinkedHashMap<>();
    }
    private static final Gson GSON = new Gson();
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, Profile> profiles = new LinkedHashMap<>();

    public Account account(String id) {
        if (!accounts.containsKey(id) && accounts.size() >= 32) accounts.remove(accounts.keySet().iterator().next());
        return accounts.computeIfAbsent(id, key -> new Account());
    }
    public Profile profile(String id) {
        if (!profiles.containsKey(id) && profiles.size() >= 128) profiles.remove(profiles.keySet().iterator().next());
        return profiles.computeIfAbsent(id, key -> new Profile());
    }

    public static PlanningStore load(Path path) throws IOException {
        var store = new PlanningStore();
        if (!Files.exists(path)) return store;
        if (Files.size(path) > 2_097_152) throw new IOException("Planning cache exceeds size limit");
        try {
            var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) throw new IOException("Unsupported planning cache version");
            var saved = GSON.fromJson(root.get("state"), PlanningStore.class);
            if (saved == null || saved.accounts.size() > 32 || saved.profiles.size() > 128) throw new IOException("Invalid planning cache");
            saved.accounts.values().forEach(account -> {
                if (account.goals.size() > 100 || account.choices.size() > 10000 || account.stopped.size() > 10000 || account.excluded.size() > 10000) throw new IllegalArgumentException("Too many planning entries");
                account.choices.forEach((id, choice) -> {
                    new dev.reisb.data.Ingredient(id, 1);
                    if (choice == null || choice < 0 || choice > 10000) throw new IllegalArgumentException("Invalid recipe choice");
                });
                java.util.stream.Stream.concat(account.stopped.stream(), account.excluded.stream()).forEach(id -> new dev.reisb.data.Ingredient(id, 1));
                account.goals.forEach((id, quantity) -> {
                    new dev.reisb.data.Ingredient(id, quantity);
                    if (quantity.signum() <= 0 || quantity.compareTo(BigDecimal.valueOf(1_000_000)) > 0) throw new IllegalArgumentException("Invalid goal");
                });
            });
            saved.profiles.values().forEach(profile -> {
                if (profile.sacks.size() > 10000 || profile.progress.size() > 10000) throw new IllegalArgumentException("Too many observations");
                java.util.stream.Stream.concat(profile.sacks.values().stream(), profile.progress.values().stream()).forEach(value -> {
                    if (value.amount == null || value.amount.signum() < 0 || value.amount.precision() > 20 || value.time < 0) throw new IllegalArgumentException("Invalid observation");
                });
            });
            return saved;
        } catch (RuntimeException error) { throw new IOException("Invalid planning cache", error); }
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        var root = new com.google.gson.JsonObject();
        root.addProperty("version", 1);
        root.add("state", GSON.toJsonTree(this));
        String json = GSON.toJson(root);
        if (json.length() > 2_097_152) throw new IOException("Planning cache exceeds size limit");
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, json);
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
    }
}
