package riskengine.margin;

/** Emitted when an account's level changes on a tick. Maps onto a margin_events row. */
public record MarginEvent(String accountId, long tickSeq, long logTimeMillis, MarginResult result,
                          MarginLevel from, MarginLevel to) {
    public String action() {
        return to.action();
    }
}
