package com.stellartrack.auth.application;

import com.stellartrack.auth.config.SecurityProperties;
import com.stellartrack.auth.persistence.AdminUserEntity;
import com.stellartrack.auth.persistence.AdminUserRepository;
import com.stellartrack.auth.persistence.AuthSessionEntity;
import com.stellartrack.auth.persistence.AuthSessionRepository;
import com.stellartrack.auth.persistence.RefreshTokenEntity;
import com.stellartrack.auth.persistence.RefreshTokenRepository;
import com.stellartrack.auth.security.AccessTokenIssuer;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.util.Hashing;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrator authentication and session lifecycle.
 *
 * <p>Invalidation model: each login opens a server-side session (absolute lifetime = refresh TTL). Access
 * tokens are short-lived and carry the session id, which is checked on every request. Refresh tokens are
 * opaque, single-use, stored only as SHA-256 hashes and rotated on each refresh; presenting an already-used
 * refresh token revokes the whole session (token theft signal). Logout revokes the session, so every access
 * token issued for it is rejected immediately — not merely forgotten by the browser.
 */
@Service
public class AuthService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final AdminUserRepository admins;
    private final AuthSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final LoginRateLimiter rateLimiter;
    private final SecurityProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String timingEqualizerHash;

    public AuthService(AdminUserRepository admins, AuthSessionRepository sessions, RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder, AccessTokenIssuer accessTokenIssuer, LoginRateLimiter rateLimiter,
            SecurityProperties properties, Clock clock) {
        this.admins = admins;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
        this.timingEqualizerHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public AuthTokens login(String username, String password, String clientKey) {
        rateLimiter.acquire(clientKey);
        Instant now = clock.instant();
        Optional<AdminUserEntity> candidate = admins.findByUsername(username);
        if (candidate.isEmpty()) {
            passwordEncoder.matches(password, timingEqualizerHash);
            throw AuthenticationFailedException.invalidCredentials();
        }
        AdminUserEntity admin = candidate.get();
        if (!admin.isEnabled() || admin.isLocked(now)) {
            passwordEncoder.matches(password, timingEqualizerHash);
            throw AuthenticationFailedException.invalidCredentials();
        }
        if (!passwordEncoder.matches(password, admin.getPasswordHash())) {
            admin.recordFailedLogin(properties.login().maxFailedAttempts(), properties.login().lockoutDuration(), now);
            throw AuthenticationFailedException.invalidCredentials();
        }
        admin.recordSuccessfulLogin(now);
        AuthSessionEntity session = sessions.save(
                AuthSessionEntity.open(admin.getId(), now, now.plus(properties.jwt().refreshTokenTtl())));
        return issueTokens(admin, session, now);
    }

    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public AuthTokens refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw AuthenticationFailedException.sessionExpired();
        }
        Instant now = clock.instant();
        RefreshTokenEntity token = refreshTokens.findByTokenHash(Hashing.sha256Hex(rawRefreshToken))
                .orElseThrow(AuthenticationFailedException::sessionExpired);
        AuthSessionEntity session = sessions.findById(token.getSessionId())
                .orElseThrow(AuthenticationFailedException::sessionExpired);
        if (token.isUsed()) {
            session.revoke(AuthSessionEntity.REVOKED_REUSE, now);
            throw AuthenticationFailedException.sessionExpired();
        }
        if (token.isExpired(now) || !session.isActive(now)) {
            throw AuthenticationFailedException.sessionExpired();
        }
        AdminUserEntity admin = admins.findById(session.getAdminId())
                .filter(AdminUserEntity::isEnabled)
                .orElseThrow(AuthenticationFailedException::sessionExpired);
        token.markUsed(now);
        session.markRefreshed(now);
        return issueTokens(admin, session, now);
    }

    /** Revokes the session identified by the refresh token and/or the access token's session id. */
    @Transactional
    public void logout(String rawRefreshToken, UUID accessTokenSessionId) {
        Instant now = clock.instant();
        Optional<UUID> sessionId = Optional.ofNullable(accessTokenSessionId);
        if (sessionId.isEmpty() && rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            sessionId = refreshTokens.findByTokenHash(Hashing.sha256Hex(rawRefreshToken))
                    .map(RefreshTokenEntity::getSessionId);
        }
        sessionId.flatMap(sessions::findById).ifPresent(session -> session.revoke(AuthSessionEntity.REVOKED_LOGOUT, now));
    }

    @Transactional(readOnly = true)
    public AdminProfile profile(UUID adminId, UUID sessionId) {
        AdminUserEntity admin = admins.findById(adminId).orElseThrow(() -> new NotFoundException("Administrator", adminId));
        AuthSessionEntity session = sessions.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Session", sessionId));
        return new AdminProfile(admin.getUsername(), admin.getRole(), admin.getLastLoginAt(),
                session.getCreatedAt(), session.getExpiresAt());
    }

    public record AdminProfile(String username, String role, Instant lastLoginAt, Instant sessionStartedAt,
            Instant sessionExpiresAt) {
    }

    private AuthTokens issueTokens(AdminUserEntity admin, AuthSessionEntity session, Instant now) {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        String rawRefresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(RefreshTokenEntity.issue(session.getId(), Hashing.sha256Hex(rawRefresh), now,
                session.getExpiresAt()));
        AccessTokenIssuer.IssuedToken access = accessTokenIssuer.issue(admin.getId(), admin.getUsername(),
                admin.getRole(), session.getId(), session.getExpiresAt());
        return new AuthTokens(access.value(), access.expiresAt(), rawRefresh, session.getExpiresAt(), admin.getUsername());
    }
}
