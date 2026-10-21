package com.stellartrack.auth.application;

import java.time.Instant;

/** Result of login/refresh. The refresh token value leaves the backend only as an HttpOnly cookie. */
public record AuthTokens(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        String username) {
}
