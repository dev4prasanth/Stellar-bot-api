# How Stellar Track works — feature flows

This guide follows every feature from the button you press (or the message that arrives) to the database and back.
It complements [overview.md](overview.md), which describes *where* code lives. This one describes *what happens*.
Rules and numbers come from [business-rules.md](../reporting/business-rules.md); status per feature comes from
[feature-matrix.md](../integrations/feature-matrix.md).

Backend paths below are relative to `src/main/java/com/stellartrack/` in this repository; frontend paths to `src/`
in the frontend repository (shown as `frontend:` references, since it is deployed and versioned separately).

---

## 0. The big picture in one minute

```
                        ┌──────────────── Browser (React) ────────────────┐
                        │ Dashboard · Trading · Race Predictions · Config │
                        │ Bet History · Capital · Reports · Replay · Audit │
                        └──────▲──────────────────────────┬───────────────┘
                     SSE "something changed" (ids only)   │ REST + JWT
                               │                          ▼
 Layer 1/2/3 ──▶ RabbitMQ ──▶ ┌──────────── Spring Boot (one process) ─────────────┐ ──▶ Betfair API-NG
 (predictions,                │ messaging → signals        (inputs only, never bet) │      (JSON-RPC)
  planet states,              │ market  ← catalogue, prices, results  ◀─────────────│ ◀── Exchange Stream
  rankings)                   │ configuration → automation/watch → decision → order │
                              │ staking · capital · reporting · audit · replay      │
                              └──────────────────────┬──────────────────────────────┘
                                                     ▼
                                               PostgreSQL (Flyway V1–V13)
```

Three ideas explain most of the app:

1. **One administrator, one global trading mode.** The mode menu on the Trading screen is MANUAL (default),
   AUTOMATIC or WATCH. It decides what happens to a bet *proposal*: wait for approval, place immediately, or
   simulate.
2. **Every real bet goes through one door**: `OrderPlacementService.submit`. That method runs every pre-trade check,
   writes a durable intent, then calls Betfair. Manual tickets, approved proposals and automatic proposals all use it.
3. **Layer messages are inputs, never permission to bet.** They fill the Race Predictions screen. They can feed a
   strategy, but the order still has to pass every check.

Nothing bets on start-up. Betfair stays disabled until `ST_BETFAIR_ENABLED=true` and credentials and a certificate
are configured. A configuration only proposes bets after you press **Start** on it.

---

## 1. First-time setup, login and staying logged in

**Screens:** `/login` → `LoginPage.tsx` (`frontend:src/features/auth/LoginPage.tsx`)
**Backend:** `auth/` — [AuthController](../../src/main/java/com/stellartrack/auth/api/AuthController.java),
[AuthService](../../src/main/java/com/stellartrack/auth/application/AuthService.java),
[AdminProvisioningService](../../src/main/java/com/stellartrack/auth/application/AdminProvisioningService.java)

### First-time setup
1. On the sign-in page, **First-time setup** posts `POST /api/v1/auth/register`.
2. `AdminProvisioningService` checks the password policy (12+ chars, letters, digits and a symbol), hashes it with
   BCrypt and creates the admin. A unique DB index allows only one admin. Once one exists, the endpoint returns 409.
3. Audit event recorded.

### Login
1. `POST /auth/login` → `LoginRateLimiter` (10/min per IP) → password check. After 5 failures the account locks for
   15 minutes, and the error message stays generic.
2. On success, an **auth session** row is created, and the server returns:
   - an **access token** (JWT, 10 min) in the body. The frontend keeps it **in memory only**
     (`tokenStore.ts` (`frontend:src/shared/api/tokenStore.ts`)).
   - a **refresh token** as an HttpOnly cookie scoped to `/api/v1/auth`. Only its SHA-256 hash is stored.

### Every request after that
`httpClient.ts` adds `Authorization: Bearer …`. `SecurityConfig` checks the signature, expiry, issuer, audience and
role, and `SessionActiveValidator` checks that the server-side session is still active.

### Token refresh (invisible to you)
On a 401 the HTTP client calls `POST /auth/refresh` with the cookie. The refresh token is **single use**: it is
rotated each time, and presenting a used one revokes the whole session (theft detection). A session lasts at most
12 hours.

### Logout
`POST /auth/logout` revokes the session, so every access token for it stops working immediately.

---

## 2. The app shell: header, live updates, capability status

**Frontend:** `AppShell.tsx` (`frontend:src/shared/components/AppShell.tsx`),
`AccountHeaderBar.tsx` (`frontend:src/features/dashboard/components/AccountHeaderBar.tsx`),
`useRealtimeUpdates.ts` (`frontend:src/shared/hooks/useRealtimeUpdates.ts`)

- **Navigation groups:** Overview (Dashboard) · Trading (Trading, Race Predictions, Configurations) · Records &
  Analysis (Bet History, Capital & Payments, Reports, Historical Replay) · Administration (Audit Logs, Settings).
- **Header bar** (`GET /dashboard/header`) shows the balance, exposure, Betfair connection state, trading mode chip
  and Stop All state.
- **Live updates:** the browser opens `GET /api/v1/events/stream` (SSE, [SseHub](../../src/main/java/com/stellartrack/realtime/SseHub.java)).
  The backend publishes `UiNotification`s such as `orders`, `recommendations` and `signals`. They carry **ids only**,
  never data. The frontend invalidates the matching TanStack Query caches and re-fetches through the normal
  authenticated API, so the SSE channel never carries sensitive data.
- **Capabilities:** `GET /system/capabilities` collects every `CapabilityProvider` (Betfair, messaging, trading,
  payments, replay) and reports each one as available, disabled or blocked, with the reason. Screens show a
  `BlockedNotice` instead of pretending a feature works.

---

## 3. Betfair connection and market data

**Backend:** `betfair/` (adapter), `market/` (what the rest of the app uses through `MarketDataPort`)

```
BetfairSessionManager ── certlogin ──▶ session token ── keepAlive every 30 min
        │
MarketCatalogService.syncUpcoming()  every 5 min ─▶ listEvents / listMarketCatalogue ─▶ market, market_runner tables
        │                                             (incl. RUNNER_METADATA: cloth number, age, weight, form…)
MarketQueryService / MarketDataPort.getPrices ─▶ listMarketBook (or the stream cache, if enabled)
        │
ExchangeStreamClient (optional, ST_BETFAIR_STREAM_ENABLED) ─▶ MarketStreamCache + OrderStreamTranslator
```

- **Freshness:** every price snapshot is labelled LIVE, DELAYED, STALE or UNAVAILABLE (`common/api/Freshness`).
  A book older than 10 s is stale. Automation only acts on **LIVE** prices. A manual ticket accepts **DELAYED**
  prices if you acknowledge them.
- **Results:** `MarketResultService.captureClosedMarkets()` (every 5 min) reads CLOSED books and stores runner
  results (WINNER/LOSER/REMOVED). It then publishes `MarketResultCapturedEvent`, which drives outcome evaluation
  (§12) and Watch settlement (§11).
- **Runner filters** (`market/filter/RunnerFilterEvaluator`): odds, liquidity, price rank, runner count, race type,
  age, weight, draw, form, rating, and more. Missing data **fails** a rule (fail closed). Race classification has
  no source, so an active rule on it excludes every runner.

**Screen:** Trading → Race markets panel (`RaceMarketsPanel.tsx` (`frontend:src/features/trading/components/RaceMarketsPanel.tsx`))
lists markets (`GET /markets`), and the market detail (`/trading/markets/$marketId`) shows the price ladder.
`POST /markets/{id}/evaluate` shows how a configuration's filters judge each runner.

---

## 4. Layer 1/2/3 ingestion → Race Predictions

**Backend:** `messaging/` (ingestion), `signals/` (read model) · **Screen:** Race Predictions (`/signals`)

```
Layer producer ─▶ stellar-track-exchange (topic) ─▶ Phase 2's OWN queue (stellartrack.phase2.layerN)
                                                     │  (producer queues untouched → no message stealing)
Layer{1,2,3}Listener (AbstractLayerListener, manual ack)
   └▶ IngestionPipeline.process
        1. open receipt (message_receipt)               ── DB down? → RETRYING (redeliver with backoff)
        2. size check                                    ── too big → QUARANTINED
        3. LayerContractRegistry.resolve → contract
           deserialize → validate → map to NormalizedLayerRecord   ── bad → INVALID / CONTRACT_PENDING
        4. freshness (L2/L3 have timestamps)             ── old → STALE (rejection off in local profile)
        5. persist with unique (layer, dedup_key)        ── seen before → DUPLICATE
        6. ACCEPTED → publish LayerRecordAcceptedEvent
   └▶ ack the RabbitMQ message only after the DB commit (at-least-once)

LayerRecordAcceptedEvent ─▶ SignalProjector ─▶ race_signal_* tables (latest revision per race)
                                            └▶ SSE "signals" → Race Predictions screen refreshes
                        ─▶ BetfairRaceCorrelator (tries cardId+race → Betfair market/runners; needs venue mapping)
```

What each layer means for **race N**:

| Layer | Content | Shown as |
| --- | --- | --- |
| L1 | Prediction for race N (`finalPrediction` with choice and planetScore, plus odds) | Layer1Table |
| L2 | Planetary states **after** race N's result, used for race N+1 | PlanetStateCard (states of race N−1) |
| L3 | Final re-ranked list for race N (rank, confidence, Top 4, NAP) | RankingTable |

The Race page merges L1 and L3 per runner and shows Betfair mapping status. Mapping is currently
`VENUE_NOT_MAPPED` everywhere because no venue mapping has been supplied.

**Operations:** Settings → Ingestion (`IngestionPanel.tsx` (`frontend:src/features/settings/components/IngestionPanel.tsx`))
shows per-layer health (`LayerFeedMonitor`), lists receipts and lets you **reprocess** a failed one
(`QuarantineReprocessService`).

---

## 5. Configurations (the "bot recipes")

**Screen:** Configurations (`/configurations`, `/configurations/new`, `/configurations/$id`)
**Backend:** [ConfigurationService](../../src/main/java/com/stellartrack/configuration/application/ConfigurationService.java)

A configuration is a named set of `BotConfigurationSettings`:

| Section | What it controls |
| --- | --- |
| Selection | Source: `MARKET_CRITERIA` (filters), `PREDICTION` (layer strategy, **blocked**) or `ADMIN_SELECTED` (you pick, can't run automatically) |
| Market | WIN/PLACE, BACK/LAY, countries, venues |
| Timing | Entry window (minutes before the off), in-play allowed, max in-play delay, persistence type |
| Price | Min/max odds, allowed adverse ticks |
| Filters | Runner filter rules (§3) |
| Staking | Method and bank basis (§6) |
| Limits | Max entries per race/day/session, race exposure, open exposure, max selections per race |
| Stops | Stop profit/loss (amount or %), consecutive win/loss stops |
| Capital | Optional allocated amount |
| Trading | Dutching, equalisation, close-out, price movement (all **blocked**) |
| Approval | Approval timeout (the old `manualApprovalRequired` flag is ignored; the global mode decides) |

**Flow:** Save → a new **immutable version** is written (`bot_configuration_version`, protected by a DB trigger).
Edit → another version. Duplicate and Archive are also available. An automation run pins the version it started
with, so editing a running configuration does not change what that run is doing.

---

## 6. Staking — how big is each bet?

**Backend:** `staking/` · used by the ticket, automation and Watch · preview: `POST /staking/preview`

```
StakingService.calculate(side, price, settings, allocation, history)
   ├─ BankResolver (CapitalService): which bank?
   │     AVAILABLE_BALANCE (fresh Betfair funds) | ALLOCATED_CAPITAL | FIXED_BANK
   └─ StakeCalculatorChain → method:
         fixed stake · % of bank · fixed liability (LAY) · % liability (LAY) · target profit · staking plan
   → StakeCalculation { stake, liability, est. profit, valid?, rejectionReason }
```

- Stakes round **down**, and a stake below the minimum is **refused, never raised**.
- **Staking plans:** upload CSV/XLSX (`step,amount,amount_type,on_win,on_loss`) in Settings. `StakingPlanParser`
  rejects formulas and macros. The current step is derived from the configuration's settled WIN/LOSS history in
  settlement order. Void and zero-profit results don't advance the plan.

---

## 7. The Trading screen and the three modes

**Screen:** `/trading` → `TradingPage.tsx` (`frontend:src/features/trading/TradingPage.tsx`) with
TradingModeSelect, AutomationPanel, PendingProposals, RaceMarketsPanel, RecentBets.
**Backend:** [TradingControlService](../../src/main/java/com/stellartrack/order/control/TradingControlService.java)
(single `global_control` row).

| Mode | Proposals from running configurations | Who places | Real money? |
| --- | --- | --- | --- |
| **MANUAL** (default) | Held as `PENDING_APPROVAL` | You, via the confirmation dialog | Yes |
| **AUTOMATIC** | `ACCEPTED`, submitted at once | The system | Yes |
| **WATCH** | Simulated bets | The Watch worker | **No**, virtual capital |

- Switch Manual ⇄ Automatic: `POST /automation/trading-mode`. This is audited (`TRADING_MODE_CHANGED`) and affects
  **new proposals only**.
- Watch is entered and left only through Start/Stop Watch (§11). The Manual/Automatic switch returns 409 while
  Watch is active.
- If the control row can't be read, the mode falls back to **MANUAL**, so a human decides (fail safe).

There are three ways a real bet can start: **7a** you pick a runner yourself, **7b** a running configuration
proposes and you approve, **7c** a running configuration proposes and it goes straight through.

### 7a. Manual bet ticket (you choose the runner)

**UI:** market detail → `BetTicketDialog.tsx` (`frontend:src/features/manual/components/BetTicketDialog.tsx`)
**Backend:** [ManualTicketService](../../src/main/java/com/stellartrack/order/application/ManualTicketService.java)

```
1. Click a price → ticket (side, price, stake or staking method)
2. "Review Bet"  POST /orders/review
     → stake/liability/estimated profit calculated server-side
     → every pre-trade check run as a preview (failures listed)
     → ReviewTokenSigner signs the exact terms → reviewToken
3. "Confirm & Place"  POST /orders/place {reviewToken}
     → token verified (terms can't be swapped); stake recomputed and must still match, otherwise reconfirm
     → your choice recorded as an ADMIN_SELECTED decision (traceability)
     → OrderPlacementService.submit (§8)   trading_mode = MANUAL
```

### 7b. Automation in MANUAL mode (the system proposes, you approve)

```
Configuration "Start"  POST /automation/configurations/{id}/start
   → AutomationService creates automation_run (pins config version, records session start bank)

AutomationWorker.tick()   every 5 s, only on the instance holding the execution lease
   └▶ AutomationEvaluator.tick(run)
        ├ WATCH active?                → pause ("real betting is paused")
        ├ exchange unavailable?        → pause with reason
        ├ strategy resolve (ProposalEngine.resolveStrategy)
        ├ required layer feeds fresh?  → else pause
        ├ candidates = markets in the entry window, not yet evaluated by this run
        ├ getPrices → only LIVE books evaluated (else "Paused entries: market data is …")
        └ per market: ProposalEngine.evaluate
              filters → strategy (MarketCriteriaStrategy: ACTIVE + all filters, keep lowest price ranks)
              → price on configured side → stake (§6) → immutable DecisionSnapshot
           per runner → DecisionService.record(recommendation):
              not recommended  → SKIPPED (reasons stored: the "NO BET" record)
              MANUAL mode      → PENDING_APPROVAL, expiresAt = now + approval timeout
                                 SSE "recommendations" → PendingProposals badge
```

**Approving:** PendingProposals → `ProposalReviewDialog.tsx` (`frontend:src/features/trading/components/ProposalReviewDialog.tsx`)
shows the snapshot (prediction inputs, gates, price, stake, liability).

```
Approve  POST /recommendations/{id}/approve
   → ManualApprovalService writes immutable approval_decision → status APPROVED
   → RecommendationApprovedEvent (after commit)
   → ApprovedRecommendationExecutor.onApproved
        → OrderPlacementService.submit with the frozen price and stake
          (full re-check: e.g. PRICE_MOVED if the price drifted beyond allowed ticks)

Reject   POST /recommendations/{id}/reject {reason, note}
   → immutable decision; never sent to Betfair; reason feeds Reports → Rejections

No answer → ManualApprovalService.expireOverdue() (every 15 s) → EXPIRED, never executed
```

### 7c. Automation in AUTOMATIC mode

Same as 7b up to the recommendation, but the status is `ACCEPTED`, and the evaluator calls
`OrderPlacementService.submit` immediately (`idempotencyKey = auto-{recommendationId}`).

- If risk checks reject it → `noteNotExecuted` stores the reasons on the recommendation.
- If the violation is a **session stop** (stop profit/loss, consecutive wins/losses) → the run is stopped
  automatically with that reason.
- An evaluation exception → the run is marked ERROR with the reason.

**Stop / restart:** `POST …/stop` ends a run. On application restart the policy is `NEVER_RESUME`: runs left
active are stopped with a recorded reason. Nothing resumes betting by itself.

---

## 8. One door for every real bet: pre-trade checks and placement

**Backend:** [OrderPlacementService](../../src/main/java/com/stellartrack/order/application/OrderPlacementService.java),
`order/risk/StandardRiskRules`, `PreTradeContextFactory`

```
submit(command)
 1. Idempotency: same key seen before → return the original result (no second bet)
 2. PreTradeContextFactory.build → market, prices + freshness, funds, exposure, entry history, controls
 3. PreTradeValidator runs every RiskRule ───── any violation → ORDER_RISK_REJECTED audit, nothing sent
 4. OrderIntentStore.createIntent  (status PENDING_SUBMISSION; DB unique index blocks a duplicate live entry)
 5. markSubmitting → SubmissionAttempt row
 6. OrderExecutionPort.place → Betfair placeOrders (customerOrderRef = our reference)
      any unexpected exception after sending → treated as UNKNOWN_OUTCOME (never assume failure)
 7. recordResult → PLACED | EXCHANGE_REJECTED | UNKNOWN_OUTCOME | TECHNICAL_FAILURE  (+ bet_order row)
 8. SSE "orders" → Bet History / Recent Bets refresh
```

**The checks** (`StandardRiskRules`), in plain words:

| Group | Fails with |
| --- | --- |
| Exchange reachable | `EXCHANGE_UNAVAILABLE` |
| Watch Mode running (no real bets) | `TRADING_MODE_WATCH` |
| Stop All engaged (entries from a configuration, incl. approved proposals) | `STOP_ALL_ENGAGED` |
| Market type matches config · market OPEN · in-play allowed and delay short enough | `MARKET_TYPE_MISMATCH`, `MARKET_NOT_OPEN`, `IN_PLAY_NOT_ALLOWED`, `IN_PLAY_DELAY_TOO_LONG` |
| Inside entry window (automatic) | `OUTSIDE_ENTRY_WINDOW` |
| Runner ACTIVE | `RUNNER_NOT_ACTIVE` |
| Prices fresh enough · price on Betfair ladder · within odds range · not moved too far | `PRICE_DATA_*`, `PRICE_NOT_ON_LADDER`, `PRICE_OUTSIDE_RANGE`, `PRICE_MOVED` |
| Stake valid | `STAKE_INVALID` |
| Fresh funds, enough money, min balance kept | `FUNDS_UNKNOWN`, `INSUFFICIENT_FUNDS`, `MIN_BALANCE` |
| No other live entry on same runner/side | `DUPLICATE_ENTRY` |
| Entry limits per race / day / session | `MAX_ENTRIES_PER_*` |
| Exposure limits per race / open | `MAX_RACE_EXPOSURE`, `MAX_OPEN_EXPOSURE` |
| Capital allocation | `ALLOCATION_EXCEEDED` |
| Session stops | `STOP_PROFIT_*`, `STOP_LOSS_*`, `CONSECUTIVE_*_STOP` |

---

## 9. After placement: reconciliation, cancellation, settlement

**Backend:** [OrderReconciliationService](../../src/main/java/com/stellartrack/order/application/OrderReconciliationService.java),
`OrderStateApplier`, `SettlementRecorder`, `OrderCancellationService`

```
Every 20 s  reconcileOpenAndAmbiguous()  → listCurrentOrders
             - matched/unmatched sizes and average price updated on bet_order
             - UNKNOWN_OUTCOME found by customerOrderRef → PLACED
             - not found after 10 min → NOT_FOUND_AFTER_RECONCILIATION (re-checked for 24 h)
             - NEVER resubmitted automatically
Every 5 min reconcileCleared()           → listClearedOrders (bet + MARKET grain)
             → SettlementRecorder: settlement rows (profit, commission) → reports, staking plan step
(Optional)  Order stream                → same state applied in near-real-time
```

- **Cancel:** Bet History → order detail → `POST /orders/bets/{betId}/cancel` cancels the unmatched part, fully or
  reduced by an amount.
- **Bet History** (`/bets`, `/bets/$intentId`): `GET /orders` lists intents with status, attempts, matches and
  settlement. A manual reconciliation run is in Settings (`POST /orders/reconciliation/run`).

---

## 10. Stop All

`POST /automation/stop-all` (red StopAllButton) → `TradingControlService.engageStopAll`.

- It blocks **new automatic entries** only. It does **not** cancel open orders or close positions; they can still
  win or lose.
- The `STOP_ALL` rule checks the order's **origin**, not the trading mode. It blocks every entry that comes from a
  running configuration, including a proposal you approve in MANUAL mode, because that proposal still originates
  from the configuration pipeline. Your own bet tickets (§7a) are still allowed. Watch Mode's simulated bets are
  not affected.
- Release: `POST /automation/stop-all/release`. Both actions are audited.

---

## 11. Watch Mode (paper trading)

**Screens:** Trading → mode menu → Watch (`/trading/watch/...`, `features/watch/`)
**Backend:** `watch/` — [WatchSessionService](../../src/main/java/com/stellartrack/watch/application/WatchSessionService.java),
[WatchWorker](../../src/main/java/com/stellartrack/watch/application/WatchWorker.java),
[WatchExecutionService](../../src/main/java/com/stellartrack/watch/application/WatchExecutionService.java),
`PaperFillEngine`, `WatchSettlementCalculator`, `CommissionCalculator`

```
Start Watch  POST /watch/sessions {capitalSource: CUSTOM amount | COPY real balance, configurations[]}
   in ONE transaction: session + capital ledger START entry
                       global mode → WATCH (previous live mode remembered)
                       all PENDING_APPROVAL proposals → EXPIRED
   → live AutomationEvaluator now pauses; rule TRADING_MODE_WATCH refuses any real entry

WatchWorker.tick()  every 5 s (own lease, independent of live automation)
   1. for each running session configuration:
        ProposalEngine (same logic as live; DELAYED prices accepted)
        → WatchExecutionService: same pre-trade rules against VIRTUAL capital + simulated bets
        → watch_decision row; if recommended → watch_bet
        → PaperFillEngine FILL_V1: limit = price worsened by slippage (1 tick); match against offered sizes
   2. re-check open simulated bets (more fills, lapse at the off/close, void if runner removed)
   3. settle markets with a captured result → WATCH_SETTLEMENT_V1
        (void, reduction factors, dead heat; unknown cases → UNRESOLVED, never guessed)
        → commission per market → capital ledger entries

Stop Watch  POST /watch/sessions/{id}/stop {resumeMode}  → mode back to MANUAL (or AUTOMATIC if chosen)
   open simulated bets keep matching/settling into their session
```

- Pause or resume one configuration inside a session through the `…/configurations/{cid}/pause|resume` endpoints.
- **Reports** (`GET /watch/reports`, CSV export): win rate, ROI, best case, max drawdown. Data is stored only in
  `watch_*` tables, so live reports never include it, and every figure is labelled **Simulated**.

---

## 12. Did we make the right call? Outcome evaluation

**Backend:** [OutcomeEvaluationService](../../src/main/java/com/stellartrack/decision/application/OutcomeEvaluationService.java),
`OutcomeClassifier` (`OUTCOME_RULES_V1`)

`MarketResultCapturedEvent` → every recommendation on that market (placed, skipped, rejected or expired) is classified:

- runner REMOVED → VOID
- BACK + WINNER, or LAY + LOSER → **favorable**, otherwise unfavorable
- missing or odd status → NOT_EVALUABLE; dead heats are flagged

This answers questions like "were my manual rejections good decisions?" in Reports. **Hypothetical missed profit is
blocked**: there is no approved model.

---

## 13. Capital & Payments

**Screen:** `/capital` · **Backend:** `capital/`

- `CapitalService.scheduledRefresh()` every 1 min → `getAccountFunds` → `account_funds_snapshot`. A snapshot older
  than 2 min is **not fresh**, and pre-trade checks then fail with `FUNDS_UNKNOWN`.
- `StatementSyncService` every 10 min → `getAccountStatement` → statement items. Manual sync is available.
- Funds history chart, statement table and a **Refresh** button (`POST /capital/funds/refresh`).
- **Payments (deposit/withdraw): blocked.** There is no authorized API, so the panel links to Betfair's site.

---

## 14. Reports & analytics

**Screen:** `/reports`, `/reports/bets/$betId` · **Backend:** `reporting/` on SQL views `v_settled_bet` and
`v_decision` (V10), so every screen and export counts the same way.

| Endpoint | Shows |
| --- | --- |
| `/reports/summary` | KPI tiles: P&L, ROI, strike rate, turnover, commission… |
| `/reports/trends` | P&L and volume over time |
| `/reports/funnel` | Evaluated → recommended → approved → placed → matched → settled |
| `/reports/outcomes` | Favorable/unfavorable by status (incl. skipped and rejected) |
| `/reports/manual-rejections` (+ breakdown) | Why you rejected, and how those runners did |
| `/reports/factor-analysis`, `/stake-analysis` | Results bucketed by odds, rank, stake band, etc. |
| `/reports/bets/{betId}/calculations` | Step-by-step math for one bet (stake, liability, profit, commission) |
| `/reports/export` | CSV of the same filtered data (`CsvWriter`, formula-injection safe) |

Filters (period, configuration, mode, market type, side) are resolved once by `ReportFilterResolver`. Definitions
are in the [KPI glossary](../reporting/kpi-glossary.md).

---

## 15. Dashboard

`/` → `GET /dashboard` returns three things: the **account header** (Betfair connection state, funds), a
**summary** of KPIs (from the same reporting services as §14) and a **P&L trend** series. It refreshes through SSE
invalidation like every other screen.

---

## 16. Historical Replay

**Screen:** `/replay` · **Backend:** [ReplayService](../../src/main/java/com/stellartrack/replay/application/ReplayService.java)

```
Upload file (+ configuration)  POST /replay (multipart)
   → stored under a generated name, SHA-256 hashed, job = PENDING
   → background validation: structural check only (consistent CSV columns / parseable JSON)
   → no approved ReplayEvaluationEngine registered → job ends BLOCKED with the reason
```

Replay **never** references order placement; `ModuleBoundaryTest` enforces this. Evaluation is blocked until a
historical schema and engine are approved.

---

## 17. Audit Logs

**Screen:** `/audit` · **Backend:** `audit/` — `AuditTrail` port → `audit_event` (append-only, protected by a DB trigger).

Recorded across the app: admin registration, configuration changes, start/stop,
trading mode changes, Stop All, approvals and rejections, risk rejections, placements, cancellations, Watch
start/stop, and reprocessing. Logins, logouts, token refreshes and refresh-token reuse are **not** audited, and
older rows of those actions are hidden from the page. Each entry has actor (ADMIN/SYSTEM), outcome, entity, configuration version,
correlation id (`CorrelationIdFilter`, also `automation-{runId}` for worker actions) and redacted details.
`GET /audit` searches and filters them.

---

## 18. Settings

`/settings` → `SettingsPage.tsx` (`frontend:src/features/settings/SettingsPage.tsx`):

- **Integrations:** live capability report (§2): what works, what's disabled, what's blocked and why
- **Ingestion:** layer health, receipts, reprocess (§4)
- **Reconciliation:** checkpoints and a manual run (§9)
- **Staking plans:** upload and list (§6)
- **System:** version, Flyway schema state (`GET /system/info`)

---

## 19. Everything that runs on its own (background jobs)

| Job | Interval | Does |
| --- | --- | --- |
| `AutomationWorker.tick` | 5 s (lease 30 s) | Live proposals for running configurations (§7) |
| `WatchWorker.tick` | 5 s (own lease) | Simulated proposals, fills, settlement (§11) |
| `ManualApprovalService.expireOverdue` | 15 s | Expire unanswered proposals |
| `OrderReconciliationService.reconcileOpenAndAmbiguous` | 20 s | Sync open and unknown orders |
| `CapitalService.scheduledRefresh` | 1 min | Funds snapshot |
| `MarketCatalogService.syncUpcoming` | 5 min | Upcoming race markets and runners |
| `MarketResultService.captureClosedMarkets` | 5 min | Results → outcome evaluation and Watch settlement |
| `OrderReconciliationService.reconcileCleared` | 5 min | Settlements and commission |
| `StatementSyncService.scheduledSync` | 10 min | Account statement |
| `BetfairSessionManager` keep-alive | 30 min | Keep Betfair session alive |
| RabbitMQ listeners | on message | Layer ingestion (§4) |

All Betfair-dependent jobs skip quietly while the exchange is unavailable.

---

## 20. What is built but intentionally blocked

| Feature | Waiting for |
| --- | --- |
| Prediction-based proposals from Layer 1/2/3 (`PREDICTION` source) | Approved formulas, required layers, conflict and staleness policy |
| Betfair mapping of layer races | Venue mapping (`venueId` → Betfair venue) |
| Race results from Layer 1 | Layer 1 publishing `race.result` on the exchange |
| Dutching, equalisation, close-out, price movement, free bets | Precise specifications |
| Deposits / withdrawals | An authorized payments API |
| Hypothetical missed profit | Approved settlement model |
| Replay evaluation | Historical schema + approved engine |
| Race classification filter | A data source |

---

## 21. Worked example: one race, end to end (MANUAL mode)

1. **09:00** The catalogue sync stores today's Kempton WIN markets and runners.
2. **13:40** Layer 1 publishes a prediction for race 4. Ingestion accepts it, the Race Predictions page updates, and
   **nothing is bet**.
3. You started configuration "Kempton favourites v3" earlier (market criteria, BACK, odds 2–6, entry window 10→2 min,
   fixed stake £5).
4. **13:52** (8 min before the off) The race enters the window. The worker reads LIVE prices; filters pass for
   2 runners; the strategy keeps the lowest price rank → 1 recommendation `PENDING_APPROVAL` (expires in, say,
   120 s). Others are stored as `SKIPPED` with reasons.
5. The Trading page badge lights up. You open the proposal and see the snapshot (price 3.5, stake £5, liability
   £5, est. profit £12.50).
6. **Approve** → executor → all checks pass → intent `PENDING_SUBMISSION → SUBMITTING` → Betfair `placeOrders` →
   **PLACED**, bet id stored, SSE refreshes Bet History.
7. Every 20 s, reconciliation updates the matched size (£5 matched at 3.5).
8. **14:05** The race finishes. The result capture marks the runner WINNER, and outcome evaluation marks the
   recommendation **favorable**.
9. **≤ 14:10** Cleared orders → settlement: +£12.50 minus commission. The funds snapshot updates, the staking plan
   advances (if used), and the Reports and Dashboard KPIs include it. The audit log has every step under one
   correlation id.

In AUTOMATIC mode, step 5 disappears, and step 6 happens inside the worker tick. In WATCH mode, step 6 becomes a
simulated fill against the book and step 9 a simulated settlement on the virtual ledger.
