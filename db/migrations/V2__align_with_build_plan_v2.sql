-- Align the V1 schema (written before Build Plan v2 was available) with the v2 data model.
-- Renames columns whose names differ, adds missing columns, remaps enum values.
-- Columns and tables that exist only in V1 are kept; see docs/decisions.md (M2, entry 12).
-- Postgres keeps foreign keys, indexes and triggers attached through a column rename.

-- ---------------------------------------------------------------- accounts
ALTER TABLE accounts RENAME COLUMN account_id TO id;
ALTER TABLE accounts ADD COLUMN collateral_value NUMERIC(18, 2) NOT NULL DEFAULT 0
    CHECK (collateral_value >= 0);                 -- pledged securities, after haircut
ALTER TABLE accounts ALTER COLUMN archetype DROP NOT NULL;  -- seeded book only

ALTER TABLE accounts DROP CONSTRAINT accounts_status_check;
ALTER TABLE accounts ALTER COLUMN status DROP DEFAULT;
UPDATE accounts SET status = CASE status
    WHEN 'NORMAL'     THEN 'ACTIVE'
    WHEN 'WARNING'    THEN 'ACTIVE'               -- a warning is a margin_events action, not a status
    WHEN 'SQUARE_OFF' THEN 'SQUARING_OFF'
    ELSE status END;
ALTER TABLE accounts ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE accounts ADD CONSTRAINT accounts_status_check
    CHECK (status IN ('ACTIVE', 'MARGIN_CALL', 'SQUARING_OFF', 'BLOCKED'));

-- ---------------------------------------------------------------- instruments
ALTER TABLE instruments RENAME COLUMN instrument_id TO id;
ALTER TABLE instruments RENAME COLUMN instrument_type TO type;

-- ---------------------------------------------------------------- positions
-- (account_id, instrument_id) is already the primary key, which gives the v2 uniqueness.
ALTER TABLE positions RENAME COLUMN qty TO net_qty;

-- ---------------------------------------------------------------- orders
ALTER TABLE orders RENAME COLUMN order_id TO id;
ALTER TABLE orders RENAME COLUMN source TO origin;
ALTER TABLE orders DROP CONSTRAINT orders_source_check;
ALTER TABLE orders DROP CONSTRAINT orders_check1;  -- old "SQUARE_OFF needs a key" rule, re-added below
UPDATE orders SET origin = CASE origin
    WHEN 'CLIENT'   THEN 'USER'
    WHEN 'RISK_OPS' THEN 'SQUARE_OFF'             -- manual square-off; the actor is in audit_log
    ELSE origin END;
ALTER TABLE orders ADD CONSTRAINT orders_origin_check CHECK (origin IN ('USER', 'SQUARE_OFF'));
ALTER TABLE orders ADD CONSTRAINT orders_square_off_has_key
    CHECK (origin <> 'SQUARE_OFF' OR idempotency_key IS NOT NULL);

-- ---------------------------------------------------------------- outbox
ALTER TABLE outbox RENAME COLUMN aggregate_id TO order_id;
ALTER TABLE outbox ADD CONSTRAINT outbox_order_fk FOREIGN KEY (order_id) REFERENCES orders (id);

-- ---------------------------------------------------------------- fills
ALTER TABLE fills RENAME COLUMN fill_id TO id;

-- ---------------------------------------------------------------- ledger_entries
-- v2 has a single "account" column. V1's ledger_account (CASH, MARGIN_BLOCKED, ...) is that
-- column; account_id stays as the owning client account (NULL for house accounts).
ALTER TABLE ledger_entries RENAME COLUMN entry_id TO id;
ALTER TABLE ledger_entries RENAME COLUMN ledger_account TO account;

-- ---------------------------------------------------------------- margin_events
ALTER TABLE margin_events RENAME COLUMN required_margin TO required;
ALTER TABLE margin_events RENAME COLUMN available_margin TO available;
ALTER TABLE margin_events ADD COLUMN action TEXT NOT NULL DEFAULT 'WARN'
    CHECK (action IN ('WARN', 'MARGIN_CALL', 'SQUARE_OFF', 'CLEAR'));
ALTER TABLE margin_events ALTER COLUMN action DROP DEFAULT;

-- ---------------------------------------------------------------- audit_log
-- ADD COLUMN does not fire the append-only UPDATE trigger, so existing rows are untouched.
ALTER TABLE audit_log ADD COLUMN reason TEXT;
ALTER TABLE audit_log ADD COLUMN request_id TEXT;
CREATE INDEX audit_log_request ON audit_log (request_id) WHERE request_id IS NOT NULL;

-- ---------------------------------------------------------------- engine_snapshots
ALTER TABLE engine_snapshots RENAME COLUMN state TO state_blob;
