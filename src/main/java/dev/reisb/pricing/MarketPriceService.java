package dev.reisb.pricing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class MarketPriceService {
    public static final URI LOWEST_BIN = URI.create("https://hysky.de/api/auctions/lowestbins");
    public static final URI BAZAAR = URI.create("https://api.hypixel.net/v2/skyblock/bazaar");
    public record Quote(BigDecimal lowestBin, BigDecimal buy, BigDecimal sell) {}
    public record Snapshot(Map<String, Quote> items, long fetchedAt, long sourceAt) {
        public boolean fresh(long now) {
            return fetchedAt > 0 && now - fetchedAt < Duration.ofMinutes(15).toMillis()
                    && (sourceAt == 0 || now - sourceAt < Duration.ofMinutes(15).toMillis());
        }
    }
    private static final class Feed {
        private volatile Snapshot snapshot = new Snapshot(Map.of(), 0, 0);
        private volatile String status = "waiting";
        private long nextRefresh;
        private CompletableFuture<Void> pending;
    }
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI lowestBinUrl;
    private final URI bazaarUrl;
    private final Clock clock;
    private final Feed lowestBin = new Feed();
    private final Feed bazaar = new Feed();

    public MarketPriceService(URI lowestBinUrl, URI bazaarUrl, Clock clock) {
        this.lowestBinUrl = lowestBinUrl;
        this.bazaarUrl = bazaarUrl;
        this.clock = clock;
    }

    public CompletableFuture<Void> refresh() {
        return CompletableFuture.allOf(refresh(lowestBin, lowestBinUrl, false), refresh(bazaar, bazaarUrl, true));
    }

    public Snapshot lowestBin() { return lowestBin.snapshot; }
    public Snapshot bazaar() { return bazaar.snapshot; }
    public String status() { return "LBIN: " + lowestBin.status + " | Bazaar: " + bazaar.status; }

    private synchronized CompletableFuture<Void> refresh(Feed feed, URI url, boolean isBazaar) {
        long now = clock.millis();
        if (feed.pending != null && !feed.pending.isDone()) return feed.pending;
        if (now < feed.nextRefresh) return CompletableFuture.completedFuture(null);
        feed.nextRefresh = now + Duration.ofMinutes(1).toMillis();
        feed.status = "loading";
        feed.pending = CompletableFuture.runAsync(() -> {
            try {
                var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "REI-Skyblock-Provider").header("Accept", "application/json").build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    if (response.statusCode() == 429) {
                        long delay = 300;
                        try { delay = Math.max(delay, Long.parseLong(response.headers().firstValue("Retry-After").orElse("300"))); }
                        catch (NumberFormatException ignored) {}
                        synchronized (this) { feed.nextRefresh = clock.millis() + Math.min(86400, delay) * 1000; }
                    }
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                if (response.body().length() > 8_000_000) throw new IllegalStateException("Price response too large");
                var json = JsonParser.parseString(response.body()).getAsJsonObject();
                feed.snapshot = parse(json, isBazaar, clock.millis());
                feed.status = "ready";
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                feed.status = "unavailable";
            }
        }, runnable -> Thread.ofVirtual().name("reisb-market-prices").start(runnable));
        return feed.pending;
    }

    static Snapshot parse(JsonObject root, boolean isBazaar, long fetchedAt) {
        var quotes = new HashMap<String, Quote>();
        if (isBazaar && (!root.has("success") || !root.get("success").getAsBoolean())) {
            throw new IllegalArgumentException("Unsuccessful Bazaar response");
        }
        JsonObject products = isBazaar ? root.getAsJsonObject("products") : root;
        for (var entry : products.entrySet()) {
            try {
                if (isBazaar) {
                    var product = entry.getValue().getAsJsonObject();
                    BigDecimal buy = order(product, "buy_summary", true);
                    BigDecimal sell = order(product, "sell_summary", false);
                    if (buy != null || sell != null) quotes.put(entry.getKey(), new Quote(null, buy, sell));
                } else {
                    BigDecimal price = positive(entry.getValue().getAsBigDecimal());
                    if (price != null) quotes.put(entry.getKey(), new Quote(price, null, null));
                }
            } catch (RuntimeException ignored) {}
        }
        if (quotes.isEmpty()) throw new IllegalArgumentException("Empty price response");
        long sourceAt = isBazaar && root.has("lastUpdated") ? root.get("lastUpdated").getAsLong() : 0;
        return new Snapshot(Map.copyOf(quotes), fetchedAt, sourceAt);
    }

    private static BigDecimal order(JsonObject product, String name, boolean cheapest) {
        BigDecimal result = null;
        for (var element : product.getAsJsonArray(name)) {
            var order = element.getAsJsonObject();
            if (!order.has("amount") || order.get("amount").getAsBigDecimal().signum() <= 0) continue;
            BigDecimal price = positive(order.get("pricePerUnit").getAsBigDecimal());
            if (price != null && (result == null || (cheapest ? price.compareTo(result) < 0 : price.compareTo(result) > 0))) result = price;
        }
        return result;
    }

    private static BigDecimal positive(BigDecimal value) { return value.signum() > 0 ? value : null; }
}
