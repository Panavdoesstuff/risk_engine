# Real-Time Margin & Risk Engine

A deterministic, sharded margin engine for Indian index derivatives (Nifty, Bank Nifty), replayed against the 4 June 2024 election-result crash.

**Status:** M1 (Foundations) in progress. See [docs/build-map.md](docs/build-map.md) for the plan and [docs/decisions.md](docs/decisions.md) for why things are the way they are.

## Quick start

```bash
docker compose up -d     # Postgres (+ migrations), Redpanda (+ topics), Redis
./gradlew build          # JDK 21+
./scripts/m1-gate.sh     # checks schema, topics, ledger/audit constraints, seed reproducibility
```

Market data setup: [data/README.md](data/README.md).

## Layout

| Path | What |
| --- | --- |
| `engine/` | Plain Java 21, no framework. Reference data now; pricing, margin and shards from M2. |
| `replayer/` | Bhavcopy → instruments, minute-bar/VIX normaliser, seeded start-of-day book generator. |
| `db/migrations/` | Flyway SQL for the full data model. |
| `scripts/` | Topic setup, milestone gate checks. |
| `docs/` | Build map, decisions, architecture, results. |
