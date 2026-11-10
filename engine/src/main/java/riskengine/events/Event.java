package riskengine.events;

/**
 * Something that happened, in log order. M2 has ticks only; M3 adds OrderRequest, Fill, SquareOffResult.
 * Every event carries its own log time: engine code never reads the wall clock.
 */
public sealed interface Event permits Tick {
    long logTimeMillis();
}
