package com.stellartrack.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_user")
public class AdminUserEntity {

    public static final String ROLE_ADMIN = "ADMIN";

    @Id
    private UUID id;

    @Column(nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminUserEntity() {
    }

    public static AdminUserEntity create(String username, String passwordHash, Instant now) {
        AdminUserEntity entity = new AdminUserEntity();
        entity.id = UUID.randomUUID();
        entity.username = username;
        entity.passwordHash = passwordHash;
        entity.role = ROLE_ADMIN;
        entity.enabled = true;
        entity.passwordChangedAt = now;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void recordFailedLogin(int maxAttempts, java.time.Duration lockout, Instant now) {
        failedLoginCount++;
        if (failedLoginCount >= maxAttempts) {
            lockedUntil = now.plus(lockout);
            failedLoginCount = 0;
        }
        updatedAt = now;
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getLockedUntil() { return lockedUntil; }
}
