# Results

Fill in as you go. Every number names the commit and command that produced it.

| Metric | Value | Where it comes from |
| --- | --- | --- |
| Accounts | | Load test config |
| p99 tick-to-margin | | Grafana / M6 |
| Sustained ticks/s | | M6 |
| Incremental speed-up | **27.4×** vs naive, **3.1×** vs full recompute (details below) | JMH / M3 |
| Mismatches over 1M events | **0** (in-memory logs, and again through Redpanda; details below) | M4 harness |
| Duplicate closes under chaos | **0** legs closed twice, 0 surplus legs, 0 unbalanced ledger transactions (details below) | M5 chaos gate |
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
