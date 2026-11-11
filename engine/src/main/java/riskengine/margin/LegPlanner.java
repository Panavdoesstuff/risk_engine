package riskengine.margin;

import java.util.SortedMap;
import java.util.TreeMap;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentType;

/**
 * Chooses the next leg of a square-off. Greedy, one leg at a time; the account is looked at afresh after
 * every leg, because each closed position changes what the rest are worth to close.
 *
 * <ol>
 *   <li><b>What to close.</b> Short options first, then futures, then long options. Within a class, the
 *       position whose closing frees the most required margin per lot. (Short options carry the
 *       open-ended risk and the short-option minimum, so they come first even when a future would free
 *       more.) Ties go to the lower instrument id.</li>
 *   <li><b>How much.</b> AUTO: the fewest lots that bring utilisation back under the target, or the
 *       whole position if that is not enough. MANUAL: the whole position.</li>
 * </ol>
 *
 * In an AUTO episode a position is only a candidate if closing a lot frees margin. A long option frees
 * none (it needs none), so it is never sold to meet a margin call.
 */
public final class LegPlanner {
    /** Two releases closer than this (relative) are a tie, so rounding dust cannot choose the instrument. */
    private static final double TIE = 1e-9;
    /** A lot must free at least this many rupees to count as freeing margin. */
    private static final double MIN_RELEASE = 0.01;

    private LegPlanner() {}

    /** @param qty signed closing quantity */
    public record Leg(String instrumentId, long qty, double expectedRelease) {}

    /**
     * @param target utilisation an AUTO episode is trying to get under
     * @return the next leg, or null if there is nothing (useful) left to close
     */
    public static Leg plan(AccountView account, AccountRisk.Mode mode, double target) {
        MarginResult now = account.margin(new TreeMap<>());
        if (now == null) return null;
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        double bestRelease = 0;
        for (var e : account.positions().entrySet()) { // sorted by instrument id
            Instrument i = account.instrument(e.getKey());
            long qty = e.getValue().qty();
            if (qty == 0) continue;
            MarginResult after = account.margin(delta(i.id(), -Long.signum(qty) * Math.min(i.lotSize(), Math.abs(qty))));
            if (after == null) continue;
            double release = now.required() - after.required();
            if (mode == AccountRisk.Mode.AUTO && release < MIN_RELEASE) continue;
            int rank = i.type() == InstrumentType.FUT ? 1 : qty < 0 ? 0 : 2;
            boolean better = best == null || rank < bestRank
                    || (rank == bestRank && release > bestRelease + TIE * Math.max(Math.abs(release), Math.abs(bestRelease)));
            if (better) {
                best = i.id();
                bestRank = rank;
                bestRelease = release;
            }
        }
        if (best == null) return null;

        long held = account.positions().get(best).qty();
        long closeAll = -held;
        if (mode == AccountRisk.Mode.MANUAL) {
            return new Leg(best, closeAll, now.required() - account.margin(delta(best, closeAll)).required());
        }
        int lot = account.instrument(best).lotSize();
        long step = -Long.signum(held) * (long) lot;
        for (long close = step; Math.abs(close) < Math.abs(held); close += step) {
            MarginResult after = account.margin(delta(best, close));
            if (after.utilisation() < target) return new Leg(best, close, now.required() - after.required());
        }
        return new Leg(best, closeAll, now.required() - account.margin(delta(best, closeAll)).required());
    }

    private static SortedMap<String, Long> delta(String instrumentId, long qty) {
        SortedMap<String, Long> m = new TreeMap<>();
        m.put(instrumentId, qty);
        return m;
    }
}
