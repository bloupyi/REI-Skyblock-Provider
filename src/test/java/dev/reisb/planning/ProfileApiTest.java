package dev.reisb.planning;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class ProfileApiTest {
    @TempDir Path temporary;
    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final String MEMBER = """
            {"inventory":{"sacks_counts":{"DIAMOND":1200,"INK_SACK:3":0,"BAD":-1}},
             "player_data":{"unlocked_coll_tiers":["DIAMOND_4","DIAMOND_3","INK_SACK:3_5","BAD_-1"],
                "experience":{"SKILL_COMBAT":175}},
             "slayer":{"slayer_bosses":{"enderman":{"xp":1000000,"claimed_levels":{"level_7":true,"level_8":false}}}},
             "dungeons":{"dungeon_types":{"catacombs":{"experience":125,"tier_completions":{"5":2,"7":0}}}}}
            """;

    private static JsonObject response(String member) {
        return JsonParser.parseString("{\"success\":true,\"profiles\":[{\"profile_id\":\"apple-id\",\"cute_name\":\"Apple\",\"selected\":false,\"members\":{\""
                + UUID + "\":" + member + "}},{\"profile_id\":\"banana-id\",\"cute_name\":\"Banana\",\"selected\":true,\"members\":{}}]}").getAsJsonObject();
    }

    @Test
    void parsesOnlyTheRequestedPlayerAndActiveProfile() {
        var leveling = JsonParser.parseString("{\"leveling_xp\":[50,125,200],\"catacombs\":[50,75,110],\"leveling_caps\":{\"combat\":60}}").getAsJsonObject();
        var data = ProfileApiData.parse(response(MEMBER), UUID, "§aApple", leveling);
        assertEquals("apple-id", data.profileId());
        assertEquals(new BigDecimal("1200"), data.sacks().get("DIAMOND"));
        assertEquals(BigDecimal.ZERO, data.sacks().get("INK_SACK-3"));
        assertFalse(data.sacks().containsKey("BAD"));
        assertEquals(new BigDecimal("4"), data.progress().get("COLLECTION:DIAMOND"));
        assertEquals(new BigDecimal("5"), data.progress().get("COLLECTION:INK_SACK_3"));
        assertEquals(new BigDecimal("2"), data.progress().get("SKILL:COMBAT"));
        assertEquals(new BigDecimal("7"), data.progress().get("SLAYER:ENDERMAN"));
        assertEquals(new BigDecimal("2"), data.progress().get("DUNGEON_SKILL:CATACOMBS"));
        assertEquals(new BigDecimal("5"), data.progress().get("DUNGEON_TIER:CATACOMBS"));
        assertThrows(IllegalArgumentException.class, () -> ProfileApiData.parse(response(MEMBER), "another-account", "Apple", leveling));
        assertThrows(IllegalArgumentException.class, () -> ProfileApiData.parse(response(MEMBER), UUID, "Mango", leveling));
    }

    @Test
    void privateOrAbsentFieldsStayUnknownAndInvalidResponsesAreRejected() {
        var data = ProfileApiData.parse(response("{}"), UUID, "Apple", new JsonObject());
        assertTrue(data.sacks().isEmpty());
        assertTrue(data.progress().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ProfileApiData.parse(JsonParser.parseString("{\"success\":false}").getAsJsonObject(), UUID, "Apple", new JsonObject()));
        var duplicate = response(MEMBER);
        duplicate.getAsJsonArray("profiles").add(duplicate.getAsJsonArray("profiles").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> ProfileApiData.parse(duplicate, UUID, "Apple", new JsonObject()));
    }

    @Test
    void recentMenuReadingsWinOverApiAndMissingFieldsDoNotEraseCache() {
        long now = 1000000;
        var target = new HashMap<String, PlanningStore.Observation>();
        target.put("DIAMOND", new PlanningStore.Observation(BigDecimal.TEN, now - 1000));
        target.put("COAL", new PlanningStore.Observation(BigDecimal.ONE, now - 400000));
        target.put("EMERALD", new PlanningStore.Observation(BigDecimal.ONE, now - 400000));
        PlanningService.mergeApi(target, Map.of("DIAMOND", BigDecimal.ZERO, "COAL", BigDecimal.ZERO, "IRON_INGOT", BigDecimal.TEN), now);
        assertEquals(BigDecimal.TEN, target.get("DIAMOND").amount());
        assertEquals(BigDecimal.ZERO, target.get("COAL").amount());
        assertEquals(BigDecimal.TEN, target.get("IRON_INGOT").amount());
        assertEquals(BigDecimal.ONE, target.get("EMERALD").amount());
        var collections = new HashMap<String, PlanningStore.Observation>();
        collections.put("COLLECTION:INK_SACK_3", new PlanningStore.Observation(BigDecimal.TEN, now));
        assertTrue(PlanningService.aliasCollections(collections, Map.of("COLLECTION:COCOA_BEANS", "COLLECTION:INK_SACK_3")));
        assertEquals(BigDecimal.TEN, collections.get("COLLECTION:COCOA_BEANS").amount());
        assertFalse(PlanningService.aliasCollections(collections, Map.of("COLLECTION:COCOA_BEANS", "COLLECTION:INK_SACK_3")));
    }

    @Test
    void missingAuthenticationAndInsecureConfigurationNeverSendARequest() {
        assertEquals("missing_key", assertThrows(ProfileApiClient.Failure.class,
                () -> ProfileApiClient.fetch(temporary, "https://example.invalid/profiles?uuid={uuid}", "API-Key", UUID, "Apple")).status);
        assertEquals("configuration", assertThrows(ProfileApiClient.Failure.class,
                () -> ProfileApiClient.fetch(temporary, "http://example.invalid/profiles", "API-Key", UUID, "Apple")).status);
    }

    @Test
    void ursaUsesOnlySessionProofAndKeepsItsOwnTokenInMemory() throws Exception {
        String uuid = "ursa-test-account";
        UrsaAuthentication.invalidate(uuid);
        var proofs = new java.util.concurrent.atomic.AtomicInteger();
        var identity = new UrsaAuthentication.Identity(uuid, "Player", challenge -> {
            assertDoesNotThrow(() -> java.util.UUID.fromString(challenge));
            proofs.incrementAndGet();
        });
        var headers = UrsaAuthentication.headers(identity, 1000);
        assertEquals("Player", headers.get("x-ursa-username"));
        assertTrue(headers.containsKey("x-ursa-serverid"));
        assertEquals(1, proofs.get());
        UrsaAuthentication.accept(uuid, "ursa-test-token", 1000);
        assertEquals(Map.of("x-ursa-token", "ursa-test-token"), UrsaAuthentication.headers(identity, 2000));
        assertEquals(1, proofs.get());
        UrsaAuthentication.headers(identity, 1000 + 55 * 60000L);
        assertEquals(2, proofs.get());
        UrsaAuthentication.invalidate(uuid);
        assertTrue(UrsaAuthentication.accepts(URI.create("https://ursa.notenoughupdates.org/v1/hypixel/v2/profiles/test")));
        assertFalse(UrsaAuthentication.accepts(URI.create("https://ursa.notenoughupdates.org.example.com/v1/hypixel/test")));
        assertFalse(UrsaAuthentication.accepts(URI.create("http://ursa.notenoughupdates.org/v1/hypixel/test")));
        assertEquals("session_auth", assertThrows(ProfileApiClient.Failure.class, () -> UrsaAuthentication.headers(null, 0)).status);
    }

    @Test
    void realHttpTransportHandlesAuthenticationRateLimitsAndJson() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/profiles", exchange -> {
            assertEquals("test-token", exchange.getRequestHeaders().getFirst("API-Key"));
            byte[] body = response(MEMBER).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/limited", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "1000");
            exchange.sendResponseHeaders(429, -1); exchange.close();
        });
        server.createContext("/forbidden", exchange -> { exchange.sendResponseHeaders(403, -1); exchange.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var request = HttpRequest.newBuilder(URI.create(base + "/profiles")).header("API-Key", "test-token").build();
            var client = HttpClient.newHttpClient();
            var data = ProfileApiClient.request(client, request, temporary.resolve("absent.zip"), UUID, "Apple");
            assertEquals(new BigDecimal("1200"), data.sacks().get("DIAMOND"));
            var limited = assertThrows(ProfileApiClient.Failure.class, () -> ProfileApiClient.request(client,
                    HttpRequest.newBuilder(URI.create(base + "/limited")).build(), temporary, UUID, "Apple"));
            assertEquals("limited", limited.status);
            assertEquals(1000000, limited.retryMillis);
            assertEquals("forbidden", assertThrows(ProfileApiClient.Failure.class, () -> ProfileApiClient.request(client,
                    HttpRequest.newBuilder(URI.create(base + "/forbidden")).build(), temporary, UUID, "Apple")).status);
        } finally { server.stop(0); }
    }
}
