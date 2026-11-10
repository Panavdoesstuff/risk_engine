# Architecture

*Filled in as milestones land. Current state: M1.*

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
