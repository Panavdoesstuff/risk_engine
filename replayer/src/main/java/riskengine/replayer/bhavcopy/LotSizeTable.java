package riskengine.replayer.bhavcopy;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import riskengine.replayer.CsvSupport;

/**
 * Lot sizes per (symbol, expiry month), parsed from NSE's contract-lot file {@code fo_mktlots.csv}.
 *
 * <p>Lot sizes are keyed by expiry month because NSE revisions apply to new far-month contracts while
 * already-listed contracts keep their old lot size until expiry. Use the file as published on (or just
 * before) the replay date; today's file will have different months and possibly different sizes.
 *
 * <p>Format: a header row {@code UNDERLYING,SYMBOL,JUN-24,JUL-24,...} (possibly preceded by banner rows),
 * then one row per symbol with whitespace-padded integers; blank cells mean no contract that month.
 */
public final class LotSizeTable {
    private static final DateTimeFormatter MONTH = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMM-yy").toFormatter(Locale.ENGLISH);

    private final Map<String, Map<YearMonth, Integer>> sizes;

    private LotSizeTable(Map<String, Map<YearMonth, Integer>> sizes) {
        this.sizes = sizes;
    }

    public static LotSizeTable parseMktLots(Path file) {
        List<String> lines = CsvSupport.lines(file);
        int headerRow = -1;
        for (int n = 0; n < lines.size(); n++) {
            String[] c = CsvSupport.split(lines.get(n));
            if (c.length > 2 && c[1].equalsIgnoreCase("SYMBOL")) { headerRow = n; break; }
        }
        if (headerRow < 0) throw new IllegalArgumentException(file + ": no UNDERLYING,SYMBOL,... header row");
        String[] header = CsvSupport.split(lines.get(headerRow));
        YearMonth[] months = new YearMonth[header.length];
        for (int i = 2; i < header.length; i++) {
            if (header[i].isEmpty()) continue;
            try {
                months[i] = YearMonth.parse(header[i], MONTH);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(file + ": bad month header '" + header[i] + "'", e);
            }
        }
        Map<String, Map<YearMonth, Integer>> sizes = new HashMap<>();
        for (int n = headerRow + 1; n < lines.size(); n++) {
            String[] c = CsvSupport.split(lines.get(n));
            if (c.length < 3 || c[1].isEmpty()) continue;
            Map<YearMonth, Integer> row = new HashMap<>();
            for (int i = 2; i < Math.min(c.length, months.length); i++) {
                if (months[i] == null || c[i].isEmpty()) continue;
                try {
                    row.put(months[i], Integer.valueOf(c[i]));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(file + ":" + (n + 1) + ": bad lot size '" + c[i] + "'", e);
                }
            }
            if (!row.isEmpty()) sizes.put(c[1], row); // banner/section rows have no numbers
        }
        return new LotSizeTable(sizes);
    }

    public int lotSize(String symbol, LocalDate expiry) {
        Map<YearMonth, Integer> row = sizes.get(symbol);
        if (row == null) throw new IllegalArgumentException("no lot sizes for symbol " + symbol);
        Integer v = row.get(YearMonth.from(expiry));
        if (v == null) {
            throw new IllegalArgumentException("no lot size for " + symbol + " expiry month " + YearMonth.from(expiry)
                    + "; is the contract-lot file from the replay date? months present: " + row.keySet());
        }
        return v;
    }
}
