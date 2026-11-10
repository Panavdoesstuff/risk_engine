package riskengine.events;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A log file with one {@link EventCodec} line per event. Offset = line number (0-based).
 * Reopening an existing file continues after its last event. Every line is decoded on open, so a corrupt
 * file fails immediately rather than halfway through a replay. Single writer.
 */
public final class FileEventLog implements EventLog {
    private final Path file;
    private final List<Long> lineStarts = new ArrayList<>(); // byte position of each event's line
    private long bytes;
    private final BufferedWriter writer;

    public FileEventLog(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                byte[] all = Files.readAllBytes(file);
                if (all.length > 0 && all[all.length - 1] != '\n') {
                    throw new IllegalStateException(file + ": last line is incomplete (crash during append?)");
                }
                int start = 0;
                for (int i = 0; i < all.length; i++) {
                    if (all[i] == '\n') {
                        EventCodec.decode(new String(all, start, i - start, StandardCharsets.UTF_8));
                        lineStarts.add((long) start);
                        start = i + 1;
                    }
                }
                bytes = all.length;
            }
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long append(Event e) {
        String line = EventCodec.encode(e) + "\n";
        try {
            writer.write(line);
            writer.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        lineStarts.add(bytes);
        bytes += line.getBytes(StandardCharsets.UTF_8).length;
        return lineStarts.size() - 1L;
    }

    @Override
    public List<LoggedEvent> read(long fromOffset, int max) {
        if (fromOffset < 0 || fromOffset > lineStarts.size()) throw new IllegalArgumentException("offset " + fromOffset + " out of range");
        int end = (int) Math.min(lineStarts.size(), fromOffset + (long) max);
        List<LoggedEvent> out = new ArrayList<>();
        if (fromOffset == end) return out;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long from = lineStarts.get((int) fromOffset);
            long to = end < lineStarts.size() ? lineStarts.get(end) : bytes;
            byte[] buf = new byte[(int) (to - from)];
            raf.seek(from);
            raf.readFully(buf);
            int start = 0;
            long offset = fromOffset;
            for (int i = 0; i < buf.length; i++) {
                if (buf[i] == '\n') {
                    out.add(new LoggedEvent(offset++, EventCodec.decode(new String(buf, start, i - start, StandardCharsets.UTF_8))));
                    start = i + 1;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @Override
    public long endOffset() {
        return lineStarts.size();
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
