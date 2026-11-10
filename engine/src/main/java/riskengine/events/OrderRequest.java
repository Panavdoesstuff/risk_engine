package riskengine.events;

/**
 * A request to trade, validated by the API and waiting for the shard's pre-trade margin check (M4).
 *
 * @param qty    units, always positive; {@code side} gives the direction
 * @param origin USER or SQUARE_OFF (orders.origin)
 */
public record OrderRequest(String orderId, String accountId, String instrumentId, Side side, long qty,
                           Origin origin, long logTimeMillis) implements Event, AccountEvent {
    public enum Side { BUY, SELL }

    public enum Origin { USER, SQUARE_OFF }

    public OrderRequest {
        Event.id("orderId", orderId);
        Event.id("accountId", accountId);
        Event.id("instrumentId", instrumentId);
        java.util.Objects.requireNonNull(side);
        java.util.Objects.requireNonNull(origin);
        if (qty <= 0) throw new IllegalArgumentException("order qty must be positive");
    }
}
