# Architecture overview

Feature-based **modular monolith**. Each backend module owns its API, application services, domain rules,
persistence and (where needed) a port that an adapter implements. RabbitMQ is used only for Layer 1/2/3
ingestion; no other infrastructure is introduced.

## Communication flows

```
Browser (React) ──JWT──▶ Spring Boot API ──▶ Betfair API-NG (JSON-RPC) + Exchange Stream (TLS socket)
Layer 1/2/3 producers ──▶ RabbitMQ ──▶ Layer{1,2,3}Listener ──▶ contract validate/map ──▶ PostgreSQL ──▶ approved evaluation
Spring Boot ──▶ PostgreSQL (schema owned by Flyway)        Spring Boot ──SSE──▶ Browser (ids only; client re-fetches)
```

Layer messages are inputs, never permission to bet: every order passes the same pre-trade validation.

## Backend modules (`com.stellartrack`)

| Module | Responsibility | Layers |
| --- | --- | --- |
| `common` | API envelopes/errors, money & price ladder, capability registry, correlation ids, JSON, time | api, exception, money, capability, web |
| `auth` | Single admin registration, login, JWT issue/validation, sessions, refresh rotation, security config | api, application, security, persistence, config |
| `audit` | Append-only audit trail (`AuditTrail` port) and search | api, application, domain, persistence |
| `messaging` | RabbitMQ Layer 1/2/3 ingestion: listeners, contract SPI, pipeline, retry/DLQ, health, reprocessing | config, contract, listener, application, health, persistence, api |
| `betfair` | Adapter for Betfair: session, JSON-RPC client, typed operations, stream client, error normalization | config, session, client, api (wire types), adapter, stream, error, health |
| `market` | Catalogue cache, live prices, result capture, runner filters with data-source tracking | api, application, domain, filter, port, persistence |
| `staking` | Stake calculators (pure), staking-plan import, bank resolution port | api, application, domain, persistence |
| `capital` | Funds snapshots, statement sync, payments capability | api, application, port, persistence |
| `configuration` | Named configurations with immutable versions | api, application, domain, persistence |
| `decision` | Recommendations, immutable snapshots, Manual-mode approvals, strategy SPI, post-race outcome evaluation | api, application, domain, strategy, persistence |
| `order` | Pre-trade risk rules, durable intents, placement, reconciliation, settlements, cancellation, Stop All | api, application, risk, control, domain, port, persistence |
| `automation` | Start/Stop/Stop All, runs, single-owner lease worker, evaluator, restart recovery | api, application, config, persistence |
| `reporting` | KPI summary, trends, funnel, rejections, factor analysis, bet calculations, CSV export | api, application, domain |
| `dashboard`, `replay`, `realtime`, `system`, `trading` | Dashboard API; replay jobs; SSE hub; capabilities/info; blocked trading capabilities | |

Dependency rules (enforced by `ModuleBoundaryTest`): `replay` never depends on `order`; `decision`,
`messaging`, `market`, `staking`, `reporting` never depend on the Betfair adapter (they use ports and
`ExchangeAvailability`).

## Frontend (frontend repository, `src/`)

| Path | Contents |
| --- | --- |
| `app/` | Providers, router, query client, design tokens (`theme/tokens.ts` → MUI theme → CSS variables → Tailwind) |
| `routes/` | Thin TanStack file routes; each renders a feature page |
| `features/<feature>/` | `api.ts` (typed calls + query hooks), `types.ts`, pages, `components/` — auth, dashboard, markets, manual, automation, approvals, configurations, orders, staking, capital, payments, reports, replay, audit, settings, trading |
| `shared/` | Single HTTP client, SSE reader, reusable components (DataTable, ChartCard, KpiTile, QueryState, StatusChip, FreshnessBadge, …), chart theme, hooks, formatting |

Server state lives in TanStack Query; local React state holds UI interactions only. Access tokens are held in
memory; the refresh token is an HttpOnly cookie.

## Data model

Flyway migrations `V1`–`V10` (`src/main/resources/db/migration`). Immutable tables are protected by
triggers (`audit_event`, `bot_configuration_version`, `decision_snapshot`, `approval_decision`). Reporting views
`v_settled_bet` and `v_decision` define counting grain and classifications once for every screen and export.
