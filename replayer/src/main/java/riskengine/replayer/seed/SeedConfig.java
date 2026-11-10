package riskengine.replayer.seed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * @param asOf             trade date the instruments were loaded for (previous close; 2024-06-03 for the replay)
 * @param proxyMarginRate  fraction of underlying notional used to size collateral before the real margin
 *                         engine exists (M2). Only affects how much cash each account gets.
 * @param thinFundedBp     share of naked short sellers (straddles, strangles, short futures) funded just
 *                         above proxy margin, in basis points
 */
public record SeedConfig(
        long seed,
        int accounts,
        LocalDate asOf,
        BigDecimal proxyMarginRate,
        int thinFundedBp,
        Map<Archetype, Integer> archetypeWeights,
        SortedMap<String, Integer> underlyingWeights) {

    public SeedConfig {
        if (accounts <= 0 || accounts > 9_999_999) throw new IllegalArgumentException("accounts out of range");
        if (thinFundedBp < 0 || thinFundedBp > 10_000) throw new IllegalArgumentException("thinFundedBp out of range");
        archetypeWeights = new EnumMap<>(archetypeWeights);
        underlyingWeights = new TreeMap<>(underlyingWeights);
    }

    public static SeedConfig defaults(long seed, int accounts, LocalDate asOf) {
        Map<Archetype, Integer> mix = new EnumMap<>(Archetype.class);
        mix.put(Archetype.FUTURES_ONLY, 20);
        mix.put(Archetype.OPTION_BUYER, 25);
        mix.put(Archetype.SHORT_STRADDLE, 15);
        mix.put(Archetype.SHORT_STRANGLE, 15);
        mix.put(Archetype.HEDGED_SPREAD, 25);
        SortedMap<String, Integer> und = new TreeMap<>();
        und.put("NIFTY", 70);
        und.put("BANKNIFTY", 30);
        return new SeedConfig(seed, accounts, asOf, new BigDecimal("0.12"), 2_500, mix, und);
    }
}
