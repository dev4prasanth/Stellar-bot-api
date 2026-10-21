# Deployment, migrations, backup and recovery

The application is hosting-agnostic: two container images, each built and released from its own repository
(backend: `Dockerfile` here; frontend: `Dockerfile` in the frontend repository), PostgreSQL 18 and RabbitMQ 4.
The two are deployed independently; the only runtime link between them is the frontend's `BACKEND_URL`.

## Topology

```
Internet ──HTTPS──▶ reverse proxy (TLS, rate limits) ──▶ frontend nginx (:8080) ──/api──▶ backend (:8080)
                                                                         backend ──▶ PostgreSQL, RabbitMQ, Betfair
```

Serve the SPA and `/api` from **one origin** (the frontend's nginx does this, proxying `/api` to `BACKEND_URL`) so
the SameSite=Strict refresh cookie works. Do not expose the backend on a separate public origin. Terminate TLS at
the reverse proxy, forward `X-Forwarded-*` headers, disable proxy buffering for `/api/v1/events/stream`, and add a
rate limit for `/api/v1/auth/*` (the in-app limiter is per instance). Run the backend with
`SPRING_PROFILES_ACTIVE=prod`.

## Environment

Use `.env.example` as the template (placeholders only). Required: database URL/credentials,
RabbitMQ connection, `ST_JWT_SECRET` (≥ 32 random bytes, base64), `ST_CORS_ALLOWED_ORIGINS`, Betfair settings when
enabling the exchange. Store secrets in the platform's secret store; mount the Betfair keystore read-only.

- **Betfair keystore (`.p12`)**: never committed (`*.p12` is ignored). Keep one copy per environment in the
  secret store or on the host (e.g. `/etc/stellartrack/certs`, `chmod 600`), point `ST_BETFAIR_CERT_DIR` at that
  folder and `ST_BETFAIR_CERT_FILE` at the file name; Compose mounts it at `/run/secrets/betfair`. Locally,
  `certificate/` is the default folder. Tests need no certificate (they run with Betfair disabled).
- **Frontend**: set `BACKEND_URL` on the frontend container to the backend's base URL on the private network
  (e.g. `http://stellartrack-backend:8080`). Set `ST_CORS_ALLOWED_ORIGINS` here to the public origin the SPA is
  served from.

## Flyway

- Migrations run automatically at startup (`validate-on-migrate=true`, `clean-disabled=true`); Hibernate only
  validates (`ddl-auto=validate`).
- Naming: `V<n>__<description>.sql`, ordered, immutable once applied. Never edit an applied migration — add `V<n+1>`.
- Manual commands (Flyway CLI or Maven plugin with the same URL/credentials):
  `flyway info`, `flyway validate`, `flyway migrate`. **Never run `flyway clean` against production.**
- Monitoring: `GET /actuator/flyway` (admin) and `GET /api/v1/system/info` show the applied version and
  pending migrations. A failed migration stops startup (readiness never turns UP).
- Recovery from a failed migration: restore from backup or write a forward-fix migration; use `flyway repair`
  only to clear a failed entry after fixing the cause in a new version.
- Use a dedicated migration role with DDL rights in deployment pipelines if the runtime role is restricted.

## Health

`/actuator/health/liveness`, `/actuator/health/readiness` (public); `/actuator/health` details (admin) include
the `ingestion` indicator (DOWN only if an enabled consumer lost its broker connection).

## Backup and restore

- Daily `pg_dump -Fc stellartrack > stellartrack-$(date +%F).dump` plus WAL archiving / PITR where available;
  keep backups encrypted and test restores monthly.
- Restore: stop the backend, `pg_restore -c -d stellartrack <dump>`, start the backend (Flyway validates).
- Back up the replay storage directory (`ST_REPLAY_STORAGE_DIR`) alongside the database.
- RabbitMQ: definitions export from the management UI; queued messages are re-deliverable and idempotent.
- After any restore, run **Settings → Reconciliation → run** so orders and settlements re-sync from Betfair.

## Live activation checklist

1. Configure Betfair credentials with the stream disabled; confirm read-only calls (Capital → Refresh funds,
   Trading → Race markets catalogue) mark the capability **Verified**.
2. Real-money placement: one human-supervised minimum-stake manual bet, explicitly approved, then confirm
   reconciliation, settlement and the calculation report.
3. Only then start a configuration, first in **Manual** trading mode (every proposal confirmed by you) before switching
   to Automatic. Starting the application
   never starts betting; restarts stop all configurations (`NEVER_RESUME`).
