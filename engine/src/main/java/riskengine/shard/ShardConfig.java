package riskengine.shard;

/**
 * @param fullRecomputeEveryTicks K: every K ticks the shard rebuilds every vector from positions, checks the
 *                                incremental values against the rebuild, then continues from the rebuilt
 *                                values (so rounding error can never build up for more than K ticks)
 * @param driftToleranceRupees    largest allowed difference in any vector component at that check
 */
public record ShardConfig(int fullRecomputeEveryTicks, double driftToleranceRupees) {
    public ShardConfig {
        if (fullRecomputeEveryTicks < 1) throw new IllegalArgumentException("fullRecomputeEveryTicks must be >= 1");
        if (!(driftToleranceRupees > 0)) throw new IllegalArgumentException("driftToleranceRupees must be positive");
    }

    /** Check every 100 ticks; anything over one paisa of drift is a bug, not rounding. */
    public static ShardConfig defaults() {
        return new ShardConfig(100, 0.01);
    }
}
