package riskengine.replayer.seed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import riskengine.margin.MarginConfig;

/**
 * @param asOf         trade date the instruments were loaded for (previous close; 2024-06-03 for the replay).
 *                     Margins are computed as of 15:30 IST on this date.
 * @param thinFundedBp share of naked short sellers (straddles, strangles, accounts short futures) that are
 *                     funded close to their margin requirement, in basis points
 * @param margin       the margin model used to size every account's cash
 * @param vix          India VIX close on {@code asOf}
 * @param spotOverrides start-of-day spot per underlying; when absent it is implied from the near-month
 *                     futures settle by removing cost of carry
 * @param targets      starting utilisation bands per kind of account
 */
public record SeedConfig(
        long seed,
        int accounts,
        LocalDate asOf,
        int thinFundedBp,
        Map<Archetype, Integer> archetypeWeights,
        SortedMap<String, Integer> underlyingWeights,
        MarginConfig margin,
        BigDecimal vix,
        SortedMap<String, BigDecimal> spotOverrides,
        FundingTargets targets) {

    /**
     * Starting utilisation = required margin / cash, drawn uniformly per account, in basis points (inclusive).
     * Option buyers are not here: a long-only account needs no margin, so it always starts at 0%.
     */
    public record FundingTargets(int thinLoBp, int thinHiBp, int fundedLoBp, int fundedHiBp, int spreadLoBp, int spreadHiBp) {
        public FundingTargets {
            check(thinLoBp, thinHiBp);
            check(fundedLoBp, fundedHiBp);
            check(spreadLoBp, spreadHiBp);
        }

        private static void check(int lo, int hi) {
            if (lo <= 0 || hi < lo || hi >= 10_000) throw new IllegalArgumentException("utilisation band must be within (0%, 100%): " + lo + ".." + hi);
        }

        /** Thin shorts 80-99%, other shorts and futures 30-70%, hedged spreads 20-55%. */
        public static FundingTargets defaults() {
            return new FundingTargets(8_000, 9_899, 3_000, 6_999, 2_000, 5_499);
        }
    }

    public SeedConfig {
        if (accounts <= 0 || accounts > 9_999_999) throw new IllegalArgumentException("accounts out of range");
        if (thinFundedBp < 0 || thinFundedBp > 10_000) throw new IllegalArgumentException("thinFundedBp out of range");
        if (vix == null || vix.signum() <= 0) throw new IllegalArgumentException("vix must be positive");
        archetypeWeights = new EnumMap<>(archetypeWeights);
        underlyingWeights = new TreeMap<>(underlyingWeights);
        spotOverrides = new TreeMap<>(spotOverrides);
    }

    public static SeedConfig defaults(long seed, int accounts, LocalDate asOf, BigDecimal vix) {
        Map<Archetype, Integer> mix = new EnumMap<>(Archetype.class);
        mix.put(Archetype.FUTURES_ONLY, 20);
        mix.put(Archetype.OPTION_BUYER, 25);
        mix.put(Archetype.SHORT_STRADDLE, 15);
        mix.put(Archetype.SHORT_STRANGLE, 15);
        mix.put(Archetype.HEDGED_SPREAD, 25);
        // Nifty only until the end of M5 (docs/decisions.md, M2). Pass explicit weights to add Bank Nifty.
        SortedMap<String, Integer> und = new TreeMap<>();
        und.put("NIFTY", 100);
        return new SeedConfig(seed, accounts, asOf, 2_500, mix, und, MarginConfig.defaults(), vix, new TreeMap<>(), FundingTargets.defaults());
    }

    public SeedConfig withUnderlyingWeights(SortedMap<String, Integer> weights, MarginConfig marginConfig) {
        return new SeedConfig(seed, accounts, asOf, thinFundedBp, archetypeWeights, weights, marginConfig, vix, spotOverrides, targets);
    }

    public SeedConfig withThinFundedBp(int bp) {
        return new SeedConfig(seed, accounts, asOf, bp, archetypeWeights, underlyingWeights, margin, vix, spotOverrides, targets);
    }

    public SeedConfig withSpot(String underlying, BigDecimal spot) {
        SortedMap<String, BigDecimal> s = new TreeMap<>(spotOverrides);
        s.put(underlying, spot);
        return new SeedConfig(seed, accounts, asOf, thinFundedBp, archetypeWeights, underlyingWeights, margin, vix, s, targets);
    }
}
