# Results

Fill in as you go. Every number names the commit and command that produced it.

| Metric | Value | Where it comes from |
| --- | --- | --- |
| Accounts | | Load test config |
| p99 tick-to-margin | | Grafana / M6 |
| Sustained ticks/s | | M6 |
| Incremental speed-up | **25.2×** vs naive, **3.1×** vs full recompute (details below) | JMH / M3 |
| Mismatches over 1M events | | M4 harness |
| Duplicate closes under chaos | | M5 |
| Recovery hash match | | M6 |
| Crash day: warned / called / squared off | | M7 report |

## M3: incremental vs full, per tick

`./gradlew :loadtest:run --args="TickBenchmark"` on branch `m3-incremental`. JMH 1.37, JDK 25.0.2, Apple M4 (10 cores, 16 GB), one fork, 4 warm-up + 6 measured iterations. One shard, 10,000 accounts × 5 positions over 166 Nifty instruments; every tick moves spot and VIX, so everything reprices.

| Method | What it does per tick | Time per tick | vs incremental |
| --- | --- | ---: | ---: |
| naive | every account reprices each of its positions in 16 scenarios | 35,294 ± 2,970 µs | 25.2× slower |
| full recompute | each instrument priced once, every account vector rebuilt from positions | 4,325 ± 29 µs | 3.1× slower |
| **incremental** | each instrument priced once, each holder's vector adjusted by qty × (new − old); includes the drift check every 100 ticks | **1,402 ± 12 µs** | 1 |

Read this honestly: most of the 25× is "price each instrument once", which the full recompute also does. The delta update itself is the 3.1×. See decisions.md #44.

At this rate one shard handles roughly 700 ticks per second for 10,000 accounts. M6 measures the real target (100k accounts, p99).

## M3: correctness gate

| Check | Result |
| --- | --- |
| Incremental = full on 10,000 random portfolios (60 ticks, 2,853 fills incl. duplicates, closes and flips) | every one of 4,086 level changes identical; every account's positions, cash, level and margin compared at 7 checkpoints |
| Largest incremental-vs-rebuild difference | 7.5e-9 rupees (tolerance: 0.01) |
| Same log twice → same state hash | identical on one shard; identical per shard with 4 shards on 4 threads |
| Hash sensitivity | one tick changed by one paisa → different hash |
