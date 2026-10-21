package com.stellartrack.messaging.listener;

import com.stellartrack.messaging.config.MessagingProperties;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** Bounded exponential back-off with symmetric jitter for transient ingestion failures. */
@Component
public class BackoffPolicy {

    private final MessagingProperties.Retry retry;

    public BackoffPolicy(MessagingProperties properties) {
        this.retry = properties.retry();
    }

    public int maxAttempts() {
        return retry.maxAttempts();
    }

    public boolean canRetry(int completedAttempt) {
        return completedAttempt < retry.maxAttempts();
    }

    /** Delay before attempt {@code completedAttempt + 1}. */
    public Duration delayAfter(int completedAttempt) {
        double base = retry.initialInterval().toMillis() * Math.pow(retry.multiplier(), Math.max(0, completedAttempt - 1));
        double capped = Math.min(base, retry.maxInterval().toMillis());
        double jitter = retry.jitterRatio() <= 0 ? 0
                : ThreadLocalRandom.current().nextDouble(-retry.jitterRatio(), retry.jitterRatio());
        return Duration.ofMillis(Math.max(1, Math.round(capped * (1 + jitter))));
    }
}
