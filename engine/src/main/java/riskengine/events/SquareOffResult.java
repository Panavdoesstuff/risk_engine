package riskengine.events;

/**
 * Final outcome of one square-off leg, reported back by the broker side (used from M5).
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
