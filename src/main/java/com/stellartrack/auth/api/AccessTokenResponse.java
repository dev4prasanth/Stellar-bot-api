package com.stellartrack.auth.api;

import java.time.Instant;

public record AccessTokenResponse(String accessToken, String tokenType, Instant expiresAt, String username) {
}
