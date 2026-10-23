-- Layer 2's RaceCardContext also carries the winning runner of the last processed race; keep it with the rest of
-- the card context so the UI can show it. Null until a Layer 2 message with a context arrives.

ALTER TABLE race_signal_card ADD COLUMN last_race_winner INTEGER;
