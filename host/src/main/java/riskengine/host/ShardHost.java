package riskengine.host;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import riskengine.events.EventLog;
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
 * publishing to the same-numbered {@code engine-out} partition.
 *
 * <p>There are no snapshots until M6, so a host always starts its shards at offset 0 and replays the
 * whole log. That rebuilds identical state (same log, same hash) and publishes the same outputs again.
 *
 * <pre>
 *   ./gradlew :host:run --args="--instruments data/processed/instruments-2024-06-03.csv --book data/seed"
 *   optional: --bootstrap localhost:19092  --partitions 4  --metrics-port 9100
 * </pre>
 */
public final class ShardHost implements AutoCloseable {
    private final RedpandaLogs logs;
    private final EngineOutPublisher out;
    private final List<EventLog> partitionLogs = new ArrayList<>();
    private final List<ShardRunner> runners = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final LagMonitor lag;
    private final TickMetrics tickMetrics;

    public ShardHost(String bootstrap, int partitions, MarginConfig cfg, ShardConfig shardCfg,
                     Collection<Instrument> instruments, Collection<AccountBook> books) {
        this(bootstrap, Topics.ENGINE_EVENTS, Topics.ENGINE_OUT, partitions, cfg, shardCfg, instruments, books);
    }

    public ShardHost(String bootstrap, String eventsTopic, String outTopic, int partitions, MarginConfig cfg, ShardConfig shardCfg,
                     Collection<Instrument> instruments, Collection<AccountBook> books) {
        this.logs = new RedpandaLogs(bootstrap, eventsTopic, partitions, Duration.ofMillis(100));
        this.out = new EngineOutPublisher(bootstrap, outTopic, partitions);
        List<List<AccountBook>> owned = new ArrayList<>();
        for (int p = 0; p < partitions; p++) owned.add(new ArrayList<>());
        for (AccountBook b : books) owned.get(Partitioner.partition(b.id(), partitions)).add(b);
        for (int p = 0; p < partitions; p++) {
            int shardId = p;
            EventLog log = logs.partition(p);
            partitionLogs.add(log);
            runners.add(ShardRunner.withOffsets(new ShardState(p, cfg, shardCfg, instruments, owned.get(p)), log,
                    (offset, o) -> out.publish(shardId, offset, o)));
        }
        this.lag = new LagMonitor(bootstrap, eventsTopic, runners);
        // Tick-to-margin from each tick's record timestamp (when the producer sent it) to now.
        this.tickMetrics = new TickMetrics(partitions, (shard, tick) -> {
            long sent = ((RedpandaEventLog) partitionLogs.get(shard)).appendedAtMillis(tick.offset());
            return sent < 0 ? -1 : (System.currentTimeMillis() - sent) * 1_000_000L;
        });
        lag.include(tickMetrics);
        for (int p = 0; p < partitions; p++) {
            int shard = p;
            runners.get(p).observe(e -> tickMetrics.applied(shard, e));
        }
    }

    public void start() {
        if (!threads.isEmpty()) throw new IllegalStateException("already started");
        for (ShardRunner r : runners) threads.add(r.startLive());
    }

    /** Per-shard lag, also served over HTTP by {@link LagMonitor#serve}. */
    public LagMonitor lag() {
        return lag;
    }

    /** Throughput and tick-to-margin latency, also served at /metrics. */
    public TickMetrics tickMetrics() {
        return tickMetrics;
    }

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
        for (int p = 0; p < runners.size(); p++) {
            long end = partitionLogs.get(p).endOffset();
            while (runners.get(p).appliedOffset() < end) {
                if (failure() != null) throw new IllegalStateException("shard host failed", failure());
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("shard " + p + " at offset " + runners.get(p).appliedOffset() + " of " + end + " after " + timeout);
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
        if (interrupted) Thread.currentThread().interrupt();
    }

    public static void main(String[] args) throws InterruptedException, java.io.IOException {
        TreeMap<String, String> f = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) break;
            f.put(args[i].substring(2), args[i + 1]);
        }
        if (args.length % 2 != 0 || f.size() * 2 != args.length || !f.containsKey("instruments") || !f.containsKey("book")) {
            System.err.println("usage: host --instruments FILE --book DIR [--bootstrap localhost:19092] [--partitions 4] [--metrics-port 9100]");
            System.exit(2);
        }
        String bootstrap = f.getOrDefault("bootstrap", "localhost:19092");
        int partitions = Integer.parseInt(f.getOrDefault("partitions", "4"));
        List<Instrument> instruments = InstrumentCsv.read(Path.of(f.get("instruments")));
        List<AccountBook> books = BookCsv.read(Path.of(f.get("book")));
        try (ShardHost host = new ShardHost(bootstrap, partitions, MarginConfig.defaults(), ShardConfig.defaults(), instruments, books)) {
            Thread main = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(main::interrupt));
            host.start();
            int metricsPort = host.lag().serve(Integer.parseInt(f.getOrDefault("metrics-port", "9100")));
            System.out.println("shard host: lag metrics on :" + metricsPort + "/metrics");
            System.out.println("shard host: " + partitions + " shards, " + books.size() + " accounts, " + instruments.size() + " instruments, reading " + bootstrap);
            try {
                while (host.failure() == null) Thread.sleep(500);
                System.err.println("shard host stopping: " + host.failure());
                host.failure().printStackTrace();
                System.exit(1);
            } catch (InterruptedException stop) {
                System.out.println("shard host: shutting down");
            }
        }
    }
}
