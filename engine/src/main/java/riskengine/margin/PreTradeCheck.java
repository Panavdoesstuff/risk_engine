package riskengine.margin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.SortedMap;
import java.util.TreeMap;
import riskengine.events.OrderRequest;
import riskengine.ref.Instrument;

/**
 * The rules of the pre-trade margin check, shared by the reference engine and the shards. The two engines
 * each compute the "what if this filled" margin their own way (rebuild vs. adjust a copy of the vector);
 * this class holds the parts that must be identical: the rule itself and the bookkeeping around it.
 *
 * <p><b>The rule.</b> Take the account as it would be if this order, and every earlier accepted order that
 * has not filled yet, filled right now at the current mark. Accept if
 * <ul>
 *   <li>utilisation would be below the margin-call threshold and available margin would not be negative; or</li>
 *   <li>the order improves the account's excess (available - required) by at least one paisa. That lets
 *       an account that is already over its limit still close or hedge positions, and nothing else.</li>
 * </ul>
 *
 * <p><b>Reservations.</b> An accepted order holds its margin until fills arrive for it. Without that, two
 * orders sent back to back would each pass against the same free margin.
 */
public final class PreTradeCheck {
    /**
     * How much an order must improve the excess of an account that is over its limit, in rupees. Not zero
     * on purpose: an order that changes nothing (buying an option whose mark is 0.00) would otherwise be
     * accepted or rejected by floating-point dust, and the two engines could disagree.
     */
    public static final double MIN_IMPROVEMENT_RUPEES = 0.01;

    private PreTradeCheck() {}

    /** Reasons to reject before any margin maths. Null means the order can be priced and checked. */
    public static OrderDecision.Reason structuralReject(Instrument instrument, MarginConfig cfg, OrderRequest o) {
        if (instrument == null || !cfg.underlyings().containsKey(instrument.underlying())) return OrderDecision.Reason.UNKNOWN_INSTRUMENT;
        if (o.qty() % instrument.lotSize() != 0) return OrderDecision.Reason.BAD_LOT_SIZE;
        return null;
    }

    public static long signedQty(OrderRequest o) {
        return o.side() == OrderRequest.Side.BUY ? o.qty() : -o.qty();
    }

    /**
     * The price a what-if fill is assumed to happen at: the engine's current mark, rounded to the paisa.
     * Converted exactly from the double's bits (never through its text form), so it is the same everywhere.
     */
    public static BigDecimal mark(double price) {
        return new BigDecimal(price).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Net signed quantity per instrument of all reserved orders plus, optionally, one more order. Sorted. */
    public static SortedMap<String, Long> netDeltas(Collection<PendingOrder> pending, String extraInstrumentId, long extraQty) {
        SortedMap<String, Long> deltas = new TreeMap<>();
        for (PendingOrder p : pending) deltas.merge(p.instrumentId(), p.remaining(), Long::sum);
        if (extraInstrumentId != null) deltas.merge(extraInstrumentId, extraQty, Long::sum);
        return deltas;
    }

    /**
     * @param before the account with its existing reservations filled, without the new order
     * @param after  the same, plus the new order
     */
    public static boolean passes(MarginResult before, MarginResult after, Thresholds t) {
        if (after.available() >= 0 && after.utilisation() < t.marginCall()) return true;
        return (after.available() - after.required()) - (before.available() - before.required()) >= MIN_IMPROVEMENT_RUPEES;
    }

    /**
     * What is left of a reservation after a fill. A fill in the opposite direction leaves it alone; a fill
     * for more than what is left ends it (0).
     */
    public static long remainingAfterFill(long remaining, long fillQty) {
        if (Long.signum(remaining) != Long.signum(fillQty)) return remaining;
        long left = remaining - fillQty;
        return Long.signum(left) == Long.signum(remaining) ? left : 0;
    }
}
