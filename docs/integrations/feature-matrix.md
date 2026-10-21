# Feature-to-integration matrix

Status vocabulary: **Implemented** (code complete, not yet exercised against the real provider here),
**Verified** (exercised against a real dependency), **Blocked** (missing contract/rule), **Disabled** (configuration).
The running application reports live status at `GET /api/v1/system/capabilities`.

| Feature | Required data / rule | Provider operation / service | Frontend | Backend | Status | Missing dependencies |
| --- | --- | --- | --- | --- | --- | --- |
| Admin login, JWT, logout, refresh | — | Spring Security, Nimbus JWT | `features/auth` | `auth` | Verified (automated tests, local run) | — |
| Audit logs | — | PostgreSQL append-only table | `features/audit` | `audit` | Verified (tests) | — |
| Flyway schema | — | Flyway 12 | Settings → System | `db/migration` | Verified (fresh install on PostgreSQL 18) | — |
| Betfair session (cert login, keep-alive) | App key, account, client cert | identitysso-cert `certlogin`, `keepAlive`, `logout` | Header status | `betfair.session` | Implemented, disabled until configured | Betfair credentials & certificate |
| Events / markets / prices | Betfair market data | `listEvents`, `listMarketCatalogue`, `listMarketBook` | Trading → Race markets | `betfair.adapter`, `market` | Implemented | Credentials; live/delayed app key |
| Streaming prices & orders | Betfair stream access | Exchange Stream API (`marketSubscription`, `orderSubscription`) | Freshness badges | `betfair.stream` | Implemented (off by default) | Credentials; enable `ST_BETFAIR_STREAM_ENABLED` |
| Manual bet placement | Funds, prices | `placeOrders` | Bet ticket | `order` | Implemented; real-money verification needs explicit approval | Credentials; human-approved live test |
| Cancel unmatched | — | `cancelOrders` | Order detail | `order` | Implemented | Credentials |
| Order reconciliation | — | `listCurrentOrders` (by customerOrderRef/market) | Settings → Reconciliation | `order.application` | Implemented; logic verified with test doubles | Credentials |
| Settlements & commission | — | `listClearedOrders` (bet and MARKET grain) | Reports, Bet History | `order`, `reporting` | Implemented; logic verified with test doubles | Credentials |
| Funds & statement | — | `getAccountFunds`, `getAccountDetails`, `getAccountStatement` | Capital | `capital` | Implemented | Credentials |
| Deposits / withdrawals | Authorized payments API | None verified | Capital → Payments | `capital` | **Blocked** | An authorized payments API (link to official site shown) |
| Basic & advanced filters | Field sources (see below) | Market book, description, runner metadata | Trading → Race markets, Configurations | `market.filter` | Implemented | Race classification needs a provider (**Blocked**) |
| Named configurations & versions | — | — | Configurations | `configuration` | Verified (tests) | — |
| Staking methods & plan import | — | — | Ticket, Configurations, Settings | `staking` | Verified (tests) | — |
| Trading mode switch (Manual / Automatic) | — | — | Trading | `order.control`, `automation` | Verified (tests) | — |
| Watch Mode (simulated bets, virtual capital, reports) | Market data (delayed accepted), official results, market base rate | `listMarketBook` / stream (read only); never `placeOrders` | Trading → mode menu → Watch | `watch` | Implemented; domain verified by unit tests, flow covered by `WatchFlowIT` | Credentials; validate settlement maths against real cleared orders |
| Automatic placement (market criteria) | Explicit admin filters | Market data, `placeOrders` | Trading | `automation`, `decision` | Implemented; flow verified with test doubles | Credentials |
| Prediction-based proposals (Layer 1) | Approved Layer 1/2/3 strategy | — | Configurations | `decision.strategy` | **Blocked** | Approved formulas, required layers, conflict/staleness policy |
| Manual-mode confirmation (approve / reject) | — | — | Trading (dialog) | `decision` | Verified (tests) | — |
| Rejected-outcome analysis | Official results | `listMarketBook` (CLOSED runner status) | Reports | `decision`, `market` | Implemented; verified with test doubles | Credentials |
| Hypothetical missed profit | Approved settlement model | — | — | — | **Blocked** | Approved model & explicit request |
| Layer 1/2/3 ingestion | Contracts `l1-prediction-v1`, `l2-planetary-state-v1`, `l3-ranking-v1` (from producer source) | RabbitMQ (Spring AMQP): own queues bound to `stellar-track-exchange` | Settings → Ingestion | `messaging` | **Implemented; verified with a real RabbitMQ** using producer-shaped messages, routing keys and headers (no message stealing asserted) | Run against the live layers |
| Race Signals screen (L1 prediction, L2 states of race N−1, L3 ranking, per-runner merge, traceability) | Accepted layer records | — | Race Signals | `signals` | Implemented; API verified (integration test) | — |
| Betfair market/runner mapping for layer races | Approved venueId → Betfair venue, race-market rule, cloth numbers | Stored market catalogue (`RUNNER_METADATA.CLOTH_NUMBER`) | Race Signals (mapping status) | `messaging.correlation` | Implemented; **pending configuration** (no venue mapping supplied) | Venue mapping |
| Race results from Layer 1 | `race.result` on the topic exchange | — | — | — | **Blocked** | Layer 1 change request: publish results to `stellar-track-exchange` / `race.result` |
| Bet types: Back/Lay on Win and Place, **Back Each-Way** | Market type `EACH_WAY` with place terms (`eachWayDivisor`, number of places) | `listMarketCatalogue` (MARKET_DESCRIPTION), `listMarketBook`, `placeOrders` | Trading → Race markets, Configurations, Watch | `market`, `staking`, `order`, `watch` | Implemented; verified with the test exchange (`OrderFlowIT`, unit tests). Lay Each-Way is not offered | Confirm the Each-Way stake convention with one small real bet (see betfair.md) |
| **Back Dutch** (Win markets, Race-market tickets) | Equal-profit stakes `S × (1/pᵢ) / Σ(1/p)`; legs share `dutch_group_id` | `placeOrders` per leg (each leg through the full single-bet path) | Trading → Race markets → tick runners → Dutch selected | `order` (`DutchTicketService`), `staking` (`DutchMath`) | Implemented; verified with the test exchange (`OrderFlowIT`). Lay Dutch not offered | — |
| Automatic Dutching, equalisation, close-out, price movement, free bets | Precise specifications | `placeOrders` (linked intents supported) | Settings, Configurations | `trading` | **Blocked** | Approved selection strategy; formulas, triggers, linkage, partial-fill & failure policy |
| Reports & graphs | Persisted records | PostgreSQL views | Reports, Dashboard | `reporting` | Verified (tests incl. corrections & export) | — |
| Historical Replay | Input schema, engine | — | Historical Replay | `replay` | Upload/validation verified; evaluation **Blocked** | Historical schema, approved engine |

## Runner filter data sources

| Field | Source |
| --- | --- |
| Odds, liquidity, runner count, number of places, market traded volume | Betfair `listMarketBook` / stream |
| Price rank | Derived from best back prices |
| Race type | Betfair market description `raceType` |
| Age, weight, cloth number, draw, days since last run, form, official rating, sex, equipment | Betfair `RUNNER_METADATA` (only when supplied) |
| Race classification | **Unavailable** — no verified source; an active rule excludes every runner |
