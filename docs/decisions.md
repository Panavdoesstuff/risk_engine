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
