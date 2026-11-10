# Architecture

*Filled in as milestones land. Current state: M3.*

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
3. Every 100 ticks: rebuild every vector from positions, check the difference is under a paisa, continue from the rebuilt values.
4. Margin and level for each affected account, in account-id order.

A fill updates the position, cash and vector at once (same bookkeeping code as the reference engine) and maintains both indexes. Duplicate fill ids are ignored.

`stateHash()` is SHA-256 over the sorted state with doubles hashed bit-for-bit. Same log in, same hash out. `DeterminismRulesTest` keeps clocks, randomness, hash-ordered collections and platform-dependent maths out of the packages a shard executes.
