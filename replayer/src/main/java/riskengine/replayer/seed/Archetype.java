package riskengine.replayer.seed;

public enum Archetype {
    /** 1-2 index futures, long or short. */
    FUTURES_ONLY,
    /** 1-3 long calls/puts near the money. Loss capped at premium. */
    OPTION_BUYER,
    /** Short ATM call + put, same strike. The book the crash day punishes. */
    SHORT_STRADDLE,
    /** Short OTM call + OTM put. */
    SHORT_STRANGLE,
    /** Credit spreads: bull put, bear call or iron condor. Risk is capped by the long wings. */
    HEDGED_SPREAD;

    boolean sellsNakedRisk() {
        return this == SHORT_STRADDLE || this == SHORT_STRANGLE;
    }
}
