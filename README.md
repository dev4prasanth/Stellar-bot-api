# Stellar Track — Backend

Horse-racing bot management for a single administrator: Betfair Exchange integration, one Trading screen with a
Manual/Automatic/Watch mode menu (Manual: every proposed bet is confirmed in a dialog; Automatic: placed without
approval; Watch: simulated bets only, against a virtual capital), RabbitMQ ingestion of Layer 1/2/3 messages,
capital tracking, Historical Replay, audit logs and graph-based reports with per-bet calculation drill-downs.

> **Integration status is reported truthfully in the app (Settings → Integrations) and in
> [docs/integrations/feature-matrix.md](docs/integrations/feature-matrix.md).** Nothing is simulated at runtime
> except Watch Mode, which never places a real bet, keeps its records apart from live ones and labels every figure
> **Simulated**.

The web UI lives in the separate **frontend repository** and is deployed independently; it reaches this API only
through its nginx `/api` proxy (`BACKEND_URL`). See [Deployment](docs/operations/deployment.md).

## Repository layout

| Path | Contents |
| --- | --- |
| `src/` | Java 21, Spring Boot 4.1 modular monolith, Flyway migrations, tests |
| `docker-compose.yml` | Local PostgreSQL/RabbitMQ (and the backend image with `--profile app`) |
| `.env.example` | Template for `.env`, the settings Compose and the `local` profile read |
| `certificate/` | Local-only Betfair keystore folder (`*.p12` is git-ignored; never commit it) |
| `docs/` | Architecture, integrations, operations, reporting definitions |

See [docs/architecture/overview.md](docs/architecture/overview.md) for the module structure.

## Quick start (local development)

Prerequisites: Java 21, Maven 3.9, Docker.

```bash
# 1. Infrastructure
cp .env.example .env                                # fill every placeholder (never commit .env)
docker compose up -d postgres rabbitmq

# 2. Backend (Flyway migrates the schema on start; Hibernate only validates it)
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run      # the local profile loads .env automatically
```

The API listens on `http://localhost:8080`. Run the frontend from its own repository (`npm run dev` proxies `/api`
here). To run the backend in Docker as well:

```bash
docker compose --profile app up -d --build
```

The Betfair keystore goes in `certificate/` locally (set `ST_BETFAIR_CERT_FILE` to its file name); see
[Deployment → Environment](docs/operations/deployment.md#environment) for servers.

On first visit choose **First-time setup** on the sign-in page to create the single administrator
(`POST /api/v1/auth/register`; refused once an administrator exists).

### Layer 1/2/3 messages (Race Signals)

Layer 1 (`stellartrackbackend`), Layer 2 and Layer 3 publish to the topic exchange `stellar-track-exchange` on the
broker at `localhost:5672`, **vhost `/`, user `guest`/`guest`**. Phase 2 must use the **same broker and vhost**, or it
receives nothing. The `local` profile defaults to exactly that and subscribes with its own queues, so Layer 2/3 keep
all of their messages:

| Variable | Local default |
| --- | --- |
| `ST_RABBITMQ_VHOST` / `ST_RABBITMQ_USERNAME` / `ST_RABBITMQ_PASSWORD` | `/` / `guest` / `guest` |
| `ST_LAYER1_QUEUE`, `ST_LAYER1_ROUTING_KEYS` | `stellartrack.phase2.layer1`, `layer1.prediction,layer1-predictions-queue` |
| `ST_LAYER2_QUEUE`, `ST_LAYER2_ROUTING_KEYS` | `stellartrack.phase2.layer2`, `state-change` |
| `ST_LAYER3_QUEUE`, `ST_LAYER3_ROUTING_KEYS` | `stellartrack.phase2.layer3`, `layer3.reranked` |
| `ST_LAYERn_EXCHANGE`, `ST_LAYERn_DECLARE_SUBSCRIPTION`, `ST_LAYERn_REJECT_STALE` | `stellar-track-exchange`, `true`, `false` |

Values in `.env` override these defaults, so an older `.env` with `ST_RABBITMQ_VHOST=stellartrack` or
`ST_LAYERn_ENABLED=false` must be updated (see `.env.example`). Then start the layers, trigger
`POST /race/manual-publish` on stellartrackbackend (or replay CSVs with `race-hit-service`) and open **Race Signals**.
Details: [RabbitMQ contracts](docs/integrations/rabbitmq-contracts.md).

Starting the application never starts betting. Betfair stays **disabled** until `ST_BETFAIR_ENABLED=true` and
credentials plus the client certificate are configured. Layer messages are inputs only; they never place bets.

## Verification commands

| Command | What it checks |
| --- | --- |
| `mvn verify` | 102 unit tests + 24 integration tests (PostgreSQL 18 & RabbitMQ 4 via Testcontainers, Flyway, Hibernate validation, auth, ingestion, orders, reports) |

## Key documents

- [Feature-to-integration matrix](docs/integrations/feature-matrix.md) — status of every capability and missing dependencies
- [Betfair integration](docs/integrations/betfair.md)
- [RabbitMQ contract checklist & blocked-contract register](docs/integrations/rabbitmq-contracts.md)
- [RabbitMQ retry/DLQ operations](docs/operations/rabbitmq-operations.md)
- [Security, roles and token lifecycle](docs/operations/security.md)
- [Deployment, Flyway, backup & recovery](docs/operations/deployment.md)
- [KPI glossary](docs/reporting/kpi-glossary.md) · [Business rules](docs/reporting/business-rules.md) · [Rejection reasons](docs/reporting/rejection-reasons.md)
# Stellar-bot-api
