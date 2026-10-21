package com.stellartrack.auth.application;

import com.stellartrack.auth.config.SecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Sliding one-minute window of login attempts per client address. Complements the persistent per-account
 * lockout in {@link AuthService}. In-memory state is sufficient for the single-instance deployment; a
 * multi-instance deployment must also rate-limit at the reverse proxy (see docs/operations/deployment.md).
 */
@Component
public class LoginRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final int maxAttemptsPerWindow;
    private final Clock clock;

    public LoginRateLimiter(SecurityProperties properties, Clock clock) {
        this.maxAttemptsPerWindow = properties.login().maxAttemptsPerMinute();
        this.clock = clock;
    }

    /** Records an attempt and throws when the client has exceeded the allowed rate. */
    public void acquire(String clientKey) {
        Instant now = clock.instant();
        if (attempts.size() > MAX_TRACKED_CLIENTS) {
            evictIdle(now);
        }
        Deque<Instant> window = attempts.computeIfAbsent(clientKey, key -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) {
                window.pollFirst();
            }
            if (window.size() >= maxAttemptsPerWindow) {
                throw new TooManyLoginAttemptsException();
            }
            window.addLast(now);
        }
    }

    private void evictIdle(Instant now) {
        attempts.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                Instant last = entry.getValue().peekLast();
                return last == null || last.isBefore(now.minus(WINDOW));
            }
        });
    }
}
