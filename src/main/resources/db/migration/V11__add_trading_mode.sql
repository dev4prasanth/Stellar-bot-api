-- Trading mode: one global switch between AUTOMATIC (proposals from running configurations are submitted without
-- administrator approval) and MANUAL (every proposal waits for the administrator to approve or reject it).
-- MANUAL is the default so a new installation never places a bet without a human decision.
ALTER TABLE global_control
    ADD COLUMN trading_mode            VARCHAR(10)  NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN trading_mode_changed_by VARCHAR(100),
    ADD COLUMN trading_mode_changed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_global_control_trading_mode CHECK (trading_mode IN ('MANUAL', 'AUTOMATIC'));

-- The mode each bet was placed under, fixed when the intent is created:
--   MANUAL    = a human confirmed it (approved proposal or administrator bet ticket)
--   AUTOMATIC = submitted without human approval
-- Distinct from order_intent.mode, which records the order's origin (configuration pipeline vs ticket) and drives
-- origin-specific pre-trade rules such as Stop All, entry windows and session limits.
ALTER TABLE order_intent ADD COLUMN trading_mode VARCHAR(10);

UPDATE order_intent oi
SET trading_mode = CASE
        WHEN oi.mode = 'MANUAL' THEN 'MANUAL'
        WHEN EXISTS (SELECT 1 FROM recommendation r
                     WHERE r.id = oi.recommendation_id AND r.requires_approval) THEN 'MANUAL'
        ELSE 'AUTOMATIC'
    END;

ALTER TABLE order_intent
    ALTER COLUMN trading_mode SET NOT NULL,
    ADD CONSTRAINT ck_order_intent_trading_mode CHECK (trading_mode IN ('MANUAL', 'AUTOMATIC'));

CREATE INDEX ix_order_intent_trading_mode_created ON order_intent (trading_mode, created_at DESC);

-- Reporting views expose the trading mode as "mode", so every screen, filter and export agrees on what a
-- Manual or Automatic bet is. The order's origin stays available as "origin".
DROP VIEW v_settled_bet;
CREATE VIEW v_settled_bet AS
SELECT s.bet_id,
       s.bet_status,
       s.revision,
       (s.revision > 1)                  AS corrected,
       b.intent_id,
       oi.recommendation_id,
       oi.configuration_id,
       oi.configuration_version,
       oi.trading_mode                   AS mode,
       oi.purpose,
       b.market_id,
       b.selection_id,
       b.side,
       oi.market_type,
       oi.requested_price,
       oi.requested_size,
       b.size_matched,
       b.average_price_matched,
       s.bet_outcome,
       s.settled_at,
       s.price_matched,
       s.price_reduced,
       s.size_settled,
       s.size_cancelled,
       s.profit,
       CASE
           WHEN s.bet_status = 'VOIDED' THEN 'VOID'
           WHEN s.bet_status IN ('LAPSED', 'CANCELLED') THEN 'UNMATCHED'
           WHEN s.profit > 0 AND s.price_reduced IS TRUE THEN 'WON_REDUCED'
           WHEN s.profit > 0 THEN 'WON'
           WHEN s.profit < 0 THEN 'LOST'
           ELSE 'SETTLED_ZERO'
       END                                AS outcome_class,
       oi.mode                            AS origin
FROM settlement s
JOIN bet_order b ON b.bet_id = s.bet_id
JOIN order_intent oi ON oi.id = b.intent_id
WHERE s.is_current;

-- A decision belongs to Manual mode when it required (or was) an administrator decision.
DROP VIEW v_decision;
CREATE VIEW v_decision AS
SELECT r.id                             AS recommendation_id,
       r.created_at,
       r.decided_at,
       (CASE WHEN r.mode = 'MANUAL' OR r.requires_approval THEN 'MANUAL' ELSE 'AUTOMATIC' END)::VARCHAR(10) AS mode,
       r.selection_source,
       r.configuration_id,
       r.configuration_version,
       r.market_id,
       r.selection_id,
       r.side,
       r.market_type,
       r.status,
       r.requires_approval,
       r.strategy_code,
       r.strategy_version,
       r.proposed_price,
       r.proposed_stake,
       r.proposed_liability,
       r.no_bet_reason,
       a.decision                       AS approval_decision,
       a.reason_code                    AS rejection_reason,
       a.decided_at                     AS approval_decided_at,
       e.classification                 AS outcome_classification,
       e.runner_result,
       e.dead_heat,
       e.rule_version                   AS outcome_rule_version,
       EXISTS (SELECT 1 FROM order_intent oi
               WHERE oi.recommendation_id = r.id
                 AND oi.status IN ('PLACED', 'UNKNOWN_OUTCOME'))           AS submitted,
       EXISTS (SELECT 1 FROM order_intent oi JOIN bet_order b ON b.intent_id = oi.id
               WHERE oi.recommendation_id = r.id AND b.size_matched > 0)  AS matched,
       r.mode                           AS origin
FROM recommendation r
LEFT JOIN approval_decision a ON a.recommendation_id = r.id
LEFT JOIN decision_outcome_evaluation e
       ON e.recommendation_id = r.id AND e.rule_version = 'OUTCOME_RULES_V1';
