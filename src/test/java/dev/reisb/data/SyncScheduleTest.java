package dev.reisb.data;

import dev.reisb.config.ProviderConfig;
import java.time.Duration;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.reisb.data.SyncSchedule.Trigger.*;

public class SyncScheduleTest {
    @Test
    void defaultIntervalAndJoinCooldownAndOptions() {
        var config = new ProviderConfig();
        var schedule = new SyncSchedule(0);
        assertFalse(schedule.due(config, PERIODIC, 1000));
        assertTrue(schedule.due(config, JOIN, 1000));
        assertTrue(schedule.due(config, STARTUP, 1000));
        schedule.checked(1000);
        assertFalse(schedule.due(config, PERIODIC, 1000 + Duration.ofMinutes(29).toMillis()));
        assertTrue(schedule.due(config, PERIODIC, 1000 + Duration.ofMinutes(30).toMillis()));
        assertFalse(schedule.due(config, JOIN, 2000));
        assertTrue(schedule.due(config, JOIN, 1000 + Duration.ofMinutes(5).toMillis()));
        config.checkUpdatesOnStartup = false;
        config.checkUpdatesOnSkyblockJoin = false;
        assertFalse(schedule.due(config, STARTUP, 0));
        assertFalse(schedule.due(config, JOIN, Long.MAX_VALUE));
        config.autoUpdateRepository = false;
        assertFalse(schedule.due(config, PERIODIC, Long.MAX_VALUE));
        assertTrue(schedule.due(config, MANUAL, 0));
        config.updateIntervalMinutes = 0;
        assertEquals(1, config.intervalMinutes());
    }

    @Test
    void quotaAndExponentialBackoffAreBoundedAndPersistent() {
        var headers = HttpHeaders.of(Map.of("X-RateLimit-Remaining", List.of("0"), "X-RateLimit-Reset", List.of("100")), (a, b) -> true);
        assertEquals(99000, SourceHealth.quotaDelay(headers, 1000));
        var health = new SourceHealth();
        health.success("Online", 1000, headers);
        assertFalse(health.available(2000, true));
        health.failure("test", 1000, 120000);
        assertEquals(121000, health.nextAttempt());
        var restored = new SourceHealth();
        restored.restore(health.snapshot());
        assertFalse(restored.available(120000, true));
        for (int i = 0; i < 30; i++) restored.failure("test", 1000, 0);
        assertEquals(1000 + Duration.ofMinutes(30).toMillis(), restored.nextAttempt());
    }
}
