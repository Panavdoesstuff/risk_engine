# Real-Time Margin & Risk Engine

A deterministic, sharded margin engine for Indian index derivatives (Nifty, Bank Nifty), replayed against the 4 June 2024 election-result crash.

**Status:** M4 part 1 done on branch `m4-shards-orders-security` (shards on Redpanda, asynchronous order API with JWT, rate limit and replay protection); part 2 (Postgres writers, 1M-event stress test) is next. M1 is waiting only on real market data. Nifty only until the end of M5. Numbers so far: [docs/results.md](docs/results.md). See [docs/build-map.md](docs/build-map.md) for the plan and [docs/decisions.md](docs/decisions.md) for why things are the way they are.

## Quick start

```bash
docker compose up -d     # Postgres (+ migrations), Redpanda (+ topics), Redis
./gradlew build          # JDK 21+; the Redpanda and API tests start their own containers, so Docker must be running
./scripts/m1-gate.sh     # checks schema, topics, ledger/audit constraints, seed reproducibility
```

Market data setup: [data/README.md](data/README.md). How margin is computed, with every number worked by hand: [docs/margin-example-short-straddle.md](docs/margin-example-short-straddle.md).

## How an order is checked without locks

`POST /orders` does not decide anything. It checks who you are and that the request is well formed, writes an `OrderRequest` to the partition that owns your account, and answers **202 Accepted** with an order id. One shard thread owns that partition. It applies the partition's events strictly in order: every market tick, your fills, your orders. When it reaches your order, your account's state is exactly "everything before this order in the log", and no other thread can touch it, so the margin check is a plain calculation. The shard publishes ACCEPTED or REJECTED (with a reason) to `engine-out`, and `GET /orders/{id}` shows it.

Accepted orders reserve their margin until they fill, so two orders sent back to back can't spend the same money. Details and the exact rule: [docs/decisions.md](docs/decisions.md) #48–#54; request flow and how to run it: [docs/architecture.md](docs/architecture.md).

## Layout

| Path | What |
| --- | --- |
| `engine/` | Plain Java 21, no framework, no dependencies. Black-Scholes + Greeks, SPAN-style 16-scenario margin, utilisation levels, `EventLog`, single-threaded reference engine, incremental shards with a state hash, pre-trade margin check. |
| `host/` | Everything that talks to Redpanda: the `EventLog` on a partition, the `engine-out` publisher, and `ShardHost` (one shard thread per partition). |
| `api/` | Spring Boot order API. Validates, authenticates (JWT), rate-limits and de-duplicates (Redis), writes `OrderRequest` to the account's partition, answers 202. Never computes margin. |
| `replayer/` | Bhavcopy → instruments, minute-bar/VIX normaliser, seeded start-of-day book generator, bars → ticks (the only tick writer). |
| `loadtest/` | JMH benchmarks (`./gradlew :loadtest:run --args="TickBenchmark"`). |
| `db/migrations/` | Flyway SQL for the full data model. |
| `scripts/` | Topic setup, milestone gate checks. |
| `docs/` | Build map, decisions, architecture, results. |
