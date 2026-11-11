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
 * What is fed in is expected utilisation (net of the square-off leg in flight), and while an episode is
 * open nothing is fed in at all: see {@link AccountRisk}. This class is only the threshold logic.
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

    /**
     * A reading that may only lower the level (a fill, or the end of a square-off episode). Going down is
     * immediate, as on a tick; going up needs consecutive ticks, so this never raises anything and never
     * adds to a streak.
     *
     * @return the level after this reading
     */
    public MarginLevel lower(double utilisation) {
        if (utilisation < t.squareOff()) squareOffStreak = 0;
        if (utilisation < t.marginCall()) callStreak = 0;
        MarginLevel reading = squareOffStreak >= t.confirmTicks() ? MarginLevel.SQUARE_OFF
                : callStreak >= t.confirmTicks() ? MarginLevel.MARGIN_CALL
                : utilisation >= t.warning() ? MarginLevel.WARNING
                : MarginLevel.OK;
        if (reading.compareTo(level) < 0) level = reading;
        return level;
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
