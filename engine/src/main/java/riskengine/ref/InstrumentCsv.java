package riskengine.ref;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The normalised instruments file shared by the loader (writer) and everything downstream (reader).
 * Output is deterministic: canonical row order, fixed column order, fixed decimal scale, LF endings.
 */
public final class InstrumentCsv {
    public static final String HEADER =
            "instrument_id,underlying,type,expiry,strike,lot_size,close,settle,open_interest";

    private InstrumentCsv() {}

    public static String format(List<Instrument> instruments) {
        List<Instrument> sorted = new ArrayList<>(instruments);
        sorted.sort(Instrument.CANONICAL);
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Instrument i : sorted) {
            sb.append(i.id()).append(',')
              .append(i.underlying()).append(',')
              .append(i.type()).append(',')
              .append(i.expiry()).append(',')
              .append(i.strike() == null ? "" : i.strike().toPlainString()).append(',')
              .append(i.lotSize()).append(',')
              .append(money(i.close())).append(',')
              .append(money(i.settle())).append(',')
              .append(i.openInterest()).append('\n');
        }
        return sb.toString();
    }

    public static void write(Path file, List<Instrument> instruments) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, format(instruments), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Instrument> read(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            throw new IllegalArgumentException(file + ": not an instruments file (bad header)");
        }
        List<Instrument> out = new ArrayList<>(lines.size() - 1);
        for (int n = 1; n < lines.size(); n++) {
            String line = lines.get(n);
            if (line.isEmpty()) continue;
            String[] c = line.split(",", -1);
            if (c.length != 9) {
                throw new IllegalArgumentException(file + ":" + (n + 1) + ": expected 9 columns, got " + c.length);
            }
            out.add(new Instrument(c[0], c[1], InstrumentType.valueOf(c[2]), LocalDate.parse(c[3]),
                    c[4].isEmpty() ? null : new BigDecimal(c[4]), Integer.parseInt(c[5]),
                    new BigDecimal(c[6]), new BigDecimal(c[7]), Long.parseLong(c[8])));
        }
        return out;
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
