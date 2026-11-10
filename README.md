# Real-Time Margin & Risk Engine

A deterministic, sharded margin engine for Indian index derivatives (Nifty, Bank Nifty), replayed against the 4 June 2024 election-result crash.

**Status:** M3 (ordered log, incremental shards) done; M1 waiting only on real market data. Nifty only until the end of M5. Numbers so far: [docs/results.md](docs/results.md). See [docs/build-map.md](docs/build-map.md) for the plan and [docs/decisions.md](docs/decisions.md) for why things are the way they are.

## Quick start

```bash
docker compose up -d     # Postgres (+ migrations), Redpanda (+ topics), Redis
./gradlew build          # JDK 21+
./scripts/m1-gate.sh     # checks schema, topics, ledger/audit constraints, seed reproducibility
```

Market data setup: [data/README.md](data/README.md). How margin is computed, with every number worked by hand: [docs/margin-example-short-straddle.md](docs/margin-example-short-straddle.md).

## Layout

| Path | What |
| --- | --- |
| `engine/` | Plain Java 21, no framework. Black-Scholes + Greeks, SPAN-style 16-scenario margin, utilisation levels, `EventLog`, single-threaded reference engine, incremental shards with a state hash. |
| `replayer/` | Bhavcopy → instruments, minute-bar/VIX normaliser, seeded start-of-day book generator, bars → ticks (the only tick writer). |
| `loadtest/` | JMH benchmarks (`./gradlew :loadtest:run --args="TickBenchmark"`). |
| `db/migrations/` | Flyway SQL for the full data model. |
| `scripts/` | Topic setup, milestone gate checks. |
| `docs/` | Build map, decisions, architecture, results. |
