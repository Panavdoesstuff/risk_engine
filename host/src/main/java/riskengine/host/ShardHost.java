package riskengine.host;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import riskengine.events.EventLog;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.margin.MarginConfig;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentCsv;
import riskengine.shard.Partitioner;
import riskengine.shard.ShardConfig;
import riskengine.shard.ShardRunner;
import riskengine.shard.ShardState;

/**
 * Runs the engine on Redpanda: one {@link ShardRunner} thread per {@code engine-events} partition, each
 * with its own {@link ShardState} holding exactly the accounts that hash to that partition, each
 * publishing to the same-numbered {@code engine-out} partition. A host may run all the shards or only
 * some of them ({@link Options#only}), so a shard can be its own process.
 *
 * <p><b>Snapshots and recovery (M6).</b> With a {@link SnapshotStore}, each shard snapshots its state
 * every {@code snapshotEveryMillis} of <em>log</em> time (checked after each tick, on the shard's thread),
 * and on start each shard loads its newest valid snapshot and carries on from that offset. Without one,
 * or if no snapshot is valid, it replays its partition from offset 0. Either way the state it reaches is
 * the same, bit for bit; outputs after the snapshot offset are published again (engine-out is
 * at-least-once and its consumers dedupe).
 *
 * <pre>
 *   ./gradlew :host:run --args="--instruments data/processed/instruments-2024-06-03.csv --book data/seed"
 *   optional: --bootstrap localhost:19092  --partitions 4  --metrics-port 9100
 *             --snapshot-dir DIR  --snapshot-every-seconds 60  --snapshot-min-ticks 50  --only 0,2
 * </pre>
 */
public final class ShardHost implements AutoCloseable {

    /**
     * A shard snapshots after a tick when both hold: at least {@code snapshotEveryMillis} of log time and at
     * least {@code snapshotMinTicks} ticks since its last snapshot. Both are counted from the log, never
     * from a clock. The tick minimum matters in a replay of minute bars, where log time moves 60 s per
     * tick: without it, M = 60 s would mean a snapshot on every tick.
     *
     * @param only                shards to run, or null for all
     * @param snapshots           where snapshots live, or null for none (replay from 0 on every start)
     * @param snapshotEveryMillis log time between snapshots of one shard
     * @param snapshotMinTicks    ticks between snapshots of one shard, at least
     */
    public record Options(SortedSet<Integer> only, SnapshotStore snapshots, long snapshotEveryMillis, int snapshotMinTicks) {
        public static final int DEFAULT_MIN_TICKS = 50;

        public Options(SortedSet<Integer> only, SnapshotStore snapshots, long snapshotEveryMillis) {
            this(only, snapshots, snapshotEveryMillis, DEFAULT_MIN_TICKS);
        }

        public static Options defaults() {
            return new Options(null, null, 60_000, DEFAULT_MIN_TICKS);
        }
    }

    /**
     * How one shard started.
     *
     * @param fromOffset   offset it carried on from: the snapshot's, or 0
     * @param loadNanos    reading, checking and restoring the snapshot (0 without one)
     * @param skipped      snapshots found but refused (damaged, wrong config, ...), with why
     */
    public record Recovery(int shardId, long fromOffset, long snapshotBytes, long loadNanos, List<String> skipped) {}

    private final RedpandaLogs logs;
    private final EngineOutPublisher out;
    private final List<EventLog> partitionLogs = new ArrayList<>();
    private final List<ShardRunner> runners = new ArrayList<>();
    private final List<Recovery> recoveries = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<Thread> threads = new ArrayList<>();
    private final LagMonitor lag;
    private final TickMetrics tickMetrics;
    private final Options options;

    public ShardHost(String bootstrap, int partitions, MarginConfig cfg, ShardConfig shardCfg,
                     Collection<Instrument> instruments, Collection<AccountBook> books) {
        this(bootstrap, Topics.ENGINE_EVENTS, Topics.ENGINE_OUT, partitions, cfg, shardCfg, instruments, books);
    }

    public ShardHost(String bootstrap, String eventsTopic, String outTopic, int partitions, MarginConfig cfg, ShardConfig shardCfg,
                     Collection<Instrument> instruments, Collection<AccountBook> books) {
        this(bootstrap, eventsTopic, outTopic, partitions, cfg, shardCfg, instruments, books, Options.defaults());
    }

    public ShardHost(String bootstrap, String eventsTopic, String outTopic, int partitions, MarginConfig cfg, ShardConfig shardCfg,
                     Collection<Instrument> instruments, Collection<AccountBook> books, Options options) {
        this.options = options;
        this.logs = new RedpandaLogs(bootstrap, eventsTopic, partitions, Duration.ofMillis(100));
        this.out = new EngineOutPublisher(bootstrap, outTopic, partitions);
        List<List<AccountBook>> owned = new ArrayList<>();
        for (int p = 0; p < partitions; p++) owned.add(new ArrayList<>());
        for (AccountBook b : books) owned.get(Partitioner.partition(b.id(), partitions)).add(b);
        this.tickMetrics = new TickMetrics(partitions, (shard, tick) -> TickMetrics.Source.UNKNOWN);
        // Restore the shards side by side: each is independent, and at 100k accounts each load is a few
        // hundred milliseconds.
        List<Integer> mine = new ArrayList<>();
        for (int p = 0; p < partitions; p++) if (options.only() == null || options.only().contains(p)) mine.add(p);
        List<EventLog> logsByShard = new ArrayList<>();
        for (int p : mine) logsByShard.add(logs.partition(p));
        List<java.util.concurrent.Future<ShardState>> restoring = new ArrayList<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, mine.size()));
        try {
            for (int i = 0; i < mine.size(); i++) {
                int p = mine.get(i);
                EventLog log = logsByShard.get(i);
                restoring.add(pool.submit(() -> recover(p, log, cfg, shardCfg, instruments, owned.get(p))));
            }
        } finally {
            pool.shutdown();
        }
        for (int i = 0; i < mine.size(); i++) {
            int p = mine.get(i);
            int shardId = p;
            EventLog log = logsByShard.get(i);
            partitionLogs.add(log);
            ShardState state;
            try {
                state = restoring.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while restoring shard " + p, e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException("cannot start shard " + p, e.getCause());
            }
            ShardRunner runner = ShardRunner.withOffsets(state, log, (offset, o) -> {
                tickMetrics.output(shardId, o);
                out.publish(shardId, offset, o);
            });
            runners.add(runner);
            tickMetrics.activate(p);
            long[] lastSnapshotAt = {lastSnapshotTime(state)};
            long[] ticksSince = {0};
            runner.observe(e -> {
                tickMetrics.applied(shardId, e);
                if (options.snapshots() != null && e.event() instanceof Tick t) {
                    // Every M seconds of log time, after a tick: the state is between events here.
                    ticksSince[0]++;
                    if (lastSnapshotAt[0] == Long.MIN_VALUE) {
                        lastSnapshotAt[0] = t.logTimeMillis();
                    } else if (t.logTimeMillis() - lastSnapshotAt[0] >= options.snapshotEveryMillis() && ticksSince[0] >= options.snapshotMinTicks()) {
                        ticksSince[0] = 0;
                        long start = System.nanoTime();
                        ShardState.SnapshotImage image = state.capture(); // the only part on the shard's thread
                        tickMetrics.snapshotTaken(shardId, System.nanoTime() - start);
                        options.snapshots().saveAsync(image);
                        lastSnapshotAt[0] = t.logTimeMillis();
                    }
                }
            });
        }
        this.lag = new LagMonitor(bootstrap, eventsTopic, runners);
        // Tick-to-margin from each tick's record timestamp (when the producer sent it) to now.
        tickMetrics.measureFrom((shard, tick) -> {
            long sent = ((RedpandaEventLog) partitionLogs.get(indexOf(shard))).appendedAtMillis(tick.offset());
            return sent < 0 ? TickMetrics.Source.UNKNOWN : (System.currentTimeMillis() - sent) * 1_000_000L;
        });
        lag.include(tickMetrics);
        if (options.snapshots() != null) options.snapshots().onWritten((shard, bytes) -> tickMetrics.snapshotWritten(shard, bytes));
    }

    private int indexOf(int shardId) {
        for (int i = 0; i < runners.size(); i++) if (runners.get(i).state().shardId() == shardId) return i;
        throw new IllegalArgumentException("shard " + shardId + " is not run by this host");
    }

    /** A restored shard's last snapshot was taken at its latest tick; a fresh shard has none (Long.MIN_VALUE). */
    private static long lastSnapshotTime(ShardState s) {
        return s.latestTickLogTime();
    }

    /** The newest snapshot that is valid and not beyond the end of the partition; a fresh state if there is none. */
    private ShardState recover(int p, EventLog log, MarginConfig cfg, ShardConfig shardCfg, Collection<Instrument> instruments, List<AccountBook> books) {
        List<String> skipped = new ArrayList<>();
        SnapshotStore store = options.snapshots();
        if (store != null) {
            long end = log.endOffset();
            try {
                for (SnapshotStore.Saved s : store.list(p)) {
                    long start = System.nanoTime();
                    if (s.offset() > end) {
                        skipped.add(s.file().getFileName() + ": offset " + s.offset() + " is past the end of the partition (" + end + ")");
                        continue;
                    }
                    try {
                        ShardState state = ShardState.restore(store.read(s), cfg, shardCfg, instruments, books);
                        long nanos = System.nanoTime() - start;
                        recoveries.add(new Recovery(p, state.nextOffset(), s.bytes(), nanos, List.copyOf(skipped)));
                        System.out.printf("shard %d: restored from %s (offset %d, %,d bytes) in %.0f ms%s%n", p, s.file().getFileName(), state.nextOffset(),
                                s.bytes(), nanos / 1e6, skipped.isEmpty() ? "" : "; skipped " + skipped);
                        return state;
                    } catch (ShardState.SnapshotException e) {
                        skipped.add(s.file().getFileName() + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                skipped.add("cannot list snapshots: " + e.getMessage());
            }
        }
        recoveries.add(new Recovery(p, 0, 0, 0, List.copyOf(skipped)));
        if (store != null) System.out.println("shard " + p + ": no usable snapshot, replaying from offset 0" + (skipped.isEmpty() ? "" : "; skipped " + skipped));
        return new ShardState(p, cfg, shardCfg, instruments, books);
    }

    public void start() {
        if (!threads.isEmpty()) throw new IllegalStateException("already started");
        for (ShardRunner r : runners) threads.add(r.startLive());
    }

    /** How each shard started: from which snapshot, how long it took to load. */
    public List<Recovery> recoveries() {
        List<Recovery> r = new ArrayList<>(recoveries);
        r.sort(java.util.Comparator.comparingInt(Recovery::shardId));
        return r;
    }

    /** Per-shard lag, also served over HTTP by {@link LagMonitor#serve}. */
    public LagMonitor lag() {
        return lag;
    }

    /** Throughput and tick-to-margin latency, also served at /metrics. */
    public TickMetrics tickMetrics() {
        return tickMetrics;
    }

    /** The runners, in shard order (only the shards this host runs). */
    public List<ShardRunner> runners() {
        return List.copyOf(runners);
    }

    /** The first shard or publisher failure, or null while everything is healthy. */
    public Throwable failure() {
        for (ShardRunner r : runners) if (r.failure() != null) return r.failure();
        return out.failure();
    }

    /**
     * Waits until every shard has applied everything that was in its partition when this was called, and
     * the outputs are on the broker. For tests and controlled shutdown.
     */
    public void awaitCaughtUp(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int i = 0; i < runners.size(); i++) {
            long end = partitionLogs.get(i).endOffset();
            while (runners.get(i).appliedOffset() < end) {
                if (failure() != null) throw new IllegalStateException("shard host failed", failure());
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("shard " + runners.get(i).state().shardId() + " at offset " + runners.get(i).appliedOffset() + " of " + end + " after " + timeout);
                }
                Thread.sleep(10);
            }
        }
        out.flush();
        if (failure() != null) throw new IllegalStateException("shard host failed", failure());
    }

    /** Stops the shard threads and waits for them. After this their states are safe to read from any thread. */
    public void stop() throws InterruptedException {
        runners.forEach(ShardRunner::stop);
        for (Thread t : threads) t.join();
        out.flush();
    }

    @Override
    public void close() {
        runners.forEach(ShardRunner::stop);
        boolean interrupted = false;
        for (Thread t : threads) {
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        lag.close();
        partitionLogs.forEach(EventLog::close);
        out.close();
        logs.close();
        if (options.snapshots() != null) options.snapshots().close();
        if (interrupted) Thread.currentThread().interrupt();
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        TreeMap<String, String> f = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) break;
            f.put(args[i].substring(2), args[i + 1]);
        }
        if (args.length % 2 != 0 || f.size() * 2 != args.length || !f.containsKey("instruments") || !f.containsKey("book")) {
            System.err.println("usage: host --instruments FILE --book DIR [--bootstrap localhost:19092] [--partitions 4] [--metrics-port 9100]\n"
                    + "            [--snapshot-dir DIR] [--snapshot-every-seconds 60] [--snapshot-min-ticks 50] [--only 0,2]");
            System.exit(2);
        }
        String bootstrap = f.getOrDefault("bootstrap", "localhost:19092");
        int partitions = Integer.parseInt(f.getOrDefault("partitions", "4"));
        List<Instrument> instruments = InstrumentCsv.read(Path.of(f.get("instruments")));
        List<AccountBook> books = BookCsv.read(Path.of(f.get("book")));
        SortedSet<Integer> only = null;
        if (f.containsKey("only")) {
            only = new TreeSet<>();
            for (String s : f.get("only").split(",")) only.add(Integer.parseInt(s.trim()));
        }
        SnapshotStore store = f.containsKey("snapshot-dir") ? new SnapshotStore(Path.of(f.get("snapshot-dir")), 3) : null;
        Options options = new Options(only, store, (long) (Double.parseDouble(f.getOrDefault("snapshot-every-seconds", "60")) * 1000),
                Integer.parseInt(f.getOrDefault("snapshot-min-ticks", String.valueOf(Options.DEFAULT_MIN_TICKS))));
        ShardHost host = new ShardHost(bootstrap, Topics.ENGINE_EVENTS, Topics.ENGINE_OUT, partitions, MarginConfig.defaults(), ShardConfig.defaults(),
                instruments, books, options);
        Thread main = Thread.currentThread();
        // On SIGTERM: stop the shards, print their final state, then let the JVM exit. (The hook waits for
        // main, so the lines get out before the process ends. kill -9 skips all of this, which is the point.)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            main.interrupt();
            try {
                main.join(30_000);
            } catch (InterruptedException ignored) {
                // exiting anyway
            }
        }));
        try {
            host.start();
            int metricsPort = host.lag().serve(Integer.parseInt(f.getOrDefault("metrics-port", "9100")));
            System.out.println("shard host: lag metrics on :" + metricsPort + "/metrics");
            System.out.println("shard host: " + host.runners().size() + " of " + partitions + " shards, " + books.size() + " accounts, " + instruments.size()
                    + " instruments, reading " + bootstrap + (store == null ? "" : "; snapshots in " + store.dir() + " every " + options.snapshotEveryMillis() / 1000.0 + " s of log time and "
                    + options.snapshotMinTicks() + " ticks at least"));
            try {
                while (host.failure() == null) Thread.sleep(500);
                System.err.println("shard host stopping: " + host.failure());
                host.failure().printStackTrace();
                System.exit(1);
            } catch (InterruptedException stop) {
                host.stop();
                for (ShardRunner r : host.runners()) {
                    System.out.println("STATE shard=" + r.state().shardId() + " offset=" + r.state().nextOffset() + " hash=" + r.state().stateHash());
                }
                System.out.println("shard host: shut down");
            }
        } finally {
            host.close();
            System.out.flush();
        }
    }
}
