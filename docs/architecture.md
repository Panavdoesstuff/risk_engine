# Architecture

*Filled in as milestones land. Current state: M6 part 1 (scale and metrics). Snapshots and recovery are M6 part 2.*

```
replayer ──ticks (every partition, global tick_seq)──▶ engine-events[0..N-1] ──▶ ShardRunner × N
api ──OrderRequest (partition = hash(accountId) % N)──▶      ▲                         │
broker-mock ──Fill callbacks (account's partition)───────────┘                         ▼
                                                                                  engine-out
                                                                                      │
                                                    writers (batched) ──▶ Postgres ◀──┘
                                                    outbox relay ──▶ broker-mock
```

- **One ordered log per shard.** Each shard thread reads one partition and applies events strictly in order. No locks; a pre-trade check is just another event in the account's partition.
- **Postgres is a projection** of `engine-out`. The log plus snapshots is the source of truth.
- **Infra:** `docker-compose.yml` — Postgres 16 (Flyway migrations in `db/migrations`), Redpanda (Kafka API), Redis (rate limits, replay protection).

## Offline data pipeline (M1)

```
NSE F&O bhavcopy (3 Jun 2024) + fo_mktlots.csv ──replayer instruments──▶ instruments-2024-06-03.csv
instruments-2024-06-03.csv + seed ──replayer seed──▶ accounts.csv, positions.csv, book.meta
Nifty / Bank Nifty minute bars, India VIX ──replayer bars──▶ normalised ts,open,high,low,close (+ .meta)
```

## Margin model (M2)

```
Tick (spot, VIX) ──▶ MarketState ──▶ RiskArray per instrument (price + 16 scenario losses per unit)
                                           │  × qty, summed
                                           ▼
                                     AccountVector (16 losses, net option value, short/exposure notional)
                                           │
                                           ▼
                     MarginCalculator: required = max(0, max(SOMC, worst loss) − NOV) + exposure
                                       utilisation = required / (cash + collateral + futures MTM)
                                           │
                                           ▼
                     MarginStatusTracker: OK / WARNING / MARGIN_CALL / SQUARE_OFF (3-tick confirmation)
```

`ReferenceMarginEngine` drives this from an `EventLog`, recomputing every account on every tick. It is the correctness reference for M3's incremental shards.

## Shards (M3)

```
replayer ──Tick (same tick_seq)──▶ partition 0 ──▶ ShardRunner 0 ──▶ ShardState 0 ──▶ margin events
                                   partition 1 ──▶ ShardRunner 1 ──▶ ShardState 1
fills ──murmur2(accountId) % N──▶  ...             (one thread each, no locks, no shared pricer)
```

Inside a shard, on each tick:

1. `byUnderlying[underlying]` lists the instruments held here. Each is repriced once.
2. `holders[instrument]` lists who holds it. Each holder's vector gets `qty × (new array − old array)`.
3. Rolling rebuild: the accounts where `index % 100 == tick_seq % 100` are rebuilt from positions, checked to be within a paisa of their incremental values, and continue from the rebuilt values. Every account gets its turn once per 100 ticks; no tick rebuilds the whole shard.
4. Margin and level for each affected account, in account-id order.

A fill updates the position, cash and vector at once (same bookkeeping code as the reference engine) and maintains both indexes. Duplicate fill ids are ignored.

`stateHash()` is SHA-256 over the sorted state with doubles hashed bit-for-bit. Same log in, same hash out. `DeterminismRulesTest` keeps clocks, randomness, hash-ordered collections and platform-dependent maths out of the packages a shard executes.

## Shards on Redpanda, the order API and the Postgres projection (M4)

```
client ──POST /orders (JWT, X-Request-Id)──▶ api ──OrderRequest──▶ engine-events[hash(account) % N]
   ▲                                          │                           ▲            │
   │                          Redis: rate limit, request ids     replayer: ticks       ▼
   │                          Postgres: PENDING row              (every partition)  ShardRunner p (host)
   │                                                                                 pre-trade check,
   └──GET /orders/{id}── Postgres ◀── writers (batched) ◀── engine-out[p] ◀───────── margin, positions
```

**A request, in order.** Each step can refuse; nothing later runs if it does.

| Step | Where | Refusal |
| --- | --- | --- |
| Token signed by us (HS256), right issuer, not expired, has role (+ account for a trader) | Spring Security | 401 |
| User within their token bucket | `RateLimitFilter` → Redis Lua script | 429 (503 if Redis is down) |
| Role may use the endpoint | Spring Security | 403 |
| Body and `X-Request-Id` well formed | controller | 400 |
| Trader owns the account | controller | 403 |
| Request id not used before by this user | `ReplayGuard` → Redis `SET NX EX` | 409 |
| Account and instrument exist (PENDING row inserted) | `OrderRepository` → Postgres foreign keys | 422 |
| Written to the account's partition, acknowledged | `RedpandaLogs` | 503 |
| | | **202** + order id |

**Then the shard decides.** The order is one more event in the account's partition. When the shard thread reaches it, the account's state is exactly "everything before this order in the log", so the margin check needs no lock. It prices the trade at the mark from the latest tick, counts earlier accepted-but-unfilled orders as filled (reservations), and publishes `ACCEPTED` or `REJECTED` with a reason. A fill releases the part of the reservation it filled; an `OrderClosed` event releases the rest.

**What a shard publishes** (`engine-out`, shard p → partition p, one text line each, plus a `source-offset` header):

| Line | When | Written to Postgres as |
| --- | --- | --- |
| `MARGIN` | an account's level changes on a tick | `margin_events`, `audit_log` |
| `DECISION` | an order was checked | `orders.status` ACCEPTED / REJECTED, `audit_log` |
| `POSITION` | a fill changed a position | `positions`, `accounts.cash_balance` |
| `RELEASED` | an order was closed | `orders.status` CANCELLED / BROKER_REJECTED, `audit_log` |

**`writers`.** Poll a batch → one transaction → commit Postgres → commit Kafka offsets. `engine-out` is at-least-once (a restarted shard replays from offset 0 and publishes everything again), so inserts have unique keys and updates only apply if their source offset is newer than the row's `last_event_seq`. Replaying the whole topic changes nothing.

**`host`.** `ShardHost` builds N `ShardState`s (each with the accounts that hash to it), gives each a `RedpandaEventLog` for its partition and starts N `ShardRunner` threads. If a shard cannot apply an event it stops and the host exits; it never skips. Per-shard lag (partition end − applied offset) is served at `:9100/metrics`; `/healthz` is 503 once a shard has stopped.

**Run it.**

```bash
./scripts/m4-e2e.sh        # everything below, with synthetic data, in a throwaway compose project; checks each step
```

By hand, with a book under `./data` (see data/README.md):

```bash
docker compose --profile app up -d --build      # infrastructure + book loader, shard host, writers, API
R=replayer/build/install/replayer/bin/replayer  # ./gradlew :replayer:installDist
$R ticks --bars data/processed/NIFTY-1m.csv --vix data/processed/INDIAVIX.csv \
         --from 2024-06-04 --to 2024-06-04 --redpanda localhost:19092 --pace-ms 200
export JWT_SECRET=local-dev-only-jwt-secret-do-not-deploy-0123456789   # the compose default
TOKEN=$(./gradlew -q :api:devToken --args="trader alice A0000001")
curl -i -X POST localhost:8080/orders -H "Authorization: Bearer $TOKEN" -H "X-Request-Id: $(uuidgen)" \
     -H "Content-Type: application/json" \
     -d '{"accountId":"A0000001","instrumentId":"NIFTY-20240627-FUT","side":"BUY","qty":25}'
curl -s localhost:9100/metrics | grep lag
```

Orders are rejected with `NO_MARKET` until at least one tick is in the log.

## The stress harness (M4)

```
8 writer threads ──orders, fills, closes, duplicates (80% on 20 hot accounts)──▶ 4 partitions ──▶ 4 live shards ──▶ outputs
1 tick thread ────every tick to every partition──────────────────────────────▶      │
                                                                                     ▼ read back afterwards
                                     single-threaded reference engine ◀── tick, then each partition's events up to the next tick
```

Compared: every output in order, every account's final state, and each live shard's hash against a single-threaded replay. `./gradlew :loadtest:stress` (add `--args="--redpanda host:port"` to run it through a broker).

## Square-off (M5)

```
                         engine-events[p] (the account's partition)
  ticks ──────────────────────┐  ▲  ▲  ▲
  API: orders, AccountControl ─┘  │  │  └──────────── fill ingester ◀── broker callbacks (FILL, REJECT)
                                  │  └─ relay: OrderClosed(REJECTED) if the broker refuses an order   ▲
                                  ▼                                                                 │
                       ShardRunner p ── AccountRisk per account                                      │
                         level on expected utilisation; episode; ONE leg in flight                  │
                                  │                                                                 │
                                  ▼  LEG / EPISODE / STATUS / POSITION / DECISION / RELEASED          │
                              engine-out[p] ──▶ writers ──▶ Postgres: orders + outbox (one txn),    │
                                                            legs, episodes, fills, ledger, audit    │
                                                                      │                             │
                                                                      ▼                             │
                                                       relay: unsent outbox rows ──POST /orders──▶ mock broker
                                                       timeout? GET /orders/{key} before resending
```

**An episode, step by step.**

1. A tick takes the account's expected utilisation to 120% or more for the third tick running. `AccountRisk` opens episode E*n*, cancels the account's open user orders, and refuses new ones.
2. `LegPlanner` picks the leg: the short option (else future, else long option) whose closing frees the most margin per lot; in AUTO the fewest lots that bring utilisation under 100%. The leg is recorded as in flight, then emitted as `LEG|v1|A:E1:INSTR:1|…`.
3. The writers insert `orders` (origin SQUARE_OFF, id = the key) and `outbox` in one transaction. The relay posts it to the broker. On a timeout it asks `GET /orders/{key}` before sending again; the broker executes a key once however often it is posted.
4. The broker's fills come back in parts, sometimes twice. The ingester checks each matches an order we placed, for that account and instrument, and writes it to the account's partition. The shard drops fill ids it has seen.
5. When the leg's fills add up to the leg, the shard looks again: back under 100% → RESOLVED; otherwise the next leg (seq 2). A broker reject (`OrderClosed`) drops the leg and plans again with seq + 1. Nothing useful left to close → EXHAUSTED, and the account is blocked for risk-ops.

**Status, as written to `accounts.status`:** BLOCKED (risk-ops, or an exhausted square-off) › SQUARING_OFF (episode open) › MARGIN_CALL (level ≥ MARGIN_CALL) › ACTIVE.

## Metrics and the scale run (M6 part 1)

```
ShardRunner p ── after each event ──▶ observer (host) ── clock read here, never in the shard
                                          │  tick? latency = now − when the tick was sent
                                          ▼
                     TickMetrics: histogram + counters ──▶ shard host :9100/metrics ◀── Prometheus (2 s) ◀── Grafana "Risk engine"
```

`./gradlew :loadtest:scale` runs the same `ShardHost` in-process against a broker: 100k accounts, ticks paced at 10, 50 and 100 per second with 1,000 orders/s alongside, then flat out. Per tick per shard it records the exact time from send to applied, and reports exact percentiles. In compose, `replayer synth --rate 50 --seconds 60 --redpanda localhost:19092` drives the stack and the dashboard shows the same histogram.
