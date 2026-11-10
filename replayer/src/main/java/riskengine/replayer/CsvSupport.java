package riskengine.replayer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal CSV helpers for exchange files, which are unquoted comma-separated text. */
public final class CsvSupport {
    private CsvSupport() {}

    public static List<String> lines(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1); // strip BOM
            return text.lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Splits one line; supports double-quoted fields (NSE quotes numbers such as "22,530.70"). Trims cells. */
    public static String[] split(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cur.append(ch);
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (quoted) throw new IllegalArgumentException("unterminated quote: " + line);
        cells.add(cur.toString().trim());
        return cells.toArray(String[]::new);
    }

    /** Header name (upper-cased, trimmed) to column index. */
    public static Map<String, Integer> index(String[] header) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            if (!header[i].isEmpty()) idx.put(header[i].toUpperCase(Locale.ROOT), i);
        }
        return idx;
    }

    public static int require(Map<String, Integer> idx, String name, Path file) {
        Integer i = idx.get(name.toUpperCase(Locale.ROOT));
        if (i == null) throw new IllegalArgumentException(file + ": missing column " + name);
        return i;
    }
}
