package riskengine.margin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Ids already applied (broker fill ids, order ids), remembered for a window of log time so that a
 * duplicate is ignored, and then forgotten so the set does not grow for ever.
 *
 * <p>Deterministic: each id is stamped with a log time given by the caller (the latest tick's log time),
 * and pruning happens on ticks with a cutoff that is also log time, so two replays of the same log keep
 * and drop exactly the same ids. The ids and their times are part of the shard's state hash and its
 * snapshots. Shared by the shard and the reference engine so they forget at exactly the same moment.
 *
 * <p>The window has to be far longer than any real duplicate: a broker's repeated callback arrives
 * within seconds, an API retry within minutes. A duplicate that arrives after the window would be applied
 * a second time.
 */
public final class SeenIds {
    /** Two days of log time. */
    public static final long DEFAULT_WINDOW_MILLIS = 2L * 24 * 60 * 60 * 1000;

    private final TreeMap<String, Long> seenAt = new TreeMap<>();
    private final TreeMap<Long, List<String>> byTime = new TreeMap<>();

    /** @return true if the id is new (and is now remembered as seen at {@code logTime}); false if it is a duplicate */
    public boolean add(String id, long logTime) {
        if (seenAt.containsKey(id)) return false;
        seenAt.put(id, logTime);
        byTime.computeIfAbsent(logTime, k -> new ArrayList<>()).add(id);
        return true;
    }

    /** Forgets every id seen before {@code cutoff} (log time). @return how many were forgotten */
    public int pruneBefore(long cutoff) {
        int removed = 0;
        while (!byTime.isEmpty() && byTime.firstKey() < cutoff) {
            for (String id : byTime.pollFirstEntry().getValue()) {
                seenAt.remove(id);
                removed++;
            }
        }
        return removed;
    }

    public boolean contains(String id) {
        return seenAt.containsKey(id);
    }

    public int size() {
        return seenAt.size();
    }

    /** Every id with the log time it was seen at, by id. A copy. */
    public SortedMap<String, Long> asMap() {
        return new TreeMap<>(seenAt);
    }

    /** Ids and times in id order, for snapshots and the state hash. */
    public String[] ids() {
        return seenAt.keySet().toArray(new String[0]);
    }

    public long[] times() {
        long[] t = new long[seenAt.size()];
        int i = 0;
        for (Map.Entry<String, Long> e : seenAt.entrySet()) t[i++] = e.getValue();
        return t;
    }
}
