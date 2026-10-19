package dev.reisb.data;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.reisb.config.ProviderConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.slf4j.Logger;
import static dev.reisb.data.SyncSchedule.Trigger.*;

public final class RepositoryService {
    public static final URI SOURCE = URI.create("https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/archive/refs/heads/master.zip");
    public record Endpoints(URI githubRef, String archiveTemplate, URI hypixel) {
        public static final Endpoints PUBLIC = new Endpoints(
                URI.create("https://api.github.com/repos/NotEnoughUpdates/NotEnoughUpdates-REPO/git/ref/heads/master"),
                "https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/archive/%s.zip",
                URI.create("https://api.hypixel.net/v2/resources/skyblock/items"));
    }
    @FunctionalInterface
    public interface Stager {
        CompletableFuture<Runnable> stage(RepositorySnapshot data, SyncMetadata metadata, boolean forceRefresh, boolean notifyUpdate);
    }
    private record Response(int code, HttpHeaders headers, Path body) {}
    private record TransportState(long lastChecked, SourceHealth.State github, SourceHealth.State hypixel) {}
    private static final Gson GSON = new Gson();
    private final CacheStore cache;
    private final Logger logger;
    private final Stager stager;
    private final Endpoints endpoints;
    private final Clock clock;
    private final SyncSchedule schedule;
    private final SourceHealth github = new SourceHealth();
    private final SourceHealth hypixel = new SourceHealth();
    private volatile CacheStore.Bundle bundle;
    private volatile RepositorySnapshot snapshot = RepositorySnapshot.EMPTY;
    private volatile String status = "Not loaded";
    private volatile long lastChecked;
    private volatile boolean loading;
    private CompletableFuture<Void> running = CompletableFuture.completedFuture(null);
    private volatile ProviderConfig queuedConfig;
    private volatile SyncSchedule.Trigger queuedTrigger;

    public RepositoryService(Path directory, Logger logger, Consumer<RepositorySnapshot> publish) {
        this(directory, logger, (data, metadata, force, notify) -> CompletableFuture.completedFuture(() -> publish.accept(data)), Endpoints.PUBLIC, Clock.systemUTC());
    }

    public RepositoryService(Path directory, Logger logger, Stager stager) {
        this(directory, logger, stager, Endpoints.PUBLIC, Clock.systemUTC());
    }

    public RepositoryService(Path directory, Logger logger, Stager stager, Endpoints endpoints, Clock clock) {
        this.cache = new CacheStore(directory, logger);
        this.logger = logger;
        this.stager = stager;
        this.endpoints = endpoints;
        this.clock = clock;
        this.schedule = new SyncSchedule(clock.millis());
        restoreTransport();
    }

    public CompletableFuture<Void> reload(ProviderConfig config) { return request(config, RELOAD); }

    public synchronized CompletableFuture<Void> request(ProviderConfig config, SyncSchedule.Trigger trigger) {
        if (loading) {
            if (trigger == MANUAL || trigger == RELOAD) { queuedConfig = config; queuedTrigger = trigger; }
            return running;
        }
        boolean retry = github.failures() > 0 && github.available(clock.millis()) || hypixel.failures() > 0 && hypixel.available(clock.millis());
        if (trigger == PERIODIC && (!config.autoUpdateRepository || (!retry && !schedule.due(config, trigger, clock.millis())))) return running;
        loading = true;
        running = CompletableFuture.runAsync(() -> {
            ProviderConfig nextConfig = config;
            SyncSchedule.Trigger nextTrigger = trigger;
            while (true) {
                try { synchronize(nextConfig, nextTrigger); }
                catch (Exception e) {
                    logger.warn("Synchronization failed; retaining last valid data", e);
                    status = "Synchronization failed; last valid data retained: " + rootMessage(e);
                } finally { saveTransport(); }
                synchronized (this) {
                    if (queuedTrigger == null) { loading = false; return; }
                    nextConfig = queuedConfig; nextTrigger = queuedTrigger;
                    queuedConfig = null; queuedTrigger = null;
                }
            }
        }, action -> Thread.ofVirtual().name("reisb-sync").start(action));
        return running;
    }

    private void synchronize(ProviderConfig config, SyncSchedule.Trigger trigger) throws Exception {
        Files.createDirectories(cache.directory());
        boolean forced = trigger == MANUAL || trigger == RELOAD;
        boolean checkedCache = bundle == null || trigger == RELOAD;
        if (checkedCache) {
            var loaded = cache.load();
            if (loaded != null) {
                if (config.useCachedDataOffline) {
                    if (!activateCached(loaded, forced)) {
                        var previous = cache.previous();
                        if (previous != null && !previous.equals(loaded)) activateCached(previous, forced);
                    }
                } else bundle = loaded;
            }
        }
        boolean retry = github.failures() > 0 && github.available(clock.millis()) || hypixel.failures() > 0 && hypixel.available(clock.millis());
        if (!schedule.due(config, trigger, clock.millis()) && !(trigger == PERIODIC && config.autoUpdateRepository && retry)) {
            status = snapshot.items().isEmpty() ? "No local cache loaded" : "Ready (local cache)";
            return;
        }
        long now = clock.millis();
        boolean retryOnly = trigger == PERIODIC && !schedule.due(config, trigger, now);
        schedule.checked(now);
        var old = bundle == null ? new CacheStore.Bundle(SyncMetadata.EMPTY, RepositorySnapshot.EMPTY, "") : bundle;
        var metadata = old.metadata();
        RepositorySnapshot neu = old.neu();
        String official = old.hypixelJson();
        String commit = metadata.neuCommit(), neuDigest = metadata.neuDigest();
        String githubEtag = metadata.githubEtag(), githubModified = metadata.githubModified();
        String hypixelEtag = metadata.hypixelEtag(), hypixelModified = metadata.hypixelModified();
        String officialDigest = metadata.hypixelDigest();
        boolean changedNeu = false, changedOfficial = false, contacted = false, updateAvailable = false;
        boolean applyChanges = config.updateOnRepositoryChange || forced || snapshot.items().isEmpty();
        status = "Checking data sources";
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()) {
            if ((!retryOnly || github.failures() > 0) && github.available(now, trigger == MANUAL)) {
                contacted = true; lastChecked = now;
                try {
                    Response ref = download(client, endpoints.githubRef(), githubEtag, githubModified, 1024 * 1024);
                    try {
                        String remoteCommit = commit;
                        if (ref.code() != 304) {
                            var object = JsonParser.parseString(Files.readString(ref.body())).getAsJsonObject().getAsJsonObject("object");
                            remoteCommit = object.get("sha").getAsString();
                            if (!remoteCommit.matches("[a-f0-9]{40}|[a-f0-9]{64}")) throw new IOException("Invalid GitHub commit");
                        }
                        if (remoteCommit.isEmpty()) throw new IOException("GitHub returned 304 without a cached revision");
                        if (!remoteCommit.equals(commit) || neu.items().isEmpty()) {
                            updateAvailable = true;
                            if (applyChanges) {
                                status = "Downloading NEU " + remoteCommit;
                                Response archive = download(client, URI.create(endpoints.archiveTemplate().formatted(remoteCommit)), "", "", 128 * 1024 * 1024);
                                try {
                                    var candidate = new RepositoryParser().parse(archive.body(), true);
                                    if (!candidate.warnings().isEmpty() || !candidate.unsupported().isEmpty()) {
                                        candidate.warnings().stream().limit(config.debugLogging ? Long.MAX_VALUE : 30).forEach(logger::warn);
                                        throw new IOException("Incompatible NEU data: " + candidate.warnings().size() + " warnings; " + candidate.unsupported());
                                    }
                                    neu = candidate;
                                    neuDigest = CacheStore.digest(archive.body());
                                    commit = remoteCommit;
                                    changedNeu = true;
                                } finally { Files.deleteIfExists(archive.body()); }
                            }
                        }
                        if (remoteCommit.equals(commit)) {
                            githubEtag = header(ref, "ETag", githubEtag);
                            githubModified = header(ref, "Last-Modified", githubModified);
                        }
                        github.success(remoteCommit.equals(commit) ? "Online: " + commit : "Update available: " + remoteCommit, now, ref.headers());
                    } finally { Files.deleteIfExists(ref.body()); }
                } catch (Exception e) { failed(github, "GitHub/NEU", e, now); }
            }
            if ((!retryOnly || hypixel.failures() > 0) && hypixel.available(now, trigger == MANUAL)) {
                contacted = true; lastChecked = now;
                try {
                    Response response = download(client, endpoints.hypixel(), hypixelEtag, hypixelModified, 32 * 1024 * 1024);
                    try {
                        if (response.code() == 304) {
                            if (official.isEmpty()) throw new IOException("Hypixel returned 304 without cached data");
                        } else {
                            var parsed = new HypixelItems().parse(Files.readString(response.body()));
                            String json = HypixelItems.canonical(parsed);
                            String digest = CacheStore.digest(json);
                            if (parsed.get("lastUpdated").getAsLong() < metadata.hypixelLastUpdated()) throw new IOException("Stale Hypixel revision");
                            if (!digest.equals(officialDigest)) {
                                updateAvailable = true;
                                if (applyChanges) { official = json; officialDigest = digest; changedOfficial = true; }
                            }
                        }
                        if (applyChanges || !updateAvailable) {
                            hypixelEtag = header(response, "ETag", hypixelEtag);
                            hypixelModified = header(response, "Last-Modified", hypixelModified);
                        }
                        hypixel.success(response.code() == 304 ? "Online (not modified)" : "Online", now, response.headers());
                    } finally { Files.deleteIfExists(response.body()); }
                } catch (Exception e) { failed(hypixel, "Hypixel", e, now); }
            }
        }
        if (neu.items().isEmpty() && official.isEmpty()) { status = "Sources unavailable; no valid cache"; return; }
        if (!config.useCachedDataOffline && snapshot.items().isEmpty() && !changedNeu && !changedOfficial
                && (!contacted || github.failures() > 0 || hypixel.failures() > 0)) {
            status = "Offline cache loading disabled";
            return;
        }
        if (!changedNeu && !changedOfficial && !snapshot.items().isEmpty() && !forced) {
            if (!githubEtag.equals(metadata.githubEtag()) || !githubModified.equals(metadata.githubModified())
                    || !hypixelEtag.equals(metadata.hypixelEtag()) || !hypixelModified.equals(metadata.hypixelModified())) {
                var refreshed = new SyncMetadata(1, 1, commit, neuDigest, githubEtag, githubModified, hypixelEtag, hypixelModified,
                        officialDigest, metadata.hypixelLastUpdated(), lastChecked, metadata.lastUpdated(), metadata.changes());
                var next = new CacheStore.Bundle(refreshed, neu, official);
                cache.save(next);
                bundle = next;
            }
            status = updateAvailable ? "Update available (automatic application disabled)" : readyStatus(contacted);
            return;
        }
        try {
            var merged = new HypixelItems().merge(neu, official);
            var before = bundle == null ? RepositorySnapshot.EMPTY : new HypixelItems().merge(bundle.neu(), bundle.hypixelJson());
            var changes = SnapshotDiff.between(before, merged);
            boolean updated = changedNeu || changedOfficial;
            var nextMetadata = new SyncMetadata(1, 1, commit, neuDigest, githubEtag, githubModified, hypixelEtag, hypixelModified,
                    officialDigest, new HypixelItems().version(official), lastChecked,
                    updated ? now : metadata.lastUpdated(), updated ? changes : metadata.changes());
            status = "Validating and preparing items";
            Runnable activate = stager.stage(merged, nextMetadata, forced, updated).join();
            if (updated) nextMetadata = nextMetadata.withLastUpdated(clock.millis());
            var next = new CacheStore.Bundle(nextMetadata, neu, official);
            cache.save(next);
            bundle = next;
            snapshot = merged;
            activate.run();
            logger.info("Data synchronized: {}; {}; items={}, recipes={}", nextMetadata.revision(), changes.summary(), merged.items().size(), merged.recipes().size());
            status = readyStatus(contacted);
        } catch (Exception e) {
            if (changedNeu) failed(github, "NEU validation", e, now);
            if (changedOfficial) failed(hypixel, "Hypixel validation", e, now);
            if (snapshot.items().isEmpty()) {
                bundle = null;
                cache.rejectPrimary();
                if (!changedNeu) failed(github, "Cached data validation", e, now);
                if (!changedOfficial) failed(hypixel, "Cached data validation", e, now);
            }
            throw e;
        }
    }

    private boolean activateCached(CacheStore.Bundle loaded, boolean forced) {
        try {
            var data = new HypixelItems().merge(loaded.neu(), loaded.hypixelJson());
            var activation = stager.stage(data, loaded.metadata(), forced, false).join();
            bundle = loaded;
            snapshot = data;
            activation.run();
            return true;
        } catch (Exception e) {
            cache.rejectPrimary();
            logger.warn("Cached items cannot be prepared; trying backup and remote recovery: {}", rootMessage(e));
            return false;
        }
    }

    private Response download(HttpClient client, URI uri, String etag, String modified, int limit) throws Exception {
        Path temporary = Files.createTempFile(cache.directory(), "download-", ".tmp");
        boolean complete = false;
        try {
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).header("User-Agent", "REI-Skyblock-Provider/1.1.0");
            if (!etag.isEmpty()) builder.header("If-None-Match", etag);
            else if (!modified.isEmpty()) builder.header("If-Modified-Since", modified);
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            var timeout = CompletableFuture.runAsync(() -> {
                try { response.body().close(); } catch (IOException ignored) {}
            }, CompletableFuture.delayedExecutor(90, java.util.concurrent.TimeUnit.SECONDS));
            try (var input = response.body(); var output = Files.newOutputStream(temporary)) {
                int code = response.statusCode();
                if (code != 200 && code != 304) throw new SourceException("HTTP " + code, SourceHealth.quotaDelay(response.headers(), clock.millis()));
                long total = 0;
                byte[] buffer = new byte[65536];
                for (int size; (size = input.read(buffer)) >= 0;) {
                    if ((total += size) > limit) throw new IOException("Response too large");
                    output.write(buffer, 0, size);
                }
                complete = true;
                return new Response(code, response.headers(), temporary);
            } finally { timeout.cancel(false); }
        } finally { if (!complete) Files.deleteIfExists(temporary); }
    }

    private static final class SourceException extends IOException {
        final long retryAfter;
        SourceException(String message, long retryAfter) { super(message); this.retryAfter = retryAfter; }
    }

    private void failed(SourceHealth health, String name, Exception error, long now) {
        long retry = error instanceof SourceException source ? source.retryAfter : 0;
        health.failure(rootMessage(error), clock.millis(), retry);
        logger.warn("{} unavailable; retaining valid source data: {}", name, rootMessage(error));
    }

    private String readyStatus(boolean contacted) {
        if (!contacted) return "Ready (backoff/cooldown; cache retained)";
        return github.failures() > 0 || hypixel.failures() > 0 ? "Ready (offline/partial sources; cache retained)" : "Ready (up to date)";
    }

    private static String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String header(Response response, String key, String fallback) { return response.headers().firstValue(key).orElse(fallback); }

    private void restoreTransport() {
        try {
            Path path = cache.directory().resolve("sync-state.json");
            if (!Files.isRegularFile(path) || Files.size(path) > 16384) return;
            var state = GSON.fromJson(Files.readString(path), TransportState.class);
            lastChecked = state.lastChecked();
            github.restore(state.github()); hypixel.restore(state.hypixel());
        } catch (Exception e) { logger.warn("Cannot restore synchronization status: {}", e.getMessage()); }
    }

    private void saveTransport() {
        Path temporary = null;
        try {
            Files.createDirectories(cache.directory());
            temporary = Files.createTempFile(cache.directory(), "sync-state-", ".tmp");
            Files.writeString(temporary, GSON.toJson(new TransportState(lastChecked, github.snapshot(), hypixel.snapshot())));
            CacheStore.atomicMove(temporary, cache.directory().resolve("sync-state.json"));
        } catch (Exception e) { logger.warn("Cannot save synchronization status: {}", e.getMessage()); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }

    public String status() { return status; }
    public boolean loading() { return loading; }
    public RepositorySnapshot snapshot() { return snapshot; }
    public SyncMetadata metadata() { return bundle == null ? SyncMetadata.EMPTY : bundle.metadata(); }
    public SourceHealth githubHealth() { return github; }
    public SourceHealth hypixelHealth() { return hypixel; }
    public List<String> statusLines() {
        var data = metadata();
        return List.of("REI SkyBlock: " + status + " | loading=" + loading,
                "Last check: " + date(lastChecked) + " | Last update: " + date(data.lastUpdated()),
                data.revision(), "Cache v" + data.cacheVersion() + " / schema v" + data.schemaVersion(),
                "Loaded: " + snapshot.items().size() + " items, " + snapshot.recipes().size() + " recipes | " + data.changes().summary(),
                "GitHub/NEU: " + github.state(), "Hypixel API: " + hypixel.state());
    }

    private static String date(long value) { return value == 0 ? "never" : Instant.ofEpochMilli(value).toString(); }
}
