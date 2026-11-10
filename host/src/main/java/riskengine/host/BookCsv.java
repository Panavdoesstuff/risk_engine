package riskengine.host;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import riskengine.margin.AccountBook;
import riskengine.margin.Position;

/** Reads the seeded start-of-day book (accounts.csv + positions.csv, written by {@code replayer seed}). */
public final class BookCsv {
    private static final String ACCOUNTS_HEADER = "account_id,archetype,thin_funded,cash_balance";
    private static final String POSITIONS_HEADER = "account_id,instrument_id,lots,qty,avg_price";

    private BookCsv() {}

    public static List<AccountBook> read(Path dir) {
        TreeMap<String, BigDecimal> cash = new TreeMap<>();
        for (String[] c : rows(dir.resolve("accounts.csv"), ACCOUNTS_HEADER, 4)) {
            if (cash.put(c[0], new BigDecimal(c[3])) != null) throw new IllegalArgumentException("duplicate account " + c[0]);
        }
        TreeMap<String, TreeMap<String, Position>> positions = new TreeMap<>();
        for (String[] c : rows(dir.resolve("positions.csv"), POSITIONS_HEADER, 5)) {
            if (!cash.containsKey(c[0])) throw new IllegalArgumentException("position for unknown account " + c[0]);
            if (positions.computeIfAbsent(c[0], k -> new TreeMap<>()).put(c[1], new Position(Long.parseLong(c[3]), new BigDecimal(c[4]))) != null) {
                throw new IllegalArgumentException("duplicate position " + c[0] + " " + c[1]);
            }
        }
        List<AccountBook> out = new ArrayList<>(cash.size());
        // The seed file has no collateral column yet: every seeded account is cash-only.
        cash.forEach((id, c) -> out.add(new AccountBook(id, c, BigDecimal.ZERO, positions.getOrDefault(id, new TreeMap<>()))));
        return out;
    }

    private static List<String[]> rows(Path file, String header, int columns) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (lines.isEmpty() || !lines.get(0).equals(header)) throw new IllegalArgumentException(file + ": bad header, expected " + header);
        List<String[]> out = new ArrayList<>(lines.size() - 1);
        for (int n = 1; n < lines.size(); n++) {
            if (lines.get(n).isEmpty()) continue;
            String[] c = lines.get(n).split(",", -1);
            if (c.length != columns) throw new IllegalArgumentException(file + ":" + (n + 1) + ": expected " + columns + " columns, got " + c.length);
            out.add(c);
        }
        return out;
    }
}
