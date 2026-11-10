package riskengine.shard;

/**
 * @param rebuildCycleTicks    K: on each tick the shard rebuilds, from positions, the accounts whose index
 *                             satisfies {@code index % K == tick_seq % K}, checks their incremental values
 *                             against the rebuild, then continues from the rebuilt values. Every account is
 *                             rebuilt once every K ticks, so rounding error can never build up for longer,
 *                             and the work is spread evenly: no tick rebuilds the whole shard.
 * @param driftToleranceRupees largest allowed difference in any vector component at that check
 */
public record ShardConfig(int rebuildCycleTicks, double driftToleranceRupees) {
    public ShardConfig {
        if (rebuildCycleTicks < 1) throw new IllegalArgumentException("rebuildCycleTicks must be >= 1");
        if (!(driftToleranceRupees > 0)) throw new IllegalArgumentException("driftToleranceRupees must be positive");
    }

    /** A 100-tick cycle; anything over one paisa of drift is a bug, not rounding. */
    public static ShardConfig defaults() {
        return new ShardConfig(100, 0.01);
    }
}
