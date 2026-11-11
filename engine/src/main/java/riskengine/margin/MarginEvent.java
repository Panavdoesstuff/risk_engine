package riskengine.margin;

/**
 * Emitted when an account's level changes. Maps onto a margin_events row.
 *
 * @param tickSeq             the tick it happened on, or the latest tick if a fill caused it
 * @param result              the account's actual margin at that moment
 * @param expectedUtilisation utilisation once the square-off leg in flight has filled; this, not the
 *                            actual figure, is what the level is decided on. Equal to actual when no leg is in flight.
 */
public record MarginEvent(String accountId, long tickSeq, long logTimeMillis, MarginResult result,
                          MarginLevel from, MarginLevel to, double expectedUtilisation) implements EngineOutput {
    public String action() {
        return to.action();
    }
}
