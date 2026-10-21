package com.stellartrack.auth.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The authenticated administrator, derived from a validated access token. */
public record AdminPrincipal(UUID adminId, String username, UUID sessionId) {

    public static AdminPrincipal from(Jwt jwt) {
        return new AdminPrincipal(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(JwtClaims.USERNAME),
                UUID.fromString(jwt.getClaimAsString(JwtClaims.SESSION_ID)));
    }

    /** Resolves the current principal; only valid inside an authenticated request. */
    public static AdminPrincipal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return from(token.getToken());
        }
        throw new IllegalStateException("No authenticated administrator in the current context");
    }

    /** Stable actor identifier used in audit events and created_by columns. */
    public String actorId() {
        return username;
    }
}
