package dev.reisb.data;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;

public final class CacheStore {
    public record Bundle(SyncMetadata metadata, RepositorySnapshot neu, String hypixelJson) {}
    private static final Gson GSON = new Gson();
    private static final int LIMIT = 64 * 1024 * 1024;
    private final Path directory;
    private final Logger logger;
    private boolean primaryValid;

    public CacheStore(Path directory, Logger logger) { this.directory = directory; this.logger = logger; }
    public Path directory() { return directory; }
    public void rejectPrimary() { primaryValid = false; }

    public Bundle previous() {
        Path path = directory.resolve("cache-v1.previous.json.gz");
        if (!Files.isRegularFile(path)) return null;
        try { return read(path); }
        catch (Exception e) { logger.warn("Previous cache unavailable: {}", e.getMessage()); return null; }
    }

    public Bundle load() throws IOException {
        primaryValid = false;
        for (String name : new String[]{"cache-v1.json.gz", "cache-v1.previous.json.gz"}) {
            Path file = directory.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try {
                var bundle = read(file);
                primaryValid = name.equals("cache-v1.json.gz");
                return bundle;
            } catch (Exception e) { logger.warn("Rejected cache {}: {}", file, e.getMessage()); }
        }
        Path legacy = directory.resolve("repository.zip");
        if (!Files.isRegularFile(legacy)) return null;
        try {
            var neu = new RepositoryParser().parse(legacy);
            var metadata = new SyncMetadata(1, 1, "", digest(legacy), "", "", "", "", "", 0, 0, 0, SnapshotDiff.EMPTY);
            return new Bundle(metadata, neu, "");
        } catch (Exception e) {
            logger.warn("Rejected legacy cache {}; remote recovery remains available: {}", legacy, e.getMessage());
            return null;
        }
    }

    private Bundle read(Path file) throws IOException {
        try (var raw = Files.newInputStream(file); var input = new GZIPInputStream(raw)) {
            byte[] bytes = input.readNBytes(LIMIT + 1);
            if (bytes.length > LIMIT) throw new IOException("Cache size limit exceeded");
            Bundle bundle = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Bundle.class);
            if (bundle == null || bundle.metadata() == null || bundle.neu() == null || bundle.hypixelJson() == null
                    || bundle.metadata().cacheVersion() != SyncMetadata.CACHE_VERSION
                    || bundle.metadata().schemaVersion() != SyncMetadata.SCHEMA_VERSION) throw new IOException("Unsupported cache schema");
            if (new HypixelItems().merge(bundle.neu(), bundle.hypixelJson()).items().isEmpty()) throw new IOException("Empty cache");
            return bundle;
        }
    }

    public void save(Bundle bundle) throws IOException {
        Files.createDirectories(directory);
        Path staged = Files.createTempFile(directory, "cache-", ".tmp");
        Path backup = null;
        try {
            try (var output = new GZIPOutputStream(Files.newOutputStream(staged))) {
                output.write(GSON.toJson(bundle).getBytes(StandardCharsets.UTF_8));
            }
            read(staged);
            Path current = directory.resolve("cache-v1.json.gz");
            if (primaryValid && Files.exists(current)) {
                try { read(current); }
                catch (Exception e) { primaryValid = false; logger.warn("Current cache is damaged; retaining previous backup"); }
            }
            if (primaryValid && Files.exists(current)) {
                backup = Files.createTempFile(directory, "backup-", ".tmp");
                Files.copy(current, backup, StandardCopyOption.REPLACE_EXISTING);
                atomicMove(backup, directory.resolve("cache-v1.previous.json.gz"));
            }
            atomicMove(staged, current);
            primaryValid = true;
        } finally {
            Files.deleteIfExists(staged);
            if (backup != null) Files.deleteIfExists(backup);
        }
    }

    static void atomicMove(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static String digest(Path path) throws IOException {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) >= 0;) hash.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
