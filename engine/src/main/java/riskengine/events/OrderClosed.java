package riskengine.events;

/**
 * An accepted order is finished without (fully) filling: whatever margin it still had reserved is released.
 * Written to the account's partition by whoever learns the order's fate (the broker side, from M5).
 *
 * <p>Closing an order that has no reservation (already filled, never accepted, closed before) changes
 * nothing, so this event is safe to deliver more than once.
 */
public record OrderClosed(String orderId, String accountId, Reason reason, long logTimeMillis) implements Event, AccountEvent {
    public enum Reason {
        /** Still open when its validity ended (for example, end of day). */
        UNFILLED,
        /** The broker or exchange refused it after the engine had accepted it. */
        REJECTED,
        /** Cancelled by the user or by risk-ops. */
        CANCELLED
    }

    public OrderClosed {
        Event.id("orderId", orderId);
        Event.id("accountId", accountId);
        java.util.Objects.requireNonNull(reason);
    }
}
