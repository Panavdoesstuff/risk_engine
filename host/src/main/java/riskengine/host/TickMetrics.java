package riskengine.host;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongConsumer;
import riskengine.events.LoggedEvent;
import riskengine.events.Tick;

/**
 * Throughput and tick-to-margin latency per shard, in the Prometheus text format.
 *
 * <p><b>Tick-to-margin</b> is measured from the moment the tick was handed to the producer (the record's
 * timestamp in the partition) to the moment the shard has applied it: every holder repriced, every
 * affected account's margin and level recomputed, every output handed to the engine-out publisher. It
 * includes the trip through the broker and any time the tick waited behind other events in the
 * partition. It does not include publishing the outputs or writing them to Postgres.
 *
 * <p>The clock is read here, on the shard's thread, after the shard is done; the shard itself never reads it.
 */
public final class TickMetrics {
    /** Upper bounds of the latency histogram's buckets, in seconds. */
    static final double[] BUCKETS = {0.0005, 0.001, 0.002, 0.003, 0.005, 0.0075, 0.01, 0.015, 0.02, 0.03, 0.05, 0.075, 0.1, 0.15, 0.25, 0.5, 1, 2.5, 5};

    /** Where a tick's latency is measured from. */
    @FunctionalInterface
    public interface Source {
        /** Returned when the send time is not known: the tick is not measured. */
        long UNKNOWN = Long.MIN_VALUE;

        /**
         * Nanoseconds since the tick was sent, or {@link #UNKNOWN}. May be negative when the sender's clock
         * is ahead of this process's (a tick written from another machine, or from the host into a VM).
         */
        long sinceSentNanos(int shard, LoggedEvent tick);
    }

    private final int shards;
    private final LongAdder[][] buckets;
    private final LongAdder[] latencyCount, latencySumNanos, ticks, events, clockSkew;
    private volatile Source source;
    private volatile LongConsumer[] listeners = new LongConsumer[0];
    private final boolean[] active;
    private final LongAdder[] snapshots, snapshotNanos;
    private final java.util.concurrent.atomic.AtomicLongArray snapshotBytes;

    public TickMetrics(int shards, Source source) {
        this.shards = shards;
        this.source = source;
        buckets = new LongAdder[shards][BUCKETS.length];
        latencyCount = adders(shards);
        latencySumNanos = adders(shards);
        ticks = adders(shards);
        events = adders(shards);
        clockSkew = adders(shards);
        snapshots = adders(shards);
        snapshotNanos = adders(shards);
        snapshotBytes = new java.util.concurrent.atomic.AtomicLongArray(shards);
        active = new boolean[shards];
        for (LongAdder[] b : buckets) for (int i = 0; i < b.length; i++) b[i] = new LongAdder();
    }

    private static LongAdder[] adders(int n) {
        LongAdder[] a = new LongAdder[n];
        for (int i = 0; i < n; i++) a[i] = new LongAdder();
        return a;
    }

    /** Marks a shard as run by this process: only active shards are reported. */
    void activate(int shard) {
        active[shard] = true;
    }

    /** Called on the shard's thread after it captured its state for a snapshot. */
    void snapshotTaken(int shard, long nanos) {
        snapshots[shard].increment();
        snapshotNanos[shard].add(nanos);
    }

    /** Called by the snapshot writer once a shard's snapshot is on disk. */
    void snapshotWritten(int shard, long bytes) {
        snapshotBytes.set(shard, bytes);
    }

    public long snapshotsTaken(int shard) {
        return snapshots[shard].sum();
    }

    /** Total time shard {@code shard} spent capturing its state for snapshots, on its own thread. */
    public long snapshotNanos(int shard) {
        return snapshotNanos[shard].sum();
    }

    public long lastSnapshotBytes(int shard) {
        return snapshotBytes.get(shard);
    }

    /** Replaces where latency is measured from (a load test that knows exactly when it sent each tick). */
    public void measureFrom(Source s) {
        this.source = s;
    }

    /** Also hands every latency sample (nanoseconds, any shard) to {@code l}, for exact percentiles. */
    public synchronized void listen(LongConsumer l) {
        LongConsumer[] now = java.util.Arrays.copyOf(listeners, listeners.length + 1);
        now[now.length - 1] = l;
        listeners = now;
    }

    /** Called on shard {@code shard}'s thread after it applied {@code e}. */
    void applied(int shard, LoggedEvent e) {
        events[shard].increment();
        if (!(e.event() instanceof Tick)) return;
        ticks[shard].increment();
        long nanos = source.sinceSentNanos(shard, e);
        if (nanos == Source.UNKNOWN) return;
        if (nanos < 0) {
            // The sender's clock is ahead of ours by more than the latency. Count it as 0 rather than drop
            // it (dropping silently left fast ticks out of the histogram and biased it upwards) and say so.
            clockSkew[shard].increment();
            nanos = 0;
        }
        double seconds = nanos / 1e9;
        for (int i = 0; i < BUCKETS.length; i++) {
            if (seconds <= BUCKETS[i]) {
                buckets[shard][i].increment();
                break;
            }
        }
        latencyCount[shard].increment();
        latencySumNanos[shard].add(nanos);
        for (LongConsumer l : listeners) l.accept(nanos);
    }

    public long ticksApplied(int shard) {
        return ticks[shard].sum();
    }

    /** Appends the metrics to {@code sb}. */
    void write(StringBuilder sb) {
        sb.append("# HELP risk_tick_to_margin_seconds From a tick being sent to the shard having recomputed every affected account.\n");
        sb.append("# TYPE risk_tick_to_margin_seconds histogram\n");
        for (int s = 0; s < shards; s++) {
            if (!active[s]) continue;
            long cumulative = 0;
            for (int i = 0; i < BUCKETS.length; i++) {
                cumulative += buckets[s][i].sum();
                sb.append("risk_tick_to_margin_seconds_bucket{shard=\"").append(s).append("\",le=\"").append(BUCKETS[i]).append("\"} ").append(cumulative).append('\n');
            }
            long count = latencyCount[s].sum();
            sb.append("risk_tick_to_margin_seconds_bucket{shard=\"").append(s).append("\",le=\"+Inf\"} ").append(count).append('\n');
            sb.append("risk_tick_to_margin_seconds_sum{shard=\"").append(s).append("\"} ").append(latencySumNanos[s].sum() / 1e9).append('\n');
            sb.append("risk_tick_to_margin_seconds_count{shard=\"").append(s).append("\"} ").append(count).append('\n');
        }
        counter(sb, "risk_ticks_applied_total", "Ticks the shard has applied.", ticks);
        counter(sb, "risk_events_applied_total", "Events of any kind the shard has applied.", events);
        counter(sb, "risk_tick_clock_skew_total", "Ticks whose sender's clock was ahead of the shard host's: measured as 0 latency.", clockSkew);
        counter(sb, "risk_snapshots_total", "Snapshots the shard has taken.", snapshots);
        sb.append("# HELP risk_snapshot_bytes Size of the shard's last snapshot.\n# TYPE risk_snapshot_bytes gauge\n");
        for (int s = 0; s < shards; s++) if (active[s]) sb.append("risk_snapshot_bytes{shard=\"").append(s).append("\"} ").append(snapshotBytes.get(s)).append('\n');
        // The JVM, for reading latency spikes against GC.
        long gcCount = 0, gcMillis = 0;
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gc : gcs) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMillis += Math.max(0, gc.getCollectionTime());
        }
        sb.append("# HELP risk_jvm_gc_collections_total Garbage collections in the shard host.\n# TYPE risk_jvm_gc_collections_total counter\n");
        sb.append("risk_jvm_gc_collections_total ").append(gcCount).append('\n');
        sb.append("# HELP risk_jvm_gc_seconds_total Time spent in garbage collection (includes concurrent phases for some collectors).\n# TYPE risk_jvm_gc_seconds_total counter\n");
        sb.append("risk_jvm_gc_seconds_total ").append(gcMillis / 1000.0).append('\n');
        sb.append("# HELP risk_jvm_heap_used_bytes Heap in use.\n# TYPE risk_jvm_heap_used_bytes gauge\n");
        sb.append("risk_jvm_heap_used_bytes ").append(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()).append('\n');
    }

    private void counter(StringBuilder sb, String name, String help, LongAdder[] values) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n').append("# TYPE ").append(name).append(" counter\n");
        for (int s = 0; s < shards; s++) if (active[s]) sb.append(name).append("{shard=\"").append(s).append("\"} ").append(values[s].sum()).append('\n');
    }
}
