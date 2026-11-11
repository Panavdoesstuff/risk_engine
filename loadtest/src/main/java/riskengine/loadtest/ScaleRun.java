package riskengine.loadtest;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import riskengine.events.OrderRequest;
import riskengine.events.Tick;
import riskengine.host.RedpandaLogs;
import riskengine.host.ShardHost;
import riskengine.host.Topics;
import riskengine.margin.AccountBook;
import riskengine.margin.MarginConfig;
import riskengine.ref.Instrument;
import riskengine.shard.ShardConfig;

/**
 * M6 part 1: tick-to-margin latency at scale, through a real Redpanda broker.
 *
 * <pre>
 *   ./gradlew :loadtest:scale --args="--bootstrap localhost:29092"
 *   options: --accounts 100000 --positions 5 --partitions 4 --rates 10,50,100 --seconds 60 --warmup 10
 *            --orders 1000 --flat-out-ticks 3000
 * </pre>
 *
 * One {@link ShardHost} (the same class the compose stack runs) on fresh topics. A tick thread writes
 * synthetic ticks at a fixed rate, every tick moving spot and VIX so every held instrument reprices; an
 * order thread writes user orders at a fixed rate to random accounts, in parallel. Each phase runs at one
 * tick rate; the first {@code warmup} seconds are not counted.
 *
 * <p>Latency is measured per tick per shard, from just before the tick is handed to the producer
 * ({@code System.nanoTime()}, same JVM) to the moment that shard has applied it (see TickMetrics). Exact
 * percentiles come from every sample, not from histogram buckets.
 *
 * <p>Then a flat-out phase writes ticks as fast as the producer takes them and measures how many ticks
 * per second the slowest shard sustains.
 */
public final class ScaleRun {
    private ScaleRun() {}

    /** @param cpuPerTickNanos mean CPU time a shard thread spent per tick interval (the tick plus the orders since the previous one) */
    record Phase(String name, double ticksPerSecond, double achievedTicksPerSecond, int samples, long p50, long p90, long p99, long p999, long max,
                 long maxLag, long gcCount, long gcMillis, long orders, long cpuPerTickNanos) {}

    public static void main(String[] args) throws Exception {
        TreeMap<String, String> f = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) f.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        String bootstrap = f.getOrDefault("bootstrap", "localhost:29092");
        int accounts = Integer.parseInt(f.getOrDefault("accounts", "100000"));
        int positions = Integer.parseInt(f.getOrDefault("positions", "5"));
        int n = Integer.parseInt(f.getOrDefault("partitions", "4"));
        int seconds = Integer.parseInt(f.getOrDefault("seconds", "60"));
        int warmup = Integer.parseInt(f.getOrDefault("warmup", "10"));
        int ordersPerSecond = Integer.parseInt(f.getOrDefault("orders", "1000"));
        int flatOut = Integer.parseInt(f.getOrDefault("flat-out-ticks", "3000"));
        int[] rates = Arrays.stream(f.getOrDefault("rates", "10,50,100").split(",")).mapToInt(Integer::parseInt).toArray();
        // Optional: snapshots every N seconds of log time while measuring (BenchBook ticks are 10 ms apart
        // in log time, so at 100 ticks/s log time runs at wall-clock speed).
        double snapshotEvery = Double.parseDouble(f.getOrDefault("snapshot-every-seconds", "0"));
        ShardHost.Options options = snapshotEvery <= 0 ? ShardHost.Options.defaults()
                : new ShardHost.Options(null, new riskengine.host.SnapshotStore(java.nio.file.Files.createTempDirectory("scale-snapshots"), 2), (long) (snapshotEvery * 1000));

        List<Instrument> instruments = BenchBook.instruments();
        long buildStart = System.nanoTime();
        List<AccountBook> books = BenchBook.books(instruments, accounts, positions, 42);
        String suffix = Long.toString(System.currentTimeMillis());
        String events = "scale-events-" + suffix, out = "scale-out-" + suffix;
        Topics.ensure(bootstrap, events, n);
        Topics.ensure(bootstrap, out, n);

        int maxTicks = 1 + Arrays.stream(rates).map(r -> r * (seconds + warmup)).sum() + flatOut + 10;
        long[] sentAt = new long[maxTicks + 1];
        long[] window = {Long.MAX_VALUE, Long.MIN_VALUE}; // tick_seq range being recorded
        long[][] samples = {new long[0]};
        AtomicLong count = new AtomicLong();
        // CPU time of each shard thread, read at each tick it applies: the difference between two ticks is
        // what that interval cost the shard, whatever else is competing for the machine.
        java.lang.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] lastCpu = new long[n];
        Arrays.fill(lastCpu, -1);
        AtomicLong cpuSum = new AtomicLong(), cpuCount = new AtomicLong();

        try (ShardHost host = new ShardHost(bootstrap, events, out, n, MarginConfig.defaults(), ShardConfig.defaults(), instruments, books, options);
             RedpandaLogs log = new RedpandaLogs(bootstrap, events, n, Duration.ofMillis(100))) {
            System.out.printf(Locale.ROOT, "scale: %,d accounts x %d positions over %d instruments, %d shards; book and shards built in %.1f s; JVM max heap %,d MB, %d cores; system load %.1f; snapshots %s%n",
                    accounts, positions, instruments.size(), n, (System.nanoTime() - buildStart) / 1e9,
                    Runtime.getRuntime().maxMemory() >> 20, Runtime.getRuntime().availableProcessors(), ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage(),
                    snapshotEvery <= 0 ? "off" : "every " + snapshotEvery + " s of log time");
            host.tickMetrics().measureFrom((shard, e) -> {
                long seq = ((Tick) e.event()).tickSeq();
                long nanos = System.nanoTime() - sentAt[(int) seq];
                long cpu = threads.getCurrentThreadCpuTime();
                long previousCpu = lastCpu[shard];
                lastCpu[shard] = cpu;
                if (seq >= window[0] && seq <= window[1] && previousCpu >= 0) {
                    cpuSum.addAndGet(cpu - previousCpu);
                    cpuCount.incrementAndGet();
                }
                if (seq >= window[0] && seq <= window[1]) {
                    synchronized (samples) {
                        long i = count.getAndIncrement();
                        if (i < samples[0].length) samples[0][(int) i] = nanos;
                    }
                }
                return nanos;
            });
            host.start();

            long[] seq = {0};
            List<Phase> phases = new ArrayList<>();
            // The first tick builds every vector from nothing; keep it out of the numbers.
            sendTick(log, n, ++seq[0], sentAt);
            log.flush();
            host.awaitCaughtUp(Duration.ofMinutes(2));

            for (int rate : rates) {
                int total = rate * (warmup + seconds);
                long recordFrom = seq[0] + 1 + (long) rate * warmup, recordTo = seq[0] + total;
                synchronized (samples) {
                    samples[0] = new long[(int) (recordTo - recordFrom + 1) * n];
                    count.set(0);
                    cpuSum.set(0);
                    cpuCount.set(0);
                    window[0] = recordFrom;
                    window[1] = recordTo;
                }
                long[] gcBefore = gc();
                long[] maxLag = {0};
                boolean[] running = {true};
                Thread lagSampler = new Thread(() -> {
                    while (running[0]) {
                        for (int p = 0; p < n; p++) maxLag[0] = Math.max(maxLag[0], host.lag().lag(p));
                        LockSupport.parkNanos(100_000_000L);
                    }
                }, "lag-sampler");
                lagSampler.setDaemon(true);
                lagSampler.start();
                AtomicLong orders = new AtomicLong();
                Thread orderThread = orderTraffic(log, books, instruments, ordersPerSecond, warmup + seconds, orders);

                long start = System.nanoTime(), recordStartNanos = 0;
                for (int k = 0; k < total; k++) {
                    long due = start + (long) (k * 1e9 / rate);
                    while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
                    if (seq[0] + 1 == recordFrom) recordStartNanos = System.nanoTime();
                    sendTick(log, n, ++seq[0], sentAt);
                }
                double achieved = (rate * (double) seconds) / ((System.nanoTime() - recordStartNanos) / 1e9);
                orderThread.join();
                log.flush();
                host.awaitCaughtUp(Duration.ofMinutes(5));
                running[0] = false;
                long[] gcAfter = gc();
                long[] s;
                synchronized (samples) {
                    s = Arrays.copyOf(samples[0], (int) Math.min(count.get(), samples[0].length));
                    window[0] = Long.MAX_VALUE;
                }
                Arrays.sort(s);
                phases.add(new Phase(rate + " ticks/s", rate, achieved, s.length, pct(s, 50), pct(s, 90), pct(s, 99), pct(s, 99.9), s[s.length - 1],
                        maxLag[0], gcAfter[0] - gcBefore[0], gcAfter[1] - gcBefore[1], orders.get(), cpuSum.get() / Math.max(1, cpuCount.get())));
                System.out.println(line(phases.get(phases.size() - 1), seconds));
            }

            // Flat out: as many ticks as the producer will take, with the order traffic still running.
            long[] before = new long[n];
            for (int p = 0; p < n; p++) before[p] = host.tickMetrics().ticksApplied(p);
            long[] gcBefore = gc();
            AtomicLong orders = new AtomicLong();
            Thread orderThread = orderTraffic(log, books, instruments, ordersPerSecond, 3_600, orders);
            long start = System.nanoTime();
            for (int k = 0; k < flatOut; k++) sendTick(log, n, ++seq[0], sentAt);
            log.flush();
            double slowest = Double.MAX_VALUE;
            for (int p = 0; p < n; p++) {
                while (host.tickMetrics().ticksApplied(p) - before[p] < flatOut) {
                    if (host.failure() != null) throw new IllegalStateException("shard host failed", host.failure());
                    LockSupport.parkNanos(1_000_000L);
                }
                slowest = Math.min(slowest, flatOut / ((System.nanoTime() - start) / 1e9));
            }
            orderThread.interrupt();
            orderThread.join();
            long[] gcAfter = gc();
            if (snapshotEvery > 0) {
                for (int p = 0; p < n; p++) {
                    System.out.printf(Locale.ROOT, "snapshots: shard %d took %d, %.1f ms each on its thread (capture), last written %,d bytes%n", p, host.tickMetrics().snapshotsTaken(p),
                            host.tickMetrics().snapshotNanos(p) / 1e6 / Math.max(1, host.tickMetrics().snapshotsTaken(p)), host.tickMetrics().lastSnapshotBytes(p));
                }
            }
            System.out.printf(Locale.ROOT, "flat out: %,d ticks, the slowest shard sustained %,.0f ticks/s (with %,d orders/s in parallel); %d GCs, %d ms%n",
                    flatOut, slowest, ordersPerSecond, gcAfter[0] - gcBefore[0], gcAfter[1] - gcBefore[1]);
            host.stop();
            if (host.failure() != null) throw new IllegalStateException("shard host failed", host.failure());

            System.out.println();
            System.out.println("| Tick rate | Achieved | Samples | p50 | p90 | p99 | p99.9 | max | Shard CPU per tick | Max shard lag | GCs (time) | Orders |");
            System.out.println("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
            for (Phase p : phases) {
                System.out.printf(Locale.ROOT, "| %s | %.1f/s | %,d | %s | %s | %s | %s | %s | %s | %,d events | %d (%d ms) | %,d |%n", p.name(), p.achievedTicksPerSecond(), p.samples(),
                        ms(p.p50()), ms(p.p90()), ms(p.p99()), ms(p.p999()), ms(p.max()), ms(p.cpuPerTickNanos()), p.maxLag(), p.gcCount(), p.gcMillis(), p.orders());
            }
            System.out.printf(Locale.ROOT, "| flat out | %,.0f/s sustained by the slowest shard | | | | | | | | | %d (%d ms) | |%n", slowest, gcAfter[0] - gcBefore[0], gcAfter[1] - gcBefore[1]);
        }
    }

    static void sendTick(RedpandaLogs log, int n, long seq, long[] sentAt) {
        // A synthetic live market: spot and VIX move on every tick (BenchBook's sawtooth), so every held
        // instrument reprices and every holder is touched.
        Tick t = BenchBook.tick(seq);
        sentAt[(int) seq] = System.nanoTime();
        for (int p = 0; p < n; p++) log.appendAsync(p, t);
    }

    static Thread orderTraffic(RedpandaLogs log, List<AccountBook> books, List<Instrument> instruments, int perSecond, int seconds, AtomicLong sent) {
        Thread t = new Thread(() -> {
            SplittableRandom r = new SplittableRandom(sent.hashCode());
            long start = System.nanoTime();
            long total = (long) perSecond * seconds;
            for (long k = 0; k < total && !Thread.currentThread().isInterrupted(); k++) {
                long due = start + (long) (k * 1e9 / perSecond);
                while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
                AccountBook b = books.get(r.nextInt(books.size()));
                Instrument i = instruments.get(r.nextInt(instruments.size()));
                log.appendAsync(new OrderRequest("S-" + System.identityHashCode(sent) + "-" + k, b.id(), i.id(),
                        r.nextBoolean() ? OrderRequest.Side.BUY : OrderRequest.Side.SELL, 25L * (1 + r.nextInt(3)), OrderRequest.Origin.USER, BenchBook.T0));
                sent.incrementAndGet();
            }
        }, "order-traffic");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static long[] gc() {
        long count = 0, millis = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, g.getCollectionCount());
            millis += Math.max(0, g.getCollectionTime());
        }
        return new long[] {count, millis};
    }

    static long pct(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.2f ms", nanos / 1e6);
    }

    static String line(Phase p, int seconds) {
        return String.format(Locale.ROOT, "%s for %d s (achieved %.1f/s): p50 %s, p90 %s, p99 %s, p99.9 %s, max %s over %,d samples; shard CPU %s per tick; max lag %,d; %d GCs (%d ms); %,d orders",
                p.name(), seconds, p.achievedTicksPerSecond(), ms(p.p50()), ms(p.p90()), ms(p.p99()), ms(p.p999()), ms(p.max()), p.samples(), ms(p.cpuPerTickNanos()),
                p.maxLag(), p.gcCount(), p.gcMillis(), p.orders());
    }
}
