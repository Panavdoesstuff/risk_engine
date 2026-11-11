# Real-Time Margin & Risk Engine

A deterministic, sharded margin engine for Indian index derivatives (Nifty, Bank Nifty), replayed against the 4 June 2024 election-result crash.

**Status:** M5 done, the MVP line: an idempotent square-off pipeline (expected-utilisation status machine, greedy one-leg-at-a-time planner, transactional outbox, a chaos broker, a double-entry ledger) on top of M4's sharded engine, order API and Postgres projection. Under full broker chaos: 0 legs closed twice, 0 surplus legs, every ledger transaction sums to zero. Next is M6 (scale and recovery). M1 is waiting only on real market data. Nifty only until the end of M5. Numbers so far: [docs/results.md](docs/results.md). See [docs/build-map.md](docs/build-map.md) for the plan and [docs/decisions.md](docs/decisions.md) for why things are the way they are.

## Quick start

```bash
docker compose up -d     # Postgres (+ migrations), Redpanda (+ topics), Redis
./gradlew build          # JDK 21+; the Redpanda and API tests start their own containers, so Docker must be running
./scripts/m1-gate.sh     # checks schema, topics, ledger/audit constraints, seed reproducibility
./scripts/m4-e2e.sh      # the whole engine in docker compose, synthetic data, chaos broker: ticks -> shards -> orders and square-offs filled -> Postgres
./gradlew :loadtest:stress   # the 1M-event concurrency stress test
```

Market data setup: [data/README.md](data/README.md). How margin is computed, with every number worked by hand: [docs/margin-example-short-straddle.md](docs/margin-example-short-straddle.md).

## How an order is checked without locks

`POST /orders` does not decide anything. It checks who you are and that the request is well formed, writes an `OrderRequest` to the partition that owns your account, and answers **202 Accepted** with an order id. One shard thread owns that partition. It applies the partition's events strictly in order: every market tick, your fills, your orders. When it reaches your order, your account's state is exactly "everything before this order in the log", and no other thread can touch it, so the margin check is a plain calculation. The shard publishes ACCEPTED or REJECTED (with a reason) to `engine-out`, and `GET /orders/{id}` shows it.

Accepted orders reserve their margin until they fill or are closed, so two orders sent back to back can't spend the same money.

Tokens are JWTs signed with HS256 and a shared secret (`JWT_SECRET`); the API only verifies them. **`docker-compose.yml` sets a fixed development secret when `JWT_SECRET` is unset. It is for a laptop only: anyone with the file can mint tokens for any account. Set your own secret anywhere shared.** If a separate login service is ever added, the upgrade is RS256: the login service signs with a private key and the API verifies with the public one, so the API can no longer mint tokens. Details and the exact rule: [docs/decisions.md](docs/decisions.md) #48–#54; request flow and how to run it: [docs/architecture.md](docs/architecture.md).

## How a square-off works

When an account stays at 120% utilisation or more for three ticks, its shard opens a square-off episode. It plans **one** closing leg (short options first, then futures; the fewest lots that bring it back under 100%), records it as in flight, and publishes it. The writers store the leg as an order plus an outbox row in one transaction; the relay sends it to the broker under its key `accountId:episodeId:instrumentId:seq`; the broker's fills come back through the fill ingester into the account's partition. Only when the leg has filled (or been rejected, which re-plans with `seq + 1`) does the shard plan the next one. Utilisation used for the decision is *expected* utilisation, with the leg in flight counted as done, so it never sends more while one is out. Every fill that moves cash is booked as two ledger rows that sum to zero. Risk-ops can block, unblock or square off an account through the API; that, too, is an event in the account's partition. Details: [docs/decisions.md](docs/decisions.md) #72–#86.

## Layout

| Path | What |
| --- | --- |
| `engine/` | Plain Java 21, no framework, no dependencies. Black-Scholes + Greeks, SPAN-style 16-scenario margin, utilisation levels, `EventLog`, single-threaded reference engine, incremental shards with a state hash, pre-trade margin check. |
| `host/` | Everything that talks to Redpanda: the `EventLog` on a partition, the `engine-out` publisher, and `ShardHost` (one shard thread per partition). |
| `api/` | Spring Boot order API. Validates, authenticates (JWT), rate-limits and de-duplicates (Redis), writes `OrderRequest` to the account's partition, answers 202. Never computes margin. |
| `writers/` | `engine-out` → Postgres in batches: positions, account status, orders + outbox (one transaction), square-off episodes and legs, fills, double-entry ledger, audit log. Safe to replay. Also the outbox relay (to the broker) and the fill ingester (broker callbacks → the log). |
| `broker-mock/` | A broker that times out, rejects, fills late and in parts, and calls back twice. Idempotent on the order key. |
| `replayer/` | Bhavcopy → instruments, minute-bar/VIX normaliser, seeded start-of-day book generator, bars → ticks (the only tick writer; to files or to Redpanda), synthetic demo inputs. |
| `loadtest/` | JMH benchmarks (`./gradlew :loadtest:run --args="TickBenchmark"`) and the concurrency stress harness (`./gradlew :loadtest:stress`). |
| `db/migrations/` | Flyway SQL for the full data model. |
| `scripts/` | Topic setup, milestone gate checks. |
| `docs/` | Build map, decisions, architecture, results. |
