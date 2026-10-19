package dev.reisb.planning;

import dev.reisb.ReiSkyblockProvider;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

public final class ProfileApiSync {
    private static final long FRESH = 300000;
    private static final LinkedHashMap<String, Long> updated = new LinkedHashMap<>();
    private static Path directory;
    private static Object connection;
    private static String target = "";
    private static String settings = "";
    private static long generation;
    private static long nextAttempt;
    private static long lastAttempt;
    private static int attempts;
    private static boolean wanted;
    private static String status = "waiting";
    private static CompletableFuture<Outcome> pending;
    private record Outcome(long generation, String scope, long started, ProfileApiData data, String error, long retry) {}
    private ProfileApiSync() {}

    public static void initialize(Path path) { directory = path; }
    public static String status() { return status; }
    public static void refresh() { generation++; wanted = true; attempts = 0; }

    public static void tick(String scope, String profile) {
        var client = Minecraft.getInstance();
        var config = ReiSkyblockProvider.config();
        String currentSettings = config.enableProfileApi + ":" + config.profileApiUrl + ":" + config.profileApiAuthHeader;
        if (connection != client.getConnection() || !target.equals(scope) || !settings.equals(currentSettings)) {
            settings = currentSettings;
            connection = client.getConnection(); target = scope; generation++;
            wanted = !scope.isEmpty(); attempts = 0; nextAttempt = 0; status = "waiting";
            if (System.currentTimeMillis() - updated.getOrDefault(scope, 0L) < FRESH) { wanted = false; status = "ready"; }
        }
        if (pending != null && pending.isDone()) {
            var outcome = pending.join();
            pending = null;
            if (config.enableProfileApi && config.enabled && outcome.generation == generation && outcome.scope.equals(scope) && !scope.isEmpty()) {
                if (outcome.data != null) {
                    PlanningService.applyApi(outcome.scope, outcome.data, outcome.started);
                    updated.put(scope, System.currentTimeMillis());
                    while (updated.size() > 32) updated.remove(updated.keySet().iterator().next());
                    wanted = false;
                    status = outcome.data.sacks().isEmpty() && outcome.data.progress().isEmpty() ? "no_data" : "ready";
                } else {
                    status = outcome.error;
                    nextAttempt = System.currentTimeMillis() + outcome.retry;
                    if (attempts >= 3 || java.util.Set.of("missing_key", "configuration", "forbidden").contains(status)) wanted = false;
                    ReiSkyblockProvider.LOGGER.warn("Profile API sync unavailable ({})", status);
                }
                PlanningService.apiChanged();
            }
        }
        if (!config.enableProfileApi || !config.enabled) { status = "disabled"; return; }
        long now = System.currentTimeMillis();
        if (directory == null || !wanted || scope.isEmpty() || client.player == null || pending != null || now < nextAttempt || now - lastAttempt < 60000) return;
        long requestGeneration = generation;
        var user = client.getUser();
        String uuid = user.getProfileId().toString();
        var session = client.services().sessionService();
        var identity = new UrsaAuthentication.Identity(uuid, user.getName(),
                serverId -> session.joinServer(user.getProfileId(), user.getAccessToken(), serverId));
        String endpoint = config.profileApiUrl;
        String header = config.profileApiAuthHeader;
        lastAttempt = now;
        int attempt = ++attempts;
        status = "loading";
        PlanningService.apiChanged();
        pending = CompletableFuture.supplyAsync(() -> {
            try { return new Outcome(requestGeneration, scope, now, ProfileApiClient.fetch(directory, endpoint, header, uuid, profile, identity), "", 0); }
            catch (ProfileApiClient.Failure error) { return new Outcome(requestGeneration, scope, now, null, error.status, error.retryMillis); }
            catch (Exception error) { return new Outcome(requestGeneration, scope, now, null, "error", 60000L * attempt); }
        }, task -> Thread.ofVirtual().name("reisb-profile-api").start(task));
    }
}
