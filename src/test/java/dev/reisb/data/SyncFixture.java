package dev.reisb.data;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class SyncFixture implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger refs = new AtomicInteger(), archives = new AtomicInteger(), apiCalls = new AtomicInteger();
    volatile String commit, official = "{\"success\":true,\"lastUpdated\":1,\"items\":[{\"id\":\"INGREDIENT\",\"name\":\"Ingredient\",\"material\":\"DIAMOND\",\"tier\":\"COMMON\"}]}";
    volatile byte[] archive;
    volatile int githubCode = 200, hypixelCode = 200;
    volatile String retryAfter = "120";
    volatile boolean lastModifiedOnly;
    volatile String seenConditional = "";

    SyncFixture(Path temporary) throws Exception {
        setArchive(temporary, List.of(RepositoryParserTest.simpleItem("INGREDIENT"), craft(1, "Output")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ref", exchange -> {
            refs.incrementAndGet();
            if (githubCode != 200) { exchange.getResponseHeaders().add("Retry-After", retryAfter); send(exchange, githubCode, "error".getBytes(StandardCharsets.UTF_8)); return; }
            String etag = "\"" + commit + "\"";
            exchange.getResponseHeaders().add("ETag", etag);
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) send(exchange, 304, new byte[0]);
            else send(exchange, 200, ("{\"object\":{\"sha\":\"" + commit + "\"}}" ).getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/archive/", exchange -> { archives.incrementAndGet(); send(exchange, 200, archive); });
        server.createContext("/items", exchange -> {
            apiCalls.incrementAndGet();
            if (hypixelCode != 200) { send(exchange, hypixelCode, new byte[0]); return; }
            String validator = lastModifiedOnly ? "Wed, 01 Jan 2025 00:00:00 GMT" : "\"" + CacheStore.digest(official) + "\"";
            String header = lastModifiedOnly ? "Last-Modified" : "ETag";
            String requestHeader = lastModifiedOnly ? "If-Modified-Since" : "If-None-Match";
            exchange.getResponseHeaders().add(header, validator);
            seenConditional = exchange.getRequestHeaders().getFirst(requestHeader);
            if (validator.equals(seenConditional)) send(exchange, 304, new byte[0]);
            else send(exchange, 200, official.getBytes(StandardCharsets.UTF_8));
        });
        server.start();
    }

    void setArchive(Path directory, List<String> items) throws Exception {
        Path zip = directory.resolve("fixture.zip");
        RepositoryParserTest.archive(zip, items);
        archive = Files.readAllBytes(zip);
        commit = CacheStore.digest(zip);
    }

    RepositoryService.Endpoints endpoints() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new RepositoryService.Endpoints(URI.create(base + "/ref"), base + "/archive/%s.zip", URI.create(base + "/items"));
    }

    static String craft(int amount, String name) {
        return "{\"internalname\":\"OUTPUT\",\"itemid\":\"minecraft:diamond\",\"displayname\":\"" + name
                + "\",\"recipe\":{\"B2\":\"INGREDIENT:" + amount + "\"}}";
    }

    private static void send(HttpExchange exchange, int status, byte[] bytes) throws java.io.IOException {
        exchange.sendResponseHeaders(status, status == 304 || bytes.length == 0 ? -1 : bytes.length);
        try (var output = exchange.getResponseBody()) { if (status != 304 && bytes.length > 0) output.write(bytes); }
    }

    @Override public void close() { server.stop(0); }

    static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
