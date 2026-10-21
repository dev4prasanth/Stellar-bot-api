# RabbitMQ operations: retry, dead-letter and reprocessing

## Owned topology (declared when `stellartrack.messaging.declare-owned-topology=true`)

- `stellartrack.ingestion.retry` (direct) → `stellartrack.layerN.retry` queues, dead-lettering back to the
  layer's subscription queue (`stellartrack.phase2.layerN`) after a per-message TTL. Retry and DLQ copies carry
  `x-st-original-exchange` / `x-st-original-routing-key`, so the contract still sees the producer's routing key.
- `stellartrack.ingestion.dlx` (direct) → `stellartrack.layerN.dlq` queues for poison and retry-exhausted messages.

## Subscription queues (declared when `ST_LAYERn_DECLARE_SUBSCRIPTION=true`)

`stellartrack.phase2.layer1|2|3` are durable queues owned by this application and bound to the producers'
`stellar-track-exchange` with `ST_LAYERn_ROUTING_KEYS`. The exchange is never redeclared with different
arguments (it is only created, as a durable topic exchange, if it does not exist yet).

The producer queues (`layer1-predictions-queue`, `race-data-queue`, `race-results-queue`, `planetary-state-queue`,
`reranked-predictions-queue`) belong to Layer 1/2/3 and are never declared or consumed here: a second consumer
would split their messages. A layer configured with one of those queue names refuses to start
(`Queue … belongs to another service`).

## Retry policy

Transient failures (database unavailable, transient persistence errors) are copied to the retry queue with
header `x-st-attempt` and an expiration of `initial-interval × multiplier^(attempt−1)`, capped at `max-interval`,
with ±`jitter-ratio` jitter; the original delivery is acknowledged only after the broker confirms the copy.
After `max-attempts` the message goes to the DLQ and the receipt becomes `QUARANTINED`. Schema/validation
errors, unsupported versions, oversize payloads and pending contracts are dead-lettered immediately.
If even the retry/DLQ publish fails, the delivery is rejected with requeue after a 1-second pause, so it is never
lost and never spins.

## Inspecting failures

- UI: **Settings → RabbitMQ ingestion** (per-layer state, 24h outcome counts, retries, dead letters, receipts).
- API: `GET /api/v1/ingestion/layers`, `GET /api/v1/ingestion/receipts?layer=&status=`.
- Broker: `stellartrack.layerN.dlq` messages carry `x-st-error-class` and `x-st-receipt-id`.

## Reprocessing

Preferred: `POST /api/v1/ingestion/receipts/{id}/reprocess` (or the Reprocess button) re-runs a QUARANTINED,
INVALID or CONTRACT_PENDING receipt from its retained raw payload. The original receipt becomes `REPROCESSED`
and the new outcome is linked via `reprocessed_from`. This cannot submit duplicate bets: ingestion only stores
source data idempotently, and every order passes duplicate-entry checks.

Alternative: move messages from the DLQ back to the subscription queue with the RabbitMQ management "Move messages"
(shovel) feature after fixing the cause; deduplication makes this safe.
