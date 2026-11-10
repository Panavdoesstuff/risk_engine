package riskengine.replayer.seed;

/**
 * SplitMix64 (Steele, Lea, Flood 2014). Hand-rolled instead of java.util.Random so the sequence is
 * defined by ten lines of code we own, not by a JDK implementation detail.
 */
final class SplitMix64 {
    private long state;

    SplitMix64(long seed) {
        this.state = seed;
    }

    /** Independent stream per account, so adding accounts or changing one archetype never shifts the others. */
    static SplitMix64 forAccount(long seed, int accountIndex) {
        return new SplitMix64(new SplitMix64(seed ^ (0x5DEECE66DL * accountIndex)).nextLong());
    }

    long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform in [lo, hi], both inclusive. Rejection sampling, so no modulo bias. */
    int nextInt(int lo, int hi) {
        if (hi < lo) throw new IllegalArgumentException(lo + " > " + hi);
        long bound = (long) hi - lo + 1;
        long limit = Long.MAX_VALUE - (Long.MAX_VALUE % bound);
        long r;
        do {
            r = nextLong() >>> 1;
        } while (r >= limit);
        return (int) (lo + r % bound);
    }

    boolean chance(int basisPoints) {
        return nextInt(0, 9_999) < basisPoints;
    }
}
