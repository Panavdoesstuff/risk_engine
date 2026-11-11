package riskengine.margin;

import java.math.BigDecimal;
import java.util.List;
import java.util.SortedMap;
import riskengine.ref.Instrument;

/**
 * One account as an engine sees it at the current market. {@link AccountRisk} and {@link LegPlanner}
 * decide what to do through this interface; each engine answers the questions with its own margin maths
 * (the shard adjusts a copy of its vectors, the reference rebuilds from positions).
 */
public interface AccountView {
    SortedMap<String, Position> positions();

    Instrument instrument(String instrumentId);

    /**
     * Margin if the given signed quantities (by instrument) filled right now at the mark, on top of the
     * real positions. An empty map is the account as it stands. Null if something cannot be priced yet.
     * User reservations are NOT included: they are orders the user hopes for, not risk the account has.
     */
    MarginResult margin(SortedMap<String, Long> deltas);

    /** The engine's current price for the instrument, rounded to the paisa. */
    BigDecimal mark(String instrumentId);

    /** Cancels every reservation of the account's accepted user orders, emitting one OrderClosure each. */
    void cancelReservations(List<EngineOutput> out, long logTimeMillis);
}
