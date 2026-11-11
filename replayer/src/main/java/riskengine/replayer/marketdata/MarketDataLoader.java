package riskengine.replayer.marketdata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import riskengine.replayer.CsvSupport;

/**
 * Normalises index minute bars and India VIX (minute or daily) from whatever CSV a vendor gives you into
 * one format: {@code ts,open,high,low,close} with ISO timestamps in IST, strictly increasing.
 *
 * <p>Input needs a header with OPEN, HIGH, LOW, CLOSE and either a single DATETIME/TIMESTAMP column, or
 * DATE + TIME columns, or DATE alone (daily). Timestamps without an offset are read as IST.
 * A {@code .json} file is read as an Upstox historical-candle response instead (see {@link #readUpstoxCandles}).
 * A {@code .meta} sidecar records the detected resolution so the crash-day report can state honestly
 * whether VIX was intraday or daily.
 */
public final class MarketDataLoader {
    public static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    public static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);
    public static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);
    /** Minute bars per regular session: 09:15 .. 15:29 inclusive. */
    public static final int SESSION_MINUTES = 375;

    public enum Resolution { MINUTE, DAILY }

    public record Series(List<Bar> bars, Resolution resolution, int outOfSessionBars, int missingMinutes) {}

    private static final List<DateTimeFormatter> DATE_TIME = List.of(
            ci("yyyy-MM-dd'T'HH:mm[:ss][XXX]"), ci("yyyy-MM-dd HH:mm[:ss][XXX]"),
            ci("dd-MM-yyyy HH:mm[:ss]"), ci("dd-MMM-yyyy HH:mm[:ss]"), ci("dd/MM/yyyy HH:mm[:ss]"),
            ci("yyyyMMdd HH:mm[:ss]"));
    private static final List<DateTimeFormatter> DATE = List.of(
            ci("yyyy-MM-dd"), ci("dd-MMM-yyyy"), ci("dd-MM-yyyy"), ci("dd/MM/yyyy"), ci("yyyyMMdd"),
            ci("dd MMM yyyy"));

    private MarketDataLoader() {}

    private static DateTimeFormatter ci(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH);
    }

    /** @param from inclusive, nullable; @param to inclusive, nullable */
    public static Series load(Path file, LocalDate from, LocalDate to) {
        List<String> lines = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")
                ? readUpstoxCandles(file) : CsvSupport.lines(file);
        if (lines.isEmpty()) throw new IllegalArgumentException(file + ": empty file");
        Map<String, Integer> idx = CsvSupport.index(CsvSupport.split(lines.get(0)));
        int cO = CsvSupport.require(idx, "OPEN", file), cH = CsvSupport.require(idx, "HIGH", file),
            cL = CsvSupport.require(idx, "LOW", file), cC = CsvSupport.require(idx, "CLOSE", file);
        Integer cDt = firstOf(idx, "DATETIME", "TIMESTAMP", "DATE_TIME", "DATE TIME");
        Integer cDate = idx.get("DATE"), cTime = idx.get("TIME");
        if (cDt == null && cDate == null) throw new IllegalArgumentException(file + ": need DATETIME, or DATE [+ TIME]");
        boolean dateOnly = cDt == null && cTime == null;

        List<Bar> bars = new ArrayList<>();
        for (int n = 1; n < lines.size(); n++) {
            if (lines.get(n).isBlank()) continue;
            String[] c = CsvSupport.split(lines.get(n));
            try {
                OffsetDateTime ts;
                if (cDt != null) ts = parseDateTime(c[cDt]);
                else if (cTime != null) ts = parseDateTime(c[cDate] + " " + c[cTime]);
                else ts = parseDate(c[cDate]).atTime(SESSION_CLOSE).atOffset(IST);
                LocalDate d = ts.atZoneSameInstant(IST).toLocalDate();
                if ((from != null && d.isBefore(from)) || (to != null && d.isAfter(to))) continue;
                bars.add(new Bar(ts.withOffsetSameInstant(IST), num(c[cO]), num(c[cH]), num(c[cL]), num(c[cC])));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(file + ":" + (n + 1) + ": " + e.getMessage(), e);
            }
        }
        if (bars.isEmpty()) throw new IllegalArgumentException(file + ": no bars in range " + from + ".." + to);
        // Vendors often ship newest-first; sort, then require strictly increasing (no duplicates).
        bars.sort((a, b) -> a.ts().compareTo(b.ts()));
        for (int i = 1; i < bars.size(); i++) {
            if (!bars.get(i).ts().isAfter(bars.get(i - 1).ts())) {
                throw new IllegalArgumentException(file + ": duplicate timestamp " + bars.get(i).ts());
            }
        }
        Resolution res = dateOnly ? Resolution.DAILY : Resolution.MINUTE;
        int outOfSession = 0, missing = 0;
        if (res == Resolution.MINUTE) {
            Map<LocalDate, Integer> perDay = new TreeMap<>();
            for (int i = 0; i < bars.size(); i++) {
                LocalDateTime t = bars.get(i).ts().toLocalDateTime();
                if (t.getSecond() != 0 || t.getNano() != 0) throw new IllegalArgumentException(file + ": not minute-aligned at " + t);
                if (i > 0 && Duration.between(bars.get(i - 1).ts(), bars.get(i).ts()).toSeconds() < 60) {
                    throw new IllegalArgumentException(file + ": sub-minute bars at " + t);
                }
                LocalTime lt = t.toLocalTime();
                if (lt.isBefore(SESSION_OPEN) || !lt.isBefore(SESSION_CLOSE)) outOfSession++;
                else perDay.merge(t.toLocalDate(), 1, Integer::sum);
            }
            for (int count : perDay.values()) missing += SESSION_MINUTES - count;
        }
        return new Series(List.copyOf(bars), res, outOfSession, missing);
    }

    public static String format(List<Bar> bars) {
        StringBuilder sb = new StringBuilder("ts,open,high,low,close\n");
        for (Bar b : bars) {
            sb.append(b.ts().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append(',')
              .append(px(b.open())).append(',').append(px(b.high())).append(',')
              .append(px(b.low())).append(',').append(px(b.close())).append('\n');
        }
        return sb.toString();
    }

    /** Writes {@code out} and {@code out.meta}; returns the meta text. */
    public static String write(Path source, Series s, Path out) {
        String csv = format(s.bars());
        String meta = "source=" + source.getFileName() + "\n"
                + "resolution=" + s.resolution() + "\n"
                + "rows=" + s.bars().size() + "\n"
                + "first=" + s.bars().get(0).ts() + "\n"
                + "last=" + s.bars().get(s.bars().size() - 1).ts() + "\n"
                + "out_of_session_bars=" + s.outOfSessionBars() + "\n"
                + "missing_session_minutes=" + s.missingMinutes() + "\n"
                + "sha256=" + sha256(csv) + "\n";
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, csv, StandardCharsets.UTF_8);
            Files.writeString(out.resolveSibling(out.getFileName() + ".meta"), meta, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return meta;
    }

    private static final Pattern UPSTOX_RESPONSE = Pattern.compile(
            "\\s*\\{\\s*\"status\"\\s*:\\s*\"success\"\\s*,\\s*\"data\"\\s*:\\s*\\{\\s*\"candles\"\\s*:\\s*\\[(.*)]\\s*}\\s*}\\s*", Pattern.DOTALL);
    private static final String NUMBER = "\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*";
    private static final Pattern UPSTOX_CANDLE = Pattern.compile(
            "\\s*\\[\\s*\"([^\"]+)\"\\s*," + NUMBER + "," + NUMBER + "," + NUMBER + "," + NUMBER + "," + NUMBER + "," + NUMBER + "]\\s*(,|$)");

    /**
     * An Upstox v3 historical-candle response, as CSV lines with a DATETIME header:
     * {@code {"status":"success","data":{"candles":[[time, open, high, low, close, volume, oi], ...]}}},
     * newest first (load() sorts). Volume and open interest are dropped (zero for an index). Anything
     * else in the file is refused rather than skipped.
     */
    public static List<String> readUpstoxCandles(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher whole = UPSTOX_RESPONSE.matcher(text);
        if (!whole.matches()) throw new IllegalArgumentException(file + ": not an Upstox candle response {\"status\":\"success\",\"data\":{\"candles\":[...]}}");
        String body = whole.group(1);
        List<String> out = new ArrayList<>();
        out.add("DATETIME,OPEN,HIGH,LOW,CLOSE");
        Matcher m = UPSTOX_CANDLE.matcher(body);
        int at = 0;
        while (at < body.length() && !body.substring(at).isBlank()) {
            if (!m.find(at) || m.start() != at) throw new IllegalArgumentException(file + ": unexpected candle near character " + at);
            out.add(m.group(1) + "," + m.group(2) + "," + m.group(3) + "," + m.group(4) + "," + m.group(5));
            at = m.end();
        }
        return out;
    }

    private static Integer firstOf(Map<String, Integer> idx, String... names) {
        for (String n : names) if (idx.containsKey(n)) return idx.get(n);
        return null;
    }

    private static OffsetDateTime parseDateTime(String s) {
        for (DateTimeFormatter f : DATE_TIME) {
            try {
                var parsed = f.parseBest(s, OffsetDateTime::from, LocalDateTime::from);
                return parsed instanceof OffsetDateTime o ? o : ((LocalDateTime) parsed).atOffset(IST);
            } catch (DateTimeParseException ignored) {
                // try next pattern
            }
        }
        throw new IllegalArgumentException("unrecognised timestamp '" + s + "'");
    }

    private static LocalDate parseDate(String s) {
        for (DateTimeFormatter f : DATE) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // try next pattern
            }
        }
        throw new IllegalArgumentException("unrecognised date '" + s + "'");
    }

    private static BigDecimal num(String s) {
        return new BigDecimal(s.replace(",", ""));
    }

    private static String px(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
