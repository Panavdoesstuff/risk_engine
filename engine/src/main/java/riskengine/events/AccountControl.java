package riskengine.events;

/**
 * A risk-ops instruction for one account, written to the account's partition by the API so that it takes
 * effect at a definite point in the log, in line with that account's ticks, orders and fills.
 *
 * @param actor     who asked, e.g. {@code risk-ops:bob}; carried through to the audit log
 * @param requestId the API request it came from
 */
public record AccountControl(String accountId, Action action, String actor, String requestId, long logTimeMillis) implements Event, AccountEvent {
    public enum Action {
        /** No new user orders. Positions and margining carry on. */
        BLOCK,
        /** Lifts a block, whether set by risk-ops or by a square-off that ran out of things to close. */
        UNBLOCK,
        /** Close every position of the account, one leg at a time. */
        SQUARE_OFF
    }

    public AccountControl {
        Event.id("accountId", accountId);
        java.util.Objects.requireNonNull(action);
        Event.id("actor", actor);
        Event.id("requestId", requestId);
    }
}
