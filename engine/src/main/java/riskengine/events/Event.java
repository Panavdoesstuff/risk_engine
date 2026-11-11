package riskengine.events;

/**
 * Something that happened, in log order. Every event carries its own log time: engine code never reads
 * the wall clock.
 *
 * <ul>
 *   <li>{@link Tick}: written to every partition by the replayer (the only tick writer).</li>
 *   <li>{@link OrderRequest}, {@link Fill}, {@link OrderClosed}, {@link SquareOffResult}: written to the owning account's
 *       partition only.</li>
 * </ul>
 */
public sealed interface Event permits Tick, OrderRequest, Fill, OrderClosed, SquareOffResult {
    long logTimeMillis();

    /** Fields are joined with '|' in the log, so identifiers must not contain it (or line breaks). */
    static String id(String name, String value) {
        if (value == null || value.isEmpty() || value.indexOf('|') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("bad " + name + ": " + value);
        }
        return value;
    }
}
