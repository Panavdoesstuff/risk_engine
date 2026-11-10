package riskengine.margin;

/**
 * Turns a stream of utilisation readings (one per tick) into a level, for one account.
 *
 * <ul>
 *   <li>WARNING is immediate: it only informs.</li>
 *   <li>MARGIN_CALL and SQUARE_OFF need {@code confirmTicks} consecutive readings at or above their threshold,
 *       so one bad print can't trigger a square-off.</li>
 *   <li>Going down is immediate: the moment utilisation is back below a threshold, its streak resets.</li>
 * </ul>
 *
 * M5 replaces raw utilisation with expected utilisation (net of in-flight square-off legs) and makes
 * square-off sticky until the episode ends; this class stays the threshold logic.
 */
public final class MarginStatusTracker {
    private final Thresholds t;
    private int callStreak;
    private int squareOffStreak;
    private MarginLevel level = MarginLevel.OK;

    public MarginStatusTracker(Thresholds t) {
        this.t = t;
    }

    public MarginLevel level() {
        return level;
    }

    /** Consecutive readings at or above the margin-call threshold. Part of the shard's hashed state. */
    public int callStreak() {
        return callStreak;
    }

    public int squareOffStreak() {
        return squareOffStreak;
    }

    /** @return the level after this reading */
    public MarginLevel onReading(double utilisation) {
        callStreak = utilisation >= t.marginCall() ? callStreak + 1 : 0;
        squareOffStreak = utilisation >= t.squareOff() ? squareOffStreak + 1 : 0;
        if (squareOffStreak >= t.confirmTicks()) level = MarginLevel.SQUARE_OFF;
        else if (callStreak >= t.confirmTicks()) level = MarginLevel.MARGIN_CALL;
        else if (utilisation >= t.warning()) level = MarginLevel.WARNING;
        else level = MarginLevel.OK;
        return level;
    }
}
