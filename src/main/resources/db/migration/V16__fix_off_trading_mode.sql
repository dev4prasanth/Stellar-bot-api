-- Fix stale 'OFF' trading_mode values that do not correspond to any TradingMode enum constant.
-- 'OFF' was used by an older version of the application; the valid values are MANUAL, AUTOMATIC, WATCH.
-- Replace 'OFF' with 'MANUAL' (the system default) so Hibernate can deserialise the entity.

UPDATE global_control
SET    trading_mode = 'MANUAL'
WHERE  trading_mode = 'OFF';

UPDATE order_intent
SET    trading_mode = 'MANUAL'
WHERE  trading_mode = 'OFF';
