package com.stellartrack.auth.security;

import com.stellartrack.auth.persistence.AuthSessionRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Server-side revocation check: an access token is only valid while the login session in its {@code sid}
 * claim is active. Logout and refresh-token reuse revoke the session, invalidating outstanding JWTs.
 */
@Component
public class SessionActiveValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error SESSION_INVALID =
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Session is no longer active", null);

    private final AuthSessionRepository sessions;
    private final Clock clock;

    public SessionActiveValidator(AuthSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String sid = token.getClaimAsString(JwtClaims.SESSION_ID);
        if (sid == null) {
            return OAuth2TokenValidatorResult.failure(SESSION_INVALID);
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(sid);
        } catch (IllegalArgumentException e) {
            return OAuth2TokenValidatorResult.failure(SESSION_INVALID);
        }
        boolean active = sessions.findById(sessionId)
                .filter(session -> session.getAdminId().toString().equals(token.getSubject()))
                .map(session -> session.isActive(clock.instant()))
                .orElse(false);
        return active ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(SESSION_INVALID);
    }
}
