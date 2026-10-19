package dev.reisb.data;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class SourceHealth {
    private volatile String state = "Not checked";
    private volatile int failures;
    private volatile long nextAttempt;
    private volatile long quotaUntil;
    public record State(String state, int failures, long nextAttempt, long quotaUntil) {}
    public State snapshot() { return new State(state, failures, nextAttempt, quotaUntil); }
    public void restore(State saved) {
        if (saved == null) return;
        state = saved.state(); failures = Math.max(0, saved.failures());
        nextAttempt = saved.nextAttempt(); quotaUntil = saved.quotaUntil();
    }

    public boolean available(long now) { return now >= nextAttempt; }
    public boolean available(long now, boolean manual) { return available(now) || (manual && failures == 0 && now >= quotaUntil); }
    public long nextAttempt() { return nextAttempt; }
    public int failures() { return failures; }
    public String state() { return state + (failures == 0 ? "" : " | retry=" + java.time.Instant.ofEpochMilli(nextAttempt)); }

    public void success(String state, long now, HttpHeaders headers) {
        this.state = state;
        failures = 0;
        long quota = quotaDelay(headers, now);
        quotaUntil = now + quota;
        nextAttempt = now + Math.max(Duration.ofMinutes(1).toMillis(), quota);
    }

    public void failure(String message, long now, long retryAfter) {
        failures = Math.min(20, failures + 1);
        long delay = Math.min(Duration.ofMinutes(30).toMillis(), 60000L << Math.min(10, failures - 1));
        nextAttempt = now + Math.max(delay, retryAfter);
        state = "Failed: " + message;
    }

    public static long quotaDelay(HttpHeaders headers, long now) {
        long delay = 0;
        String retry = headers.firstValue("Retry-After").orElse("");
        try { delay = Math.max(delay, Long.parseLong(retry) * 1000); }
        catch (NumberFormatException e) {
            try { delay = Math.max(delay, ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now); }
            catch (RuntimeException ignored) {}
        }
        if (headers.firstValue("X-RateLimit-Remaining").orElse("1").equals("0")) {
            try { delay = Math.max(delay, Long.parseLong(headers.firstValue("X-RateLimit-Reset").orElse("0")) * 1000 - now); }
            catch (NumberFormatException ignored) {}
        }
        if (headers.firstValue("RateLimit-Remaining").orElse("1").equals("0")) {
            try { delay = Math.max(delay, Long.parseLong(headers.firstValue("RateLimit-Reset").orElse("0")) * 1000); }
            catch (NumberFormatException ignored) {}
        }
        return Math.max(0, Math.min(Duration.ofDays(7).toMillis(), delay));
    }
}
