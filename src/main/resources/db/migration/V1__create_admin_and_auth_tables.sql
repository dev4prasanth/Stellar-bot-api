-- Single-administrator identity, login sessions and hashed refresh tokens.
-- Access JWTs carry the session id (sid); revoking a session invalidates every access token issued for it.

CREATE TABLE admin_user (
    id                   UUID PRIMARY KEY,
    username             VARCHAR(100) NOT NULL,
    password_hash        VARCHAR(255) NOT NULL,
    role                 VARCHAR(30)  NOT NULL DEFAULT 'ADMIN',
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_count   INTEGER      NOT NULL DEFAULT 0,
    locked_until         TIMESTAMPTZ,
    last_login_at        TIMESTAMPTZ,
    password_changed_at  TIMESTAMPTZ  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_admin_user_username UNIQUE (username),
    CONSTRAINT ck_admin_user_role CHECK (role IN ('ADMIN')),
    CONSTRAINT ck_admin_user_failed_count CHECK (failed_login_count >= 0)
);

-- Enforce the single-administrator scope at the database level.
CREATE UNIQUE INDEX uq_admin_user_single_admin ON admin_user ((role)) WHERE role = 'ADMIN';

CREATE TABLE auth_session (
    id                UUID PRIMARY KEY,
    admin_id          UUID         NOT NULL REFERENCES admin_user (id),
    created_at        TIMESTAMPTZ  NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    last_refreshed_at TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,
    revoked_reason    VARCHAR(40),
    CONSTRAINT ck_auth_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_session_revoked_reason CHECK (
        revoked_reason IS NULL OR revoked_reason IN ('LOGOUT', 'REFRESH_REUSE_DETECTED', 'ADMIN_DISABLED'))
);

CREATE INDEX ix_auth_session_admin_active ON auth_session (admin_id) WHERE revoked_at IS NULL;

CREATE TABLE auth_refresh_token (
    id           UUID PRIMARY KEY,
    session_id   UUID         NOT NULL REFERENCES auth_session (id),
    token_hash   VARCHAR(64)   NOT NULL,
    issued_at    TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    used_at      TIMESTAMPTZ,
    CONSTRAINT uq_auth_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_auth_refresh_token_session ON auth_refresh_token (session_id);
