# KPI glossary (definitions version 2026.10-1)

Served live at `GET /api/v1/reports/definitions` and shown in Reports → Definitions. Dashboard tiles, charts,
tables and CSV exports all read the same views (`v_settled_bet`, `v_decision`) through one filter builder.

## Counting grains and date bases

| Stage | Identifier (grain) | Date basis |
| --- | --- | --- |
| Recommendation / decision | `recommendation.id` | `created_at` (decision date); administrator rejections in trends use `approval_decided_at` |
| Submission | `order_intent.id` (one per order attempt intent) | `created_at` (placement date) |
| Matching | `bet_order.bet_id` | placement date |
| Settlement | settlement unit = (`bet_id`, Betfair `bet_status`), current revision | `settled_at` |
| Commission | market (`market_settlement.market_id`) | market settlement date |

All timestamps are stored in UTC; day/week/month buckets are drawn in the selected display time zone
(weeks start Monday). Custom ranges are inclusive local dates.

## Metrics

| Metric | Definition |
| --- | --- |
| Evaluated recommendations | All decision records: ACCEPTED, SKIPPED (NO BET), PENDING_APPROVAL, APPROVED, REJECTED, EXPIRED; includes administrator-selected Manual Mode tickets (source ADMIN_SELECTED). |
| Mode (filter, column) | Trading mode: **Manual** = confirmed by the administrator (approved proposal or bet ticket, or a decision that required approval); **Automatic** = placed or decided without approval. Reports, Bet History and exports use the same definition. |
| Manually rejected | Recommendations rejected in the Manual-mode confirmation dialog. Never submitted. |
| Bets placed | Intents with status PLACED (Betfair accepted). Proposals, risk refusals, exchange rejections, technical failures and unknown outcomes are excluded. |
| Matched / unmatched | Placed bets with matched size > 0 / = 0. |
| Unresolved | Placed bets with matched size but no SETTLED/VOIDED settlement yet. |
| Exchange-rejected | Intents Betfair refused (report or instruction FAILURE). Distinct from administrator rejections. |
| Unknown outcome | Ambiguous submissions awaiting reconciliation (not counted as placed until found). |
| Won / Won (reduced) / Lost | SETTLED units with profit > 0 / profit > 0 with a Betfair price reduction / profit < 0. |
| Settled at zero, Void, Unmatched | SETTLED with profit 0; VOIDED; LAPSED or CANCELLED units. |
| Corrected | Units whose current revision is > 1 (provider correction). Corrections replace, never add. |
| Win rate | (won + won reduced) ÷ (won + won reduced + lost). Denominator always shown. |
| Rejected → favorable / unfavorable / void / not evaluable / awaiting | Post-race selection outcome of rejected proposals (rule `OUTCOME_RULES_V1`). Favorable rate = favorable ÷ (favorable + unfavorable); coverage = evaluated ÷ rejected. **Hypothetical** — never a won bet, never P&L. |
| Matched stake, LAY liability | Σ settled size; Σ settled size × (matched price − 1) for LAY. |
| Gross P&L | Σ Betfair settled profit of SETTLED units (before commission). |
| Commission | Σ market-level commission for markets with this application's bets. Not attributable when filtering by configuration, mode, side or market type (shown as such). |
| Net P&L | Gross P&L − commission (only when commission is attributable). |

Deposits are not profit, withdrawals are not losses, and open exposure is not realized P&L.

## Factor analysis

Compares settled wins vs losses using values stored at decision time (snapshot inputs/intermediates, final
score, decision odds, price rank, market volume) and execution facts (matched odds, settled stake). Missing values
are counted, never filled. Results are descriptive and do not imply causation or future predictive power.
