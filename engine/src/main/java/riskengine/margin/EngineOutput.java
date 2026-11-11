package riskengine.margin;

/**
 * Something an engine says to the outside world as a result of applying one event. Shards publish these
 * to the {@code engine-out} topic; nothing in the engine ever reads them back.
 */
public sealed interface EngineOutput permits MarginEvent, OrderDecision, PositionChange, OrderClosure, SquareOffLeg, EpisodeChange, AccountStatusChange {
    String accountId();
}
