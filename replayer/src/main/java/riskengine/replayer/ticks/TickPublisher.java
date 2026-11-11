package riskengine.replayer.ticks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import riskengine.events.EventLog;
import riskengine.events.FileEventLog;
import riskengine.events.Tick;

/**
 * The only writer of ticks. Every tick goes to every partition with the same global tick_seq, so each
 * shard sees the whole market in the same order, interleaved with its own accounts' events.
 */
public final class TickPublisher {
    private TickPublisher() {}

    public static void publish(List<Tick> ticks, List<? extends EventLog> partitions) {
        long last = Long.MIN_VALUE;
        for (Tick t : ticks) {
            if (t.tickSeq() <= last) throw new IllegalArgumentException("tick_seq must be strictly increasing, got " + t.tickSeq() + " after " + last);
            last = t.tickSeq();
            for (EventLog p : partitions) p.append(t);
        }
    }

    /**
     * Writes every tick to every partition of {@code engine-events} on a Redpanda broker. Like the file
     * version, it refuses to run twice: if any partition already holds a tick, nothing is written, because
     * a second copy would break "tick_seq strictly increases" and stop every shard.
     *
     * @param paceMillis pause between ticks, so a demo can watch the market move; 0 writes at full speed
     */
    public static void publishToRedpanda(List<Tick> ticks, String bootstrap, String topic, int partitions, long paceMillis) {
        try (riskengine.host.RedpandaLogs logs = new riskengine.host.RedpandaLogs(bootstrap, topic, partitions, java.time.Duration.ofMillis(200))) {
            for (int p = 0; p < partitions; p++) {
                try (EventLog log = logs.partition(p)) {
                    long end = log.endOffset(), next = 0;
                    while (next < end) {
                        for (riskengine.events.LoggedEvent e : log.read(next, 1_000)) {
                            next = e.offset() + 1;
                            if (e.event() instanceof Tick t) {
                                throw new IllegalArgumentException(topic + " partition " + p + " already holds ticks (tick_seq " + t.tickSeq()
                                        + " at offset " + e.offset() + "); ticks have one writer and are written once");
                            }
                        }
                    }
                }
            }
            long last = Long.MIN_VALUE;
            for (Tick t : ticks) {
                if (t.tickSeq() <= last) throw new IllegalArgumentException("tick_seq must be strictly increasing, got " + t.tickSeq() + " after " + last);
                last = t.tickSeq();
                for (int p = 0; p < partitions; p++) logs.appendAsync(p, t);
                if (paceMillis > 0) {
                    logs.flush();
                    try {
                        Thread.sleep(paceMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted after tick " + t.tickSeq(), e);
                    }
                }
            }
            logs.flush();
        }
    }

    /** Writes partition-0.log .. partition-(n-1).log into {@code dir}. Refuses to append to existing logs. */
    public static List<Path> publishToFiles(List<Tick> ticks, Path dir, int partitions) {
        if (partitions < 1) throw new IllegalArgumentException("partitions must be >= 1");
        try {
            Files.createDirectories(dir);
            List<Path> files = new ArrayList<>();
            for (int p = 0; p < partitions; p++) {
                Path f = dir.resolve("partition-" + p + ".log");
                if (Files.exists(f)) throw new IllegalArgumentException(f + " already exists; ticks have one writer and are written once");
                files.add(f);
            }
            List<FileEventLog> logs = new ArrayList<>();
            try {
                for (Path f : files) logs.add(new FileEventLog(f));
                publish(ticks, logs);
            } finally {
                logs.forEach(FileEventLog::close);
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
