-- Core schema for the risk engine (Build Plan v2 data model).
-- Money is NUMERIC, never float. Quantities are signed contract units (lots * lot_size);
-- negative = short.
-- Postgres is a projection of engine-out, not the source of truth: the ordered log is.

-- ---------------------------------------------------------------- reference data
CREATE TABLE instruments (
    instrument_id   TEXT PRIMARY KEY,                -- e.g. NIFTY-20240606-22500-CE
    underlying      TEXT        NOT NULL,
    instrument_type TEXT        NOT NULL CHECK (instrument_type IN ('FUT', 'CE', 'PE')),
    expiry          DATE        NOT NULL,
    strike          NUMERIC(12, 2),
    lot_size        INTEGER     NOT NULL CHECK (lot_size > 0),
    trade_date      DATE        NOT NULL,            -- bhavcopy date the row was loaded from
    CHECK ((instrument_type = 'FUT') = (strike IS NULL))
);
CREATE INDEX instruments_underlying_expiry ON instruments (underlying, expiry);

-- ---------------------------------------------------------------- accounts + positions
CREATE TABLE accounts (
    account_id   TEXT PRIMARY KEY,
    archetype    TEXT        NOT NULL,              -- seeded book only; NULL-able later for real users
    status       TEXT        NOT NULL DEFAULT 'NORMAL'
                 CHECK (status IN ('NORMAL', 'WARNING', 'MARGIN_CALL', 'SQUARE_OFF', 'BLOCKED')),
    cash_balance NUMERIC(18, 2) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE positions (
    account_id    TEXT        NOT NULL REFERENCES accounts (account_id),
    instrument_id TEXT        NOT NULL REFERENCES instruments (instrument_id),
    qty           BIGINT      NOT NULL,
    avg_price     NUMERIC(14, 4) NOT NULL,
    last_event_seq BIGINT     NOT NULL DEFAULT 0,   -- engine-out offset that last wrote this row
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, instrument_id)
);

-- ---------------------------------------------------------------- orders + fills
CREATE TABLE orders (
    order_id        TEXT PRIMARY KEY,
    account_id      TEXT        NOT NULL REFERENCES accounts (account_id),
    instrument_id   TEXT        NOT NULL REFERENCES instruments (instrument_id),
    side            TEXT        NOT NULL CHECK (side IN ('BUY', 'SELL')),
    qty             BIGINT      NOT NULL CHECK (qty > 0),
    order_type      TEXT        NOT NULL CHECK (order_type IN ('MARKET', 'LIMIT')),
    limit_price     NUMERIC(14, 4),
    source          TEXT        NOT NULL CHECK (source IN ('CLIENT', 'SQUARE_OFF', 'RISK_OPS')),
    status          TEXT        NOT NULL
                    CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'SENT', 'PARTIALLY_FILLED',
                                      'FILLED', 'BROKER_REJECTED', 'CANCELLED')),
    reject_reason   TEXT,
    request_id      TEXT UNIQUE,                     -- client replay protection (API side)
    idempotency_key TEXT UNIQUE,                     -- square-off leg key: accountId:episodeId:instrumentId:seq
    created_by      TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((order_type = 'LIMIT') = (limit_price IS NOT NULL)),
    CHECK (source <> 'SQUARE_OFF' OR idempotency_key IS NOT NULL)
);
CREATE INDEX orders_account ON orders (account_id, created_at);

CREATE TABLE fills (
    fill_id        BIGSERIAL PRIMARY KEY,
    broker_fill_id TEXT        NOT NULL UNIQUE,     -- duplicate broker callbacks die here
    order_id       TEXT        NOT NULL REFERENCES orders (order_id),
    account_id     TEXT        NOT NULL REFERENCES accounts (account_id),
    instrument_id  TEXT        NOT NULL REFERENCES instruments (instrument_id),
    qty            BIGINT      NOT NULL,            -- signed: + bought, - sold
    price          NUMERIC(14, 4) NOT NULL,
    filled_at      TIMESTAMPTZ NOT NULL,            -- broker/log time, not wall clock
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX fills_order ON fills (order_id);

-- ---------------------------------------------------------------- margin + square-off
CREATE TABLE margin_events (
    id               BIGSERIAL PRIMARY KEY,
    account_id       TEXT        NOT NULL REFERENCES accounts (account_id),
    tick_seq         BIGINT      NOT NULL,
    log_time         TIMESTAMPTZ NOT NULL,
    required_margin  NUMERIC(18, 2) NOT NULL,
    available_margin NUMERIC(18, 2) NOT NULL,
    utilisation      NUMERIC(10, 4) NOT NULL,
    expected_utilisation NUMERIC(10, 4) NOT NULL,   -- after subtracting in-flight legs' release
    status_from      TEXT        NOT NULL,
    status_to        TEXT        NOT NULL,
    UNIQUE (account_id, tick_seq)                    -- re-emission after recovery is a no-op
);

CREATE TABLE square_off_episodes (
    episode_id       TEXT PRIMARY KEY,
    account_id       TEXT        NOT NULL REFERENCES accounts (account_id),
    started_tick_seq BIGINT      NOT NULL,
    ended_tick_seq   BIGINT,
    status           TEXT        NOT NULL CHECK (status IN ('OPEN', 'RESOLVED', 'EXHAUSTED')),
    started_at       TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ
);
CREATE UNIQUE INDEX one_open_episode_per_account
    ON square_off_episodes (account_id) WHERE status = 'OPEN';

CREATE TABLE square_off_legs (
    leg_key          TEXT PRIMARY KEY,               -- accountId:episodeId:instrumentId:seq
    episode_id       TEXT        NOT NULL REFERENCES square_off_episodes (episode_id),
    account_id       TEXT        NOT NULL REFERENCES accounts (account_id),
    instrument_id    TEXT        NOT NULL REFERENCES instruments (instrument_id),
    seq              INTEGER     NOT NULL,
    qty              BIGINT      NOT NULL,            -- signed closing qty
    expected_release NUMERIC(18, 2) NOT NULL,
    order_id         TEXT REFERENCES orders (order_id),
    status           TEXT        NOT NULL
                     CHECK (status IN ('PLANNED', 'SENT', 'FILLED', 'REJECTED', 'TIMED_OUT')),
    planned_tick_seq BIGINT      NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX square_off_legs_episode ON square_off_legs (episode_id);

-- ---------------------------------------------------------------- double-entry ledger
-- Every txn_id must sum to zero. Enforced at COMMIT by a deferred constraint trigger,
-- so a writer can insert legs one at a time inside a transaction.
CREATE TABLE ledger_entries (
    entry_id       BIGSERIAL PRIMARY KEY,
    txn_id         TEXT        NOT NULL,
    ledger_account TEXT        NOT NULL,             -- e.g. CASH, MARGIN_BLOCKED, REALISED_PNL, BROKER_CLEARING
    account_id     TEXT REFERENCES accounts (account_id),  -- NULL for house accounts
    amount         NUMERIC(18, 2) NOT NULL CHECK (amount <> 0),
    reason         TEXT        NOT NULL,             -- FILL, MARGIN_BLOCK, MARGIN_RELEASE, PREMIUM, ...
    ref_id         TEXT,                             -- broker_fill_id / leg_key / order_id
    log_time       TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ledger_txn ON ledger_entries (txn_id);
CREATE INDEX ledger_account_idx ON ledger_entries (account_id, ledger_account);

CREATE FUNCTION ledger_txn_balances() RETURNS trigger AS $$
DECLARE
    total NUMERIC;
BEGIN
    SELECT COALESCE(SUM(amount), 0) INTO total FROM ledger_entries WHERE txn_id = NEW.txn_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'ledger txn % does not balance (sum = %)', NEW.txn_id, total;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_txn_must_balance
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_txn_balances();

-- ---------------------------------------------------------------- transactional outbox
-- The writer inserts the order and its outbox row in one transaction; the relay sends
-- rows with sent_at IS NULL and stamps them. idempotency_key makes re-inserts no-ops.
CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_id    TEXT        NOT NULL,            -- order_id
    event_type      TEXT        NOT NULL,            -- e.g. BROKER_PLACE_ORDER
    idempotency_key TEXT        NOT NULL UNIQUE,
    payload         JSONB       NOT NULL,
    attempts        INTEGER     NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);
CREATE INDEX outbox_unsent ON outbox (id) WHERE sent_at IS NULL;

-- ---------------------------------------------------------------- audit log (append-only)
CREATE TABLE audit_log (
    id         BIGSERIAL PRIMARY KEY,
    actor      TEXT        NOT NULL,                 -- 'engine:shard-2', 'risk-ops:<user>', 'trader:<user>'
    action     TEXT        NOT NULL,                 -- STATUS_CHANGE, SQUARE_OFF_LEG, BLOCK, UNBLOCK, ...
    account_id TEXT REFERENCES accounts (account_id),
    tick_seq   BIGINT,
    log_time   TIMESTAMPTZ NOT NULL,
    details    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_account ON audit_log (account_id, log_time);

CREATE FUNCTION audit_log_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_append_only();

-- ---------------------------------------------------------------- engine snapshots
-- Snapshot every M seconds of log time. Recovery = load latest snapshot, replay from offset.
CREATE TABLE engine_snapshots (
    shard_id         INTEGER     NOT NULL,
    partition_offset BIGINT      NOT NULL,           -- next offset to consume after loading
    tick_seq         BIGINT      NOT NULL,
    log_time         TIMESTAMPTZ NOT NULL,
    state_hash       TEXT        NOT NULL,
    state            BYTEA       NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (shard_id, partition_offset)
);
