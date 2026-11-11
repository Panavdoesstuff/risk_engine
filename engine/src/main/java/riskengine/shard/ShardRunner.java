package riskengine.shard;

import java.util.List;
import java.util.function.Consumer;
import riskengine.events.EventLog;
import riskengine.events.LoggedEvent;
import riskengine.margin.EngineOutput;

/**
 * One thread, one partition, one {@link ShardState}. Reads the log in order and applies every event.
 * Nothing else touches the state, so there are no locks.
 *
 * <p>Two ways to run: {@link #runToEnd} replays whatever is in the log and returns (files, tests);
 * {@link #runUntilStopped} sits on a live partition and keeps reading as events arrive.
 */
public final class ShardRunner implements Runnable {
    private static final int BATCH = 1_000;

    private final ShardState state;
    private final EventLog log;
    private final OutputSink sink;
    // The only two things other threads may read while the shard runs.
    private volatile long appliedOffset;
    private volatile boolean stopRequested;
    private volatile Throwable failure;

    /** Receives everything the shard says, on the shard's thread, in log order. */
    @FunctionalInterface
    public interface OutputSink {
        /**
         * @param sourceOffset offset, in this shard's partition, of the event that caused the output. It
         *                     is the same on every replay of the log, so consumers can use it to tell an
         *                     old output from a new one.
         */
        void accept(long sourceOffset, EngineOutput out);
    }

    public ShardRunner(ShardState state, EventLog log, Consumer<EngineOutput> sink) {
        this(state, log, (OutputSink) (offset, out) -> sink.accept(out), true);
    }

    /** Like the constructor, for a sink that also wants each output's source offset. */
    public static ShardRunner withOffsets(ShardState state, EventLog log, OutputSink sink) {
        return new ShardRunner(state, log, sink, true);
    }

    private ShardRunner(ShardState state, EventLog log, OutputSink sink, boolean unused) {
        this.state = state;
        this.log = log;
        this.sink = sink;
        this.appliedOffset = state.nextOffset();
    }

    /** Applies everything from the state's next offset to the current end of the log. */
    public long runToEnd() {
        while (true) {
            List<LoggedEvent> batch = log.read(state.nextOffset(), BATCH);
            if (batch.isEmpty()) return state.nextOffset();
            applyAll(batch);
        }
    }

    /**
     * Keeps applying events until {@link #stop()} is called. An empty read is not the end: the log's
     * {@code read} is expected to wait a moment for new events (the Redpanda log does).
     *
     * <p>If an event cannot be applied the shard stops for good and {@link #failure()} says why. It never
     * skips an event: a shard that guessed would hold a state nobody can reproduce.
     */
    public void runUntilStopped() {
        try {
            while (!stopRequested) applyAll(log.read(state.nextOffset(), BATCH));
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        }
    }

    private void applyAll(List<LoggedEvent> batch) {
        for (LoggedEvent e : batch) {
            for (EngineOutput out : state.apply(e)) sink.accept(e.offset(), out);
            appliedOffset = state.nextOffset();
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

    public Thread startLive() {
        Thread t = new Thread(this::runUntilStopped, "shard-" + state.shardId());
        t.start();
        return t;
    }

    public void stop() {
        stopRequested = true;
    }

    /** Offset of the next event this shard will apply. Safe to read from any thread (lag = log end - this). */
    public long appliedOffset() {
        return appliedOffset;
    }

    /** Why a live shard stopped, or null if it has not failed. */
    public Throwable failure() {
        return failure;
    }

    /** The shard's state. Only its own thread may touch it while it runs. */
    public ShardState state() {
        return state;
    }
}
