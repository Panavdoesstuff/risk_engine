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
