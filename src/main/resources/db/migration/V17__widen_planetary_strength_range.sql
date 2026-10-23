-- Layer 2 clamps its final strength score to 50-200 (rule-based 50-150 plus component adjustments), not 0-100.
-- The old 0-100 check rejected most real Layer 2 state changes.

ALTER TABLE race_signal_planetary_state DROP CONSTRAINT ck_race_signal_strength;

ALTER TABLE race_signal_planetary_state
    ADD CONSTRAINT ck_race_signal_strength CHECK (strength_score IS NULL OR strength_score BETWEEN 0 AND 200);
