package dev.reisb.data;

import dev.reisb.config.ProviderConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static dev.reisb.data.SyncSchedule.Trigger.*;

public class RepositoryServiceTest {
    @TempDir Path temporary;
    private final SyncFixture.MutableClock clock = new SyncFixture.MutableClock();
    private final CopyOnWriteArrayList<RepositorySnapshot> published = new CopyOnWriteArrayList<>();

    private RepositoryService service(SyncFixture fixture, Path directory) {
        return new RepositoryService(directory, LoggerFactory.getLogger("test"),
                (data, metadata, force, notify) -> CompletableFuture.completedFuture(() -> published.add(data)), fixture.endpoints(), clock);
    }

    private static void check(RepositoryService service, ProviderConfig config, SyncSchedule.Trigger trigger) throws Exception {
        service.request(config, trigger).get(30, TimeUnit.SECONDS);
        assertFalse(service.loading());
    }

    @Test
    void successiveUpdatesAddModifyAndRemoveItemsAndRecipes() throws Exception {
        try (var fixture = new SyncFixture(temporary)) {
            fixture.setArchive(temporary, List.of(RepositoryParserTest.simpleItem("INGREDIENT"), SyncFixture.craft(1, "Output"), RepositoryParserTest.simpleItem("OLD")));
            var config = new ProviderConfig();
            var service = service(fixture, temporary.resolve("cache"));
            check(service, config, STARTUP);
            assertEquals(3, service.snapshot().items().size());
            assertEquals(1, service.snapshot().recipes().size());
            fixture.setArchive(temporary, List.of(RepositoryParserTest.simpleItem("INGREDIENT"), SyncFixture.craft(2, "Output"), RepositoryParserTest.simpleItem("NEW")));
            clock.advance(Duration.ofMinutes(30));
            check(service, config, PERIODIC);
            assertEquals(new SnapshotDiff(1, 0, 1, 0, 1, 0), service.metadata().changes());
            assertEquals(2, service.snapshot().recipes().getFirst().inputs().get(4).count());
            fixture.setArchive(temporary, List.of(RepositoryParserTest.simpleItem("INGREDIENT"), RepositoryParserTest.simpleItem("OUTPUT"), RepositoryParserTest.simpleItem("NEW")));
            clock.advance(Duration.ofMinutes(30));
            check(service, config, PERIODIC);
            assertTrue(service.snapshot().recipes().isEmpty());
            assertEquals(1, service.metadata().changes().recipesRemoved());
            assertEquals(3, published.size());
            assertEquals(3, fixture.archives.get());
            var stored = new CacheStore(temporary.resolve("cache"), LoggerFactory.getLogger("test")).load();
            assertEquals(fixture.commit, stored.metadata().neuCommit());
            assertTrue(stored.neu().recipes().isEmpty());
            assertEquals(1, stored.metadata().cacheVersion());
        }
    }

    @Test
    void unchangedRevisionUses304WithoutArchiveOrPublication() throws Exception {
        try (var fixture = new SyncFixture(temporary)) {
            fixture.lastModifiedOnly = true;
            var service = service(fixture, temporary.resolve("cache"));
            var config = new ProviderConfig();
            check(service, config, STARTUP);
            long updated = service.metadata().lastUpdated();
            clock.advance(Duration.ofMinutes(30));
            check(service, config, PERIODIC);
            assertEquals(1, fixture.archives.get());
            assertEquals(1, published.size());
            assertEquals(2, fixture.refs.get());
            assertEquals("Wed, 01 Jan 2025 00:00:00 GMT", fixture.seenConditional);
            assertEquals(updated, service.metadata().lastUpdated());
            assertTrue(service.statusLines().get(1).contains(clock.instant().toString()));
        }
    }

    @Test
    void invalidTruncatedAndIncompatibleDownloadsRetainLastValidData() throws Exception {
        try (var fixture = new SyncFixture(temporary)) {
            var service = service(fixture, temporary.resolve("cache"));
            var config = new ProviderConfig();
            check(service, config, STARTUP);
            var original = service.snapshot();
            String commit = service.metadata().neuCommit();
            fixture.setArchive(temporary, List.of(RepositoryParserTest.simpleItem("INGREDIENT"), SyncFixture.craft(2, "Output")));
            fixture.archive = java.util.Arrays.copyOf(fixture.archive, fixture.archive.length - 30);
            clock.advance(Duration.ofMinutes(30));
            check(service, config, PERIODIC);
            assertEquals(original, service.snapshot());
            assertEquals(commit, service.metadata().neuCommit());
            fixture.setArchive(temporary, List.of(RepositoryParserTest.simpleItem("INGREDIENT"), SyncFixture.craft(3, "Output"),
                    "{\"internalname\":\"UNKNOWN\",\"itemid\":\"minecraft:diamond\",\"displayname\":\"Unknown\",\"recipes\":[{\"type\":\"future_mechanic\"}]}"));
            clock.advance(Duration.ofMinutes(30));
            check(service, config, PERIODIC);
            assertEquals(original, service.snapshot());
            assertEquals(commit, service.metadata().neuCommit());
            fixture.official = "{bad json";
            clock.advance(Duration.ofMinutes(30));
            check(service, config, PERIODIC);
            assertEquals(original, service.snapshot());
            assertTrue(service.hypixelHealth().failures() > 0);
        }
    }

    @Test
    void offlineRestartAndCorruptedPrimaryFallBackToPreviousCache() throws Exception {
        try (var fixture = new SyncFixture(temporary)) {
            Path directory = temporary.resolve("cache");
            var config = new ProviderConfig();
            var service = service(fixture, directory);
            check(service, config, STARTUP);
            String original = service.metadata().neuCommit();
            fixture.setArchive(temporary, List.of(RepositoryParserTest.simpleItem("INGREDIENT"), SyncFixture.craft(2, "Output")));
            clock.advance(Duration.ofMinutes(30));
            check(service, config, PERIODIC);
            Files.write(directory.resolve("cache-v1.json.gz"), new byte[]{1, 2, 3});
            fixture.githubCode = 503; fixture.hypixelCode = 503;
            clock.advance(Duration.ofMinutes(30));
            var restarted = service(fixture, directory);
            check(restarted, config, STARTUP);
            assertEquals(original, restarted.metadata().neuCommit());
            assertEquals(1, restarted.snapshot().recipes().getFirst().inputs().get(4).count());
            assertTrue(restarted.status().contains("offline"));
            assertTrue(restarted.statusLines().get(1).contains("Last update:"));
        }
    }

    @Test
    void exponentialBackoffAndRetryAfterCannotBeBypassedManually() throws Exception {
        try (var fixture = new SyncFixture(temporary)) {
            fixture.githubCode = 429;
            var service = service(fixture, temporary.resolve("cache"));
            var config = new ProviderConfig();
            check(service, config, MANUAL);
            assertEquals(clock.millis() + 120000, service.githubHealth().nextAttempt());
            check(service, config, MANUAL);
            assertEquals(1, fixture.refs.get());
            clock.advance(Duration.ofSeconds(119));
            check(service, config, MANUAL);
            assertEquals(1, fixture.refs.get());
            clock.advance(Duration.ofSeconds(1));
            check(service, config, PERIODIC);
            assertEquals(2, fixture.refs.get());
            assertEquals(2, service.githubHealth().failures());
            clock.advance(Duration.ofMinutes(2));
            check(service, config, PERIODIC);
            assertEquals(3, service.githubHealth().failures());
            assertEquals(clock.millis() + 240000, service.githubHealth().nextAttempt());
        }
    }

    @Test
    void preparationFailureDoesNotCommitCacheOrPublishCandidate() throws Exception {
        try (var fixture = new SyncFixture(temporary)) {
            var reject = new AtomicBoolean();
            Path directory = temporary.resolve("cache");
            var service = new RepositoryService(directory, LoggerFactory.getLogger("test"), (data, metadata, force, notify) -> {
                if (reject.get()) return CompletableFuture.failedFuture(new IllegalArgumentException("Rejected item conversion"));
                return CompletableFuture.completedFuture(() -> published.add(data));
            }, fixture.endpoints(), clock);
            var config = new ProviderConfig();
            check(service, config, STARTUP);
            var before = service.snapshot();
            byte[] cache = Files.readAllBytes(directory.resolve("cache-v1.json.gz"));
            reject.set(true);
            fixture.setArchive(temporary, List.of(RepositoryParserTest.simpleItem("INGREDIENT"), SyncFixture.craft(2, "Output")));
            clock.advance(Duration.ofMinutes(30));
            check(service, config, PERIODIC);
            assertEquals(before, service.snapshot());
            assertEquals(1, published.size());
            assertArrayEquals(cache, Files.readAllBytes(directory.resolve("cache-v1.json.gz")));
        }
    }

    @Test
    void disabledStartupJoinAndIntervalAndManualOverride() throws Exception {
        try (var fixture = new SyncFixture(temporary)) {
            var config = new ProviderConfig();
            config.checkUpdatesOnStartup = false;
            var service = service(fixture, temporary.resolve("cache"));
            check(service, config, STARTUP);
            assertEquals(0, fixture.refs.get());
            check(service, config, PERIODIC);
            assertEquals(0, fixture.refs.get());
            check(service, config, JOIN);
            assertEquals(1, fixture.refs.get());
            check(service, config, JOIN);
            assertEquals(1, fixture.refs.get());
            clock.advance(Duration.ofMinutes(5));
            check(service, config, JOIN);
            assertEquals(2, fixture.refs.get());
            fixture.setArchive(temporary, List.of(RepositoryParserTest.simpleItem("INGREDIENT"), SyncFixture.craft(2, "Output")));
            config.updateOnRepositoryChange = false;
            clock.advance(Duration.ofMinutes(30));
            check(service, config, PERIODIC);
            assertEquals(1, fixture.archives.get());
            assertTrue(service.status().contains("Update available"));
            config.autoUpdateRepository = false;
            check(service, config, MANUAL);
            assertEquals(2, fixture.archives.get());
            assertEquals(2, service.snapshot().recipes().getFirst().inputs().get(4).count());
        }
    }

    @Test
    void missingCacheWithUpdatesDisabledAndOfflineOptOut() throws Exception {
        try (var fixture = new SyncFixture(temporary)) {
            var config = new ProviderConfig();
            config.autoUpdateRepository = false;
            var service = service(fixture, temporary.resolve("cache"));
            check(service, config, STARTUP);
            assertTrue(service.snapshot().items().isEmpty());
            assertEquals(0, fixture.refs.get());
            config.autoUpdateRepository = true;
            check(service, config, MANUAL);
            fixture.githubCode = 503; fixture.hypixelCode = 503;
            config.useCachedDataOffline = false;
            clock.advance(Duration.ofMinutes(30));
            var restarted = service(fixture, temporary.resolve("cache"));
            check(restarted, config, STARTUP);
            assertTrue(restarted.snapshot().items().isEmpty());
            assertEquals("Offline cache loading disabled", restarted.status());
        }
    }

    @Test
    void eachSourceCanUpdateWhileTheOtherIsUnavailable() throws Exception {
        try (var fixture = new SyncFixture(temporary)) {
            var config = new ProviderConfig();
            var service = service(fixture, temporary.resolve("cache"));
            check(service, config, STARTUP);
            fixture.hypixelCode = 503;
            fixture.setArchive(temporary, List.of(RepositoryParserTest.simpleItem("INGREDIENT"), SyncFixture.craft(2, "Output")));
            clock.advance(Duration.ofMinutes(30));
            check(service, config, PERIODIC);
            assertEquals(2, service.snapshot().recipes().getFirst().inputs().get(4).count());
            assertTrue(service.hypixelHealth().failures() > 0);
            fixture.githubCode = 503;
            fixture.hypixelCode = 200;
            fixture.official = fixture.official.replace("\"lastUpdated\":1", "\"lastUpdated\":2").replace("\"name\":\"Ingredient\"", "\"name\":\"Official updated\"");
            clock.advance(Duration.ofMinutes(30));
            check(service, config, PERIODIC);
            assertEquals("§fOfficial updated", service.snapshot().items().get("INGREDIENT").name());
            assertEquals(2, service.snapshot().recipes().getFirst().inputs().get(4).count());
            assertEquals(1, service.metadata().changes().itemsModified());
        }
    }

    @Test
    void damagedCachesDoNotPreventRemoteRecovery() throws Exception {
        try (var fixture = new SyncFixture(temporary)) {
            Path directory = temporary.resolve("cache");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("repository.zip"), "invalid");
            Files.writeString(directory.resolve("cache-v1.json.gz"), "invalid");
            Files.writeString(directory.resolve("cache-v1.previous.json.gz"), "invalid");
            var service = service(fixture, directory);
            check(service, new ProviderConfig(), STARTUP);
            assertEquals(2, service.snapshot().items().size());
            assertEquals(fixture.commit, service.metadata().neuCommit());
            assertNotNull(new CacheStore(directory, LoggerFactory.getLogger("test")).load());
        }
    }

    @Test
    void incompatibleCachedItemsFallBackAndCanBeRepairedRemotely() throws Exception {
        try (var fixture = new SyncFixture(temporary)) {
            Path directory = temporary.resolve("cache");
            var config = new ProviderConfig();
            var initial = service(fixture, directory);
            check(initial, config, STARTUP);
            var store = new CacheStore(directory, LoggerFactory.getLogger("test"));
            var valid = store.load();
            var items = new java.util.HashMap<>(valid.neu().items());
            var item = items.get("OUTPUT");
            items.put("OUTPUT", new SkyblockItem(item.id(), item.minecraftId(), 0, "{broken", item.name(), item.lore(), item.info()));
            var broken = new RepositorySnapshot(items, valid.neu().recipes(), List.of(), java.util.Map.of());
            store.save(new CacheStore.Bundle(valid.metadata(), broken, valid.hypixelJson()));
            fixture.githubCode = 503; fixture.hypixelCode = 503;
            clock.advance(Duration.ofMinutes(30));
            var restarted = new RepositoryService(directory, LoggerFactory.getLogger("test"), (data, metadata, force, notify) -> {
                if (data.items().values().stream().anyMatch(value -> value.nbt().equals("{broken"))) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid cached NBT"));
                return CompletableFuture.completedFuture(() -> published.add(data));
            }, fixture.endpoints(), clock);
            check(restarted, config, STARTUP);
            assertEquals(valid.neu().items().get("OUTPUT").nbt(), restarted.snapshot().items().get("OUTPUT").nbt());
            fixture.githubCode = 200; fixture.hypixelCode = 200;
            fixture.setArchive(temporary, List.of(RepositoryParserTest.simpleItem("INGREDIENT"), SyncFixture.craft(2, "Output")));
            clock.advance(Duration.ofMinutes(30));
            check(restarted, config, PERIODIC);
            assertEquals(2, restarted.snapshot().recipes().getFirst().inputs().get(4).count());
            assertEquals(fixture.commit, restarted.metadata().neuCommit());
        }
    }
}
