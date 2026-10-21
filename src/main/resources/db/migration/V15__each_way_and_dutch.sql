-- Each-Way markets (Back only) and Dutch groups.
--  * EACH_WAY joins WIN and PLACE as a supported Betfair horse-racing market type. Its place terms come from the
--    market description: each_way_divisor (place part paid at 1/divisor of the win odds) and number_of_winners
--    (the number of places).
--  * A Dutch bet is several Back bets in one WIN market placed together; its legs share dutch_group_id.

ALTER TABLE market DROP CONSTRAINT ck_market_type;
ALTER TABLE market ADD CONSTRAINT ck_market_type CHECK (market_type IN ('WIN', 'PLACE', 'EACH_WAY'));
ALTER TABLE market ADD COLUMN each_way_divisor NUMERIC(6, 2);

ALTER TABLE recommendation DROP CONSTRAINT ck_recommendation_market_type;
ALTER TABLE recommendation ADD CONSTRAINT ck_recommendation_market_type CHECK (market_type IN ('WIN', 'PLACE', 'EACH_WAY'));

ALTER TABLE order_intent DROP CONSTRAINT ck_order_intent_market_type;
ALTER TABLE order_intent ADD CONSTRAINT ck_order_intent_market_type CHECK (market_type IN ('WIN', 'PLACE', 'EACH_WAY'));
ALTER TABLE order_intent ADD COLUMN dutch_group_id UUID;
CREATE INDEX ix_order_intent_dutch_group ON order_intent (dutch_group_id) WHERE dutch_group_id IS NOT NULL;

-- Watch tables stored the type in VARCHAR(5); widen them (v_watch_bet depends on watch_bet.market_type).
DROP VIEW v_watch_bet;
ALTER TABLE watch_session_configuration ALTER COLUMN market_type TYPE VARCHAR(10);
ALTER TABLE watch_decision ALTER COLUMN market_type TYPE VARCHAR(10);
ALTER TABLE watch_bet ALTER COLUMN market_type TYPE VARCHAR(10);

-- 12. Reporting view: one row per simulated bet with its current settlement and race context. display_status is
--     the single definition of the status shown for a Watch bet (screens, filters and exports all read it here).
CREATE VIEW v_watch_bet AS
SELECT b.id                            AS bet_id,
       b.session_id,
       b.session_configuration_id,
       b.configuration_id,
       b.configuration_version,
       sc.configuration_name,
       sc.strategy_code,
       sc.staking_method,
       b.market_id,
       m.market_name,
       m.venue,
       m.market_start_time,
       b.selection_id,
       r.runner_name,
       b.side,
       b.market_type,
       b.persistence_type,
       b.requested_price,
       b.requested_size,
       b.limit_price,
       b.slippage_ticks,
       b.size_matched,
       b.average_price_matched,
       b.size_lapsed,
       b.size_voided,
       b.matching_status,
       b.result_status,
       CASE
           WHEN b.result_status = 'OPEN' AND b.matching_status = 'UNMATCHED' THEN 'PENDING'
           WHEN b.result_status = 'OPEN' AND b.matching_status = 'PARTIALLY_MATCHED' THEN 'PARTIALLY_MATCHED'
           WHEN b.result_status = 'OPEN' AND b.matching_status = 'VOIDED' THEN 'VOID'
           WHEN b.result_status = 'OPEN' AND b.size_matched > 0 THEN 'MATCHED'
           WHEN b.result_status = 'OPEN' THEN 'UNMATCHED'
           WHEN b.result_status = 'NOT_MATCHED' THEN 'UNMATCHED'
           ELSE b.result_status
       END                              AS display_status,
       b.lapse_reason,
       b.data_delay_ms,
       b.placed_at,
       b.matched_at,
       b.settled_at,
       s.settled_price,
       s.reduction_factor,
       s.dead_heat_divisor,
       s.gross_profit,
       s.commission,
       s.net_profit,
       s.best_case_gross_profit,
       s.best_case_net_profit,
       (d.snapshot -> 'marketState' ->> 'priceRank')::INTEGER AS price_rank,
       (d.snapshot ->> 'finalScore')::NUMERIC                AS final_score
FROM watch_bet b
JOIN watch_session_configuration sc ON sc.id = b.session_configuration_id
JOIN watch_decision d ON d.id = b.decision_id
LEFT JOIN market m ON m.market_id = b.market_id
LEFT JOIN market_runner r ON r.market_id = b.market_id AND r.selection_id = b.selection_id
LEFT JOIN watch_bet_settlement s ON s.bet_id = b.id AND s.is_current;

