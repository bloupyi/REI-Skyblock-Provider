package dev.reisb.data;

import dev.reisb.config.ProviderConfig;
import java.time.Duration;

public final class SyncSchedule {
    public enum Trigger { STARTUP, JOIN, PERIODIC, RELOAD, MANUAL }
    private long lastAutomaticCheck = Long.MIN_VALUE;
    private final long startedAt;
    public SyncSchedule() { this(System.currentTimeMillis()); }
    public SyncSchedule(long startedAt) { this.startedAt = startedAt; }

    public synchronized boolean due(ProviderConfig config, Trigger trigger, long now) {
        if (trigger == Trigger.MANUAL) return true;
        if (!config.enabled || !config.autoUpdateRepository) return false;
        if (trigger == Trigger.STARTUP) return config.checkUpdatesOnStartup;
        if (trigger == Trigger.RELOAD) return true;
        long elapsed = lastAutomaticCheck == Long.MIN_VALUE ? Long.MAX_VALUE : Math.max(0, now - lastAutomaticCheck);
        if (trigger == Trigger.JOIN) return config.checkUpdatesOnSkyblockJoin && elapsed >= Duration.ofMinutes(5).toMillis();
        return (lastAutomaticCheck == Long.MIN_VALUE ? Math.max(0, now - startedAt) : elapsed) >= Duration.ofMinutes(config.intervalMinutes()).toMillis();
    }

    public synchronized void checked(long now) { lastAutomaticCheck = now; }
    public synchronized long next(ProviderConfig config, long now) {
        return lastAutomaticCheck == Long.MIN_VALUE ? now : lastAutomaticCheck + Duration.ofMinutes(config.intervalMinutes()).toMillis();
    }
}
