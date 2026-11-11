package riskengine.events;

/**
 * Not used. M5 reports a leg's outcome with the events every order uses: {@link Fill} when it executes and
 * {@link OrderClosed} when the broker rejects it. Shards accept this event and ignore it, so old logs still read.
 *
 * @param legKey accountId:episodeId:instrumentId:seq
 */
public record SquareOffResult(String legKey, String accountId, String instrumentId, Status status,
                              long logTimeMillis) implements Event, AccountEvent {
    public enum Status { FILLED, REJECTED, TIMED_OUT }

    public SquareOffResult {
        // legKey contains ':' separators, never '|'
        Event.id("legKey", legKey);
        Event.id("accountId", accountId);
        Event.id("instrumentId", instrumentId);
        java.util.Objects.requireNonNull(status);
    }
}
