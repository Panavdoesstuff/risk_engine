# Architecture

*Filled in as milestones land. Current state: M4 part 1 (shards on Redpanda, order API, security). Postgres writers, broker and square-off are not built yet.*

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

## Shards on Redpanda and the order API (M4 part 1)

```
client ──POST /orders (JWT, X-Request-Id)──▶ api ──OrderRequest──▶ engine-events[hash(account) % N]
   ▲                                          │                           │
   │                                          │ Redis: rate limit,        ▼
   └──GET /orders/{id}── order status (Redis) │ request ids,        ShardRunner p (host)
                              ▲               │ order status          pre-trade check
                              │                                           │
                        engine-out listener ◀── engine-out[p] ◀── DECISION / MARGIN lines
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
| Written to the account's partition, acknowledged | `RedpandaLogs` | 503 |
| | | **202** + order id |

**Then the shard decides.** The order is one more event in the account's partition. When the shard thread reaches it, the account's state is exactly "everything before this order in the log", so the margin check needs no lock. It prices the trade at the mark from the latest tick, counts earlier accepted-but-unfilled orders as filled (reservations), and publishes `ACCEPTED` or `REJECTED` with a reason. The API's listener copies the decision into the order's status.

**`host`.** `ShardHost` builds N `ShardState`s (each with the accounts that hash to it), gives each a `RedpandaEventLog` for its partition and starts N `ShardRunner` threads. Outputs go to the same-numbered partition of `engine-out`. If a shard cannot apply an event it stops and the host exits; it never skips. On start it replays from offset 0 (snapshots are M6), which reproduces the same state hash and republishes the same lines.

**Run it locally.**

```bash
docker compose up -d
export JWT_SECRET=<at least 32 characters>
./gradlew :host:run --args="--instruments data/processed/instruments-2024-06-03.csv --book data/seed"
./gradlew :api:bootRun
TOKEN=$(./gradlew -q :api:devToken --args="trader alice A0000001")
curl -i -X POST localhost:8080/orders -H "Authorization: Bearer $TOKEN" -H "X-Request-Id: $(uuidgen)" \
     -H "Content-Type: application/json" \
     -d '{"accountId":"A0000001","instrumentId":"NIFTY-20240627-FUT","side":"BUY","qty":25}'
```

Orders are rejected with `NO_MARKET` until at least one tick is in the log. Writing ticks to Redpanda from the replayer is not wired up yet (it writes files); the tests write them directly.
