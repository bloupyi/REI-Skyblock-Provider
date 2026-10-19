package dev.reisb.config;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

public class ProviderConfigTest {
    @TempDir Path directory;

    @Test
    void olderConfigurationGetsDefaultsWithoutLosingPreferences() throws Exception {
        Path file = directory.resolve("rei_skyblock_provider.json");
        Files.writeString(file, "{\"enabled\":false,\"autoUpdateRepository\":false,\"unknownPreference\":\"kept\"}");
        var config = ProviderConfig.load(file, LoggerFactory.getLogger("test"));
        assertFalse(config.enabled);
        assertFalse(config.autoUpdateRepository);
        assertTrue(config.checkUpdatesOnStartup);
        assertEquals(30, config.updateIntervalMinutes);
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertTrue(json.has("enableAutomaticReiRefresh"));
        assertEquals("kept", json.get("unknownPreference").getAsString());
        assertFalse(json.get("autoUpdateRepository").getAsBoolean());
    }

    @Test
    void invalidConfigurationIsNotOverwritten() throws Exception {
        Path file = directory.resolve("rei_skyblock_provider.json");
        Files.writeString(file, "{broken");
        assertNotNull(ProviderConfig.load(file, LoggerFactory.getLogger("test")));
        assertEquals("{broken", Files.readString(file));
    }
}
