package com.stellartrack.auth.api;

import com.stellartrack.auth.config.SecurityProperties;
import com.stellartrack.common.api.ApiPaths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Builds the HttpOnly, SameSite=Strict refresh-token cookie, scoped to the auth endpoints only. */
@Component
public class RefreshCookies {

    public static final String NAME = "st_refresh";

    private final SecurityProperties properties;
    private final Clock clock;

    public RefreshCookies(SecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public ResponseCookie issue(String value, Instant expiresAt) {
        Duration maxAge = Duration.between(clock.instant(), expiresAt);
        return base(value).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.refreshCookie().secure())
                .sameSite("Strict")
                .path(ApiPaths.AUTH);
    }
}
