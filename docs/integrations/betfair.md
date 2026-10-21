# Betfair Exchange integration

Adapter package: `com.stellartrack.betfair`. Domain modules depend only on the ports `MarketDataPort`,
`OrderExecutionPort`, `AccountPort` and `ExchangeAvailability`.

## Configuration (backend environment only)

`ST_BETFAIR_ENABLED`, `ST_BETFAIR_APP_KEY`, `ST_BETFAIR_USERNAME`, `ST_BETFAIR_PASSWORD`,
`ST_BETFAIR_CERT_KEYSTORE` (PKCS#12 with the client certificate registered on the account),
`ST_BETFAIR_CERT_KEYSTORE_PASSWORD`, `ST_BETFAIR_STREAM_ENABLED`. Endpoints default to the global
(`.com`) exchange and are overridable for other jurisdictions. None of these values reach the frontend or logs.

**Where the values come from**

- Docker Compose: `.env` (`env_file`). The keystore is read from the mounted `ST_BETFAIR_CERT_DIR`
  (default `certificate/`) as `/run/secrets/betfair/${ST_BETFAIR_CERT_FILE}`.
- Backend outside Docker (IDE, `java -jar`) with the `local` profile: `.env` is loaded automatically
  (relative to the working directory, i.e. this repository's root), values taken literally like Compose. Set `ST_ENV_FILE` to use
  another file in any profile. Real environment variables and IDE run settings always take precedence. Startup
  logs `Loaded N settings from …` (never the values).

**Delayed vs live application key.** Betfair's *Delayed* application key returns prices flagged
`isMarketDataDelayed` (delayed by a variable interval). The app shows these as **DELAYED**, never as live, and
automatic entries require LIVE prices. Real-time prices need Betfair's *Live* application key (activated on the
account by Betfair); no code change is required to switch keys.

**Troubleshooting**

| Symptom (Settings → Integrations / header) | Cause |
| --- | --- |
| "disabled (ST_BETFAIR_ENABLED=false)" | Flag not `true`, or the backend process does not receive the settings |
| "credentials or client certificate are not configured" | App key, username, password or keystore path missing |
| Login failed: `CERT_AUTH_REQUIRED` / `INVALID_USERNAME_OR_PASSWORD` / `ACCOUNT_*` | Certificate not uploaded to the account, wrong credentials or account restriction (`loginStatus` from Betfair) |
| "Cannot load Betfair client certificate keystore" | Wrong keystore path or password, or not a PKCS#12 file |
| Prices DELAYED | Delayed application key (see above) |

## Session lifecycle

Non-interactive login (`identitysso-cert.betfair.com/api/certlogin`) on first use; `keepAlive` every
`keep-alive-interval` (30 min); an `INVALID_SESSION_INFORMATION`/`NO_SESSION` error triggers one re-login and a
single retry (safe because the request was not executed); `logout` on shutdown.

## Operations

| Operation | Use | Limits handled |
| --- | --- | --- |
| `listEvents` | Meetings list | — |
| `listMarketCatalogue` | Catalogue sync (WIN/PLACE, horse racing `eventTypeId=7`) | Weight 2/market → pages of 100, continued by start time |
| `listMarketBook` | Prices/status/results (`EX_BEST_OFFERS`, virtualised) | Weight 5/market → chunks of 40 |
| `placeOrders` | One LIMIT instruction per intent with `customerRef`, `customerOrderRef`, `customerStrategyRef`, `marketVersion` | Never retried on transport failure |
| `cancelOrders` | Full or partial cancellation | Never retried |
| `listCurrentOrders` | Reconciliation by market or `customerOrderRef` | 1000/page |
| `listClearedOrders` | SETTLED/VOIDED/LAPSED/CANCELLED bets; MARKET grain for commission | 1000/page |
| `getAccountFunds`, `getAccountDetails`, `getAccountStatement` | Funds, currency, statement (DEPOSITS_WITHDRAWALS and EXCHANGE separately) | 100/page |

`replaceOrders`/`updateOrders` are not used: no approved feature requires them.

## Bet types

Every bet type is an ordinary `placeOrders` LIMIT order on the right market; Betfair has no per-type endpoint.

- **Win / Place:** `WIN` and `PLACE` markets; a Place market pays `numberOfWinners` places. Back and Lay.
- **Each-Way (Back only):** `EACH_WAY` markets (UK/IE racing). Place terms come from the market description
  (`eachWayDivisor`: the place part pays at 1/divisor of the win odds) and the book's `numberOfWinners` (places).
  The application treats the submitted `size` as the **stake per part**, so the money at risk is `2 × size` (used for
  exposure limits, funds checks and Watch). **Verify this convention with one small real Each-Way bet before using real
  money**; if Betfair treats `size` as the total, change `BetMath.eachWayTotalStake` and the two exposure queries.
  Real settlement uses Betfair's own profit (`listClearedOrders`); Watch settles WINNER / PLACED / LOSER itself.
- **Dutch (Back, WIN markets only):** not a Betfair feature. The application splits the total stake for equal profit
  and places each leg through the normal single-bet path (durable intent, idempotency, reconciliation), so legs are
  separate orders that match independently. Legs share `order_intent.dutch_group_id`; an incomplete Dutch is reported
  and its unmatched stake can be revoked for the whole group.

## Error normalization

`BetfairApiException.Kind`: SESSION, TRANSIENT (`TOO_MANY_REQUESTS`, `SERVICE_BUSY`, `TIMEOUT_ERROR`,
`UNEXPECTED_ERROR`), ACCESS, CLIENT, TIMEOUT, NETWORK, PROTOCOL, DISABLED. Reads retry with exponential back-off
(longer after `TOO_MANY_REQUESTS`). For placement, report status `TIMEOUT`, transport timeouts/resets and APING
`TIMEOUT_ERROR`/`UNEXPECTED_ERROR` become **UNKNOWN_OUTCOME** and are reconciled by `customerOrderRef`; other
pre-execution errors are TECHNICAL_FAILURE; instruction/report FAILURE is EXCHANGE_REJECTED with the
instruction error code preserved.

## Exchange Stream

TLS socket to `stream-api.betfair.com:443`; authentication with app key + session; `marketSubscription`
(EX_BEST_OFFERS, EX_TRADED_VOL, EX_LTP, EX_MARKET_DEF, 3 levels) for the watch list (upcoming markets and markets
with open orders, ≤ 200) refreshed every 30 s; `orderSubscription` filtered by `customerStrategyRef`.
Reconnects with exponential back-off and resubscribes with `initialClk`/`clk` for delta recovery. Data counts as
fresh only while subscribed, the provider is not flagging `status=503`, and a message or heartbeat arrived within
three heartbeat intervals. Stream prices are labelled delayed unless a polled market book confirmed
`isMarketDataDelayed=false`. Order updates flow through the same `OrderStateApplier` as polling.

## Attribution

Only orders created through this application (intent with `customerOrderRef`) are stored and reported; bets
placed elsewhere on the account appear only in account-wide funds/exposure, never in a configuration's results.
