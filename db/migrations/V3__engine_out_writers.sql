-- What the engine-out writers need (M4 part 2).
--
-- engine-out is at-least-once: a shard that restarts replays its log and publishes everything again,
-- in order. Two tools keep the projection right anyway:
--
--   1. A guard for rows that are UPDATED (positions, account cash, order status). Every output carries
--      the offset, in the account's engine-events partition, of the event that caused it. That offset is
--      the same on every replay. A row remembers the offset that last wrote it (last_event_seq) and only
--      accepts a strictly newer one, so a replayed old output can never overwrite a newer state.
--   2. A unique key for rows that are INSERTED once (margin_events already has one; audit_log gets one).

-- positions.last_event_seq already exists. It was described as an engine-out offset; it holds the
-- engine-events (source) offset instead, for the reason above. Seeded rows have seen no event: -1.
ALTER TABLE positions ALTER COLUMN last_event_seq SET DEFAULT -1;
UPDATE positions SET last_event_seq = -1 WHERE last_event_seq = 0;

ALTER TABLE accounts ADD COLUMN last_event_seq BIGINT NOT NULL DEFAULT -1;  -- guards cash_balance
ALTER TABLE orders   ADD COLUMN last_event_seq BIGINT NOT NULL DEFAULT -1;  -- guards status; -1 = written by the API, not yet decided

-- One audit row per engine decision, however many times the decision is published.
-- e.g. DECISION:<orderId>, CLOSED:<orderId>, MARGIN:<accountId>:<tickSeq>. NULL for rows that are not
-- projections of engine-out (risk-ops actions, later).
-- ADD COLUMN does not fire the append-only trigger.
ALTER TABLE audit_log ADD COLUMN event_key TEXT;
CREATE UNIQUE INDEX audit_log_event_key ON audit_log (event_key) WHERE event_key IS NOT NULL;
