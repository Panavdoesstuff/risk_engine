package riskengine.margin;

/** Declaration order = severity. */
public enum MarginLevel {
    OK, WARNING, MARGIN_CALL, SQUARE_OFF;

    /** The margin_events.action written when an account moves INTO this level. */
    public String action() {
        return switch (this) {
            case OK -> "CLEAR";
            case WARNING -> "WARN";
            case MARGIN_CALL -> "MARGIN_CALL";
            case SQUARE_OFF -> "SQUARE_OFF";
        };
    }

    /** accounts.status for this level (v2 has no WARNING status; a warning is only an event). */
    public String accountStatus() {
        return switch (this) {
            case OK, WARNING -> "ACTIVE";
            case MARGIN_CALL -> "MARGIN_CALL";
            case SQUARE_OFF -> "SQUARING_OFF";
        };
    }
}
