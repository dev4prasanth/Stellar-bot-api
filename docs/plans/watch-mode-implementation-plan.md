# Watch Mode — Implementation Plan (rev. 4)

Status: **Implemented (phases 1–4)** · Scope change approved by the product owner (lifts the "Watch Mode / paper trading" exclusion
in the original implementation plan).

> **Mode model.** StellarTrack has **three trading modes: Manual, Automatic and Watch. Exactly one is active at a
> time.** Manual and Automatic are **Live** (real Betfair bets). Watch is **simulation only** (no real bets). Live and
> Watch never run at the same time.
>
> **rev. 4 — one mode dropdown.** The Trading screen's Manual/Automatic switch becomes a **mode dropdown with all
> three modes: Manual, Automatic, Watch**. Choosing **Watch** in the dropdown shows the **Watch screen** (start/stop,
> virtual capital, bets, reports, sessions) in place of the Live trading content. There is no separate sidebar entry
> for Watch any more.

## 1. Goal

When the administrator starts Watch Mode, StellarTrack stops real betting and runs the selected betting
configurations against real (delayed) Betfair market data and real Layer 1/2/3 predictions, placing **simulated bets
automatically against a virtual bankroll**. The Watch screen (chosen from the Trading mode dropdown) shows what the administrator *would have
won or lost* with real money.

### Agreed decisions

| # | Decision |
|---|---|
| D1 | Three mutually exclusive trading modes: **Manual**, **Automatic** (Live) and **Watch** (simulation) |
| D2 | All three modes are chosen from **one dropdown on the Trading screen**. Choosing Watch opens the **Watch screen**, where Watch is **started and stopped** and its capital, bets and reports are shown |
| D3 | While Watch is active, **no real bet can be placed** (automatic, approved proposal or bet ticket) |
| D4 | In Watch, simulated bets are placed **automatically** (no approval) |
| D5 | Starting bankroll per session: **custom amount** or **copy of the real available balance** |
| D6 | Betfair **delayed** app key for now; the fill model compensates |
| D7 | Watch data, bankroll and reports are **completely separate** from Live and always labelled **Simulated** |

### Requirements → where they are met

| Requirement | Covered by |
|---|---|
| Watch shown next to Manual and Automatic, chosen from a dropdown | Mode dropdown (§6.1) |
| Configure starting bankroll | Start-Watch wizard, step 1 (§6.4) |
| Start and stop Watch from the Watch screen | Watch control card (§6.3–6.4); `POST /api/watch/sessions`, `…/stop` |
| Monitor simulated bets (pending, matched, unmatched, won, lost, settled) | Bets tab and bet detail (§6.5–6.6); status model (§4) |
| Track virtual capital (start, current, P&L, changes) | Capital tab and ledger (§6.7, §4) |
| Watch reports and graphs (wins, losses, ROI, staking, strategy) | Reports tab (§6.8) |
| Session history (configurations, bets, results, reports) | Sessions tab (§6.9) |
| Own UI, execution flow, records, bankroll, reports | `watch` backend module, `features/watch`, `watch_*` tables |
| Reuse Layer 1/2/3 pipeline and strategy logic | Shared `ProposalEngine` (§5.1) |
| Never place real bets or move money | Mode gate on every Live entry path + ArchUnit isolation (§3) |
| Watch must not damage Live records or settings | No writes to Live tables; Live reports never read Watch tables (§3) |

## 2. Mode model

```
                     ┌──────────────── global_control.trading_mode ────────────────┐
                     │                                                             │
          LIVE  ─────┤  MANUAL     real bets, each proposal approved by you        │
                     │  AUTOMATIC  real bets, placed without approval              │
                     │                                                             │
          SIM   ─────┤  WATCH      simulated bets only, placed automatically       │
                     └─────────────────────────────────────────────────────────────┘

  Trading screen — one mode dropdown:

     Mode: [ ✋ Manual ▾ ]
           ├ ✋ Manual      You approve each real bet
           ├ ⟳ Automatic   Real bets placed without approval
           └ 👁 Watch       Simulated bets only · Off / Running
```

### Choosing in the dropdown vs. changing the active mode

The dropdown both **shows the active mode** and **chooses which screen is shown**:

- Choosing **Manual** or **Automatic** changes the active Live mode (confirmation dialog, as today) and shows the
  Live trading content (Proposals & automation, Race markets).
- Choosing **Watch** shows the **Watch screen**. It does **not** start Watch by itself: the Watch screen shows
  **Start Watch** (bankroll + configurations wizard). Until Watch is started, the active mode stays as it was, and
  the Watch screen says so ("Watch is off · Live mode Manual is active").
- While Watch is **running**, the dropdown shows **Watch · Running** and the Trading screen opens on the Watch screen.
  Choosing Manual or Automatic then opens the **Stop Watch** dialog with that mode pre-selected.

### Transitions

| From → To | How | What happens (one transaction, `global_control` row locked) |
|---|---|---|
| Manual ↔ Automatic | Dropdown → confirm | As today |
| Manual/Automatic → **Watch** | Dropdown → *Watch* → Watch screen → **Start Watch** wizard | Records the previous Live mode on the new `watch_session`; sets `trading_mode = WATCH`; **expires all pending approvals** (status `EXPIRED`, reason "Watch Mode started" — none can become a real bet); creates the session, virtual capital and pinned configurations; audits `TRADING_MODE_CHANGED` + `WATCH_SESSION_STARTED` |
| **Watch** → Manual/Automatic | **Stop Watch** button on the Watch screen, or choosing Manual/Automatic in the dropdown | Stop Watch dialog asks which Live mode to return to — **Manual pre-selected** unless chosen from the dropdown; ends the session; sets the chosen mode; audits both events |

The backend keeps `POST /automation/trading-mode` for Manual ↔ Automatic only (409 while Watch is running); the
dropdown calls the Watch start/stop endpoints for anything involving Watch.

### What happens to Live while Watch is active

| Live element | Behaviour in Watch |
|---|---|
| Automation runs started on the Trading screen | Stay as they are but **paused** with reason "Watch Mode active"; `AutomationEvaluator.tick` returns before evaluating. They resume when Watch stops |
| Approval queue | Emptied on start (expired); no new Live proposals are created |
| Live trading content (Proposals & automation, Race markets) | Not shown while Watch is running — the Trading screen shows the Watch screen. Race-market bet tickets reached by direct link are disabled ("Real betting is paused while Watch Mode is on") |
| Any real order submission | Blocked by a new risk rule `TRADING_MODE_WATCH` (defence in depth, see §3.1) |
| **Real bets already open on Betfair** | Unaffected — they still match, settle and appear in Live Bet History/Reports. Cancelling them stays possible. The start wizard lists them so this is visible |
| Live Dashboard, Bet History, Reports, Capital | Unchanged; show only real data. App bar mode chip shows **Mode: Watch** |
| Stop All | Unchanged (applies to Live automatic entries) |

## 3. Safety and separation (acceptance criteria)

### 3.1 No real bets while Watch is active — layered guards

1. **Mode gate in Live entry points:** `AutomationEvaluator.tick` (pause), `ManualApprovalService.approve`
   (reject — queue is empty anyway), `ManualTicketService.review/confirm` (reject with code `WATCH_MODE_ACTIVE`).
2. **Risk rule `TRADING_MODE_WATCH`** in `StandardRiskRules`: any `ENTRY` placement is rejected while the mode is
   `WATCH`. The mode is read inside `PreTradeContextFactory` immediately before submission, so an in-flight Live tick
   that started just before the switch is also stopped. Cancellations of existing real orders are still allowed.
3. **Watch module has no path to Betfair orders:** it must not depend on `OrderExecutionPort`,
   `OrderPlacementService`, `ApprovedRecommendationExecutor`, `ManualTicketService` or
   `betfair.adapter.BetfairOrderExecutionAdapter`. Enforced by an ArchUnit test.
4. **Fail safe:** if the mode cannot be read, `TradingControlService.tradingMode()` already returns `MANUAL`
   (a human decides) — never Automatic.

### 3.2 Separate data
- Watch data only in `watch_*` tables (§4). No Live table gets Watch rows; no Live view or report query changes.
- Dashboard, Bet History, Reports, Capital and the Live approval/decision views stay real-money only.

### 3.3 Watch must not disturb Live infrastructure

| Shared resource | Guard |
|---|---|
| Betfair data calls | Watch reads `MarketStreamCache` first (upcoming markets are already subscribed via `findStreamCandidates`); polling fallback goes through a Watch-only bulkhead (max concurrency, markets-per-minute budget). No change to the stream subscription |
| Scheduler / lease | Separate `WatchWorker` with its own lease `watch-worker` (Live uses `automation-worker`); scheduled tasks run on virtual threads, so ticks never queue behind each other |
| Configurations | Pinned read-only (`configuration_id + version`); editing a configuration during a session creates a new version and does not affect the session |
| Database | Watch report queries over indexed views, paged, with a statement timeout |

## 4. Data model

### 4.1 `V13__add_watch_trading_mode.sql` (Live control table)
- `global_control.trading_mode`: check constraint → `IN ('MANUAL','AUTOMATIC','WATCH')`.
- No other Live table changes. `order_intent.trading_mode` stays `MANUAL`/`AUTOMATIC` (real bets only).

### 4.2 `V14__create_watch_tables.sql` (new tables only)

| Table | Purpose | Key columns |
|---|---|---|
| `watch_session` | One Watch period | `id`, `name`, `status` (`ACTIVE`/`STOPPED`/`FAILED`), `started_at/by`, `stopped_at/by`, `stop_reason`, `previous_trading_mode`, `resumed_trading_mode`, `bankroll_source` (`CUSTOM`/`REAL_BALANCE`), `starting_bankroll`, `currency`, `real_balance_snapshot_id` (nullable FK `account_funds_snapshot`), `commission_discount_rate`, `fill_model_version`, `slippage_ticks`, `settlement_rule_version`, `last_tick_at`, `last_error`, `notes`. Partial unique index: one `ACTIVE` session |
| `watch_session_configuration` | Configurations in the session, pinned | `session_id`, `configuration_id`, `configuration_version`, `configuration_name`, `strategy_code`, `settings_snapshot` (jsonb), `state` (`RUNNING`/`PAUSED`/`STOPPED`), `state_reason`, `decisions`, `bets_placed` |
| `watch_decision` | Every evaluation (bet or no bet) with the full decision snapshot | `id`, `session_id`, `session_configuration_id`, `market_id`, `selection_id`, `side`, `market_type`, `outcome` (`BET_PLACED`/`SKIPPED`/`RISK_REJECTED`), `reasons` (jsonb), `snapshot` (jsonb `DecisionSnapshot`), `proposed_price`, `proposed_stake`, `proposed_liability`, `created_at`. Unique `(session_configuration_id, market_id, selection_id)` |
| `watch_bet` | A simulated bet | `id`, `session_id`, `decision_id` (unique), `configuration_id/version`, `market_id`, `selection_id`, `side`, `market_type`, `persistence_type`, `requested_price`, `requested_size`, `size_matched`, `average_price_matched`, `size_remaining`, `size_lapsed`, `size_voided`, `matching_status`, `result_status`, `liability`, `data_delay_ms`, `book_at_placement` (jsonb), `placed_at`, `matched_at`, `settled_at` |
| `watch_bet_fill` | Each simulated fill | `bet_id`, `seq`, `price`, `size`, `reason` (`IMMEDIATE`/`PRICE_CROSSED`), `book_as_of`, `created_at` |
| `watch_bet_settlement` | Versioned settlement | `bet_id`, `rule_version`, `runner_status`, `adjustment_factor`, `dead_heat_divisor`, `adjusted_price`, `gross_profit`, `best_case_gross_profit`, `is_current`, `evidence` (jsonb) |
| `watch_market_commission` | Commission per session × market | `session_id`, `market_id`, `net_market_profit`, `base_rate`, `discount_rate`, `commission`, `best_case_commission` |
| `watch_bankroll_entry` | Append-only bankroll ledger | `session_id`, `seq`, `entry_type` (`START`/`BET_SETTLED`/`COMMISSION`/`ADJUSTMENT`), `amount`, `balance_after`, `bet_id`, `market_id`, `created_at` |

Views: `v_watch_bet`, `v_watch_session_summary`, `v_watch_daily_pnl`, `v_watch_bankroll_curve`.

### Bet status model

| Display status | Matching | Result | Meaning |
|---|---|---|---|
| **Pending** | `UNMATCHED` (open) | `OPEN` | Waiting for the price before the off |
| **Partially matched** | `PARTIALLY_MATCHED` | `OPEN` | Part matched, rest waiting |
| **Matched** | `MATCHED` | `OPEN` | Fully matched, race not settled |
| **Unmatched** | `LAPSED` | `NOT_MATCHED` | Never matched; lapsed; no P&L |
| **Won** / **Lost** | matched | `WON` / `LOST` | Settled |
| **Void** | any | `VOID` | Non-runner / voided; stake returned |

"Settled" = Won + Lost + Void.

### Bankroll rules
- **Current balance** = starting bankroll + Σ settled net P&L (from the ledger).
- **Available to bet** = current balance − open simulated liability.
- New simulated bets are checked against *available to bet*, like a real wallet.

## 5. Backend design

### 5.1 Changes to existing (Live) code

| Change | File(s) | Purpose |
|---|---|---|
| New enum `TradingMode { MANUAL, AUTOMATIC, WATCH }` for the global switch | `common/domain`, `GlobalControlEntity`, `TradingControlService`, `AutomationController.TradingModeRequest`, `DashboardController` | `OperatingMode` keeps its meaning (order origin / real-bet mode: `MANUAL`/`AUTOMATIC`) so origin-based risk rules are untouched |
| `TradingControlService.changeTradingMode` rejects `WATCH` and rejects any change while in `WATCH` | `TradingControlService` | Watch can only be entered/left through `WatchSessionService` |
| New internal methods `enterWatch(session, actor)` / `leaveWatch(session, liveMode, actor)` | `TradingControlService` | Called only by `WatchSessionService` in the same transaction |
| Expire pending approvals on entering Watch | `ManualApprovalService` (`expireAllPending(reason)`) | No Live proposal can become a real bet |
| Pause Live automation in Watch | `AutomationEvaluator.tick` | Returns paused reason "Watch Mode active" |
| Block bet ticket in Watch | `ManualTicketService.review/confirm` | Code `WATCH_MODE_ACTIVE` |
| Risk rule `TRADING_MODE_WATCH` | `StandardRiskRules`, `PreTradeContext` (+ `tradingMode`), `PreTradeContextFactory` | Final guard on every real entry |
| Extract **`ProposalEngine`** (strategy, filters, ranks, price, staking, snapshot) from `AutomationEvaluator.evaluateMarket`, plus the candidate-market helper from `tick` | `automation/application` | Watch and Live use identical decision logic; pure extraction, Live behaviour unchanged |
| `StakingService.calculate(..., BigDecimal bank)` overload | `staking/application` | Percent-of-bank staking uses the virtual bankroll in Watch (default path still uses `BankResolver`) |
| `ExecutionLeaseService.WATCH_LEASE` | `automation/application` | Own lease |
| New audit actions | `AuditAction` | `WATCH_SESSION_STARTED`, `WATCH_SESSION_STOPPED`, `WATCH_BET_PLACED`, `WATCH_BET_RISK_REJECTED`, `WATCH_BET_SETTLED` |
| Capability row "Watch Mode" | `TradingCapabilityProvider` / Settings | Shows status, fill model, data delay |

### 5.2 New `watch` module (`com.stellartrack.watch`)

```
watch/
  api/          WatchSessionController, WatchBetController, WatchReportController, DTO records
  application/  WatchSessionService, WatchWorker, WatchEvaluator, WatchPreTradeContextFactory,
                WatchExecutionService, WatchFillService, WatchSettlementService, WatchBankrollService,
                WatchReportService, WatchMarketDataGateway (bulkhead)
  domain/       PaperFillEngine (FILL_V1), WatchSettlementCalculator (WATCH_SETTLEMENT_V1),
                CommissionCalculator, WatchBetStatus, BankrollSource
  persistence/  entities + repositories (§4.2)
  config/       WatchProperties
```

**WatchSessionService**
- `start(name, bankrollSource, customAmount, configurationIds, notes, actor)` — in one transaction: lock
  `global_control`; require mode `MANUAL`/`AUTOMATIC` and no active session; validate configurations (exist, not
  archived, selection source `MARKET_CRITERIA` or `PREDICTION`); resolve bankroll (`REAL_BALANCE`: refresh funds if
  stale, copy *available to bet*, store snapshot id; discount rate from the same snapshot); pin configuration
  versions; write `START` ledger entry; expire pending approvals; `TradingControlService.enterWatch`; audit.
- `stop(id, resumeMode, reason, actor)` — ends the session and calls `leaveWatch(resumeMode)` (default `MANUAL`).
  Open simulated bets still fill/lapse and settle into the ended session so its results complete.
- `pauseConfiguration` / `resumeConfiguration` inside the session.
- Restart recovery: mode `WATCH` is persisted; the active session resumes after a restart.

**WatchWorker / WatchEvaluator** (every 5 s, `watch-worker` lease, only while mode is `WATCH`)
1. Candidate markets in each configuration's entry window (shared helper).
2. Books via `WatchMarketDataGateway` (stream cache first, bulkhead-limited polling).
3. Freshness: `LIVE` or `DELAYED` accepted (delay recorded); `STALE`/`UNAVAILABLE` → configuration paused with reason.
4. Required Layer feeds usable (same `LayerFeedMonitor` rule as Live).
5. `ProposalEngine` with the virtual bank and the Watch win/loss sequence.
6. `watch_decision` for every evaluated runner; recommended runners → `WatchExecutionService.place`.

**WatchPreTradeContextFactory** — builds `PreTradeContext` from Watch data so all `StandardRiskRules` are reused:
funds = virtual *available to bet*; duplicates, entry counts and exposures from `watch_bet`; session P&L and
consecutive wins/losses from `watch_bet_settlement`; `DELAYED` prices accepted; `TRADING_MODE_WATCH` rule not
applied (Watch context). A configuration hitting a stop rule (stop-loss, stop-profit, max entries, consecutive
losses) is paused with reason, like Live.

**WatchExecutionService.place** — idempotent on `decision_id`; validate; snapshot book and delay;
`PaperFillEngine.initialFill`; write `watch_bet` + `watch_bet_fill`; SSE `watch`; audit.

**PaperFillEngine — `FILL_V1`**
- Effective limit = requested price worsened by `slippage_ticks` (default 1 on the delayed key, `PriceLadder.offset`).
- BACK matches back levels ≥ limit; LAY matches lay levels ≤ limit; walks levels, capped at the size shown;
  matched at the level price.
- Remainder stays Pending; `WatchFillService` re-checks every 5 s and fills when the price crosses.
- `LAPSE` lapses at the off; `PERSIST` lapses at close; `MARKET_ON_CLOSE` unsupported → lapses with reason.
- Runner removed before matching voids the unmatched part.
- Best case recorded alongside (full size at requested price).

**WatchSettlementService** — listens to the existing `MarketResultCapturedEvent` (after commit, async, idempotent per
rule version). `WATCH_SETTLEMENT_V1`: removed runner → void; non-runner price reduction via `adjustmentFactor`
(validated against real cleared orders before figures are relied on); dead-heat stake division; WIN/PLACE from
runner status and `numberOfWinners`; BACK win `stake × (price − 1)` / lose `−stake`, LAY win `+stake` /
lose `−stake × (price − 1)`; commission per session × market `max(0, net) × marketBaseRate × (1 − discountRate)`.
Writes realistic and best-case figures and the ledger entries. Results not captured → **Unresolved**, never guessed.

**WatchReportService** — SQL over `v_watch_*` with a `WatchReportFilter` (session, period, configuration,
strategy, side, market type, venue) and a definitions version, following the Live reporting pattern.

### 5.3 REST API (`/api/watch`)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/status` | Current trading mode, active session (if any), bankroll, KPIs, configuration states |
| `GET` | `/sessions/start-options` | Eligible configurations, current real available balance + age, pending approvals and open real bets counts (shown as warnings) |
| `POST` | `/sessions` | **Start Watch** (name, bankroll source, custom amount, configuration ids, notes) |
| `POST` | `/sessions/{id}/stop` | **Stop Watch** (`resumeMode`: `MANUAL` default or `AUTOMATIC`, reason) |
| `POST` | `/sessions/{id}/configurations/{cid}/pause` · `/resume` | Per configuration |
| `GET` | `/sessions`, `/sessions/{id}`, `/sessions/{id}/bankroll` | History, detail, ledger + curve |
| `GET` | `/bets`, `/bets/{id}`, `/decisions` | Bets (filters), bet detail, no-bet decisions |
| `GET` | `/reports/summary` · `/trend` · `/outcomes` · `/staking` · `/strategy` · `/matching` · `/compare` | Reports |
| `GET` | `/reports/export.csv` | CSV, every row tagged `SIMULATED` |

Existing `POST /automation/trading-mode` keeps accepting only `MANUAL`/`AUTOMATIC` and returns `409` while Watch is
active. SSE topic `watch` added to `SseHub`.

## 6. UI workflow and screens

Built with the existing design system (`tokens.ts`, `muiTheme.ts`) and shared components (`PageHeader`, `KpiTile`,
`ChartCard`, `DataTable`, `FilterBar`, `PeriodFilter`, `StatusChip`, `ModeChip`, `Money`, `DescriptionList`,
`ConfirmDialog`, `QueryState`, `FreshnessBadge`, `ChartPrimitives`). New: a **`watch` colour token** (distinct from
Manual indigo, Automatic teal, Back blue, Lay pink), a `WATCH` variant of `ModeChip`, and a **Simulated** badge.

### 6.1 Mode dropdown (replaces the Manual/Automatic switch)

Today `ModeControl` in `TradingPage.tsx` renders `TradingModeSwitch` (two radio buttons). It is replaced by
**`TradingModeSelect`**, a dropdown (MUI `Select` with rich menu items) in the same place on the Trading screen:

```
┌ Trading ──────────────────────────────────────────────────────────────────────────┐
│  [✋]  Manual mode is on                                    Mode: [ ✋ Manual    ▾ ] │
│        Every proposal waits for your approval …            ┌───────────────────────┐│
│                                                            │ ✋ Manual       ✓ Active ││
│                                                            │   You approve each bet  ││
│                                                            │ ⟳ Automatic             ││
│                                                            │   No approval needed    ││
│                                                            │ ─────────────────────── ││
│                                                            │ 👁 Watch          Off   ││
│                                                            │   Simulated bets only   ││
│                                                            └───────────────────────┘│
└───────────────────────────────────────────────────────────────────────────────────┘
```

- Each item: icon in the mode colour, name, one-line description, and state (`Active` / `Running` / `Off`).
- Watch is separated by a divider and carries the **Simulated** badge, so it is clearly not real betting.
- Choosing Manual/Automatic → existing confirmation dialog (unchanged text) → Live content.
- Choosing Watch → navigates to the Watch screen (`/trading/watch`). Starting is a separate, explicit step there.
- While Watch is running → the control shows **Watch · Running**; choosing Manual/Automatic opens the Stop Watch
  dialog with that mode pre-selected.
- Accessible: keyboard navigation, `aria-label="Trading mode"`, each item announces its state.

### 6.2 Routes and layout

The Trading screen gets a shared header (`TradingLayout`: page title, mode dropdown, mode summary) and two bodies:

| Dropdown selection | Body | Routes |
|---|---|---|
| Manual / Automatic | **Live** — existing tabs *Proposals & automation* · *Race markets* (unchanged) | `/trading`, `/trading?tab=markets`, `/trading/markets/$marketId` |
| Watch | **Watch screen** — tabs *Overview* · *Bets* · *Capital* · *Reports* · *Sessions* | `/trading/watch`, `/trading/watch/bets`, `/trading/watch/bets/$betId`, `/trading/watch/capital`, `/trading/watch/reports`, `/trading/watch/sessions`, `/trading/watch/sessions/$sessionId` |

- Opening `/trading` while Watch is running redirects to `/trading/watch`.
- Sidebar: **no separate Watch entry**; the *Trading* item shows a dot while Watch is running.
- App bar mode chip shows **Mode: Watch** (watch colour) while running and links to `/trading/watch`.
- Watch pages are bookmarkable and keep their own filters in the URL.

### 6.3 Watch screen — Overview (`/trading/watch`)

**Watch off**

```
Trading                                                       Mode: [ 👁 Watch  ▾ ]
Overview · Bets · Capital · Reports · Sessions                              [Simulated]

┌ Watch is off · Live mode Manual is active ───────────────────────────────────────┐
│ Run your configurations on real market data with a virtual capital.               │
│ While Watch runs, no real bet is placed.                       [ ▶ Start Watch ]  │
└───────────────────────────────────────────────────────────────────────────────────┘
Last session: "Flat NAP trial" · 03/06 · £1,000 → £1,142.10 · +14.2%      View →
```

**Watch running**

```
Trading                                             Mode: [ 👁 Watch · Running ▾ ]
Overview · Bets · Capital · Reports · Sessions                              [Simulated]

┌ Watch is running · "Flat NAP trial" · since 09:00 · data delayed ~2 s     [■ Stop Watch] ┐
│ UK-Flat-NAP-v3 v5 [Running] [Pause]      Jumps-Lay-Strategy v2 [Paused: stop-loss]       │
└──────────────────────────────────────────────────────────────────────────────────────────┘
KPIs: Current capital £1,142.10 · Net P&L (realistic) +£142.10 · Net P&L (best case) +£212.40
      · Win rate 62% · ROI 9.8% · Bets matched/placed 12/14
┌ Capital curve (mini) ─────────────────┐ ┌ Open bets: Pending · Partially matched · Matched ┐
└───────────────────────────────────────┘ └──────────────────────────────────────────────────┘
Latest results: Won · Lost · Void · Unmatched
```

### 6.4 Start Watch wizard and Stop Watch dialog

**Start Watch** (dialog, 3 steps)
1. **Capital** — *Custom amount* (input) / *Use my real available balance* (value and age, e.g.
   "£4,820.00 · 45 s ago"). Session name, notes.
2. **Configurations** — checklist of eligible configurations (version, strategy, side/market, staking); ineligible
   ones shown with reason. At least one.
3. **Review & start** — summary; commission terms; delayed-data and fill-model notice; warnings:
   "Real betting will pause. N pending approvals will expire. M real bets already on Betfair will settle normally."
   → **Start Watch**.

**Stop Watch** — "No new simulated bets will be placed; open simulated bets will still settle.
Return to: (●) Manual ( ) Automatic" → **Stop Watch**. After stopping, the dropdown shows the chosen Live mode and
the screen returns to the Live content; the finished session stays available under *Sessions*.

### 6.5 Bets (`/trading/watch/bets`)
Filters: session (default current/last), status (Pending, Partially matched, Matched, Unmatched, Won, Lost, Void,
Settled), configuration, side, market type, period. Columns: Placed, Race, Runner, Side, Requested, Matched, Status,
Gross, Commission, Net, Configuration. A *Decisions* sub-tab lists no-bet decisions with reasons.

### 6.6 Bet detail (`/trading/watch/bets/$betId`)
Four steps, like Live Bet Detail, with the Simulated badge: **Bet summary** · **Prediction & decision**
(`DecisionSnapshot`) · **Simulated matching** (ladder at placement, delay, slippage, fills, lapse reason) ·
**Simulated settlement** (result, adjustments, gross, commission, net, best case, rule version).

### 6.7 Capital (`/trading/watch/capital`)
The Watch equivalent of *Capital & Payments*, for the virtual capital only:
- KPI tiles: Starting capital (and source: custom / copied from real balance at time T), Current capital,
  Available to bet, Open simulated liability, Total P&L (£ and %), Commission paid.
- Capital over time chart (with starting line) and drawdown.
- **Capital statement** (ledger): Date, Type (Start, Bet settled, Commission), Bet / market, Amount, Balance after.

### 6.8 Reports (`/trading/watch/reports`)
Filters: session(s), period + granularity, configuration, strategy, side, market type.

| Section | Content |
|---|---|
| Key figures | Net P&L, ROI, win rate, bets placed/matched, average odds, max drawdown, current capital |
| P&L trend | Realistic vs best case |
| Outcomes | Won / Lost / Void / Unmatched counts and P&L |
| Staking performance | Stake over time, P&L by stake band, staking-plan step reached, longest win/loss streak, avg/max stake, ROI by staking method |
| Strategy effectiveness | Per configuration/strategy: bets, strike rate, ROI, P&L; by odds band, side, market type, venue, Layer score/rank band |
| Matching quality | Matched %, average slippage, unmatched reasons, realistic vs best-case gap |
| Export | CSV with definitions version and `SIMULATED` tag |

### 6.9 Sessions (`/trading/watch/sessions`)
List: name, period, status, capital source, start → end capital, net P&L, ROI, bets, configurations, mode before
and after. Select 2–4 → **Compare**. Session detail: settings, pinned configuration versions, and the Overview /
Bets / Capital / Reports views scoped to that session (read-only).

### 6.10 Frontend structure

```
features/trading/
  TradingLayout.tsx                     (new: header + mode dropdown, shared by Live and Watch bodies)
  TradingPage.tsx                       (Live body: existing tabs; ModeControl uses TradingModeSelect)
  components/TradingModeSelect.tsx      (new: replaces TradingModeSwitch.tsx)
features/watch/
  api.ts, types.ts, useWatchEvents.ts
  WatchPage.tsx (tabs), WatchOverviewTab.tsx, WatchBetsTab.tsx, WatchBetDetailPage.tsx,
  WatchCapitalTab.tsx, WatchReportsTab.tsx, WatchSessionsTab.tsx, WatchSessionDetailPage.tsx
  components/ StartWatchWizard.tsx, StopWatchDialog.tsx, WatchControlCard.tsx, CapitalCurve.tsx,
              CapitalStatement.tsx, WatchBetStatusChip.tsx, SimulatedBadge.tsx, WatchKpis.tsx, report sections
routes/_app/trading/
  route.tsx (new layout route rendering TradingLayout + <Outlet/>)
  index.tsx, markets.$marketId.tsx                                  (existing Live routes)
  watch/index.tsx, watch/bets/index.tsx, watch/bets/$betId.tsx, watch/capital.tsx,
  watch/reports.tsx, watch/sessions/index.tsx, watch/sessions/$sessionId.tsx
```

Existing frontend files touched: `TradingPage.tsx` (`ModeControl` → dropdown, summary text for Watch),
`TradingModeSwitch.tsx` (replaced), `routes/_app/trading/*` (layout route), `automation/api.ts` (`TradingMode`
adds `WATCH`), `ModeChip.tsx` + `tokens.ts` (Watch variant/colour), `AppShell.tsx` (dot on Trading),
`AccountHeaderBar.tsx` (chip label/link), `RaceMarketsPanel.tsx` (ticket disabled in Watch),
`trading.test.tsx` (switch tests → dropdown tests), `StellarTrack_UI_Mockup.html` (new slides).

## 7. Configuration properties

```yaml
stellartrack:
  watch:
    enabled: true
    tick-interval: PT5S
    fill-check-interval: PT5S
    fill-model: FILL_V1
    slippage-ticks: 1                 # delayed data
    slippage-ticks-live-data: 0       # once a live app key is configured
    data-budget:
      max-concurrent-requests: 2
      max-markets-per-minute: 120
    report-statement-timeout: PT10S
    default-commission-base-rate: 0.05  # only if a market lacks marketBaseRate
```

## 8. Phased delivery

| Phase | Content | Outcome |
|---|---|---|
| **0. Sign-off** | Update plan/README/feature matrix; mockup slides (mode dropdown open, Watch off, Start wizard, Watch running, Bets, Capital, Reports, Sessions) | Design approved |
| **1. Mode and guards** | `TradingMode` + V13; enter/leave Watch; Live gates (automation pause, ticket block, approvals expiry, `TRADING_MODE_WATCH` rule); `ProposalEngine` and staking-bank refactors; V14; session start/stop API; mode dropdown (`TradingModeSelect`) and Trading layout route, Watch Overview, wizard, stop dialog; mode chip and Trading nav dot; ArchUnit test | Watch can be started/stopped; real betting provably paused |
| **2. Simulated execution** | Worker/evaluator, data gateway, Watch pre-trade context, `PaperFillEngine`, fill checks, lapse; Bets screen with live status | Pending / Matched / Unmatched in real time |
| **3. Settlement & bankroll** | Settlement calculator, commission, ledger, Capital tab (curve + statement), bet detail | Won / Lost / Void with net P&L; virtual capital tracks |
| **4. Reports & history** | Report service, Reports tab, Sessions list/detail/compare, CSV | Full reporting |
| **5. Accuracy (optional)** | Validate settlement against real cleared orders; traded-volume fill model | Higher fidelity |

## 9. Testing

**Unit** — fill engine (full/partial/no fill, better price, slippage, ladder walk, lapse, persist, runner removal);
settlement (back/lay × win/lose, WIN/PLACE, dead heat, non-runner, void); commission; bankroll; status mapping;
mode transitions (allowed/rejected).

**Integration (Testcontainers)**
- Start Watch → mode `WATCH`, pending approvals expired, Live automation paused, ticket rejected,
  `POST /automation/trading-mode` returns 409.
- Full Watch session: Layer messages → watch decision → bet → fills → market closes → settlement → ledger → reports;
  `OrderExecutionPort` mock records **zero** placements.
- Race: a Live automation tick in flight at the moment Watch starts is rejected by `TRADING_MODE_WATCH`.
- Real bets open before Watch still reconcile and settle; Live reports unchanged by Watch activity.
- Stop Watch → chosen Live mode restored (Manual default); Live automation resumes; open simulated bets settle into
  the ended session.
- Restart during Watch → mode and session restored.

**Refactor safety** — Live automation, approval and order suites pass unchanged; golden test: same inputs →
identical proposals from Live and Watch.

**Architecture** — ArchUnit: `..watch..` must not access Live order execution classes or `..betfair.adapter..`;
Live packages must not depend on `..watch..` (except `TradingControlService` mode check).

**Frontend** — wizard validation and warnings; start/stop flows; mode dropdown (three options, states, Watch opens the Watch screen without starting it, Manual/Automatic while Watch runs opens Stop Watch with that mode pre-selected, `/trading` redirects to `/trading/watch` while running); ticket
disabled; status chips; Simulated badge on every Watch view and CSV; empty/loading/error states; SSE updates.

## 10. Risks and future enhancements

| Risk | Mitigation |
|---|---|
| Delayed data makes matching optimistic | Slippage, size cap, realistic vs best case side by side, delay stored per bet |
| Settlement edge cases differ from Betfair | Versioned rules with evidence; validation against real cleared orders; uncertain cases "Not evaluable" |
| Simulated bets do not move the market | Size cap per level; limitation shown on screen |
| Forgetting Watch is on (no real bets) | Mode chip on every screen, dropdown shows "Watch · Running", nav dot on Trading |
| Returning to Automatic by accident | Stop dialog pre-selects Manual |

**Future:** manual simulated bet ticket in Watch; bankroll top-up/withdraw (`ADJUSTMENT` entries modelled);
session stop rules (bankroll floor/target); promoting a configuration to Live with its Watch report attached; reuse of
the fill and settlement engines in Historical Replay.

## 11. Documentation updates

- Original plan: move Watch Mode from *Exclude* to *Support* (third trading mode, simulation only, labelled Simulated).
- `README.md`: modes are Manual / Automatic / Watch; "Nothing is simulated at runtime" → "…except Watch Mode, which
  never places real bets and is always labelled Simulated."
- `docs/integrations/feature-matrix.md`, `docs/reporting/business-rules.md` (`FILL_V1`, `WATCH_SETTLEMENT_V1`,
  bankroll), `docs/reporting/kpi-glossary.md` (Watch KPIs), `docs/architecture/overview.md` (mode model, `watch`
  module, guards).

## 12. Implementation notes (differences from the plan above)

- **One migration**, `V13__create_watch_mode.sql` (mode constraint, `market.market_base_rate`, `watch-worker` lease,
  `watch_*` tables, `v_watch_bet`). The capital ledger table is `watch_capital_entry` ("Capital" in the UI).
- **Shared code instead of copies**: `ProposalEngine` (live + Watch proposals), `EntryHistory` (implemented by
  `OrderExposureQueries` and `WatchExposureQueries`), `PreTradeContextFactory.assemble`, `BankResolver.byBasis`,
  `SequenceOutcome.fromSigns`, `StandardRiskRules.SESSION_STOP_CODES`, `MarketResult` (now with removal dates),
  `BetMath`/`MoneyPolicy`, `JdbcAggregates`, `ReportSql`/`ReportFilterResolver`/`CsvWriter` from reporting;
  frontend `TRADING_MODES`, `TradingModeControl`, `ReportFiltersBar`, `downloadCsv`, `StepMarker`, `AutoStatusChip`.
- **No separate market-data gateway**: `MarketDataPort.getPrices` already reads the stream cache first; while Watch
  runs, live automation makes no price calls, so there is no competing load.
- **Display status** of a Watch bet is defined once, as `display_status` in `v_watch_bet`.
- **Watch colour** uses the existing `info` palette token (distinct from Manual indigo and Automatic teal).
- **Mode menu** shows the three modes as a single list (MUI announces dividers/subheaders inside a Select as empty
  options); Watch is set apart by a rule and the "Simulated" badge.
- The original implementation plan document still lists Watch Mode under *Exclude*; update it when the scope change
  is formally recorded.
