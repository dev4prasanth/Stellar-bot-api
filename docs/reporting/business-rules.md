# Business rules

## Monetary precision

BigDecimal throughout; currency scale 2. Stakes are rounded **down** (never exceed a limit or bank share);
derived amounts use HALF_EVEN. A stake below the minimum (configured or exchange minimum, `ST_BETFAIR_MIN_STAKE`)
is refused, never raised. Prices must be on the Betfair ladder.

Base-case formulas (fully matched, unadjusted, before commission) at odds p, stake s: BACK win profit s(p−1),
BACK loss −s; LAY liability s(p−1), LAY win profit s, LAY loss −s(p−1). Actual results always come from Betfair
settlements.

## Staking methods

| Method | Stake | Side |
| --- | --- | --- |
| Fixed stake | amount | BACK/LAY |
| Percentage of bank | bank × percent | BACK/LAY |
| Fixed liability | liability ÷ (p − 1) | LAY only |
| Percentage-of-bank liability | (bank × percent) ÷ (p − 1) | LAY only |
| Target profit | BACK: target ÷ (p − 1); LAY: target | BACK/LAY |
| Staking plan | current plan step (STAKE, or LIABILITY ÷ (p − 1) for LAY) | per step |

Bank basis: AVAILABLE_BALANCE (fresh Betfair funds only), ALLOCATED_CAPITAL (configuration allocation), or
FIXED_BANK. Plan steps advance on settled WIN/LOSS of the configuration's entries in settlement order; zero-profit
and void units do not advance. Plan files: CSV/XLSX schema v1 `step,amount,amount_type,on_win,on_loss`.

## Limits and stops (definitions)

- **Race** = event + scheduled start (WIN and PLACE share the race limit). **Session** = one automation run.
  **Day** = calendar day in the reporting time zone.
- Entry limits count live ENTRY intents (pending, submitting, placed, unknown).
- Exposure is the conservative sum of unsettled orders (BACK stake; LAY liability), plus requested exposure of
  unconfirmed intents; hedges are not netted.
- P/L stops use current settled profit of the session's entries; percentage stops use the bank at session start
  (allocation if set, else available balance). Consecutive win/loss stops skip zero and void units.
- Stops and Stop All prevent new entries only; they do not cancel or close existing positions.

## Pre-trade checks (every submission)

Exchange available; Stop All (automatic); market type; market OPEN; in-play allowed and Betfair in-play bet delay
≤ configured maximum; entry window (automatic); runner ACTIVE; price data LIVE (automatic) or LIVE/acknowledged
DELAYED (manual); price on ladder and within odds range; adverse price move ≤ allowed ticks (manual/approved, else
reconfirm); stake valid; fresh funds and minimum balance; no live entry on the same runner/side (any mode or
configuration; database-enforced); per-race/day/session limits; race and open exposure limits; capital
allocation; session stops.

## Market-criteria selection v1

A runner is selected when it is ACTIVE and passes every configured filter (missing data fails the rule). When
more qualify than `maxSelectionsPerRace`, the lowest price ranks are kept. Each evaluated runner is recorded
(selected or NO BET with reasons) with an immutable snapshot, once per market per session, and only when prices
are LIVE.

## Post-race outcome rules (OUTCOME_RULES_V1)

REMOVED → VOID; WIN market: BACK favorable if WINNER, LAY favorable if LOSER; PLACE market: Betfair marks
placed runners WINNER, same rule; other statuses or missing results → NOT_EVALUABLE; dead heats flagged, no
payout inferred.

## Order lifecycle

PENDING_SUBMISSION → SUBMITTING → PLACED | EXCHANGE_REJECTED | UNKNOWN_OUTCOME | TECHNICAL_FAILURE.
UNKNOWN_OUTCOME is reconciled by `customerOrderRef`: found → PLACED; not found after 10 min →
NOT_FOUND_AFTER_RECONCILIATION (still re-checked for 24 h). Never resubmitted automatically.

## Trading mode

One global switch on the Trading screen (default **MANUAL**; changes are audited as `TRADING_MODE_CHANGED`):

- **MANUAL**: every proposal from a running configuration is held as PENDING_APPROVAL. A confirmation dialog shows
  the prediction (decision snapshot), bet type and all values; the bet is placed only when the administrator
  approves it, after every pre-trade check runs again. Unanswered proposals expire after the configuration's
  approval timeout and are never placed.
- **AUTOMATIC**: proposals are submitted without approval, through the same pre-trade checks.
- Switching affects new proposals only; queued proposals stay until decided or expired. The mode never starts or
  stops a configuration, and Stop All still blocks new automatic entries. The per-configuration
  `approval.manualApprovalRequired` flag is superseded by the trading mode and ignored.
- Each order records the mode it was placed under (`order_intent.trading_mode`): MANUAL when a human confirmed it
  (approved proposal or bet ticket), AUTOMATIC otherwise. This is separate from the order's origin
  (`order_intent.mode`: configuration pipeline or ticket), which still selects origin-specific pre-trade rules
  (Stop All, entry window, session limits and stops).

## Watch Mode (simulation)

A third trading mode, **WATCH**, chosen from the same mode menu. Choosing Watch opens the Watch screen; Watch starts
only with **Start Watch** (starting capital: a custom amount or a copy of the real available balance, plus the
configurations to run) and ends with **Stop Watch** (returns to Manual unless Automatic is chosen).

- While WATCH is active no real bet can be placed: live automation pauses, pending approvals expire when Watch
  starts, and the pre-trade rule `TRADING_MODE_WATCH` refuses every real entry. Real bets already on Betfair settle
  normally. The live Manual/Automatic switch is refused (409) until Watch stops.
- Running configurations use the same proposal logic as live automation (`ProposalEngine`) and the same pre-trade
  rules, evaluated against the session's virtual capital and simulated bets. Delayed prices are accepted. Live
  Stop All does not apply to simulated bets.
- Simulated bets are placed automatically (no approval) and stored only in `watch_*` tables; live reports never
  include them.
- **Matching `FILL_V1`**: limit = requested price made worse by the slippage allowance (default 1 tick); BACK matches
  offered back prices ≥ limit, LAY offered lay prices ≤ limit, best first, at the offered price, capped at the size
  shown (minus what the same bet already took at that price). Unmatched parts lapse at the off (LAPSE,
  MARKET_ON_CLOSE) or at close (PERSIST); a removed runner voids the unmatched part.
- **Settlement `WATCH_SETTLEMENT_V1`** (from the captured market result): nothing matched → NOT_MATCHED; own runner
  removed → VOID; later removals with reduction factor ≥ 2.5% reduce the matched price `× (1 − ΣR/100)`; WIN-market
  dead heat divides the stake by the runners tied; a PLACE-market dead heat on a winner and unknown statuses are
  UNRESOLVED (never guessed).
- **Commission**: per session and market, `max(0, net market profit) × market base rate × (1 − account discount)`,
  attributed to winning bets in proportion; the base rate falls back to the configured default when the market
  description has none.
- **Virtual capital**: append-only ledger (start, settled bets, commission). Available to bet = capital − open
  simulated exposure (same worst case as live orders).
- **Reports** (`WATCH_REPORTS_V1`, placement-date basis): win rate = won ÷ (won + lost); ROI = net ÷ matched stake;
  best case = every bet fully matched at its requested price; max drawdown = largest fall of cumulative net P&L.

## Restart policy

`NEVER_RESUME`: configurations running at shutdown are stopped on startup with a recorded reason.
