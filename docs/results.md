# Results

Fill in as you go. Every number names the commit and command that produced it.

| Metric | Value | Where it comes from |
| --- | --- | --- |
| Accounts | | Load test config |
| p99 tick-to-margin | | Grafana / M6 |
| Sustained ticks/s | | M6 |
| Incremental speed-up | **27.4×** vs naive, **3.1×** vs full recompute (details below) | JMH / M3 |
| Mismatches over 1M events | | M4 harness |
| Duplicate closes under chaos | | M5 |
| Recovery hash match | | M6 |
| Crash day: warned / called / squared off | | M7 report |

## M3: incremental vs full, per tick

`./gradlew :loadtest:run --args="TickBenchmark"` on branch `m3-rolling-rebuild` (re-run after the rolling rebuild replaced the every-100-ticks full rebuild). JMH 1.37, JDK 25.0.2, Apple M4 (10 cores, 16 GB), one fork, 4 warm-up + 6 measured iterations. One shard, 10,000 accounts × 5 positions over 166 Nifty instruments; every tick moves spot and VIX, so everything reprices.

| Method | What it does per tick | Time per tick | vs incremental |
| --- | --- | ---: | ---: |
| naive | every account reprices each of its positions in 16 scenarios | 36,998 ± 1,469 µs | 27.4× slower |
| full recompute | each instrument priced once, every account vector rebuilt from positions | 4,186 ± 62 µs | 3.1× slower |
| **incremental** | each instrument priced once, each holder's vector adjusted by qty × (new − old); includes the rolling rebuild and drift check (100 of the 10,000 accounts per tick) | **1,350 ± 34 µs** | 1 |

The average is the same as before the rolling rebuild (1,402 ± 12 µs), as expected: it does the same work, spread out. What changed is the worst tick. Before, every 100th tick rebuilt all 10,000 accounts on top of its normal work; now no tick rebuilds more than 100. An average can't show that; M6's p99 will.

Read this honestly: most of the 27× is "price each instrument once", which the full recompute also does. The delta update itself is the 3.1×. See decisions.md #44.

At this rate one shard handles roughly 700 ticks per second for 10,000 accounts. M6 measures the real target (100k accounts, p99).

## M4 part 1: shards on Redpanda, order API, security

`./gradlew build` on branch `m4-shards-orders-security`: 111 tests, 0 failures. The Redpanda and API tests run against real Redpanda and Redis containers.

| Check | Result |
| --- | --- |
| Shards on Redpanda vs shards on in-memory logs (2,000 accounts, 1,050 events, 4 partitions) | identical state hash on every shard; 703 `engine-out` lines identical and in the same order |
| Restarted host replays from offset 0 | same hashes again; every output published a second time, byte for byte |
| Pre-trade decisions, shard vs reference engine (10,000 portfolios, 88 orders: 77 accepted, 11 rejected) | every decision and reason identical |
| 401 | no token; wrong key; expired; wrong issuer; `alg: none`; edited claims; missing role, account, subject or expiry (11 forged tokens × 2 endpoints) |
| 403 | trader ordering on another account (and nothing reaches the log); risk-ops placing an order; unlisted paths |
| 429 | burst of 15 against a bucket of 10: first 10 pass, then refused with `Retry-After`; other users unaffected; bucket refills; requests with bad tokens use no one's bucket |
| Replay | same `X-Request-Id` again → 409 with the original order id, also with a different body; exactly one order in the log; ids are per user |
| Input validation | 21 malformed bodies → 400, including `\|` and line-break injection, strings and fractions for qty, unknown fields, attempts to set origin or order id; none reach the log |
| End to end | 202 at once with PENDING; shard decides; GET shows ACCEPTED, or REJECTED with INSUFFICIENT_MARGIN / BAD_LOT_SIZE / UNKNOWN_INSTRUMENT / UNKNOWN_ACCOUNT |

Not measured yet (part 2): mismatches over 1M interleaved events.

## M3: correctness gate

| Check | Result |
| --- | --- |
| Incremental = full on 10,000 random portfolios (60 ticks, 2,853 fills incl. duplicates, closes and flips) | every one of 4,086 level changes identical; every account's positions, cash, level and margin compared at 7 checkpoints |
| Largest incremental-vs-rebuild difference | 7.5e-9 rupees (tolerance: 0.01), same with the rolling rebuild |
| Rolling rebuild | every account rebuilt exactly once per cycle; never more than ⌈accounts ÷ K⌉ on one tick; changing K changes the hash |
| Same log twice → same state hash | identical on one shard; identical per shard with 4 shards on 4 threads |
| Hash sensitivity | one tick changed by one paisa → different hash |
