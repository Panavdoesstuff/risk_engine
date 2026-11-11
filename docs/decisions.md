# Decisions

One entry per choice. Newest at the bottom. Format: **what**, why, what it costs.

## M1 — Foundations

**1. Gradle (Kotlin DSL) multi-module, compiled with `--release 21`.**
Any JDK ≥ 21 builds it; CI pins Temurin 21. Modules are added when their milestone starts rather than as empty shells, so `settings.gradle.kts` always shows what really exists. `-Xlint:all -Werror` from day one, because warnings never get fixed later.

**2. The seeded book generator lives in `replayer/`.**
It consumes the same market-data inputs as the replayer and runs in the same offline step. Not worth its own module.

**3. Positions open at the 3 June 2024 *settlement* price.**
Settle is the exchange's official end-of-day mark (always populated, even for untraded contracts). Opening every position at the mark means the book starts at zero MTM, so every rupee of loss on 4 June comes from the crash, not from seeding noise.

**4. ATM reference = near-month futures settle, not spot.**
The legacy bhavcopy has no underlying-price column. The near future is within carry of spot and keeps the generator self-contained (no second data source just to pick strikes).

**5. Lot sizes come from `fo_mktlots.csv` *as published on the replay date*, keyed by (symbol, expiry month).**
NSE lot revisions apply to newly listed months while existing contracts keep their old size until expiry, so a single "Nifty lot size" is wrong around a revision. The loader fails loudly if an expiry month is missing. UDiFF bhavcopies carry `NewBrdLotQty`; if a lot file is also passed, the two are cross-checked.

**6. Determinism: SplitMix64 per account, integer basis points, BigDecimal money.**
`java.util.Random` is spec-stable, but a ten-line generator we own (and test against the published reference vector) is easier to defend. Each account gets its own stream derived from (seed, index), so adding accounts or changing one archetype never reshuffles the others. Random factors are integer basis points, so there is no double→decimal conversion anywhere (`Double.toString` output changed in JDK 19). All output is sorted canonically with LF line endings and no timestamps.

**7. Collateral is sized with a proxy margin (12% of notional) until M2 exists.** *(Superseded by #30: cash is now sized with the real margin engine.)*
Naked shorts: 12% × ref × qty for one side (a straddle's risk is roughly one side). Spreads: max loss. Buyers: 10–100% of premium as free cash. 25% of naked sellers get 102–115% of proxy ("thin"); others 140–300%. *Cost:* after M2, re-check that "thin" accounts really are near 100% utilisation under the real margin model and recalibrate `proxyMarginRate` if not.

**8. `cash_balance` is cash after opening premiums settle.**
Sellers have received premium, buyers have paid it. *(The second half of the original entry, "available = cash + net option value", was replaced by #23: net option value is subtracted from the requirement instead.)*

**9. Daily VIX bars are stamped at 15:30 IST.**
A daily close isn't known until the close. Stamping it at 00:00 would let a replayer use 4 June's close at 09:15 — look-ahead bias that would flatter the margin calls.

**10. `engine-events` and `engine-out` share partition count N; retention is infinite.**
The log is the source of truth (recovery replays from a snapshot offset), so it must not age out mid-project. `create-topics.sh` refuses to continue if an existing topic has the wrong partition count, because silently changing N re-routes accounts.

**11. Postgres is a projection; correctness rules are enforced in the schema too.**
- `ledger_entries`: deferred constraint trigger — every `txn_id` must sum to zero at COMMIT.
- `audit_log`: trigger blocks UPDATE/DELETE.
- UNIQUE on `fills.broker_fill_id`, `orders.idempotency_key`, `outbox.idempotency_key`, `margin_events(account_id, tick_seq)`: duplicates from broker callbacks or post-recovery re-emission become no-ops.
- Partial unique index: at most one OPEN square-off episode per account.

## M2: Schema fix, pricing and margin

**12. Schema aligned to Build Plan v2 with a new migration (V2), not by editing V1.**
V1 was already applied on dev databases, and Flyway refuses edited migrations. V2 renames columns to the v2 names (`accounts.id`, `positions.net_qty`, `orders.origin`, `engine_snapshots.state_blob`, …), adds what was missing (`accounts.collateral_value`, `margin_events.action`, `audit_log.reason` and `request_id`, an `outbox → orders` foreign key) and remaps values:
- Account statuses become ACTIVE / MARGIN_CALL / SQUARING_OFF / BLOCKED. "Warning" is no longer a status. It's a `margin_events.action`, because a warning changes nothing about what the account may do.
- Order origin becomes USER / SQUARE_OFF. A risk-ops manual square-off is a SQUARE_OFF order; *who* did it goes in `audit_log.actor`.
- v2's single ledger `account` column is V1's `ledger_account` (CASH, MARGIN_BLOCKED, …). `account_id` stays as the owning client.

Things only V1 has are **kept**, because dropping them can't be undone and nothing in v2 contradicts them: `square_off_episodes`, `square_off_legs`, `accounts.archetype`, `instruments.trade_date`, `fills.account_id/instrument_id/filled_at`, `orders.request_id/order_type/limit_price/reject_reason/created_by`, `outbox.event_type/idempotency_key/attempts/last_error`, `margin_events.expected_utilisation/status_from/status_to/log_time`, `audit_log.details/log_time`, `engine_snapshots.tick_seq/log_time`, `ledger_entries.reason/ref_id/log_time`, timestamps.

**13. Engine input goes through an `EventLog` interface; Redpanda arrives in M4.**
One log = one partition = one shard: `append`, `read(fromOffset, max)`, `endOffset`. Two implementations: in-memory, for tests, and a file with one text line per event. The file is readable, diffable and hashable, and reopening it continues from the last event. A half-written last line is refused, not skipped. Swapping in Redpanda later doesn't touch engine code.

**14. Nifty only until the end of M5.**
`margin.properties` lists only NIFTY, and the engine refuses positions or ticks on anything else (so a Bank Nifty position can't be silently ignored). The loader and seed generator default to Nifty. Bank Nifty still works if you ask for it explicitly. Bank Nifty's volatility (Nifty vol × a calibrated ratio) waits until then.

**15. Black-Scholes-Merton on the spot index with a dividend yield.**
Nifty options are European and cash-settled on the index, so this is the textbook model, not an approximation. Futures are priced from spot by cost of carry, S·e^((r−q)T). *Cost:* we don't have traded futures prices on each tick, so the futures mark is theoretical; any real basis (futures richer or cheaper than carry) is ignored.

**16. Normal CDF: Hart (1968) as written up by West (2005), not the common Abramowitz & Stegun formula.**
A&S is accurate to about 1e-7. On a 23,000 index with a few lots that's up to a rupee of error, which would break "matches to the rupee". Hart's is accurate to about 1e-15. It's still just a ratio of two polynomials times e^(−x²/2), so you can explain it, and the tests compare it against Python's `erfc` at 27 points.

**17. `StrictMath`, not `Math`, in pricing.**
`Math.exp` and `Math.log` may use CPU-specific instructions that differ in the last bit between machines. `StrictMath` gives the same bits everywhere. Shard state hashes (M3) and recovery (M6) depend on that. It costs some speed; measure in M6.

**18. Doubles inside pricing and margin; round only when storing or showing.**
Black-Scholes needs exp, log and square roots, so BigDecimal buys nothing there. Rupee rounding happens at the edges (Postgres NUMERIC, reports). The worked example matches to the paisa, so double precision isn't what limits accuracy.

**19. Time to expiry = milliseconds from the tick's log time to 15:30 IST on expiry day, divided by a 365-day year.**
It never uses the wall clock. Calendar time, not trading time, so weekends decay options too. At or after expiry, options are worth intrinsic value.

**20. One volatility for every strike and expiry: VIX ÷ 100.**
VIX measures roughly 30-day at-the-money volatility. *Cost:* out-of-the-money options usually trade at higher implied vol (the smile), so short strangles look cheaper and safer here than in the market. That's acceptable for a margin engine demo, but say it if asked.

**21. Scenario grid: the 16 standard SPAN scenarios.**
Spot moves of 0, ±⅓, ±⅔ and ±1 times the price scan range, each with vol up and down, plus two extreme moves (2 × the range, vol unchanged) where only 35% of the loss counts. The extremes exist for far out-of-the-money short options, which look safe inside the normal range. Vol moves are absolute (±4 vol points) and floored at 1%. Scenarios revalue at the *current* time to expiry, with no look-ahead decay, which is slightly conservative for option sellers. All of the parameter values are guesses until checked against NSE Clearing's parameter files for 3–4 June 2024 (marked GUESS in `margin.properties`).

**22. A risk array is per *unit* of one instrument, held long. An account is the sum of qty × risk array.**
That makes everything linear in qty, which is what M3's incremental update (`vector += qty × (new − old)`) needs. The short-option minimum and exposure margin depend on whether a position is short, so removing a position must use the same qty it was added with; the class says so.

**23. Net option value is subtracted from the requirement, not added to available. This differs from the build map.**
The map says "available margin includes net option value". NSE does it the other way round: required = max(0, max(SOMC, worst loss) − NOV) + exposure, against available = cash + collateral. The two agree on exactly when an account crosses 100%, but not on the ratio below that. Under the map's version, a buyer who spent all their cash on premium sits near 100% and would be warned and margin-called, though they can't lose more than they've paid. Under NSE's version they show 0%. That is also how "long options: loss capped at premium" is enforced: a long-only book's worst loss can never exceed its option value, so its risk requirement is zero (a property test checks this). Bonus: "adding a hedge never raises margin" holds exactly in this form, and doesn't in the other one.

**24. Exposure margin: 2% of notional on futures and short options, gross, with no hedge offset.**
Long options pay no exposure margin. Notional is spot for options and the futures price for futures. Hedged spreads still pay it on the short leg, as on NSE.

**25. Short option minimum (SOMC): 3% of spot × the gross number of short option units.**
It stops a deep out-of-the-money short option from needing almost nothing. *Uncertain:* whether NSE nets long options against shorts here (see the M2 report). This is the main reason hedged spreads currently come out expensive.

**26. Each underlying is margined on its own; no calendar spread charge.**
Requirements add across underlyings, with no Nifty/Bank Nifty offset. Within Nifty, all expiries share one scenario set, so long June / short July futures nearly cancel. NSE adds a calendar spread charge for that, and we don't yet. *Cost:* calendar spreads are under-margined.

**27. Utilisation = required ÷ (cash + collateral). WARNING is immediate; MARGIN_CALL and SQUARE_OFF need 3 consecutive ticks; going down is immediate.**
Thresholds are 80/100/120%, inclusive. The 3-tick confirmation stops one bad print from triggering a square-off. Immediate de-escalation keeps M2 simple; M5 makes square-off sticky until the episode ends. If something is required and available is zero or negative, utilisation is infinite. A warning maps to account status ACTIVE (decision 12).

**28. A single-threaded reference engine recomputes everything on every tick.**
`ReferenceMarginEngine` is deliberately slow and obvious. M3's incremental shard must produce identical results, and M4's concurrency harness compares against it. Ticks must have strictly increasing `tick_seq`; anything else is an error, not something to reorder.

**29. The worked example is checked by a second, independent implementation.**
Writing the expected numbers by running the Java code would prove nothing. `scripts/straddle_example.py` recomputes the straddle from the textbook formulas using Python's own `erfc`, and the doc's numbers come from it.

## M3: Follow-ups from M2, then the ordered log and incremental recompute

**30. The seed generator sizes each account's cash with the real margin engine. (Replaces #7.)**
The 12% proxy was wrong for hedged spreads: they started at 217–4,344% utilisation and would all have been squared off on the first tick. Now the generator computes each account's required margin at the previous close and sets cash so the account starts at a drawn target:
- thin short sellers: 80–99%
- other short sellers and futures accounts: 30–70%
- hedged spreads: 20–55%
- option buyers: 0%, always, because a long-only account needs no margin. They keep 10–100% of their premium as free cash.

Consequences:
- The seed command now needs the as-of day's India VIX close (`--vix`). Spot is implied from the near-month futures settle by removing cost of carry, so that future starts at exactly zero profit and loss. `--spot` overrides it.
- A different margin config gives different cash balances. `book.meta` records `margin_config_sha256` (a hash of every parameter value, ignoring comments and key order), plus the spot and VIX used. Same instruments + same seed + same config hash = same bytes.
- Cash is rounded *down* to the paisa, so rounding can only push an account a hair above its target, never below the band's floor.

**31. Required margin is clamped at zero.**
Both parts (risk requirement and exposure margin) are already non-negative, so the clamp never changes a number today. It is there so the rule is stated in code and survives future changes to the formula.

**32. Unsettled futures profit and loss counts toward available margin. (Fixes a gap in M2.)**
M2 only looked forward: a long future that had already fallen 2,000 points showed its scenario risk but not the loss already taken. Options don't have this problem, because their premium is in cash and their current value is in net option value. Now: available = cash + collateral + (current futures value − what was paid). The exchange settles this in cash every evening; intraday we count it as if it had been. The straddle example is unaffected (no futures).

**33. One piece of bookkeeping code for fills, shared by the reference engine and the shards.**
Options: premium moves in cash at once. Futures: no cash when opening; closing realises the profit or loss on the closed part into cash. Average price: weighted average when adding, unchanged when reducing, restarts at the fill price when a position flips through zero. Averages keep 6 decimals with a fixed rounding rule. Because both engines call the same code, a mismatch between them can only be a margin-maths bug, never a bookkeeping one.

**34. Only ticks feed the level tracker.** *(Changed in M5, decision 73: a fill may now lower a level, never raise it.)*
"Three consecutive ticks over 100%" should mean three market readings. A fill updates positions, cash and the account's latest margin figure immediately, but it is not a reading and can't escalate or clear a level. *Agreed for M5:* a fill will be allowed to lower an account's level (a square-off leg that brings it back under a threshold should show at once) but never to raise it.

**35. Four event types, one text line each. Orders and square-off results are accepted and ignored for now.**
`Tick`, `OrderRequest`, `Fill`, `SquareOffResult` all have a fixed field order and encode to the same bytes every time. Identifiers containing `|` or a line break are refused when the event is created. Shards apply ticks and fills fully. `OrderRequest` waits for M4's pre-trade check *(now built: decisions 48–50)* and `SquareOffResult` for M5; until then they advance the offset and change nothing else, so logs written now stay readable.

**36. Partition = Kafka's own key hash (murmur2) of the account id, modulo N.**
Using the same function as Kafka's default partitioner means that in M4, producing with key = account id lands where our code expects, with no custom partitioner to configure. A test checks 100,000 cases against the Kafka client library; that library is a test-only dependency and the engine still has none.

**37. Each shard keeps two indexes and prices only what it holds.**
`holders` (instrument → accounts holding it) says whose numbers to adjust when an instrument reprices. `byUnderlying` (underlying → instruments held) says what to reprice on a tick. When the last holder closes out, the instrument drops out of both and is no longer priced. Pricing is per shard; there is no shared pricer to synchronise on.

**38. Incremental update, with a rolling rebuild and check: 1% of accounts per tick.** *(Changed after M3 review; it was "rebuild everything every 100 ticks".)*
On a tick, each holder's vector gets `qty × (new array − old array)`. Floating-point rounding means that is not bit-identical to rebuilding from positions, so accounts are also rebuilt from positions on a schedule: on each tick, the accounts where `index % 100 == tick_seq % 100`. Each one is compared with its incremental value, the shard fails loudly if any component differs by more than one paisa, and the account *continues from the rebuilt values*. So every account is rebuilt once per K = 100 ticks and rounding error can never accumulate for longer.
- *Why rolling:* rebuilding the whole shard on one tick made every 100th tick several times slower than the rest. That is exactly the kind of spike a p99 latency target punishes (M6). Rolling does the same total work, spread evenly: never more than ⌈accounts ÷ K⌉ rebuilds on a tick.
- *What "index" is:* the account's position in the shard's sorted list of account ids. The list is fixed when the shard starts, so the index never changes.
- *Why it stays deterministic:* the schedule depends only on `tick_seq` (which is in the log) and that fixed list. There is no "ticks since last rebuild" counter any more, so there is one less piece of state to hash and to snapshot.
- K is part of the shard's behaviour: change it and the hashes change (a test checks this). Hashes from before this change don't match hashes after it.
- Worst difference seen in the 10,000-portfolio test: 7.5e-9 rupees, unchanged.

**39. `stateHash()` is SHA-256 over the whole shard state in sorted order, with doubles hashed by their exact bits.**
It covers the next offset, last tick, markets, risk arrays, and per account: cash, positions, vectors, level, streak counters, latest margin result, plus every fill id seen. *(M4 adds reserved orders and every order id seen: decisions 49–50.)* Strings are length-prefixed and decimals are hashed by value (2.50 = 2.5). Counters that exist only for diagnostics (largest drift seen, duplicate count) are left out. Bit-exact hashing only works because of the rules in #40.

**40. The "banned in shard code" list is enforced by a test that reads the source.**
`DeterminismRulesTest` scans the shard, margin, pricing and events packages (comments stripped) for wall-clock calls, random number sources, hash-ordered collections (`HashMap`, `HashSet`, `Map.of`, …), `Math.exp/log/pow` (use `StrictMath`) and parallel streams. A second test proves the scanner catches each kind. It is a grep, not a proof, but it stops the common mistakes at build time.

**41. Shards refuse out-of-order input rather than trying to fix it.**
An offset gap, a tick whose `tick_seq` doesn't increase, or a fill for an account the shard doesn't own is an error that stops the shard. Guessing would produce a state nobody can reproduce.

**42. Which scenario is reported as "worst" can differ between engines when two scenarios tie.** *(M5 adds a related case, decision 83.)*
For a futures-only account, scenarios that differ only in volatility lose exactly the same amount, so rounding dust decides which number is reported. The loss itself agrees. Within one shard the label is still deterministic.

**43. Ticks are built from minute bars using only what was known at the time, and written once by one writer.**
A minute bar's close becomes a tick when the minute ends (the 09:15 bar is a tick at 09:16). VIX is joined "as of": each tick takes the latest value already known. With daily VIX, every tick on 4 June uses 3 June's close and only the 15:30 tick sees 4 June's. The replayer writes every tick to every partition with the same `tick_seq` and refuses to write into existing log files.

**44. The speed-up is reported against two baselines, because one number would mislead.**
Per tick, 10,000 accounts × 5 positions: naive (every account reprices every position) 37.0 ms; full recompute (price each instrument once, rebuild every vector) 4.2 ms; incremental 1.35 ms. So 27× over naive and 3.1× over full recompute (numbers re-measured after #38 changed to a rolling rebuild). Most of the 27× comes from pricing each instrument once per tick instead of once per position. That is a property of risk arrays, which the full recompute has too. The incremental delta itself is worth about 3×, and part of that is the shard holding direct references where the reference engine does map lookups. JMH runs through its annotation processor and `org.openjdk.jmh.Main`, with no Gradle plugin.

## M4 part 1: shards on Redpanda, the order API, security

**45. Two new modules: `host` and `api`. The engine still has no dependencies.**
`host` holds everything that talks to Redpanda: the `EventLog` implementation, the `engine-out` publisher and `ShardHost`, the process that runs one shard thread per partition. `api` is the Spring Boot order API. *Differs from the build map's layout*, which had no module for this: putting the Kafka client inside `engine/` would have ended "plain Java, no framework in the hot path". The engine's random-book test fixtures are now shared with the other modules' tests (Gradle test fixtures).

**46. A Redpanda partition is an `EventLog`; the partition's offsets are the log's offsets.**
The shard code did not change (decision 13 held). What makes it safe:
- *Offsets have no gaps* because the topic is never compacted, never expires, and is not written with transactions (transaction markers use up offsets). The shard still checks every offset.
- *No consumer group.* Each shard's consumer is assigned its one partition and seeks to the offset the shard's state asks for. A committed offset on the broker would be a second opinion about where the shard is, and after a crash the two could disagree.
- *Writes wait for the broker* (`acks=all`) with idempotence on, so a retry inside the client can't duplicate or reorder.
- *The partition is always set explicitly* from `Partitioner`. The record key (account id) is only there for people reading the topic. Decision 36 still holds (same hash as Kafka's), but nothing depends on a client's partitioner setting.
- Starting against a topic with the wrong number of partitions is refused (same reason as decision 10).
- An empty read means "nothing yet", not "end of log"; a live shard keeps asking.

**47. A shard host always replays from offset 0, so `engine-out` is at-least-once.**
There are no snapshots until M6. Replaying gives bit-identical state (tested: same hashes as an in-memory run, and again after a restart) and publishes every output a second time, byte for byte. Consumers must cope: order decisions are keyed by order id, margin events by (account, tick_seq), which is what the Postgres unique constraints in decision 11 are for. *Cost:* restart time grows with the log until M6.

**48. The pre-trade rule.** Take the account as it would be if this order filled now at the current mark. Accept if
- utilisation would be below 100% (the margin-call threshold) and available margin would not be negative; or
- the order improves the account's excess (available − required) by at least one paisa.

Why each part:
- *Below 100%:* an order may take an account into the warning band but never into a margin call.
- *Available not negative:* a long option needs no margin, so utilisation alone would let an account with ₹3,000 buy any amount of options. It has to be able to pay the premium.
- *The second branch* lets an account that is already over its limit close or hedge. Without it, a margin-called account could not reduce its own risk.
- *One paisa, not zero:* buying an option whose mark is 0.00 changes nothing, and "not worse" would then be decided by floating-point dust. The reference engine and the shard disagreed on exactly such an order in the 10,000-portfolio test until this was added.
- *Priced at the mark from the latest tick before the order in the log*, rounded to the paisa. Orders are market orders (no limit price yet). The decision records which tick it used.

**49. Accepted orders reserve margin until they fill.**
Nothing has filled when the next order arrives, so a check against positions alone would pass two identical orders against the same free margin. Each accepted order is kept as a reservation (order id → instrument, remaining quantity); every later check treats reservations as already filled; a fill with that order id releases what it filled. Reservations do not affect the account's reported margin or level, only pre-trade checks. They are part of the state hash.
*Resolved in part 2 (decision 62):* an `OrderClosed` event releases a reservation when an order ends without filling. Until M5's broker exists nothing writes fills or closes in a live run, so there reservations still only build up.

**50. Orders the shard can't check are rejections with a reason, never exceptions.**
Unknown account, unknown instrument, quantity that is not a whole number of lots, no tick yet: each is a `REJECTED` decision with a reason code. Decision 41 (stop on bad input) is for the log's own integrity (offset gaps, tick order). Anything a user can cause must not stop a shard. An order id seen before is ignored (decided once). Orders with origin `SQUARE_OFF` are the engine's own and still wait for M5.

**51. The reference engine does the pre-trade check too, in its own way.**
The shard adjusts a copy of the account's vector; the reference rebuilds from hypothetical positions. They share only the rule and the bookkeeping (`PreTradeCheck`, `PositionAccounting`). Every test of the check runs both and asserts they agree, and part 2's stress harness can compare decisions as well as margins.

**52. What shards publish: one text line per output on `engine-out`, shard p to partition p.**
`MARGIN|v1|…` for a level change and `DECISION|v1|…` for an order decision, same style as the input log. Rupees to 4 decimals, utilisation to 6, converted exactly from the double's bits and then rounded (never via `Double.toString`, see decision 6). The lines are a report; they can't restore engine state. Sends are not awaited one at a time (the shard must not wait on the network per output); a failed send stops the host.

**53. The order API is asynchronous: 202 means "written to the log", not "accepted by risk".**
POST /orders validates, writes an `OrderRequest` to the account's partition, waits for the broker's acknowledgement and answers 202 with an order id. The shard decides when it reaches the order, in line with that account's fills and the market's ticks. That is the whole answer to "how does a pre-trade check work without locks": one thread owns the account, and the partition's order is the only order there is. A test shows the same order accepted or rejected depending only on whether a crash tick sits before or after it in the log.

**54. Order id = "O-" + first 128 bits of SHA-256(user, request id).**
If a write times out, the API can't know whether it landed. It gives the request id back, the client retries, and the retry gets the *same* order id. If both writes landed, the shard sees one id twice and decides it once (decision 50). A random id would have made two orders.

**55. JWT: HS256 with a shared secret; the API only verifies.**
Claims: `iss`, `sub` (user), `role` (`trader` or `risk-ops`), `account` (traders), `exp`. Rejected with 401: wrong key, wrong issuer, expired, any algorithm other than HS256 (including `none`), edited claims, and correctly signed tokens missing a role, an account (for a trader), a subject or an expiry. The secret comes from `JWT_SECRET`, must be at least 32 characters, and has no default: the API won't start without it. There is no login endpoint; `./gradlew :api:devToken` prints tokens for local use. *Cost:* a shared secret means anything that can verify tokens can also mint them. Fine for one service; with a separate login service it should be RS256 with a published public key.

**56. Roles. Trader: own account only. Risk-ops: read any order, place none.**
A trader's token names one account; an order for any other account is 403 and never reaches the log. Risk-ops can read every order but can't place user orders. *Not built yet:* risk-ops block/unblock and manual square-off from the build map. They change account state inside the shard, which arrives with M5. Reading someone else's order returns the same 404 as an order that doesn't exist, so order ids can't be probed. Any path not listed is denied.

**57. Rate limit: a token bucket per user in Redis, one Lua script.**
Each user has a bucket of 20 tokens refilled at 10 a second *(both numbers are guesses)*. Read, refill, take and write happen in one script, which Redis runs atomically, so two requests can't both take the last token; the clock is Redis's own, so API instances agree. It runs right after the token is verified and before any other work, so a refused request costs one Redis call. Refusals are 429 with `Retry-After`. If Redis is down the API answers 503 rather than letting everything through. *Gap:* requests without a valid token are not limited (there is no user to count them against); that needs a per-IP limit at a proxy.

**58. Replay protection: `SET request:{user}:{id} NX EX 24h`.**
Every order needs an `X-Request-Id`. The first use claims it in one atomic Redis command; a repeat inside 24 hours is 409, with the id of the order it first created. Ids are per user, so nobody can use up someone else's. The same id with a different body is still a replay. After the 24 hours, a repeat would get the same order id (decision 54) and the shard would ignore it.

**59. The API checks form; the shard checks meaning.** *(Part 2 adds one check to the API: an unknown account or instrument is 422, see decision 65.)*
API (400): strict JSON (unknown fields, `"25"` for 25 and 25.5 for a whole number are errors), narrow patterns for account and instrument ids, side, 1 ≤ qty ≤ 100,000. The patterns also guarantee that nothing a caller sends can contain the `|` or line breaks that separate fields and events in the log. Shard (rejection): does the account exist, is the instrument listed, is the quantity whole lots, is there margin. The API holds no reference data and never computes margin. Callers can't set the order id or the origin. Error bodies never contain class names or parser messages.

**60. Order status for GET /orders/{id} lives in Redis for now.** *(Superseded by decision 65: it is read from Postgres and the Redis store is gone.)*
The API writes PENDING (only if no status is there); a listener on `engine-out` writes the decision (always). Whichever lands first, a decision is never overwritten by PENDING. Records expire after 7 days. This is a convenience view, not a record; part 2's Postgres writers produce the durable `orders` rows, and the endpoint could read from there instead.

**61. The Redpanda and API tests run against real containers; nothing is mocked.**
Testcontainers starts the same Redpanda image as docker-compose, plus Redis. The API tests go through the real Spring Security filter chain, real Redis scripts, a real broker and real shards. They need Docker (CI has it). *Cost:* about a minute of test time.

## M4 part 2: OrderClosed, Postgres writers, the stress harness, lag, docker compose

**62. `OrderClosed` releases a reservation when an order ends without filling.**
A fifth event type, `CLOSED|v1|orderId|accountId|reason|logTime`, with reason UNFILLED, REJECTED or CANCELLED, written to the account's partition. The shard removes whatever that order still had reserved and answers with how much it released. M5's broker side will write it; today the stress harness and tests do.
- Closing an order that has nothing reserved (already filled, never accepted, closed before, unknown) releases 0 and is not an error, so the event can be delivered twice.
- A partly filled order releases only the unfilled part.
- A fill that arrives after the close still changes the position, because the trade happened, and releases nothing.

**63. Two more outputs, and every output says which event caused it.**
`POSITION|v1|…` (a fill changed a position: the new quantity, average price and cash) and `RELEASED|v1|…` (the answer to an `OrderClosed`). Each `engine-out` record also carries a `source-offset` header: the offset, in the account's `engine-events` partition, of the event that caused it. The record's own offset in `engine-out` grows every time a shard replays; the source offset is the same on every replay, which is what makes decision 64 work.

**64. The Postgres writers: batch, commit the database first, and make every statement safe to run twice.**
One loop: poll up to 2,000 records → one database transaction → commit it → commit the Kafka offsets. If the process dies between the two commits the batch is read and written again, so every statement has to be harmless the second time:
- *Rows inserted once* have a unique key and `ON CONFLICT DO NOTHING`: `margin_events (account_id, tick_seq)`, and a new `audit_log.event_key` (`DECISION:<order>`, `MARGIN:<account>:<tick>`, `CLOSED:<order>:<offset>`).
- *Rows that are updated* (positions, account cash, order status) carry `last_event_seq`, the source offset that last wrote them, and only accept a strictly newer one. Without this, a restarted shard replaying its history would briefly put old positions back.
- Migration V3 adds `accounts.last_event_seq`, `orders.last_event_seq` and `audit_log.event_key`. `positions.last_event_seq` already existed; V1 described it as an engine-out offset, and it now holds the source offset, for the reason in decision 63.
- A row that would break a foreign key (an account or instrument Postgres has never seen) is skipped and counted, and its audit row is still written without the account link. One bad row must not block the stream.
- Utilisation is capped at 999999.9999 in `margin_events`, because NUMERIC(10,4) can't hold the infinity the engine uses when nothing is available.
- All SQL is parameterised. No framework: a Kafka consumer and JDBC batches.
- *Tested:* after a run the tables equal the reference engine's positions, cash and order statuses account by account; then the shard host is restarted, republishes all 1,063 outputs, and not one projected value changes.
- *Not written yet:* `accounts.status`, `fills` and the ledger. Those belong to M5 (the status state machine, broker fills, double entry).

**65. Order status is read from Postgres; the Redis status store is gone.** *(Replaces #60.)*
The API inserts the order as PENDING just before writing it to the log (`ON CONFLICT DO NOTHING`); the writers set ACCEPTED / REJECTED, and later CANCELLED / BROKER_REJECTED on a close. `last_event_seq = -1` marks a row the engine has not decided yet, so a decision can never be overwritten by PENDING. Redis now holds only rate-limit buckets and request ids.
- *Approved after M4.* *Side effect, a change from part 1:* the `orders` table has foreign keys to accounts and instruments, so an order naming something that doesn't exist can't be inserted. The API answers **422** and writes nothing to the log (the request id is given back). In part 1 such orders got 202 and a shard rejection. The shard still rejects them if they reach it some other way.
- `GET /orders/{id}` returns the order row, including the required and available margin the pre-trade check computed *(added back in M5 at your request; V4 stores them on the order)*.
- *Cost:* the API now needs Postgres to accept an order, where before it needed only Redis and Redpanda. If Postgres is down the API answers 503.

**66. The stress harness races real threads, then lets the log say what happened.**
Eight writer threads append orders, fills, closes and duplicates for the same 20 hot accounts (80% of events) while another thread writes ticks to every partition and four shard threads consume live. Nobody coordinates, so the order of records in a partition is decided by the race, as in production.
- *How the reference gets the same input:* afterwards the harness reads back what each partition actually holds and feeds the single-threaded reference engine "tick, then every partition's account events up to the next tick". Events in different partitions belong to different accounts, so their relative order can't matter; each account's own order and where the ticks fall in it are preserved.
- *What is compared:* every output of every shard, one by one and in order (level changes, decisions and reasons, position changes, releases); every account's final positions, cash, level, reservations and margin; and each live shard's state hash against a single-threaded replay of its recorded partition.
- *Tolerance:* money to a millionth of a rupee, because the shard updates incrementally and the reference rebuilds (decision 38). Everything else must be identical.
- *It can fail:* a test hides one fill from the reference and checks that the harness reports mismatches.
- *Result:* 1,000,000 records, 0 mismatches, on in-memory logs and again through a real Redpanda broker. Numbers in results.md.
- *What it does not cover:* the Postgres writers. They are tested separately (decision 64) on about a thousand outputs, not a million.

**67. The shard keeps a running net reservation per instrument; the reference adds up every open order each time.**
The first 1M run took 60 seconds, because each pre-trade check walked every open order of the account and hot accounts had thousands. The shard now keeps, per account, the net reserved quantity per instrument, updated when an order is accepted, filled or closed. The same run takes 4.5 seconds. The reference engine was deliberately left adding them up from scratch, so the two engines reach the reservation total by different routes and the harness compares them. (The reference did get one harmless speed-up: it prices each instrument once per tick instead of once per order.)

**68. Per-shard lag = partition end offset − the shard's applied offset.**
It is the number that says whether a shard is keeping up. A background thread asks the broker for the end offsets twice a second with its own consumer, so measuring never gets in a shard's way. The shard host serves the numbers as Prometheus text at `:9100/metrics` (`risk_shard_lag_events`, `risk_shard_applied_offset`, `risk_shard_log_end_offset`, `risk_shard_up`) and `/healthz` turns 503 once a shard has stopped. It uses the JDK's built-in HTTP server; M6 points Prometheus at it. The two offsets are read a moment apart, so it is a gauge, not an exact count.

**69. docker compose: the engine is behind the `app` profile, in one image.** *(Approved after M4.)*
`docker compose up -d` still starts only the infrastructure, because that has to work on a machine with no data yet (the M1 gate). `docker compose --profile app up -d` adds a one-shot book loader, the shard host, the writers and the API, all from one image built by the Dockerfile (Gradle runs inside the build, so only Docker is needed). They read the book from `./data`.
- Host ports are variables, so a second copy can run beside the first. `scripts/m4-e2e.sh` uses that: it runs as its own compose project on its own ports and volumes and deletes them afterwards, never touching the default stack.
- The compose file gives the API a fixed development JWT secret if `JWT_SECRET` is not set. That is fine on a laptop and nowhere else; the API itself still has no default. *(Approved after M4; the README now says so.)*

**70. The replayer writes ticks to Redpanda, once.**
`replayer ticks --redpanda host:port` writes every tick to every partition of `engine-events`. It first reads the partitions and refuses if any already holds a tick: a second copy would break "tick_seq strictly increases" and stop every shard. `--pace-ms` slows it down for watching.

**71. `replayer demo` writes synthetic inputs, clearly marked.**
The real 3–4 June files aren't in the repo yet, and the end-to-end run needs a book and ticks. `replayer demo` writes an instruments file priced by the engine's own Black-Scholes and raw minute bars with a made-up fall of about 8%, then the normal `bars`, `seed` and `ticks` commands run on them. Every file says SYNTHETIC. Nothing produced from it may be quoted as a result about 4 June 2024.

## M5: the square-off pipeline

**72. The fill ingester is the gate in front of the shards; shards keep stopping on bad input.** *(Your call after M4.)*
Broker callbacks arrive over HTTP at the ingester (in the `writers` module), the only thing that writes fills to the log. A callback is refused with 422, recorded in `audit_log` as `CALLBACK_REFUSED`, and never written, unless it is exactly the expected shape (well-formed ids, non-zero quantity, non-negative price) and its key is an order we placed *for that account and that instrument*. The order row's foreign keys prove both exist, and the match stops a fill being booked to the wrong account. So a shard never sees a fill it can't apply, and decision 41 stays as it was. Duplicates are let through on purpose: whether a fill id is new is decided by the shard, in log order. If Postgres or the log is down the answer is 503 and the broker calls again.

**73. One state machine, `AccountRisk`, shared by the shard and the reference engine.**
Level, status and square-off episode are decided in one class. The two engines differ only in how they answer "what would the margin be if…" (`AccountView`): the shard adjusts a copy of its vectors, the reference rebuilds from positions. That is the same split as decision 33 for bookkeeping: a disagreement between them can only be margin maths.
- *Level* (OK / WARNING / MARGIN_CALL / SQUARE_OFF) is fed **expected** utilisation once per tick: the account as it will be once the leg in flight has filled. Feeding actual utilisation would keep asking for more while a leg is on its way.
- *A fill may lower the level at once, never raise it* (your rule after M3). Raising still takes three consecutive ticks. Lowering also resets the streaks, so a fill that cures an account cancels a square-off that was one tick away.
- *Status* (accounts.status): BLOCKED over SQUARING_OFF over MARGIN_CALL over ACTIVE. Each change is an output with a cause (`SQUARE_OFF_STARTED`, `BLOCKED_BY_RISK_OPS`, `SQUARE_OFF_EXHAUSTED`, …) and an actor.

**74. A square-off episode: opened by the level, closed by the account's state, one leg at a time.**
- *Opens* when the level reaches SQUARE_OFF (AUTO) or when risk-ops asks (MANUAL). Episode ids count per account (E1, E2, …), so leg keys from two episodes can never collide.
- *While open:* the level is frozen (square-off is sticky, as the build map asked in M2); user orders are refused with `SQUARING_OFF`; the account's reservations are cancelled, each with its own `RELEASED` output.
- *Ends* RESOLVED when utilisation is back under 100% (AUTO) or the account is flat (MANUAL), and EXHAUSTED when it is still over and nothing left would free margin when closed. The check runs after every fill, so an episode ends on the fill that cures it, not on the next tick.
- *An exhausted account is blocked* until risk-ops unblocks it (or its positions change). Otherwise it would open a new, useless episode every three ticks. *Needs a decision, see the report.*

**75. The in-flight tracker: at most one leg, recorded before it is emitted.**
`AccountRisk` holds the leg in flight (key, instrument, signed remaining quantity) and sets it in the same step that emits the `LEG` output, so the state hash never shows a leg that was sent but not recorded. No new leg is planned while one is out. Fills with the leg's key reduce what remains; the episode moves on when it reaches zero. A fill for a leg that is no longer in flight (it was rejected, then filled after all) changes the position, because the trade happened, but does not count as progress. *This is what makes "0 surplus legs while one is in flight" true by construction; the gate checks it from the outside anyway.*
*Deviation from the map:* the map says one in-flight record per leg, which suggests several legs out at once. One at a time is slower to close a big account but can never overshoot. M6's latency numbers will show whether that matters.

**76. Leg key = `accountId:episodeId:instrumentId:seq`. It is the order id, the outbox idempotency key and what the broker dedupes on.**
`seq` counts legs within the episode, so a re-planned leg after a reject gets `seq + 1`: a new key, which the broker treats as a new order. However many times a leg is published (replays), stored (ON CONFLICT DO NOTHING) or sent (timeouts), it executes once.

**77. The greedy planner: short options first, then futures, then long options; within a class, most margin freed per lot.**
Short options carry the open-ended risk and the short-option minimum, so they go first even when closing a future would free more. In AUTO it closes the fewest whole lots that bring utilisation under 100%, or the whole position if that is not enough; in MANUAL, whole positions until flat. A position whose first lot frees under a paisa (a long option needs no margin) is never a candidate in AUTO, so a margin call never sells someone's hedge for nothing. Ties go to the lower instrument id.
*Deviation:* the map says "next tick re-plans with seq + 1" after a reject. It re-plans at once, on the `OrderClosed`, at the latest tick's prices. Same rule, one tick sooner.

**78. Order and outbox row in one transaction, and accepted user orders go to the broker too.**
The writers insert a leg's `orders` row (origin SQUARE_OFF, id = the leg key) and its outbox row in the same database transaction as the rest of the batch. Accepted user orders get an outbox row the same way, so user fills now come from the broker as well and release reservations normally. Replaying `engine-out` creates no second outbox row (unique key), so nothing is sent twice because of a restart.

**79. The relay: send in order; on a timeout, ask before sending again.**
One relay thread sends unsent outbox rows oldest first and marks them sent on an acknowledgement.
- *Timeout or connection error:* the order may or may not have arrived. The relay asks the broker (`GET /orders/{key}`). If it is there, the row is marked sent. If not, the row stays unsent and the next round sends it again with the same key.
- *Broker refuses the order as malformed (4xx):* the relay writes `OrderClosed(REJECTED)` to the log so the engine releases the order or re-plans the leg.
- *Limitation:* one relay at a time. Two would need row locking (`FOR UPDATE SKIP LOCKED`), which isn't there.

**80. The mock broker misbehaves on purpose, and is idempotent on the key.**
- 5% of requests time out. Half lose the request (the order is never created) and half lose only the answer (the order exists). That way the relay's "ask before resending" is exercised both ways.
- 2% of orders are rejected by callback after being acknowledged.
- Fills are split into one to three parts, each after a random delay.
- 10% of fill callbacks are delivered twice.
- What happens to an order is drawn from its key (the same key always meets the same fate); whether a request times out is drawn per request.
- The first POST for a key executes it; every later POST for that key is answered and executes nothing.
- *GUESS:* the rates are the build map's numbers, not measured from any real broker.

**81. What the writers store for M5.** Migration V4.
- `accounts.status`, guarded by its own `status_event_seq`, because one event can change both status and cash.
- `square_off_episodes` (with mode and actor), `square_off_legs` (PLANNED → SENT → FILLED / REJECTED).
- `fills`, one row per broker fill id, only for orders Postgres knows.
- `orders` gets the decision's required and available margin.
- `margin_events` is now unique on (account, source offset) instead of (account, tick_seq), because a level can also change on a fill: two changes can share a tick.
- One audit row per decision: level changes, order decisions, closes, every planned leg, episode opened/resolved/exhausted, every status change. Risk-ops actions carry the user as the actor.

**82. The double-entry ledger books every fill that moves cash: two rows that sum to zero.**
An option fill moves premium between the account's CASH and BROKER_CLEARING; a futures fill that closes a position moves the realised profit or loss between CASH and REALISED_PNL. The transaction id is `FILL:<broker fill id>`, so a duplicate fill can't be booked twice, and both rows go in one statement guarded by "this transaction is not booked yet", so a transaction is never half there. Postgres' deferred trigger rejects any transaction that does not sum to zero at commit. That is the gate's third check.
*Not booked:* "margin movements". The engine never moves money when the requirement changes: required margin is a computed number, not cash set aside. There is nothing to book until we decide to model blocking margin as a transfer (CASH → MARGIN_BLOCKED). *Needs a decision, see the report.* Fills that open or add to a futures position move no cash and book nothing.

**83. A requirement under a millionth of a rupee is zero.** *(Found by the chaos gate.)*
The shard updates vectors incrementally. After a short option is bought back, its share of the totals can be left at 1e-10 instead of 0 until the next rolling rebuild. An account holding only long options with negative cash then read as "something required, nothing available" = infinite utilisation, and was squared off and blocked, while the reference engine, which rebuilds from positions, saw zero. Four accounts in the first chaos run did this. `MarginCalculator` now treats a requirement below ₹0.000001 as zero, and a regression test reproduces the case. Decision 38's drift check didn't catch it, because the drift was far below a paisa; what made it matter was a threshold sitting exactly at zero.

**84. Risk-ops: block, unblock and manual square-off go through the log like everything else.**
`POST /accounts/{id}/block`, `/unblock` and `/square-off` (risk-ops only; 403 for traders) write an `AccountControl` event to the account's partition and answer 202, exactly like an order. The shard applies it in line with the account's ticks, fills and orders, so "blocked" takes effect at a definite point in the log, and replays reproduce it. Each needs an `X-Request-Id`, and a repeat is 409, so a retried square-off can't fire twice. `GET /accounts/{id}` shows status and cash (traders: their own account only, otherwise 404). Unblocking also lifts a block the engine set after an exhausted episode. A manual square-off on an account already being squared off switches that episode to MANUAL (close everything).

**85. The stress harness now includes square-off and risk-ops.**
The 1M-event run adds `AccountControl` events (1% of account events) and a small broker thread that answers every leg with a fill for the whole leg (4% rejected instead, 10% delivered twice). Episodes, legs, in-flight state and status are part of what is compared and hashed. Hot accounts are hammered so hard that they spend most of the run being squared off, which shows up as many `SQUARING_OFF` rejections: that is the intended stress, not a business result.

**86. On the M4 CI failure (job `m4-e2e`, "FAIL compose up").**
The script ran `docker compose up -d >/dev/null 2>&1 || fail "compose up"`, so it threw away the one message that said what failed. Running main's compose stack on a clean Linux Docker engine (Docker 28, the same steps as the job) succeeded, so I can't name the cause from here. Changes:
- The script now saves compose's output and, on any failure, prints it with the state of every container and the last 60 lines of every service's log.
- Redpanda's health check may now take up to three minutes instead of one. A dependency that never turns healthy is what makes `up` fail, and a cold CI runner starting the broker is the likeliest one.
If CI fails again, the log will say why.
