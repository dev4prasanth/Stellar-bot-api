-- Stop All is no longer offered in the application: live trading is stopped per configuration from the Trading
-- screen (with bet review). Release a lock left engaged, which would otherwise block starting any configuration.
UPDATE global_control
SET stop_all = FALSE,
    stop_all_reason = NULL
WHERE id = 1 AND stop_all;
