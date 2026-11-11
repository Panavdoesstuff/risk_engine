# Results

Fill in as you go. Every number names the commit and command that produced it.

| Metric | Value | Where it comes from |
| --- | --- | --- |
| Accounts | **100,000** × 5 positions, 4 shards | M6 scale run |
| p99 tick-to-margin | **under 50 ms at 50 ticks/s** (15.7 and 35.8 ms over 2-minute runs, snapshots on). **The gate is accepted at 50 ticks/s on this laptop**; 100 ticks/s does not hold reliably here | M6 scale runs |
| Sustained ticks/s | **50** with p99 under 50 ms; flat-out bursts reach 105–127 for a few seconds | M6 scale runs |
| Incremental speed-up | **27.4×** vs naive, **3.1×** vs full recompute (details below) | JMH / M3 |
| Mismatches over 1M events | **0** (in-memory logs, and again through Redpanda; details below) | M4 harness |
| Duplicate closes under chaos | **0** legs closed twice, 0 surplus legs, 0 unbalanced ledger transactions (details below) | M5 chaos gate |
| Recovery hash match | **4 of 4 shards identical** after 6 kills -9 under chaos; 0 duplicate broker orders | M6 kill test |
| Crash day, 4 June 2024, 100k accounts: warned / margin-called / squared off | **23,662 / 13,350 / 8,710** (9,077 legs); first square-off 09:24, last ended 12:28; 0 accounts below zero equity | M7 crash-day report |

## M7: the crash day

`./gradlew :report:crashDay` on branch `m7`: 4 June 2024 replayed minute by minute (375 ticks, real NSE and Upstox data, minute VIX) against 100,000 seeded accounts opened at the 3 June close, with the margin parameters checked against NSE Clearing's rules (decision 115). The production shard code, 4 shards in one process; every square-off leg filled at the engine's mark before the next tick (decision 117). Full write-up, chart, five worst accounts and the minute-by-minute curve: [crash-day-2024-06-04.md](crash-day-2024-06-04.md).

| | |
| --- | --- |
| Warned (80%) / margin-called (100%, 3 ticks) / squared off (120%, 3 ticks) | 23,662 / 13,350 / 8,710 accounts |
| Square-off episodes / legs / lots | 9,060 / 9,077 / 19,377; all resolved, 0 exhausted |
| Who was squared off | every thinly funded short straddle and strangle (7,493), 1,217 futures accounts; no option buyer or hedged spread |
| First square-off started / last episode ended (log time) | 09:24 / 12:28 IST: **3 h 04 min** |
| Busiest minute | 10:58 IST: 782 square-offs |
| Accounts below zero equity | 0 at every minute |
| Shard compute per tick, slowest shard (no broker hop) | median 4.7 ms, p99 17 ms; busiest minute 5.4 ms + 0.65 ms for its fills |
| Two runs | identical state hashes on all 4 shards, identical counts minute by minute |

These numbers describe this engine on a synthetic book on that day, not Indian traders on that day. The limits are listed in the report.

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

## M6 part 2: recovery

**Scale gate, as accepted:** p99 tick-to-margin under 50 ms at 100,000 accounts is held at **50 ticks/s** on the development laptop (a fanless MacBook Air M4), with snapshots on. At 100 ticks/s it is hit or miss here and is not claimed.

### Kill -9 under a chaos square-off (`ShardKillTest`, runs on every build)

400 accounts, 80 ticks, each shard its own process, a snapshot every 5 minutes of log time; the real writers, relay and fill ingester, and the mock broker at full chaos. Six SIGKILLs at random moments to random shards, each followed by a restart.

| | Result |
| --- | --- |
| Kills -9 / restarts from a snapshot / from offset 0 | 6 / 6 / 0 (each snapshot loaded in 17–19 ms) |
| **State hash after recovery vs an uninterrupted replay of the same log** | **4 of 4 shards identical** |
| Broker: distinct keys / executed / sent again after it had them | 890 / 890 / 0: **0 duplicate broker orders** |
| Legs closed twice / ledger transactions not summing to zero / episodes left open | 0 / 0 / 0 |
| Outputs published again after restarts | 125 of 3,953, all absorbed by the writers |

### Recovery time and snapshot size at 100k accounts (`./gradlew :loadtest:recovery`)

100,000 accounts × 5 positions, 4 shards, 2,500 ticks at 100/s with 1,000 orders/s, a snapshot every 10 s of log time (every 1,000 ticks). The host is stopped, then started again: once with its snapshots, once without. Three runs; median shown, range in brackets. Every run reached exactly the state hashes of the run that wrote the snapshots.

| | Load (read, checksum, restore, verify) | Replay to the end | Events replayed | **Total** |
| --- | ---: | ---: | ---: | ---: |
| From snapshots | 0.74 s [0.70–0.75] | 5.3 s [4.9–9.9] | about 8,000 (≈2,000 per shard) | **6.0 s** [5.7–10.6] |
| From offset 0 | 0.14 s | 19.5 s [16.9–19.7] | 36,000 | 19.7 s [17.0–19.9] |

- **Snapshot size:** 11.7–11.9 MB per shard, **47 MB for 100k accounts (about 470 bytes per account)**. It grows with the seen-order and seen-fill sets, which are never pruned: 12.3–13.2 MB per shard after 2-minute runs with 1,000 orders/s.
- **Replay dominates,** and it is bound by ticks (about 7 ms each per shard at 25,000 accounts). The worst case is replaying M seconds of log: about 0.7 × M seconds at 100 ticks/s, plus about 0.7 s to load. With M = 60 s, up to about 40 s.
- **Cost on the shard thread:** a capture takes 18–37 ms at 25,000 accounts; serialising, checksumming and writing happen on a background thread. At 100 ticks/s that pause still costs p99 when snapshots are frequent (30 s: 84.8 ms; 10 s: 111 ms). At 50 ticks/s with one a minute, p99 stays under the target (below).

### Correction: sustained p99 at 100k accounts

Part 1's 100 ticks/s figure (p99 19.7 ms) came from 60-second windows. In two-minute runs on this fanless laptop, cooled before each run, it is not reliable (decision 105):

| 2 minutes at 100k accounts | p99 by run | Shard CPU per tick |
| --- | --- | --- |
| 100 ticks/s, snapshots off | 7,949 ms; 495 ms | 8.7; 7.3 ms |
| 100 ticks/s, a snapshot every 60 s | 31.7 ms; 690 ms | 7.2; 8.2 ms |
| 100 ticks/s, a snapshot every 30 s | 84.8 ms | 7.6 ms |
| **50 ticks/s, a snapshot every 60 s** | **35.8 ms; 15.7 ms** | 8.2; 7.6 ms |

### k6: POST /orders (the API only)

`scripts/k6-orders.sh`: k6 0.54 against the compose stack on the same laptop, 60 s, constant arrival rate of 1,000 requests/s, each user with their own HS256 token. These are API numbers (JWT check, Redis rate limit and replay guard, PENDING row in Postgres, acknowledged write to Redpanda); they say nothing about the shards.

| | Requests/s | p50 | p90 | p99 | 429s |
| --- | ---: | ---: | ---: | ---: | ---: |
| 100 users, about 10/s each (at the limit) | 968 | 3.6 ms | 131 ms | 893 ms | 0% |
| 20 users, 50/s each (5× the limit) | 994 | 0.32 ms (all) / 2.6 ms (202s) | 2.7 ms / 4.8 ms | 18 ms / 723 ms | 79.4% |

With users at the limit, k6 dropped 1,935 requests because all 600 of its virtual users were waiting on slow responses. Accepted orders have a heavy tail (p99 0.7–0.9 s).

**Profiled in M7 (decision 119):** the tail is the PENDING row's commit waiting for Postgres to flush its write-ahead log to the Docker VM's disk, not Redpanda. Per 10-second window under this load, the insert's p99 was 92–435 ms, while Redis stayed under 1 ms and the Redpanda write at 1–2 ms. With asynchronous commit for the API's connections, the endpoint's p99 fell from 441 ms to 103 ms (p90 55 → 2.2 ms). That trades durability, so it is not applied; the options are in decision 119.

### Tests

`./gradlew build`: 147 tests, 0 failures. `scripts/m4-e2e.sh`: 10 of 10 on the final code, now including kill -9 of the shard host and recovery from snapshots in compose.

## M6 part 1: tick-to-margin at 100k accounts

`./gradlew :loadtest:scale --args="--bootstrap localhost:29092"` on branch `m6-scale`. Apple M4 laptop (4 performance + 6 efficiency cores, 16 GB), JDK 25.0.2, `-Xmx6g`, G1. One Redpanda v24.2.7 container with the same flags as compose (`--smp=1 --memory=1G`). The shard host is the same class compose runs, in-process so each tick can be timed exactly. 100,000 accounts × 5 positions over 166 Nifty instruments, 4 shards (25,000 accounts each). Every tick moves spot and VIX, so every held instrument reprices and every account is recomputed. 1,000 user orders/s go to random accounts alongside, each through the full pre-trade check. Each phase is 60 s, after a 10 s warm-up that isn't counted. System load average at the start: 2.6 (the machine otherwise quiet; see below).

**Tick-to-margin** is measured from just before the tick is handed to the producer to the moment the shard has recomputed every affected account and handed its outputs on (decision 89). One sample per tick per shard; exact percentiles.

| Ticks/s | Achieved | Samples | p50 | p90 | **p99** | p99.9 | max | Shard CPU per tick | Max shard lag | GCs (time) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 10.0/s | 2,400 | 13.3 ms | 18.1 ms | **28.8 ms** | 53.1 ms | 55.3 ms | 13.1 ms | 2 events | 7 (218 ms) |
| 50 | 50.0/s | 12,000 | 8.0 ms | 9.0 ms | **14.2 ms** | 21.4 ms | 29.5 ms | 7.1 ms | 2 events | 10 (129 ms) |
| 100 | 100.0/s | 24,000 | 8.0 ms | 9.3 ms | **19.7 ms** | 52.5 ms | 66.9 ms | 6.9 ms | 10 events | 20 (209 ms) |
| flat out | **119/s sustained** by the slowest shard | | | | | | | | | 9 (93 ms) |

Under 50 ms at every rate. Reading it:
- **10 ticks/s is slower than 50 and 100.** With 100 ms of idle between ticks, the shard threads' caches go cold and the cores clock down; the same tick costs 13.1 ms of CPU instead of 6.9 ms. This is the usual laptop behaviour, not queueing (lag never exceeds 2 events).
- **p99.9 and max at 100 ticks/s (52–67 ms) are GC pauses and the odd tick that lands on an efficiency core.** p99 has about 30 ms of room; p99.9 does not.
- **Headroom is about 20%.** Flat out, the slowest shard manages 119 ticks/s. A burst above that queues, and latency climbs with the queue.

**How it got here (decision 94).** The first run missed badly (p99 0.96 s at 10 ticks/s, unbounded at 50 and 100; 35 ticks/s sustained). Profiling: GC and boxing were not the problem. That run had another job on the machine using about half the cores, and the tick loop's memory access pattern made four shards fight over memory bandwidth. Old engine against new, same harness, same quiet machine:

| | Old tick loop | New tick loop |
| --- | ---: | ---: |
| Shard CPU per tick (50 / 100 ticks/s) | 9.5 / 9.4 ms | 7.1 / 6.9 ms |
| p99 at 50 ticks/s | 17.0 ms | 14.2 ms |
| p99 at 100 ticks/s | 3,414 ms (can't keep up) | 19.7 ms |
| Flat out | 100 ticks/s | 119 ticks/s |

The incremental JMH benchmark on one thread is unchanged by the restructure (10,000 accounts: 1.64 ms per tick on a quiet machine; 25,000 accounts: 4.2 ms). The win appears only with several shards running at once.

**The 1M-event stress run after the change:** 0 mismatches against the single-threaded reference (outputs, final state, replay hash), and the shards apply it in 3.0 s (was 4.5 s).

**In compose:** `replayer synth --rate 50 --seconds 10` after the replay; Prometheus has the histogram, and Grafana serves the "Risk engine" dashboard and queries Prometheus (checked by `scripts/m4-e2e.sh`). The compose stack is 200 accounts and its window includes the replay's 375 ticks written all at once, so its latency figures there say nothing about scale.

### Follow-ups from M5

| Check | Result |
| --- | --- |
| Three outbox relays, 3,000 rows, well-behaved broker | 3,000 requests, 0 duplicates, each row attempted once, about 1,000 per relay |
| The same with the row lock removed | 7,490 requests for 3,000 rows: the test catches it |
| Three relays under 5% timeouts, 1,000 rows | every extra request is a resend of one the broker never received; 0 repeats of a key it had |
| Fills in the chaos gate | every `fills.filled_at` and ledger `log_time` falls inside the replay's tick times |

`./gradlew build`: 139 tests, 0 failures, re-run from scratch.

## M5: square-off under full broker chaos

`./gradlew :writers:test --tests '*SquareOffChaosTest' -Dchaos.accounts=2000 -Dchaos.ticks=80` on branch `m5-square-off`, JDK 25.0.2, Apple M4. Real Redpanda and Postgres (containers), real shards, writers, outbox relay and fill ingester; the mock broker at full chaos: 5% of requests time out (half lose the request, half lose the answer), 2% of orders rejected, fills late and split in up to three parts, 10% of fill callbacks delivered twice. Random book with a quarter of its cash, a falling market, users ordering throughout, and risk-ops squaring 8 accounts off by hand. The same test at 400 accounts runs on every build.

| | 2,000 accounts, 80 ticks | 400 accounts, 40 ticks (every build) |
| --- | ---: | ---: |
| Square-off episodes (by hand) | 717 (8) | 122 (4) |
| … resolved / exhausted | 717 / 0 | 122 / 0 |
| Legs planned / filled / rejected and re-planned with seq + 1 | 1,695 / 1,662 / 33 | 281 / 273 / 8 |
| Orders sent to the broker (legs + accepted user orders) | 4,419 keys, executed 4,419 times | 557 keys, executed 557 times |
| Broker requests | 4,556 (137 requests lost, 102 answers lost) | 574 (17 lost, 12 answers lost) |
| Relay timeouts → confirmed by `GET` (not resent) / not there (resent) | 102 / 137 | 12 / 17 |
| Fills / duplicate callbacks / duplicates dropped by the shards | 7,253 / 726 / 726 | 939 / 91 / 91 |
| Rows in `fills` / ledger transactions | 7,253 / 6,760 | 939 / 879 |
| **Legs closed twice** (engine's outputs / Postgres fills per leg) | **0 / 0** | **0 / 0** |
| **Surplus legs while one was in flight** | **0** | **0** |
| **Ledger transactions not summing to zero** | **0** | **0** |
| Accounts where the shards and the single-threaded reference engine disagree | 0 | 0 |
| Wall time | 58 s | 13 s |

The broker's own "same key twice executes once" never fired here: every time an answer was lost, the relay's query found the order, so no key was ever posted twice. That path is tested directly (`MockBrokerTest`: five posts of one key, one execution).

The first version of this test found a real bug: dust left in an incrementally updated vector after a short was closed (about 1e-10 rupees) made four long-only accounts with negative cash look infinitely over their limit, so they were squared off and blocked while the reference engine left them alone. Fixed (decision 83), with a regression test.

### The 1M-event stress run, with square-off in the engine

`./gradlew :loadtest:stress`, as for M4, now also with risk-ops events and a broker thread answering every leg: 1,009,534 records, 945,499 outputs compared, 648 episodes, 8,671 legs, **0 mismatches** (outputs, final account state including the whole risk state, replay hash). Shards apply everything in 4.5 s; the reference takes 14.6 s.

### Everything else

`./gradlew build`: 137 tests, 0 failures, re-run from scratch. The Redpanda, Postgres, API and chaos tests run against real containers.

### End to end through docker compose

`./scripts/m4-e2e.sh`, now with the relay and the mock broker at full chaos. The order placed over HTTP goes ACCEPTED → SENT → FILLED, with its fill and ledger rows. The synthetic fall squared off 18 accounts (22 legs, all filled), with 0 legs closed twice and 30 ledger transactions all summing to zero. Risk-ops blocked and unblocked an account over HTTP (a replayed request was 409, a trader 403).

## M4: shards, orders, security

### Stress harness: 1M interleaved events on hot accounts

`./gradlew :loadtest:stress` on branch `m4-shards-orders-security`, JDK 25.0.2, Apple M4. 2,000 accounts on 4 shards; 8 writer threads plus a tick writer, all racing; 80% of account events on 20 hot accounts. Seed 20240604.

| | In-memory logs | Through Redpanda v24.2.7 (one container) |
| --- | ---: | ---: |
| Records written (a tick counts once per partition) | 1,000,000 | 1,000,000 |
| of which ticks / orders / fills / order closes / ignored square-off results | 20,000 / 465,328 / 367,179 / 118,046 / 29,447 | 20,000 / 465,229 / 367,286 / 118,015 / 29,470 |
| Outputs compared one by one with the reference engine | 919,640 | 920,080 |
| Orders accepted / rejected for margin | 408,181 / 15,091 | 408,124 / 15,098 |
| Other rejections (bad lot size, unknown account, unknown instrument, no market yet) | 17,735 | 17,706 |
| Level changes | 18,064 | 18,554 |
| Reservations released by an order close | 72,863 | 72,919 |
| Duplicate fills / duplicate orders ignored | 24,656 / 24,321 | 24,703 / 24,301 |
| **Mismatches: outputs / final account state / replay hash** | **0 / 0 / 0** | **0 / 0 / 0** |
| Time for the shards to apply everything | 4.5 s (about 220,000 records/s) | 5.6 s (about 180,000 records/s) |
| Reference engine, single thread, same records | 107 s | 109 s |

The two columns differ in their counts because the interleaving is a real race and comes out differently every run; within a run, shards and reference see the same logs.

How to read the speed: this is 4 shards with 500 accounts each and only 2% of records being ticks, so it says the order path is cheap, not that the engine meets its latency target. M6 measures that (100k accounts, p99). The "max shard lag" the harness prints (about 420,000 records) only shows that in-memory writers are faster than shards; it is not a production figure.

Before decision 67 (running net reservation per instrument) the same run took 60 s.

A small version (80,000 records) runs on every build, with a second test that hides one fill from the reference and checks the harness reports it.

### Everything else

`./gradlew build`: 121 tests, 0 failures. The Redpanda, Postgres and API tests run against real containers.

| Check | Result |
| --- | --- |
| Shards on Redpanda vs shards on in-memory logs (2,000 accounts, 4 partitions) | identical state hash on every shard; `engine-out` lines identical and in the same order |
| Restarted host replays from offset 0 | same hashes again; every output published a second time, byte for byte |
| Postgres projection vs the reference engine (400 accounts; 1,063 outputs: 124 margin events, 31 decisions, 8 closes, 900 position changes) | positions, cash and order statuses equal, account by account |
| Shard host restarted, all 1,063 outputs published again | not one projected value changes |
| Out-of-order outputs | an older position, cash or order status never overwrites a newer one; a decision is audited once |
| Per-shard lag | 40 waiting events → lag 40; caught up → 0; a stopped shard → `risk_shard_up 0`, `/healthz` 503 |
| `OrderClosed` | releases what is left of a reservation (all of it, or the unfilled part); closing twice, or an unknown order, releases 0 |
| 401 | no token; wrong key; expired; wrong issuer; `alg: none`; edited claims; missing role, account, subject or expiry (11 forged tokens × 2 endpoints) |
| 403 | trader ordering on another account (and nothing reaches the log); risk-ops placing an order; unlisted paths |
| 429 | burst of 15 against a bucket of 10: first 10 pass, then refused with `Retry-After`; other users unaffected; bucket refills; requests with bad tokens use no one's bucket |
| Replay | same `X-Request-Id` again → 409 with the original order id, also with a different body; exactly one order in the log; ids are per user |
| Input validation | 21 malformed bodies → 400, including `\|` and line-break injection, strings and fractions for qty, unknown fields, attempts to set origin or order id; unknown account or instrument → 422; none reach the log |

### Everything else

`./gradlew build`: 137 tests, 0 failures, re-run from scratch. The Redpanda, Postgres, API and chaos tests run against real containers.

### End to end through docker compose

`./scripts/m4-e2e.sh` (about two minutes; synthetic market data, its own compose project):

| Step | Result |
| --- | --- |
| Book into Postgres | 200 accounts, 437 positions |
| `replayer ticks --redpanda` | 375 ticks to each of 4 partitions; a second run is refused |
| Shards | every shard applied 375 ticks; lag 0 |
| Margin events in Postgres from the synthetic fall | 59 warnings, 37 margin calls, 18 square-off levels, 10 clears |
| `POST /orders` for a seeded option buyer | 202; `orders.status = ACCEPTED` in Postgres; one `audit_log` row; `GET /orders/{id}` shows ACCEPTED |
| Over real HTTP | same request id → 409; no token → 401; another account → 403; 40 rapid requests → 20 answered 429 |

## M3: correctness gate

| Check | Result |
| --- | --- |
| Incremental = full on 10,000 random portfolios (60 ticks, 2,853 fills incl. duplicates, closes and flips) | every one of 4,086 level changes identical; every account's positions, cash, level and margin compared at 7 checkpoints |
| Largest incremental-vs-rebuild difference | 7.5e-9 rupees (tolerance: 0.01), same with the rolling rebuild |
| Rolling rebuild | every account rebuilt exactly once per cycle; never more than ⌈accounts ÷ K⌉ on one tick; changing K changes the hash |
| Same log twice → same state hash | identical on one shard; identical per shard with 4 shards on 4 threads |
| Hash sensitivity | one tick changed by one paisa → different hash |
