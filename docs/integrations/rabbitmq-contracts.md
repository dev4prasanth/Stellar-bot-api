# RabbitMQ Layer 1/2/3 contracts

Phase 2 consumes the messages that Layer 1 (`stellartrackbackend`), Layer 2 (`planet-determinstic-system-layer-2`)
and Layer 3 (`re-ranking-engine-layer-3`) publish today, unchanged. The facts below were taken from the producers'
source code; the contracts are `l1-prediction-v1`, `l2-planetary-state-v1` and `l3-ranking-v1`
(`messaging/contract/layer1|layer2|layer3`). Accepted records are **inputs only**: they fill the Race Signals read
model and never create recommendations or orders.

## Contract register

| Item | Layer 1 (prediction) | Layer 2 (planetary state) | Layer 3 (prediction) |
| --- | --- | --- | --- |
| Producer | `RabbitMQService.publishRacePrediction` (NestJS, `amqp-connection-manager`, `json: true`) | `StateChangePublisher.publishStateChange` (Spring AMQP, default `Jackson2JsonMessageConverter`) | `Layer3RankingPublisher.publish` (Spring AMQP, Jackson 2 + JavaTimeModule, ISO dates) |
| Broker / vhost / credentials (local) | `localhost:5672`, vhost `/`, `guest`/`guest` | same | same |
| Exchange | `stellar-track-exchange` (topic, durable) | same | same |
| Routing key | `layer1.prediction` (replay tool publishing directly: `layer1-predictions-queue`) | `state-change` | `layer3.reranked` |
| Producer-owned queue (never consumed by Phase 2) | `layer1-predictions-queue` (Layer 3 consumes it) | `planetary-state-queue` (Layer 3 consumes it) | `reranked-predictions-queue` (no consumer; Phase 2 is the intended "Layer 4") |
| Phase 2 queue | `stellartrack.phase2.layer1` | `stellartrack.phase2.layer2` | `stellartrack.phase2.layer3` |
| Headers | `contentType: application/json`, persistent; **no** `__TypeId__`, message id or correlation id. Replay tool: `__TypeId__ = com.kmss.racehitservice.dto.rabbit.Layer1PredictionMessage` | `__TypeId__ = java.util.HashMap` (+ `__KeyTypeId__`, `__ContentTypeId__`) | `__TypeId__ = com.stellartrack.layer3.event.dto.Layer3RankingMessage` |
| Version selector | routing key `layer1.prediction`/`layer1-predictions-queue` **or** the replay type header, **and** payload has `finalPrediction` + `odds` | `__TypeId__ = java.util.HashMap` or payload has `states` | `__TypeId__` ends with `Layer3RankingMessage` or payload has `rankings` + `rerankPolicyVersion` |
| Payload | `cardId`, `raceNumber`, `raceDate`, `finalPrediction[{horseNumber, choice, planetScore}]`, `otherRunners[]?`, `odds[{horseNumber, odds}]` | `cardId`, `raceNumber`, `timestamp`, `states{PLANET: PlanetaryState}`, `raceCardContext` (nullable) | `cardId`, `raceNumber`, `source`, `rerankPolicyVersion`, `generatedAt`, `rankings[{horseId, horseNumber, finalRank, confidenceScore, selectionConfidenceScore, rationaleTags, top4, nap, odds}]` |
| Encoding details | `odds = 0` means unknown; replay tool may send `odds: null` (also unknown) and an extra `metadata` map (ignored) | `Instant` as **decimal epoch seconds** (`1754055910.123456789`), `LocalTime` as an **array** (`[14,30]`); ISO text also accepted. `hora` is read as text (Layer 3 types it as a string) | `isTop4`/`isNap` Java fields serialise as `top4`/`nap`; both spellings accepted. `odds` may be null |
| Validation | `cardId` = `^\d+-\d{4}-\d{2}-\d{2}$` and matches `raceDate`; `raceNumber ≥ 2`; `finalPrediction` non-empty; `horseNumber ≥ 1`, unique; `choice ≥ 1`, unique (across final + other runners); `planetScore` integer; `odds ≥ 0` | `cardId` format; `raceNumber ≥ 1`; `timestamp` present; `states` keys are MARS/VENUS/MERCURY/MOON/SUN; `strengthScore` 0–100 | `cardId` format; `raceNumber ≥ 1`; `source` ∈ {`LAYER1_RERANKED`, `STANDALONE`}; `generatedAt` present; `rankings` non-empty; `finalRank ≥ 1`, unique; `horseNumber ≥ 1` |
| Identity (dedup key) | `L1:{cardId}:{raceNumber}:{payloadSha256}` | `L2:{cardId}:{raceNumber}:{payloadSha256}` | `L3:{cardId}:{raceNumber}:{source}:{generatedAt}` |
| Revisions | Cron re-runs and manual publishes resend; an identical body is `DUPLICATE`, a changed one is a new `ACCEPTED` revision (latest by arrival) | Re-processing after a changed result publishes a new `timestamp` → new revision (latest by `timestamp`) | Republish after a replacement has a new `generatedAt` → new revision (latest by `generatedAt`) |
| Source event time / freshness | **None** — Layer 1 has no timestamp, so freshness is never applied | `timestamp` | `generatedAt` |
| Race semantics | Prediction for race N; race 1 is never predicted | States **after race N's result**; Layer 3 uses them for race **N + 1** | Final ranking for race N; none for race 1 |
| Betfair `marketId` / `selectionId` mapping | Approved venue mapping + race market rule + runner `CLOTH_NUMBER` (see below) — **pending: no venue mapping supplied** | not applicable (planets, not runners) | as Layer 1 — **pending** |

Freshness is configurable per layer (`ST_LAYERn_MAX_AGE`, `ST_LAYERn_REJECT_STALE`). The `local` profile turns
rejection off so historical CSV replays through `race-hit-service` are not marked `STALE`.

### Race result (not consumable today)

`RaceResultDto` (`cardId, raceNumber, venue, raceDate, raceTime?, winner/second/third/fourth, fieldSize,
scratches[], metadata{hora, chartNumber, zone, totalRaces}`) is sent by Layer 1 with
`sendToQueue('race-results-queue')` on the **default exchange**, so it never passes through
`stellar-track-exchange` and Phase 2 cannot subscribe to it without stealing Layer 2's messages.

### Optional: race data

`race.data` (`RaceDataDto`: `cardId, raceNumber ≥ 2, raceDate, runnerNumbers[], scratchedHorseNumbers[]?, odds[]`)
feeds Layer 3's standalone flow. Phase 2 does not consume it yet; Layer 3's `STANDALONE` rankings already arrive on
`layer3.reranked`.

## Subscription model (no message stealing)

Layer 2 and Layer 3 already consume `planetary-state-queue`, `layer1-predictions-queue` and `race-data-queue`;
Layer 2 consumes `race-results-queue`. A second consumer on any of them would split the messages between services.
Phase 2 therefore declares **its own durable queues** and binds them to the existing exchange
(`LayerListenerRegistrar`, `ST_LAYERn_DECLARE_SUBSCRIPTION=true`). The exchange is never redeclared with different
arguments, and configuring a producer-owned queue as a Phase 2 queue blocks that consumer at start-up.
`RabbitIngestionIT` asserts that `layer1-predictions-queue` and `planetary-state-queue` still receive their copy of
every message.

| Variable | Layer 1 | Layer 2 | Layer 3 |
| --- | --- | --- | --- |
| `ST_LAYERn_QUEUE` | `stellartrack.phase2.layer1` | `stellartrack.phase2.layer2` | `stellartrack.phase2.layer3` |
| `ST_LAYERn_EXCHANGE` | `stellar-track-exchange` | `stellar-track-exchange` | `stellar-track-exchange` |
| `ST_LAYERn_ROUTING_KEYS` | `layer1.prediction,layer1-predictions-queue` | `state-change` | `layer3.reranked` |

## Betfair mapping (pending)

Market links are only emitted when every step has an approved rule; otherwise the race is reported as unmapped
(`VENUE_NOT_MAPPED`, `MARKET_NOT_FOUND`, `RUNNERS_NOT_MAPPED`) and no link is written:

1. `venueId` (from `cardId`) → Betfair venue: `stellartrack.messaging.betfair-mapping.venues` (YAML), e.g.
   `"[1096]": { betfair-venue: Kempton, time-zone: Europe/London, race-rule: MARKET_NAME_PREFIX }`.
2. Race number → stored WIN market at that venue on the race date: `MARKET_NAME_PREFIX` (name starts with `R{n}`)
   or, only where confirmed, `START_TIME_ORDER` (n-th WIN market of the day).
3. Horse number → `selectionId` of the runner whose stored `RUNNER_METADATA.CLOTH_NUMBER` equals it.

No venue mapping has been supplied, so every race currently shows `VENUE_NOT_MAPPED`.

## Producer change request (not implemented here)

To let Phase 2 show actual race results, Layer 1 should change `RabbitMQService.publishRaceResult` from
`sendToQueue('race-results-queue', …)` to `publish('stellar-track-exchange', 'race.result', …)`.
Layer 2 already binds `race-results-queue` to `race.result` on that exchange (`RabbitMQConfig.raceResultBinding`), so
Layer 2 keeps receiving every result; Phase 2 could then bind its own `stellartrack.phase2.race-results` queue.

## Adding a new contract version (no redesign required)

1. Implement a deserializer, validator and mapper (to `NormalizedLayerRecord`: dedup key, event time, normalized
   fields, approved market links). The mapper must not change source semantics.
2. Register a `LayerContract<T>` bean (`LayerContractsConfig`) with a `versionSelector` that matches only the new
   version.
3. If confirmed fields deserve typed columns, add a new Flyway migration (never edit applied ones).
4. Verify with real sample messages; reprocess any `CONTRACT_PENDING` / `INVALID` receipts.

## Processing guarantees

- Manual acknowledgement only after the receipt and record commit (at-least-once).
- Idempotency: unique `(layer, dedup_key)` for accepted receipts and records; redeliveries become `DUPLICATE`.
- Outcomes per receipt: ACCEPTED, DUPLICATE, STALE, INVALID, RETRYING, QUARANTINED, CONTRACT_PENDING, REPROCESSED.
- Payload size limit (`max-payload-bytes`); payload bodies are never logged or returned by the API.
- Accepted records publish an internal event that updates the Race Signals read model (`race_signal_*`, V12) and
  pushes a `signals` notification to the UI; they never trigger an order.
