package riskengine.host;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shard snapshots as files: {@code shard-<id>-<offset>.snap} in one directory.
 *
 * <p>Writing is crash-safe: the bytes go to a temporary file, which is forced to disk and then renamed
 * into place (atomic on one file system). A process killed mid-write leaves at worst a temporary file,
 * never a half-written snapshot under the real name; and every snapshot carries its own checksum
 * anyway, so a damaged one is noticed and skipped. Writes happen on a background thread, so the shard
 * thread only pays for turning its state into bytes. The newest {@code keep} snapshots per shard are
 * kept; older ones are deleted after a new one is in place.
 */
public final class SnapshotStore implements AutoCloseable {
    private static final Pattern NAME = Pattern.compile("shard-(\\d+)-(\\d{20})\\.snap");

    public record Saved(int shardId, long offset, Path file, long bytes) {}

    private record Job(int shardId, long offset, riskengine.shard.ShardState.SnapshotImage image) {}

    private final Path dir;
    private final int keep;
    /** The newest snapshot waiting to be written, per shard. A newer one replaces an older one that was not written yet. */
    private final java.util.concurrent.ConcurrentSkipListMap<Integer, Job> pending = new java.util.concurrent.ConcurrentSkipListMap<>();
    private final Thread writer;
    private final AtomicLong written = new AtomicLong(), dropped = new AtomicLong(), lastBytes = new AtomicLong();
    private volatile boolean running = true;
    private volatile IOException failure;
    private volatile java.util.function.ObjLongConsumer<Integer> onWritten = (shard, bytes) -> {};

    /** Told the shard and size of every snapshot written. */
    public void onWritten(java.util.function.ObjLongConsumer<Integer> l) {
        this.onWritten = l;
    }

    public SnapshotStore(Path dir, int keep) {
        this.dir = dir;
        this.keep = keep;
        try {
            Files.createDirectories(dir);
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".tmp")).toList()) Files.deleteIfExists(p); // left by a crash
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        writer = new Thread(this::writeLoop, "snapshot-writer");
        writer.setDaemon(true);
        writer.setPriority(Thread.MIN_PRIORITY); // background work: the shard threads come first
        writer.start();
    }

    /**
     * Hands a captured snapshot to the background writer and returns at once. The writer turns it into
     * bytes, adds the checksum and writes the file, so none of that costs the shard's thread anything.
     * Each shard has one slot: if its previous snapshot has not been written yet, the newer one replaces
     * it (only the newest matters). The writer serves the shards in turn, so a busy shard can never
     * crowd out another's snapshots.
     */
    public void saveAsync(riskengine.shard.ShardState.SnapshotImage image) {
        if (pending.put(image.shardId(), new Job(image.shardId(), image.nextOffset(), image)) != null) dropped.incrementAndGet();
    }

    /** Writes now, on the caller's thread (tests, and shutdown). */
    public Saved save(int shardId, long offset, byte[] bytes) throws IOException {
        Path file = dir.resolve(String.format("shard-%d-%020d.snap", shardId, offset));
        Path tmp = dir.resolve(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes);
            while (b.hasRemaining()) ch.write(b);
            ch.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        List<Saved> all = list(shardId);
        for (int i = keep; i < all.size(); i++) Files.deleteIfExists(all.get(i).file());
        written.incrementAndGet();
        lastBytes.set(bytes.length);
        onWritten.accept(shardId, bytes.length);
        return new Saved(shardId, offset, file, bytes.length);
    }

    private void writeLoop() {
        int next = 0;
        while (running || !pending.isEmpty()) {
            try {
                // Round robin over the shards that have something waiting.
                Integer shard = pending.ceilingKey(next);
                if (shard == null) shard = pending.isEmpty() ? null : pending.firstKey();
                if (shard == null) {
                    Thread.sleep(50);
                    continue;
                }
                Job j = pending.remove(shard);
                next = shard + 1;
                if (j != null) save(j.shardId(), j.offset(), riskengine.shard.ShardState.withChecksum(j.image().serialize()));
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                failure = e; // reported; the next snapshot tries again
            }
        }
    }

    /** This shard's snapshots, newest (highest offset) first. */
    public List<Saved> list(int shardId) throws IOException {
        List<Saved> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                Matcher m = NAME.matcher(p.getFileName().toString());
                if (m.matches() && Integer.parseInt(m.group(1)) == shardId) out.add(new Saved(shardId, Long.parseLong(m.group(2)), p, Files.size(p)));
            }
        }
        out.sort(Comparator.comparingLong(Saved::offset).reversed());
        return out;
    }

    public byte[] read(Saved s) throws IOException {
        return Files.readAllBytes(s.file());
    }

    /** Snapshots written so far. */
    public long written() {
        return written.get();
    }

    /** Size of the last snapshot written by any shard, in bytes. */
    public long lastBytes() {
        return lastBytes.get();
    }

    /** Snapshots replaced by a newer one from the same shard before they were written. */
    public long dropped() {
        return dropped.get();
    }

    public IOException failure() {
        return failure;
    }

    public Path dir() {
        return dir;
    }

    /** Writes whatever is still queued, then stops. */
    @Override
    public void close() {
        running = false;
        try {
            writer.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
