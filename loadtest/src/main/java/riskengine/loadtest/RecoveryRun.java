package riskengine.loadtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import riskengine.host.RedpandaLogs;
import riskengine.host.ShardHost;
import riskengine.host.SnapshotStore;
import riskengine.host.Topics;
import riskengine.margin.AccountBook;
import riskengine.margin.MarginConfig;
import riskengine.ref.Instrument;
import riskengine.shard.ShardConfig;
import riskengine.shard.ShardRunner;

/**
 * M6 part 2: how long recovery takes at 100k accounts, and how big snapshots are.
 *
 * <pre>
 *   ./gradlew :loadtest:recovery --args="--bootstrap localhost:29092"
 *   options: --accounts 100000 --positions 5 --partitions 4 --ticks 2500 --rate 100 --orders 1000 --snapshot-every-seconds 10
 * </pre>
 *
 * <ol>
 *   <li>A host with snapshots runs ticks (and orders alongside) at {@code rate}. Synthetic ticks are 10 ms
 *       apart in log time, so a snapshot every 10 s of log time is one every 1,000 ticks.</li>
 *   <li>It is stopped. (A crash leaves the same snapshot files behind; the kill test covers kill -9.)</li>
 *   <li>A new host starts: load each shard's newest snapshot (read, checksum, restore, verify the state
 *       hash), then replay the partition from the snapshot's offset to the end. Timed separately.</li>
 *   <li>For comparison, a host with no snapshots replays every partition from offset 0.</li>
 * </ol>
 * Both must end with the first host's state hashes, shard for shard.
 */
public final class RecoveryRun {
    private RecoveryRun() {}

    public static void main(String[] args) throws Exception {
        TreeMap<String, String> f = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) f.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        String bootstrap = f.getOrDefault("bootstrap", "localhost:29092");
        int accounts = Integer.parseInt(f.getOrDefault("accounts", "100000"));
        int positions = Integer.parseInt(f.getOrDefault("positions", "5"));
        int n = Integer.parseInt(f.getOrDefault("partitions", "4"));
        int ticks = Integer.parseInt(f.getOrDefault("ticks", "2500"));
        int rate = Integer.parseInt(f.getOrDefault("rate", "100"));
        int orders = Integer.parseInt(f.getOrDefault("orders", "1000"));
        long every = (long) (Double.parseDouble(f.getOrDefault("snapshot-every-seconds", "10")) * 1000);

        List<Instrument> instruments = BenchBook.instruments();
        List<AccountBook> books = BenchBook.books(instruments, accounts, positions, 42);
        String suffix = Long.toString(System.currentTimeMillis());
        String events = "recovery-events-" + suffix, out = "recovery-out-" + suffix;
        Topics.ensure(bootstrap, events, n);
        Topics.ensure(bootstrap, out, n);
        Path dir = Files.createTempDirectory("snapshots");
        MarginConfig cfg = MarginConfig.defaults();
        System.out.printf(Locale.ROOT, "recovery: %,d accounts x %d positions, %d shards; %,d ticks at %d/s with %,d orders/s; snapshot every %.0f s of log time%n",
                accounts, positions, n, ticks, rate, orders, every / 1000.0);

        // 1. Run with snapshots.
        List<String> hashes = new ArrayList<>();
        long[] snapshotBytes = new long[n], snapshotNanos = new long[n], snapshotCount = new long[n], endOffsets = new long[n];
        SnapshotStore store = new SnapshotStore(dir, 3);
        try (ShardHost host = new ShardHost(bootstrap, events, out, n, cfg, ShardConfig.defaults(), instruments, books,
                new ShardHost.Options(null, store, every));
             RedpandaLogs log = new RedpandaLogs(bootstrap, events, n, Duration.ofMillis(100))) {
            host.start();
            long[] sentAt = new long[ticks + 2];
            AtomicLong sent = new AtomicLong();
            Thread orderThread = ScaleRun.orderTraffic(log, books, instruments, orders, ticks / rate + 1, sent);
            long start = System.nanoTime();
            for (int k = 1; k <= ticks; k++) {
                long due = start + (long) ((k - 1) * 1e9 / rate);
                while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
                ScaleRun.sendTick(log, n, k, sentAt);
            }
            orderThread.join();
            log.flush();
            host.awaitCaughtUp(Duration.ofMinutes(5));
            host.stop();
            for (ShardRunner r : host.runners()) {
                int s = r.state().shardId();
                hashes.add(r.state().stateHash());
                endOffsets[s] = r.state().nextOffset();
                snapshotBytes[s] = host.tickMetrics().lastSnapshotBytes(s);
                snapshotNanos[s] = host.tickMetrics().snapshotNanos(s);
                snapshotCount[s] = host.tickMetrics().snapshotsTaken(s);
            }
        } // closing the host closes the store, which writes whatever is still queued
        long totalBytes = 0;
        System.out.println("\nsnapshots (taken on the shard thread, written by a background thread):");
        for (int s = 0; s < n; s++) {
            List<SnapshotStore.Saved> saved = new SnapshotStore(dir, 3).list(s);
            totalBytes += saved.isEmpty() ? 0 : saved.get(0).bytes();
            System.out.printf(Locale.ROOT, "  shard %d: %d taken, %,d bytes (%.1f MB) on disk, %.1f ms each on the shard thread (capture); newest at offset %,d of %,d%n",
                    s, snapshotCount[s], saved.isEmpty() ? 0 : saved.get(0).bytes(), (saved.isEmpty() ? 0 : saved.get(0).bytes()) / 1e6, snapshotCount[s] == 0 ? 0 : snapshotNanos[s] / 1e6 / snapshotCount[s],
                    saved.isEmpty() ? -1 : saved.get(0).offset(), endOffsets[s]);
        }
        System.out.printf(Locale.ROOT, "  all shards: %,d bytes (%.1f MB) for %,d accounts, %.0f bytes per account%n", totalBytes, totalBytes / 1e6, accounts,
                totalBytes / (double) accounts);

        // 2. Recover from the snapshots.
        Result fromSnapshots = recover("from snapshots", bootstrap, events, out, n, cfg, instruments, books, new SnapshotStore(dir, 3), every, hashes);
        // 3. Recover with no snapshots: replay everything.
        Result fromZero = recover("from offset 0 (no snapshots)", bootstrap, events, out, n, cfg, instruments, books, null, every, hashes);

        System.out.println();
        System.out.println("| Recovery | Load snapshots | Replay | Events replayed | Total | State hashes |");
        System.out.println("| --- | ---: | ---: | ---: | ---: | --- |");
        for (Result r : List.of(fromSnapshots, fromZero)) {
            System.out.printf(Locale.ROOT, "| %s | %.2f s | %.2f s | %,d | **%.2f s** | %s |%n", r.name, r.loadNanos / 1e9, r.replayNanos / 1e9, r.replayed,
                    (r.loadNanos + r.replayNanos) / 1e9, r.hashesMatch ? "identical to the run that wrote them" : "DIFFERENT");
        }
        if (!fromSnapshots.hashesMatch || !fromZero.hashesMatch) System.exit(1);
    }

    record Result(String name, long loadNanos, long replayNanos, long replayed, boolean hashesMatch) {}

    static Result recover(String name, String bootstrap, String events, String out, int n, MarginConfig cfg, List<Instrument> instruments, List<AccountBook> books,
                          SnapshotStore store, long every, List<String> expected) throws Exception {
        long t0 = System.nanoTime();
        try (ShardHost host = new ShardHost(bootstrap, events, out, n, cfg, ShardConfig.defaults(), instruments, books,
                new ShardHost.Options(null, store, every))) {
            long t1 = System.nanoTime(); // shards built: snapshots loaded and verified (or fresh states)
            long replayed = 0;
            for (ShardHost.Recovery r : host.recoveries()) {
                System.out.printf(Locale.ROOT, "  %s: shard %d from offset %,d%s%n", name, r.shardId(), r.fromOffset(),
                        r.snapshotBytes() == 0 ? "" : String.format(Locale.ROOT, " (snapshot %,d bytes, loaded in %.0f ms)", r.snapshotBytes(), r.loadNanos() / 1e6));
            }
            long[] from = host.recoveries().stream().mapToLong(ShardHost.Recovery::fromOffset).toArray();
            host.start();
            host.awaitCaughtUp(Duration.ofMinutes(10));
            long t2 = System.nanoTime();
            host.stop();
            List<String> hashes = new ArrayList<>();
            for (ShardRunner r : host.runners()) {
                hashes.add(r.state().stateHash());
                replayed += r.state().nextOffset() - from[r.state().shardId()];
            }
            System.out.printf(Locale.ROOT, "  %s: shards built in %.2f s (includes reading the book), caught up %.2f s later%n", name, (t1 - t0) / 1e9, (t2 - t1) / 1e9);
            return new Result(name, t1 - t0, t2 - t1, replayed, hashes.equals(expected));
        }
    }
}
