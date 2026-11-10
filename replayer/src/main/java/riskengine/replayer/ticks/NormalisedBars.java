package riskengine.replayer.ticks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import riskengine.replayer.marketdata.Bar;
import riskengine.replayer.marketdata.MarketDataLoader;
import riskengine.replayer.marketdata.MarketDataLoader.Resolution;

/** Reads a file written by the {@code bars} command, plus its .meta sidecar (which says minute or daily). */
public final class NormalisedBars {
    private NormalisedBars() {}

    public record Series(List<Bar> bars, Resolution resolution) {}

    public static Series read(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals("ts,open,high,low,close")) {
                throw new IllegalArgumentException(file + ": not a normalised bars file; run the `bars` command first");
            }
            List<Bar> bars = new ArrayList<>(lines.size() - 1);
            for (int n = 1; n < lines.size(); n++) {
                if (lines.get(n).isEmpty()) continue;
                String[] c = lines.get(n).split(",", -1);
                bars.add(new Bar(OffsetDateTime.parse(c[0]), new BigDecimal(c[1]), new BigDecimal(c[2]), new BigDecimal(c[3]), new BigDecimal(c[4])));
            }
            for (int i = 1; i < bars.size(); i++) {
                if (!bars.get(i).ts().isAfter(bars.get(i - 1).ts())) throw new IllegalArgumentException(file + ": timestamps not increasing at " + bars.get(i).ts());
            }
            Path meta = file.resolveSibling(file.getFileName() + ".meta");
            Resolution res = null;
            for (String line : Files.readAllLines(meta, StandardCharsets.UTF_8)) {
                if (line.startsWith("resolution=")) res = Resolution.valueOf(line.substring("resolution=".length()).trim());
            }
            if (res == null) throw new IllegalArgumentException(meta + ": no resolution= line");
            return new Series(List.copyOf(bars), res);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** For tests and in-memory pipelines. */
    public static Series of(MarketDataLoader.Series s) {
        return new Series(s.bars(), s.resolution());
    }
}
