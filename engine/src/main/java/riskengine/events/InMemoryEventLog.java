package riskengine.events;

import java.util.ArrayList;
import java.util.List;

/** For tests and single-process runs. Not thread-safe: one writer, one reader, same thread. */
public final class InMemoryEventLog implements EventLog {
    private final List<Event> events = new ArrayList<>();

    @Override
    public long append(Event e) {
        events.add(e);
        return events.size() - 1L;
    }

    @Override
    public List<LoggedEvent> read(long fromOffset, int max) {
        if (fromOffset < 0 || fromOffset > events.size()) throw new IllegalArgumentException("offset " + fromOffset + " out of range");
        int end = (int) Math.min(events.size(), fromOffset + (long) max);
        List<LoggedEvent> out = new ArrayList<>(end - (int) fromOffset);
        for (int i = (int) fromOffset; i < end; i++) out.add(new LoggedEvent(i, events.get(i)));
        return out;
    }

    @Override
    public long endOffset() {
        return events.size();
    }

    @Override
    public void close() {}
}
