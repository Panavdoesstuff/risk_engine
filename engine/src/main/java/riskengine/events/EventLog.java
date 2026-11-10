package riskengine.events;

import java.util.List;

/**
 * An append-only, totally ordered log of events: the engine's only input. One log = one partition = one
 * shard. In M2 and M3 this is in-memory or a local file; in M4 a Redpanda partition implements the same
 * interface, so engine code doesn't change.
 */
public interface EventLog extends AutoCloseable {

    /** Appends and returns the event's offset. */
    long append(Event e);

    /** Up to {@code max} events starting at {@code fromOffset}, in order. Empty at the end of the log. */
    List<LoggedEvent> read(long fromOffset, int max);

    /** Offset the next appended event will get (= number of events in the log). */
    long endOffset();

    @Override
    void close();
}
