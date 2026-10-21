package com.stellartrack.betfair.config;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Betfair Exchange API configuration. Credentials, the application key and the client certificate keystore
 * are supplied only through secure backend configuration and are never exposed to the frontend or logs.
 */
@ConfigurationProperties(prefix = "stellartrack.betfair")
public record BetfairProperties(
        boolean enabled,
        String appKey,
        String username,
        String password,
        String certKeystore,
        String certKeystorePassword,
        URI certLoginUrl,
        URI identityUrl,
        URI bettingUrl,
        URI accountUrl,
        URI accountManagementUrl,
        String locale,
        Duration keepAliveInterval,
        Duration connectTimeout,
        Duration readTimeout,
        Duration orderTimeout,
        ReadRetry readRetry,
        Stream stream,
        Staleness staleness,
        String customerStrategyRef,
        BigDecimal minimumStake) {

    public record ReadRetry(int maxAttempts, Duration initialInterval) {
    }

    public record Stream(boolean enabled, String host, int port, Duration heartbeat, Duration conflate,
            Duration reconnectMaxBackoff) {
    }

    public record Staleness(Duration marketBookMaxAge, Duration fundsMaxAge) {
    }

    /** True when every credential needed for a non-interactive (certificate) login is present. */
    public boolean credentialsConfigured() {
        return notBlank(appKey) && notBlank(username) && notBlank(password) && notBlank(certKeystore)
                && !appKey.startsWith("<");
    }

    /** The Betfair settings still to be provided, in plain words for the administrator (empty when complete). */
    public java.util.List<String> missingCredentials() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (!notBlank(appKey) || appKey.startsWith("<")) {
            missing.add("Betfair application key");
        }
        if (!notBlank(username) || !notBlank(password)) {
            missing.add("Betfair account username and password");
        }
        if (!notBlank(certKeystore)) {
            missing.add("Betfair client certificate");
        }
        return missing;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        return "BetfairProperties[enabled=" + enabled + ", credentialsConfigured=" + credentialsConfigured() + "]";
    }
}
