# Approval rejection reason taxonomy

Every rejection stores the reason code, optional note (required for OTHER, max 1000 characters), administrator,
timestamp and the frozen proposal snapshot. Rejected proposals are never sent to Betfair.

| Code | Meaning |
| --- | --- |
| `PRICE_UNATTRACTIVE` | Offered price is not attractive enough |
| `INSUFFICIENT_CONFIDENCE` | Not confident in the selection |
| `CONFLICTING_INFORMATION` | Conflicting information from other sources |
| `MARKET_CONDITIONS` | Liquidity, movement or suspension risk |
| `RISK_LIMIT_CONCERN` | Exposure or risk concern |
| `DATA_QUALITY_CONCERN` | Input data looks wrong, stale or incomplete |
| `RACE_CHANGE` | Non-runner, going change or other race change |
| `OTHER` | Anything else — note required |

Proposals not reviewed within the configuration's approval timeout become EXPIRED and are never executed.
