package com.kestrel.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class LoginThrottle {
    private static final int MAX_KEYS = 10_000;
    private static final int IP_LIMIT = 30;
    private static final int ACCOUNT_LIMIT = 5;
    private static final Duration IP_WINDOW = Duration.ofMinutes(1);
    private static final Duration ACCOUNT_WINDOW = Duration.ofMinutes(5);

    private record Window(int count, Instant expiresAt) {}

    private final Clock clock;
    private final Map<String, Window> sources = new HashMap<>();
    private final Map<String, Window> accounts = new HashMap<>();

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    synchronized void checkSource(String source) {
        Instant now = clock.instant();
        Window window = active(sources, source, now);
        reject(window, IP_LIMIT, now);
        record(sources, source, window, now, IP_WINDOW);
    }

    synchronized void checkAccount(String username) {
        Instant now = clock.instant();
        reject(active(accounts, username, now), ACCOUNT_LIMIT, now);
    }

    synchronized void failed(String username) {
        Instant now = clock.instant();
        Window window = active(accounts, username, now);
        record(accounts, username, window, now, ACCOUNT_WINDOW);
        Window updated = accounts.get(username);
        if (updated.count() == ACCOUNT_LIMIT) {
            accounts.put(username, new Window(ACCOUNT_LIMIT, now.plus(ACCOUNT_WINDOW)));
        }
    }

    synchronized void succeeded(String username) {
        accounts.remove(username);
    }

    private static Window active(Map<String, Window> windows, String key, Instant now) {
        Window window = windows.get(key);
        if (window != null && !window.expiresAt().isAfter(now)) {
            windows.remove(key);
            return null;
        }
        return window;
    }

    private static void reject(Window window, int limit, Instant now) {
        if (window != null && window.count() >= limit) {
            long millis = Duration.between(now, window.expiresAt()).toMillis();
            throw ApiProblem.rateLimited(Math.max(1, (millis + 999) / 1000));
        }
    }

    private static void record(Map<String, Window> windows, String key, Window previous, Instant now, Duration ttl) {
        if (previous == null && windows.size() >= MAX_KEYS) {
            windows.values().removeIf(window -> !window.expiresAt().isAfter(now));
            if (windows.size() >= MAX_KEYS) {
                throw ApiProblem.rateLimited(ttl.toSeconds());
            }
        }
        var next = previous == null ? new Window(1, now.plus(ttl))
                : new Window(previous.count() + 1, previous.expiresAt());
        windows.put(key, next);
    }
}
