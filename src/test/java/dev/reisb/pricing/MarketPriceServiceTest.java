package dev.reisb.pricing;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

public class MarketPriceServiceTest {
    private static final String BAZAAR = "{\"success\":true,\"products\":{\"DIAMOND\":{\"buy_summary\":[{\"amount\":3,\"pricePerUnit\":11.2},{\"amount\":2,\"pricePerUnit\":10.1}],\"sell_summary\":[{\"amount\":4,\"pricePerUnit\":5.2},{\"amount\":1,\"pricePerUnit\":6.1}]}}}";

    @Test
    void pricesUseTheCorrectOrderSidesAndIgnoreInvalidListings() {
        var bazaar = MarketPriceService.parse(JsonParser.parseString(BAZAAR).getAsJsonObject(), true, 1000);
        assertEquals(new BigDecimal("10.1"), bazaar.items().get("DIAMOND").buy());
        assertEquals(new BigDecimal("6.1"), bazaar.items().get("DIAMOND").sell());
        var bin = MarketPriceService.parse(JsonParser.parseString("{\"TERMINATOR\":363000000,\"EMPTY\":0,\"INVALID\":-1}").getAsJsonObject(), false, 1000);
        assertEquals(new BigDecimal("363000000"), bin.items().get("TERMINATOR").lowestBin());
        assertFalse(bin.items().containsKey("EMPTY"));
        assertFalse(bin.items().containsKey("INVALID"));
        assertFalse(bin.fresh(1000 + 900000));
        assertThrows(IllegalArgumentException.class, () -> MarketPriceService.parse(JsonParser.parseString("{\"success\":false}").getAsJsonObject(), true, 1000));
    }

    @Test
    void requestsAreSharedAndOneFailedSourceDoesNotEraseTheOther() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/bin", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"TERMINATOR\":363000000}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/bazaar", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var service = new MarketPriceService(base.resolve("/bin"), base.resolve("/bazaar"), Clock.systemUTC());
            for (int i = 0; i < 100; i++) service.refresh();
            service.refresh().get(10, TimeUnit.SECONDS);
            assertEquals(2, calls.get());
            assertTrue(service.lowestBin().items().containsKey("TERMINATOR"));
            assertTrue(service.bazaar().items().isEmpty());
            assertTrue(service.status().contains("unavailable"));
        } finally { server.stop(0); }
    }

    @Test
    void sourceTimestampPreventsOldBazaarDataFromLookingFresh() {
        var json = JsonParser.parseString(BAZAAR).getAsJsonObject();
        json.addProperty("lastUpdated", 1000);
        assertFalse(MarketPriceService.parse(json, true, 1000000).fresh(1000000));
    }

    @Test
    @EnabledIfSystemProperty(named = "reisb.livePrices", matches = "true")
    void liveFeedsSupplyTerminatorAndBazaarPricesWithoutAnInventory() throws Exception {
        var service = new MarketPriceService(MarketPriceService.LOWEST_BIN, MarketPriceService.BAZAAR, Clock.systemUTC());
        service.refresh().get(35, TimeUnit.SECONDS);
        assertTrue(service.lowestBin().fresh(System.currentTimeMillis()), service.status());
        assertTrue(service.bazaar().fresh(System.currentTimeMillis()), service.status());
        assertTrue(service.lowestBin().items().get("TERMINATOR").lowestBin().signum() > 0);
        assertTrue(service.bazaar().items().get("DIAMOND").buy().signum() > 0);
        assertTrue(service.bazaar().items().get("ENCHANTMENT_ULTIMATE_WISE_5").sell().signum() > 0);
    }
}
