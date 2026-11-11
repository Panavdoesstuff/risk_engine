package riskengine.events;

/**
 * A request to trade, validated by the API and waiting for the shard's pre-trade margin check (M4).
 *
 * @param qty              units, always positive; {@code side} gives the direction
 * @param origin           USER or SQUARE_OFF (orders.origin)
 * @param logTimeMillis    the order's time in the log: the replay's "now" when it was written (the latest
 *                         tick's log time). Everything the shard does with the order uses this.
 * @param receivedAtMillis wall-clock time the API received it, kept as metadata for tracing only.
 *                         The shard never reads it: it is in no output, no decision and not in the state
 *                         hash. -1 if unknown (orders written before it existed, tests).
 */
public record OrderRequest(String orderId, String accountId, String instrumentId, Side side, long qty,
                           Origin origin, long logTimeMillis, long receivedAtMillis) implements Event, AccountEvent {
    public enum Side { BUY, SELL }

    public enum Origin { USER, SQUARE_OFF }

    public static final long UNKNOWN = -1;

    public OrderRequest {
        Event.id("orderId", orderId);
        Event.id("accountId", accountId);
        Event.id("instrumentId", instrumentId);
        java.util.Objects.requireNonNull(side);
        java.util.Objects.requireNonNull(origin);
        if (qty <= 0) throw new IllegalArgumentException("order qty must be positive");
    }

    /** An order with no wall-clock receipt time (tests, harnesses, and lines written before M6). */
    public OrderRequest(String orderId, String accountId, String instrumentId, Side side, long qty, Origin origin, long logTimeMillis) {
        this(orderId, accountId, instrumentId, side, qty, origin, logTimeMillis, UNKNOWN);
    }
}
