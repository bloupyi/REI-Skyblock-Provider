package dev.reisb;

import com.sun.net.httpserver.HttpServer;
import dev.reisb.config.ProviderConfig;
import dev.reisb.data.CacheStore;
import dev.reisb.data.RepositoryService;
import dev.reisb.data.SyncSchedule;
import dev.reisb.data.SkyblockRecipe;
import dev.reisb.item.SkyblockIds;
import dev.reisb.rei.SkyblockDisplayGenerator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class LiveSyncGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try {
            run(context);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void run(ClientGameTestContext context) throws Exception {
        Path directory = FabricLoader.getInstance().getGameDir().resolve("live-sync-test");
        Files.createDirectories(directory);
        var body = new AtomicReference<byte[]>();
        var revision = new AtomicReference<String>();
        var downloads = new AtomicInteger();
        setArchive(directory, body, revision, 1, false, true);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ref", exchange -> {
            String etag = "\"" + revision.get() + "\"";
            exchange.getResponseHeaders().set("ETag", etag);
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) exchange.sendResponseHeaders(304, -1);
            else {
                byte[] json = ("{\"object\":{\"sha\":\"" + revision.get() + "\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
            }
            exchange.close();
        });
        server.createContext("/archive/", exchange -> {
            downloads.incrementAndGet();
            byte[] bytes = body.get();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/items", exchange -> {
            byte[] json = "{\"success\":true,\"lastUpdated\":1,\"items\":[{\"id\":\"REISB_TEST_INGREDIENT\",\"material\":\"DIAMOND\",\"name\":\"Test Ingredient\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("ETag", "\"test-official\"");
            if ("\"test-official\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) exchange.sendResponseHeaders(304, -1);
            else { exchange.sendResponseHeaders(200, json.length); exchange.getResponseBody().write(json); }
            exchange.close();
        });
        server.start();
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> !ReiSkyblockProvider.synchronizing(), 6000);
            context.runOnClient(client -> {
                var config = ReiSkyblockProvider.config();
                config.onlyOnHypixelSkyblock = false;
                config.enableAutomaticReiRefresh = true;
                config.notifyWhenItemsUpdated = false;
            });
            var clock = new TestClock();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var service = new RepositoryService(directory.resolve("cache"), ReiSkyblockProvider.LOGGER, ReiSkyblockProvider::stage,
                    new RepositoryService.Endpoints(URI.create(base + "/ref"), base + "/archive/%s.zip", URI.create(base + "/items")), clock);
            var config = new ProviderConfig();
            sync(context, service.request(config, SyncSchedule.Trigger.STARTUP));
            context.waitFor(client -> count() == 2, 1000);
            context.runOnClient(client -> require(inputAmount() == 1, "Initial recipe missing"));
            setArchive(directory, body, revision, 2, true, true);
            clock.advance();
            sync(context, service.request(config, SyncSchedule.Trigger.PERIODIC));
            context.waitFor(client -> count() == 3, 1000);
            context.runOnClient(client -> {
                require(inputAmount() == 2, "Recipe did not update live");
                require(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(ReiSkyblockProvider.repository().stacks().get("REISB_TEST_OUTPUT")))
                        .streamDisplays().findAny().isPresent(), "Live REI recipe query failed");
            });
            int transferred = downloads.get();
            var unchanged = ReiSkyblockProvider.repository();
            clock.advance();
            sync(context, service.request(config, SyncSchedule.Trigger.PERIODIC));
            require(downloads.get() == transferred, "Unchanged repository downloaded again");
            require(ReiSkyblockProvider.repository() == unchanged, "Unchanged data republished");
            setArchive(directory, body, revision, 1, false, false);
            clock.advance();
            sync(context, service.request(config, SyncSchedule.Trigger.PERIODIC));
            context.waitFor(client -> count() == 2, 1000);
            context.runOnClient(client -> require(inputAmount() == 0, "Removed recipe remains visible"));
            var valid = ReiSkyblockProvider.repository();
            String validCommit = service.metadata().neuCommit();
            body.set(new byte[]{1, 2, 3});
            revision.set(CacheStore.digest("invalid-test-archive"));
            clock.advance();
            sync(context, service.request(config, SyncSchedule.Trigger.PERIODIC));
            require(service.metadata().neuCommit().equals(validCommit), "Invalid update replaced cache revision");
            require(ReiSkyblockProvider.repository() == valid, "Invalid update replaced live data");
            context.runOnClient(client -> require(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(Items.CRAFTING_TABLE))
                    .streamDisplays().findAny().isPresent(), "Vanilla recipe lost during synchronization"));
            setArchive(directory, body, revision, 3, true, true);
            clock.advance();
            context.runOnClient(client -> ReiSkyblockProvider.config().enableAutomaticReiRefresh = false);
            sync(context, service.request(config, SyncSchedule.Trigger.PERIODIC));
            require(service.snapshot().items().size() == 3, "Background update was not cached");
            context.runOnClient(client -> require(count() == 2, "Disabled automatic refresh changed REI"));
            sync(context, service.request(config, SyncSchedule.Trigger.MANUAL));
            context.waitFor(client -> count() == 3, 1000);
            context.runOnClient(client -> require(inputAmount() == 3, "Manual update did not refresh REI"));
            context.takeScreenshot("live-sync-updated");
            context.runOnClient(client -> ReiSkyblockProvider.reload());
            context.waitFor(client -> !ReiSkyblockProvider.synchronizing(), 6000);
        } finally { server.stop(0); }
    }

    private static void sync(ClientGameTestContext context, CompletableFuture<Void> future) {
        context.waitFor(client -> future.isDone(), 6000);
        future.join();
        context.waitFor(client -> dev.reisb.rei.SkyblockClientPlugin.isSynchronized(), 6000);
    }

    private static long count() {
        return EntryRegistry.getInstance().getEntryStacks().filter(entry -> entry.getValue() instanceof ItemStack stack && SkyblockIds.owned(stack)).count();
    }

    private static long inputAmount() {
        var entry = EntryStacks.of(ReiSkyblockProvider.repository().stacks().get("REISB_TEST_OUTPUT"));
        return new SkyblockDisplayGenerator().getRecipeFor(entry).orElseThrow().stream()
                .filter(display -> display.recipe().kind() == SkyblockRecipe.Kind.CRAFTING)
                .mapToLong(display -> display.recipe().inputs().get(4).count()).findFirst().orElse(0);
    }

    private static void setArchive(Path directory, AtomicReference<byte[]> body, AtomicReference<String> revision,
                                    int amount, boolean extra, boolean recipe) throws Exception {
        Path archive = directory.resolve("fixture.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            var items = new java.util.ArrayList<String>();
            items.add(item("INGREDIENT", ""));
            items.add(item("OUTPUT", recipe ? ",\"recipe\":{\"B2\":\"REISB_TEST_INGREDIENT:" + amount + "\"}" : ""));
            if (extra) items.add(item("NEW", ""));
            for (int i = 0; i < items.size(); i++) {
                output.putNextEntry(new ZipEntry("test/items/" + i + ".json"));
                output.write(items.get(i).getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        body.set(Files.readAllBytes(archive));
        revision.set(CacheStore.digest(archive));
    }

    private static String item(String suffix, String recipe) {
        return "{\"internalname\":\"REISB_TEST_" + suffix + "\",\"itemid\":\"minecraft:diamond\",\"displayname\":\"Test " + suffix + "\"" + recipe + "}";
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class TestClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        void advance() { instant = instant.plus(Duration.ofMinutes(30)); }
        @Override public Instant instant() { return instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
