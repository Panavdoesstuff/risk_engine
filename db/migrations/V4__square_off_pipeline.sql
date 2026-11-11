-- M5: what the square-off pipeline writes. Same two tools as V3: unique keys for rows inserted once,
-- last_event_seq guards for rows that are updated.

-- ---------------------------------------------------------------- margin_events
-- A level can now also change on a fill, so two changes for one account can share a tick_seq.
-- The key that is unique (and the same on every replay) is the source offset of the causing event.
ALTER TABLE margin_events ADD COLUMN source_offset BIGINT;       -- NULL only for rows written before V4
ALTER TABLE margin_events DROP CONSTRAINT margin_events_account_id_tick_seq_key;
CREATE UNIQUE INDEX margin_events_account_source ON margin_events (account_id, source_offset);
CREATE INDEX margin_events_account_tick ON margin_events (account_id, tick_seq);

-- ---------------------------------------------------------------- accounts
-- Status and cash are changed by different outputs, and one event can produce both (a fill that ends a
-- square-off), so each has its own guard.
ALTER TABLE accounts ADD COLUMN status_event_seq BIGINT NOT NULL DEFAULT -1;

-- ---------------------------------------------------------------- orders
-- The margin numbers behind the pre-trade decision, for GET /orders/{id}.
ALTER TABLE orders ADD COLUMN required_after NUMERIC(18, 2);
ALTER TABLE orders ADD COLUMN available_after NUMERIC(18, 2);

-- ---------------------------------------------------------------- square-off
ALTER TABLE square_off_episodes ADD COLUMN mode TEXT NOT NULL DEFAULT 'AUTO' CHECK (mode IN ('AUTO', 'MANUAL'));
ALTER TABLE square_off_episodes ADD COLUMN actor TEXT NOT NULL DEFAULT 'engine';  -- or risk-ops:<user> for a manual one
ALTER TABLE square_off_episodes ADD COLUMN legs INTEGER NOT NULL DEFAULT 0;
ALTER TABLE square_off_legs ADD COLUMN mark NUMERIC(14, 4);
ALTER TABLE square_off_legs ADD COLUMN last_event_seq BIGINT NOT NULL DEFAULT -1;

-- ---------------------------------------------------------------- ledger
-- One row per (transaction, ledger account), so a fill published twice is booked once.
CREATE UNIQUE INDEX ledger_txn_account ON ledger_entries (txn_id, account);
