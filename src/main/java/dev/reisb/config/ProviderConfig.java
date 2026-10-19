package dev.reisb.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;

public final class ProviderConfig {
    public boolean enabled = true;
    public boolean onlyOnHypixelSkyblock = true;
    public boolean enableSkyblockItems = true;
    public boolean enableCraftingRecipes = true;
    public boolean enableForgeRecipes = true;
    public boolean enableKatRecipes = true;
    public boolean enableNpcRecipes = true;
    public boolean enableItemInfo = true;
    public boolean autoUpdateRepository = true;
    public boolean checkUpdatesOnStartup = true;
    public boolean checkUpdatesOnSkyblockJoin = true;
    public int updateIntervalMinutes = 30;
    public boolean updateOnRepositoryChange = true;
    public boolean useCachedDataOffline = true;
    public boolean notifyWhenItemsUpdated = true;
    public boolean enableAutomaticReiRefresh = true;
    public boolean enableProfileApi = true;
    public String profileApiUrl = "https://ursa.notenoughupdates.org/v1/hypixel/v2/profiles/{uuid}";
    public String profileApiAuthHeader = "";
    public boolean debugLogging = false;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int intervalMinutes() { return Math.max(1, Math.min(10080, updateIntervalMinutes)); }

    public static ProviderConfig load(Path path, Logger logger) {
        try {
            if (Files.exists(path)) {
                com.google.gson.JsonObject json;
                try (var reader = Files.newBufferedReader(path)) {
                    json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                }
                var config = GSON.fromJson(json, ProviderConfig.class);
                if (config == null) throw new IOException("Empty configuration");
                boolean missing = false;
                for (var entry : GSON.toJsonTree(config).getAsJsonObject().entrySet()) {
                    if (!json.has(entry.getKey())) { json.add(entry.getKey(), entry.getValue()); missing = true; }
                }
                if (missing) {
                    Path temporary = null;
                    try {
                        temporary = Files.createTempFile(path.getParent(), "reisb-config-", ".tmp");
                        Files.writeString(temporary, GSON.toJson(json));
                        Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) { logger.warn("Cannot save new configuration defaults; retaining loaded settings", e); }
                    finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
                }
                return config;
            }
            Files.createDirectories(path.getParent());
            var config = new ProviderConfig();
            Files.writeString(path, GSON.toJson(config));
            return config;
        } catch (Exception e) {
            logger.warn("Cannot read configuration {}; using defaults", path, e);
            return new ProviderConfig();
        }
    }
}
