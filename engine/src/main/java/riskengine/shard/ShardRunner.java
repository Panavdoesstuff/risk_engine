package riskengine.shard;

import java.util.List;
import java.util.function.Consumer;
import riskengine.events.EventLog;
import riskengine.events.LoggedEvent;
import riskengine.margin.MarginEvent;

/**
 * One thread, one partition, one {@link ShardState}. Reads the log in order and applies every event.
 * Nothing else touches the state, so there are no locks.
 *
 * <p>In M3 a runner replays whatever is in its log and stops. From M4 the same loop sits on a live
 * Redpanda partition.
 */
public final class ShardRunner implements Runnable {
    private static final int BATCH = 1_000;

    private final ShardState state;
    private final EventLog log;
    private final Consumer<MarginEvent> sink;

    /** @param sink receives level changes on the shard's thread, in log order */
    public ShardRunner(ShardState state, EventLog log, Consumer<MarginEvent> sink) {
        this.state = state;
        this.log = log;
        this.sink = sink;
    }

    /** Applies everything from the state's next offset to the current end of the log. */
    public long runToEnd() {
        while (true) {
            List<LoggedEvent> batch = log.read(state.nextOffset(), BATCH);
            if (batch.isEmpty()) return state.nextOffset();
            for (LoggedEvent e : batch) state.apply(e).forEach(sink);
        }
    }

    @Override
    public void run() {
        runToEnd();
    }

    public Thread start() {
        Thread t = new Thread(this, "shard-" + state.shardId());
        t.start();
        return t;
    }

    public ShardState state() {
        return state;
    }
}
