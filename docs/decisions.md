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

**7. Collateral is sized with a proxy margin (12% of notional) until M2 exists.**
Naked shorts: 12% × ref × qty for one side (a straddle's risk is roughly one side). Spreads: max loss. Buyers: 10–100% of premium as free cash. 25% of naked sellers get 102–115% of proxy ("thin"); others 140–300%. *Cost:* after M2, re-check that "thin" accounts really are near 100% utilisation under the real margin model and recalibrate `proxyMarginRate` if not.

**8. `cash_balance` is cash after opening premiums settle.**
Sellers have received premium, buyers have paid it. Available margin in M2 = cash + net option value, which then starts equal to the collateral the generator intended.

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
