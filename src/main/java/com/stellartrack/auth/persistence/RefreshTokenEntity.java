package com.stellartrack.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Refresh token record. Only the SHA-256 hash of the opaque token value is stored. */
@Entity
@Table(name = "auth_refresh_token")
public class RefreshTokenEntity {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected RefreshTokenEntity() {
    }

    public static RefreshTokenEntity issue(UUID sessionId, String tokenHash, Instant now, Instant expiresAt) {
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.id = UUID.randomUUID();
        entity.sessionId = sessionId;
        entity.tokenHash = tokenHash;
        entity.issuedAt = now;
        entity.expiresAt = expiresAt;
        return entity;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void markUsed(Instant now) {
        usedAt = now;
    }

    public UUID getSessionId() { return sessionId; }
    public Instant getExpiresAt() { return expiresAt; }
}
