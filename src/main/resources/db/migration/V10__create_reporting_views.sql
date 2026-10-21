-- Shared reporting definitions. Dashboard tiles, report charts, drill-downs and CSV exports all read these
-- views so every surface uses the same counting grain and classification (see docs/reporting/kpi-glossary.md).

-- Settlement units attributed to this application: one row per (bet_id, bet_status) current revision.
-- Bets placed elsewhere on the account have no order_intent and are excluded by the inner join.
CREATE VIEW v_settled_bet AS
SELECT s.bet_id,
       s.bet_status,
       s.revision,
       (s.revision > 1)                  AS corrected,
       b.intent_id,
       oi.recommendation_id,
       oi.configuration_id,
       oi.configuration_version,
       oi.mode,
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
       END                                AS outcome_class
FROM settlement s
JOIN bet_order b ON b.bet_id = s.bet_id
JOIN order_intent oi ON oi.id = b.intent_id
WHERE s.is_current;

-- Recommendation lifecycle with the manual decision and post-race selection outcome (current rule version).
CREATE VIEW v_decision AS
SELECT r.id                             AS recommendation_id,
       r.created_at,
       r.decided_at,
       r.mode,
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
               WHERE oi.recommendation_id = r.id AND b.size_matched > 0)  AS matched
FROM recommendation r
LEFT JOIN approval_decision a ON a.recommendation_id = r.id
LEFT JOIN decision_outcome_evaluation e
       ON e.recommendation_id = r.id AND e.rule_version = 'OUTCOME_RULES_V1';
