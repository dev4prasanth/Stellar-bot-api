package com.stellartrack.auth.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "stellartrack.security")
public record SecurityProperties(
        Jwt jwt,
        RefreshCookie refreshCookie,
        Cors cors,
        Login login,
        boolean publicApiDocs) {

    public record Jwt(String secret, String issuer, String audience, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    public record RefreshCookie(boolean secure) {
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null
                    ? List.of()
                    : allowedOrigins.stream().filter(origin -> !origin.isBlank()).toList();
        }
    }

    public record Login(int maxFailedAttempts, Duration lockoutDuration, int maxAttemptsPerMinute) {
    }

}
