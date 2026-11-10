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
- other short sellers and futures accounts: 30–70% *(my choice; you specified the other three)*
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

**34. Only ticks feed the level tracker.**
"Three consecutive ticks over 100%" should mean three market readings. A fill updates positions, cash and the account's latest margin figure immediately, but it is not a reading and can't escalate or clear a level. M5 will revisit this for square-off fills.

**35. Four event types, one text line each. Orders and square-off results are accepted and ignored for now.**
`Tick`, `OrderRequest`, `Fill`, `SquareOffResult` all have a fixed field order and encode to the same bytes every time. Identifiers containing `|` or a line break are refused when the event is created. Shards apply ticks and fills fully. `OrderRequest` waits for M4's pre-trade check and `SquareOffResult` for M5; until then they advance the offset and change nothing else, so logs written now stay readable.

**36. Partition = Kafka's own key hash (murmur2) of the account id, modulo N.**
Using the same function as Kafka's default partitioner means that in M4, producing with key = account id lands where our code expects, with no custom partitioner to configure. A test checks 100,000 cases against the Kafka client library; that library is a test-only dependency and the engine still has none.

**37. Each shard keeps two indexes and prices only what it holds.**
`holders` (instrument → accounts holding it) says whose numbers to adjust when an instrument reprices. `byUnderlying` (underlying → instruments held) says what to reprice on a tick. When the last holder closes out, the instrument drops out of both and is no longer priced. Pricing is per shard; there is no shared pricer to synchronise on.

**38. Incremental update, with a full rebuild and check every 100 ticks.**
On a tick, each holder's vector gets `qty × (new array − old array)`. Floating-point rounding means that is not bit-identical to rebuilding from positions, so every K = 100 ticks the shard rebuilds every vector, compares, and fails loudly if any component differs by more than one paisa. It then *continues from the rebuilt values*, so rounding error can never accumulate for more than K ticks. Worst difference seen in the 10,000-portfolio test: 7.5e-9 rupees. K is part of the shard's behaviour: change it and the hashes change.

**39. `stateHash()` is SHA-256 over the whole shard state in sorted order, with doubles hashed by their exact bits.**
It covers the next offset, last tick, markets, risk arrays, and per account: cash, positions, vectors, level, streak counters, latest margin result, plus every fill id seen. Strings are length-prefixed and decimals are hashed by value (2.50 = 2.5). Counters that exist only for diagnostics (largest drift seen, duplicate count) are left out. Bit-exact hashing only works because of the rules in #40.

**40. The "banned in shard code" list is enforced by a test that reads the source.**
`DeterminismRulesTest` scans the shard, margin, pricing and events packages (comments stripped) for wall-clock calls, random number sources, hash-ordered collections (`HashMap`, `HashSet`, `Map.of`, …), `Math.exp/log/pow` (use `StrictMath`) and parallel streams. A second test proves the scanner catches each kind. It is a grep, not a proof, but it stops the common mistakes at build time.

**41. Shards refuse out-of-order input rather than trying to fix it.**
An offset gap, a tick whose `tick_seq` doesn't increase, or a fill for an account the shard doesn't own is an error that stops the shard. Guessing would produce a state nobody can reproduce.

**42. Which scenario is reported as "worst" can differ between engines when two scenarios tie.**
For a futures-only account, scenarios that differ only in volatility lose exactly the same amount, so rounding dust decides which number is reported. The loss itself agrees. Within one shard the label is still deterministic.

**43. Ticks are built from minute bars using only what was known at the time, and written once by one writer.**
A minute bar's close becomes a tick when the minute ends (the 09:15 bar is a tick at 09:16). VIX is joined "as of": each tick takes the latest value already known. With daily VIX, every tick on 4 June uses 3 June's close and only the 15:30 tick sees 4 June's. The replayer writes every tick to every partition with the same `tick_seq` and refuses to write into existing log files.

**44. The speed-up is reported against two baselines, because one number would mislead.**
Per tick, 10,000 accounts × 5 positions: naive (every account reprices every position) 35.3 ms; full recompute (price each instrument once, rebuild every vector) 4.3 ms; incremental 1.4 ms. So 25× over naive and 3.1× over full recompute. Most of the 25× comes from pricing each instrument once per tick instead of once per position. That is a property of risk arrays, which the full recompute has too. The incremental delta itself is worth about 3×, and part of that is the shard holding direct references where the reference engine does map lookups. JMH runs through its annotation processor and `org.openjdk.jmh.Main`, with no Gradle plugin.
