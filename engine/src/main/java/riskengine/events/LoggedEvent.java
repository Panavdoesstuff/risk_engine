package riskengine.events;

/** An event and its position in the log. Offsets start at 0 and have no gaps. */
public record LoggedEvent(long offset, Event event) {}
