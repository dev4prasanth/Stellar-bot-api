package com.stellartrack.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A login session. Its id is embedded as the {@code sid} claim of every access token issued for it. */
@Entity
@Table(name = "auth_session")
public class AuthSessionEntity {

    public static final String REVOKED_LOGOUT = "LOGOUT";
    public static final String REVOKED_REUSE = "REFRESH_REUSE_DETECTED";

    @Id
    private UUID id;

    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason")
    private String revokedReason;

    protected AuthSessionEntity() {
    }

    public static AuthSessionEntity open(UUID adminId, Instant now, Instant expiresAt) {
        AuthSessionEntity session = new AuthSessionEntity();
        session.id = UUID.randomUUID();
        session.adminId = adminId;
        session.createdAt = now;
        session.expiresAt = expiresAt;
        return session;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(String reason, Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
            revokedReason = reason;
        }
    }

    public void markRefreshed(Instant now) {
        lastRefreshedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getAdminId() { return adminId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
