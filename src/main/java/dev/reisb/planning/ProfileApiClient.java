package dev.reisb.planning;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class ProfileApiClient {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public static final class Failure extends IOException {
        public final String status;
        public final long retryMillis;
        public Failure(String status, long retryMillis) { super(status); this.status = status; this.retryMillis = retryMillis; }
    }
    private ProfileApiClient() {}

    public static ProfileApiData fetch(Path directory, String endpoint, String header, String uuid, String profile) throws Exception {
        return fetch(directory, endpoint, header, uuid, profile, null);
    }

    public static ProfileApiData fetch(Path directory, String endpoint, String header, String uuid, String profile, UrsaAuthentication.Identity identity) throws Exception {
        URI uri = URI.create(endpoint.replace("{uuid}", uuid.replace("-", "")));
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new Failure("configuration", 300000);
        boolean ursa = UrsaAuthentication.accepts(uri);
        String secret = "";
        if (!ursa && header != null && !header.isBlank()) {
            if (!header.matches("[A-Za-z][A-Za-z0-9-]{0,63}")) throw new Failure("configuration", 300000);
            Path file = directory.resolve("profile-api-key.txt");
            if (Files.exists(file)) {
                if (Files.size(file) > 4096) throw new Failure("configuration", 300000);
                secret = Files.readString(file).strip();
            } else if ("api.hypixel.net".equals(uri.getHost())) secret = System.getenv().getOrDefault("REISB_HYPIXEL_API_KEY", "").strip();
            if (secret.isEmpty()) throw new Failure("missing_key", 300000);
            if (secret.contains("\n") || secret.contains("\r")) throw new Failure("configuration", 300000);
        }
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json").header("User-Agent", "REI-Skyblock-Provider/ProfileCache");
        if (!secret.isEmpty()) request.header(header, secret);
        if (ursa) UrsaAuthentication.headers(identity, System.currentTimeMillis()).forEach(request::header);
        return request(HTTP, request.GET().build(), directory.resolve("repository.zip"), uuid, profile, ursa ? uuid : null);
    }

    static ProfileApiData request(HttpClient client, HttpRequest request, Path archive, String uuid, String profile) throws Exception {
        return request(client, request, archive, uuid, profile, null);
    }

    static ProfileApiData request(HttpClient client, HttpRequest request, Path archive, String uuid, String profile, String authUuid) throws Exception {
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (authUuid != null && (response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 403)) {
                UrsaAuthentication.invalidate(authUuid);
                throw new Failure("session_auth", 60000);
            }
            if (authUuid != null && response.statusCode() == 200)
                UrsaAuthentication.accept(authUuid, response.headers().firstValue("x-ursa-token").orElse(null), System.currentTimeMillis());
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new Failure("forbidden", 300000);
            if (response.statusCode() == 429) {
                long retry = 300000;
                try { retry = Math.max(retry, Math.min(3600000, Math.multiplyExact(Long.parseLong(response.headers().firstValue("Retry-After").orElse("300")), 1000))); }
                catch (RuntimeException ignored) {}
                throw new Failure("limited", retry);
            }
            if (response.statusCode() != 200) throw new Failure("error", 60000);
            byte[] bytes = body.readNBytes(8388609);
            if (bytes.length > 8388608) throw new Failure("error", 300000);
            var json = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            return ProfileApiData.parse(json, uuid, profile, ProfileApiData.leveling(archive));
        }
    }
}
