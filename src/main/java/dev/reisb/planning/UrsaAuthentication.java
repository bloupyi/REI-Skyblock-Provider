package dev.reisb.planning;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

public final class UrsaAuthentication {
    @FunctionalInterface public interface SessionProof { void join(String serverId) throws Exception; }
    public record Identity(String uuid, String username, SessionProof proof) {}
    private record Token(String uuid, String value, long expires) {}
    private static volatile Token token;
    private UrsaAuthentication() {}

    public static boolean accepts(URI uri) {
        return "https".equals(uri.getScheme()) && "ursa.notenoughupdates.org".equals(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null
                && uri.getPath().startsWith("/v1/hypixel/");
    }

    public static Map<String, String> headers(Identity identity, long now) throws Exception {
        if (identity == null) throw new ProfileApiClient.Failure("session_auth", 60000);
        Token current = token;
        if (current != null && current.uuid.equals(identity.uuid) && now < current.expires)
            return Map.of("x-ursa-token", current.value);
        String challenge = UUID.randomUUID().toString();
        try { identity.proof.join(challenge); }
        catch (Exception error) { throw new ProfileApiClient.Failure("session_auth", 60000); }
        return Map.of("x-ursa-username", identity.username, "x-ursa-serverid", challenge);
    }

    public static void accept(String uuid, String value, long now) {
        if (value != null && !value.isBlank() && value.length() <= 4096 && !value.contains("\r") && !value.contains("\n"))
            token = new Token(uuid, value, now + 55 * 60000L);
    }

    public static void invalidate(String uuid) {
        Token current = token;
        if (current != null && current.uuid.equals(uuid)) token = null;
    }
}
