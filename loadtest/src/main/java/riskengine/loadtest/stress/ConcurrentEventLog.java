package riskengine.loadtest.stress;

import java.util.ArrayList;
import java.util.List;
import riskengine.events.Event;
import riskengine.events.EventLog;
import riskengine.events.LoggedEvent;

/**
 * An in-memory log that many threads may append to while another reads: a stand-in for a partition when
 * the harness runs without a broker. The order in which appends win the lock is the log's order, exactly
 * as the order in which a broker receives records is a partition's order.
 */
final class ConcurrentEventLog implements EventLog {
    private final List<Event> events = new ArrayList<>();

    @Override
    public synchronized long append(Event e) {
        events.add(e);
        notifyAll();
        return events.size() - 1L;
    }

    /** Like a live partition: when there is nothing new, waits a moment and returns an empty list. */
    @Override
    public synchronized List<LoggedEvent> read(long fromOffset, int max) {
        if (fromOffset < 0 || fromOffset > events.size()) throw new IllegalArgumentException("offset " + fromOffset + " out of range");
        if (fromOffset == events.size()) {
            try {
                wait(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        int end = (int) Math.min(events.size(), fromOffset + (long) max);
        List<LoggedEvent> out = new ArrayList<>(Math.max(0, end - (int) fromOffset));
        for (int i = (int) fromOffset; i < end; i++) out.add(new LoggedEvent(i, events.get(i)));
        return out;
    }

    @Override
    public synchronized long endOffset() {
        return events.size();
    }

    @Override
    public void close() {}
}
