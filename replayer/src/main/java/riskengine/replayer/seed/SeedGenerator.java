package riskengine.replayer.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.math.MathContext;
import riskengine.margin.AccountVector;
import riskengine.margin.MarginCalculator;
import riskengine.margin.MarginResult;
import riskengine.margin.MarketState;
import riskengine.margin.RiskArray;
import riskengine.margin.ScenarioGrid;
import riskengine.margin.UnderlyingParams;
import riskengine.pricing.TimeToExpiry;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentType;

/**
 * Builds a believable start-of-day book from the previous day's instruments. Every position opens at
 * that day's settlement price, so the replay day starts (almost) flat and the P&L comes from the crash.
 *
 * <p><b>Funding.</b> Each account's cash is sized with the real margin engine: the generator computes the
 * account's required margin at the previous close and sets cash so that utilisation lands in a target
 * band for its kind (see {@link SeedConfig.FundingTargets}). The margin config's fingerprint is written
 * to book.meta, because a different config means different cash balances.
 *
 * <p>Determinism rules: all iteration is over sorted collections; each account draws from its own
 * {@link SplitMix64} stream; random factors are integer basis points; cash is rounded down to the paisa
 * with a fixed rule.
 */
public final class SeedGenerator {
    /** A chain needs at least this many strikes (with open interest on both sides) to be used. */
    static final int MIN_STRIKES = 21;
    private static final BigDecimal BP = BigDecimal.valueOf(10_000);

    private SeedGenerator() {}

    /** Options of one expiry with OI on both the call and the put at every listed strike. */
    record Chain(LocalDate expiry, List<BigDecimal> strikes, Map<BigDecimal, Instrument> calls,
                 Map<BigDecimal, Instrument> puts, int atm) {
        int clamp(int i, int lo, int hi) {
            return Math.max(lo, Math.min(hi, i));
        }
        int last() {
            return strikes.size() - 1;
        }
        Instrument call(int i) {
            return calls.get(strikes.get(i));
        }
        Instrument put(int i) {
            return puts.get(strikes.get(i));
        }
    }

    /** @param ref ATM reference: near-month futures settle (the legacy bhavcopy has no spot column) */
    record Universe(String underlying, List<Instrument> futures, List<Chain> chains, BigDecimal ref) {}

    public static SeededBook generate(List<Instrument> instruments, SeedConfig cfg) {
        SortedMap<String, Universe> universes = buildUniverses(instruments, cfg.asOf());
        SortedMap<String, Integer> weights = new TreeMap<>();
        cfg.underlyingWeights().forEach((u, w) -> {
            if (universes.containsKey(u) && w > 0) weights.put(u, w);
        });
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("no usable underlying among " + cfg.underlyingWeights().keySet()
                    + " (need futures and an option chain with >= " + MIN_STRIKES + " strikes expiring after " + cfg.asOf() + ")");
        }

        // Start-of-day market per underlying, as of 15:30 IST on the as-of date.
        long valuation = TimeToExpiry.expiryMillis(cfg.asOf());
        SortedMap<String, BigDecimal> startSpots = new TreeMap<>();
        SortedMap<String, MarketState> markets = new TreeMap<>();
        SortedMap<String, ScenarioGrid> grids = new TreeMap<>();
        for (String u : weights.keySet()) {
            UnderlyingParams p = cfg.margin().params(u);
            BigDecimal spot = cfg.spotOverrides().get(u);
            if (spot == null) spot = impliedSpot(universes.get(u).futures().get(0), valuation, cfg.margin().riskFreeRate(), p.dividendYield());
            startSpots.put(u, spot);
            markets.put(u, new MarketState(u, spot.doubleValue(), cfg.vix().doubleValue() * p.volScale(), valuation));
            grids.put(u, new ScenarioGrid(p));
        }
        SortedMap<String, RiskArray> arrays = new TreeMap<>(); // each instrument priced once

        List<SeededBook.Account> accounts = new ArrayList<>(cfg.accounts());
        List<SeededBook.Position> positions = new ArrayList<>();
        for (int n = 1; n <= cfg.accounts(); n++) {
            SplitMix64 rng = SplitMix64.forAccount(cfg.seed(), n);
            String accountId = String.format(Locale.ROOT, "A%07d", n);
            Archetype archetype = pick(rng, cfg.archetypeWeights());
            Universe u = universes.get(pick(rng, weights));

            // Legs keyed canonically so the output order never depends on draw order.
            TreeMap<Instrument, Integer> legs = new TreeMap<>(Instrument.CANONICAL);
            build(archetype, u, rng, legs);
            legs.values().removeIf(l -> l == 0);

            BigDecimal premiumPaid = BigDecimal.ZERO;
            boolean hasShort = false;
            AccountVector vector = new AccountVector(u.underlying());
            MarketState market = markets.get(u.underlying());
            for (var e : legs.entrySet()) {
                Instrument i = e.getKey();
                if (e.getValue() < 0) hasShort = true;
                if (i.type().isOption()) premiumPaid = premiumPaid.add(value(i, e.getValue()));
                RiskArray array = arrays.computeIfAbsent(i.id(), id -> RiskArray.compute(i, market, grids.get(i.underlying()), cfg.margin().riskFreeRate()));
                vector.add((long) e.getValue() * i.lotSize(), i.settle().doubleValue(), array);
            }
            MarginResult margin = MarginCalculator.compute(vector, cfg.margin(), 0, 0);

            boolean nakedShort = archetype.sellsNakedRisk() || (archetype == Archetype.FUTURES_ONLY && hasShort);
            boolean thin = nakedShort && rng.chance(cfg.thinFundedBp());
            SeedConfig.FundingTargets t = cfg.targets();
            BigDecimal cash;
            if (archetype == Archetype.OPTION_BUYER) {
                // Premium already paid; left with 10-100% of it as free cash. A buyer needs no margin.
                cash = premiumPaid.multiply(bp(rng.nextInt(1_000, 10_000)));
            } else {
                int targetBp = thin ? rng.nextInt(t.thinLoBp(), t.thinHiBp())
                        : archetype == Archetype.HEDGED_SPREAD ? rng.nextInt(t.spreadLoBp(), t.spreadHiBp())
                        : rng.nextInt(t.fundedLoBp(), t.fundedHiBp());
                // utilisation = required / (cash + futures MTM)  =>  cash = required / target - futures MTM.
                // new BigDecimal(double) is the exact binary value: no Double.toString involved.
                cash = new BigDecimal(margin.required()).multiply(BP).divide(BigDecimal.valueOf(targetBp), MathContext.DECIMAL128)
                        .subtract(new BigDecimal(margin.futuresMtm())).max(BigDecimal.ZERO);
            }
            // Round DOWN: a paisa less cash can only push utilisation up, never below the band's floor.
            accounts.add(new SeededBook.Account(accountId, archetype, thin, cash.setScale(2, RoundingMode.DOWN)));
            for (var e : legs.entrySet()) {
                Instrument i = e.getKey();
                int lots = e.getValue();
                positions.add(new SeededBook.Position(accountId, i.id(), lots, (long) lots * i.lotSize(),
                        i.settle().setScale(2, RoundingMode.HALF_UP)));
            }
        }
        return new SeededBook(cfg, List.copyOf(weights.keySet()), startSpots, List.copyOf(accounts), List.copyOf(positions));
    }

    /**
     * Spot implied by the near-month futures settle: S = F x e^(-(r-q)T), rounded to the paisa. Under the
     * engine's cost-of-carry pricing, that future then starts exactly at its settle price (zero MTM).
     */
    static BigDecimal impliedSpot(Instrument nearFuture, long valuationMillis, double r, double q) {
        double t = Math.max(0, TimeToExpiry.years(nearFuture.expiry(), valuationMillis));
        return new BigDecimal(nearFuture.settle().doubleValue() * StrictMath.exp(-(r - q) * t)).setScale(2, RoundingMode.HALF_UP);
    }

    /** Fills {@code legs} (instrument to signed lots) for one account. */
    private static void build(Archetype a, Universe u, SplitMix64 rng, Map<Instrument, Integer> legs) {
        switch (a) {
            case FUTURES_ONLY -> {
                int nLegs = rng.nextInt(1, Math.min(2, u.futures().size()));
                int first = (u.futures().size() > 1 && !rng.chance(7_000)) ? 1 : 0;
                for (int k = 0; k < nLegs; k++) {
                    Instrument f = u.futures().get(k == 0 ? first : 1 - first);
                    int lots = rng.nextInt(1, 10) * (rng.chance(5_000) ? 1 : -1);
                    legs.merge(f, lots, Integer::sum);
                }
            }
            case OPTION_BUYER -> {
                Chain c = pickChain(rng, u);
                int nLegs = rng.nextInt(1, 3);
                for (int k = 0; k < nLegs; k++) {
                    boolean call = rng.chance(5_000);
                    int off = rng.nextInt(0, 6);
                    int lots = rng.nextInt(1, 20);
                    Instrument i = call ? c.call(c.clamp(c.atm() + off, 0, c.last()))
                                        : c.put(c.clamp(c.atm() - off, 0, c.last()));
                    legs.merge(i, lots, Integer::sum);
                }
            }
            case SHORT_STRADDLE -> {
                Chain c = pickChain(rng, u);
                int k = c.clamp(c.atm() + rng.nextInt(-1, 1), 0, c.last());
                int lots = rng.nextInt(1, 10);
                legs.merge(c.call(k), -lots, Integer::sum);
                legs.merge(c.put(k), -lots, Integer::sum);
            }
            case SHORT_STRANGLE -> {
                Chain c = pickChain(rng, u);
                int kc = c.clamp(c.atm() + rng.nextInt(2, 8), 0, c.last());
                int kp = c.clamp(c.atm() - rng.nextInt(2, 8), 0, c.last());
                int lots = rng.nextInt(1, 10);
                legs.merge(c.call(kc), -lots, Integer::sum);
                legs.merge(c.put(kp), -lots, Integer::sum);
            }
            case HEDGED_SPREAD -> {
                Chain c = pickChain(rng, u);
                int variant = rng.nextInt(0, 2); // 0 bull put, 1 bear call, 2 iron condor
                int away = rng.nextInt(0, 4), width = rng.nextInt(1, 4), lots = rng.nextInt(1, 10);
                if (variant == 0 || variant == 2) {
                    int s = c.clamp(c.atm() - away, 1, c.last());
                    int l = Math.max(0, s - width);
                    legs.merge(c.put(s), -lots, Integer::sum);
                    legs.merge(c.put(l), lots, Integer::sum);
                }
                if (variant == 1 || variant == 2) {
                    int s = c.clamp(c.atm() + away, 0, c.last() - 1);
                    int l = Math.min(c.last(), s + width);
                    legs.merge(c.call(s), -lots, Integer::sum);
                    legs.merge(c.call(l), lots, Integer::sum);
                }
            }
            default -> throw new IllegalStateException(a.toString());
        }
    }

    private static BigDecimal value(Instrument i, int lots) {
        return i.settle().multiply(BigDecimal.valueOf((long) lots * i.lotSize()));
    }

    private static BigDecimal bp(int basisPoints) {
        return BigDecimal.valueOf(basisPoints).divide(BP);
    }

    private static Chain pickChain(SplitMix64 rng, Universe u) {
        return u.chains().size() > 1 && !rng.chance(7_000) ? u.chains().get(1) : u.chains().get(0);
    }

    /** Weighted pick over a map with deterministic iteration order (EnumMap / TreeMap). */
    private static <K> K pick(SplitMix64 rng, Map<K, Integer> weights) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        int r = rng.nextInt(0, total - 1);
        for (var e : weights.entrySet()) {
            r -= e.getValue();
            if (r < 0) return e.getKey();
        }
        throw new IllegalStateException("unreachable");
    }

    static SortedMap<String, Universe> buildUniverses(List<Instrument> instruments, LocalDate asOf) {
        SortedMap<String, List<Instrument>> byUnderlying = new TreeMap<>();
        for (Instrument i : instruments) {
            if (!i.expiry().isAfter(asOf) || i.settle().signum() <= 0) continue;
            byUnderlying.computeIfAbsent(i.underlying(), k -> new ArrayList<>()).add(i);
        }
        SortedMap<String, Universe> out = new TreeMap<>();
        for (var e : byUnderlying.entrySet()) {
            List<Instrument> all = new ArrayList<>(e.getValue());
            all.sort(Instrument.CANONICAL);
            List<Instrument> futures = all.stream().filter(i -> i.type() == InstrumentType.FUT).toList();
            if (futures.isEmpty()) continue;
            BigDecimal ref = futures.get(0).settle();

            SortedMap<LocalDate, TreeMap<BigDecimal, Instrument>> calls = new TreeMap<>(), puts = new TreeMap<>();
            for (Instrument i : all) {
                if (!i.type().isOption() || i.openInterest() <= 0) continue;
                (i.type() == InstrumentType.CE ? calls : puts)
                        .computeIfAbsent(i.expiry(), k -> new TreeMap<>()).put(i.strike(), i);
            }
            List<Chain> chains = new ArrayList<>();
            for (LocalDate exp : calls.keySet()) {
                TreeMap<BigDecimal, Instrument> c = calls.get(exp), p = puts.get(exp);
                if (p == null) continue;
                List<BigDecimal> strikes = c.keySet().stream().filter(p::containsKey).toList();
                if (strikes.size() < MIN_STRIKES) continue;
                int atm = 0;
                for (int k = 1; k < strikes.size(); k++) {
                    if (strikes.get(k).subtract(ref).abs().compareTo(strikes.get(atm).subtract(ref).abs()) < 0) atm = k;
                }
                chains.add(new Chain(exp, strikes, c, p, atm));
                if (chains.size() == 2) break;
            }
            if (chains.isEmpty()) continue;
            out.put(e.getKey(), new Universe(e.getKey(), futures, List.copyOf(chains), ref));
        }
        return out;
    }
}
