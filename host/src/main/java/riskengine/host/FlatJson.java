package riskengine.host;

import java.util.Map;
import java.util.TreeMap;

/**
 * Just enough JSON for the messages between the relay, the fill ingester and the broker: one flat object
 * whose values are strings or numbers. Anything else (nesting, arrays, booleans) is refused, which is
 * also the input validation: a message is either exactly this shape or it is rejected.
 */
public final class FlatJson {
    private FlatJson() {}

    /** @return the fields in key order; numbers are returned as their text */
    public static Map<String, String> parse(String json) {
        Map<String, String> out = new TreeMap<>();
        int[] at = {0};
        skipSpace(json, at);
        expect(json, at, '{');
        skipSpace(json, at);
        if (peek(json, at) == '}') {
            at[0]++;
        } else {
            while (true) {
                skipSpace(json, at);
                String key = string(json, at);
                skipSpace(json, at);
                expect(json, at, ':');
                skipSpace(json, at);
                String value = peek(json, at) == '"' ? string(json, at) : number(json, at);
                if (out.put(key, value) != null) throw new IllegalArgumentException("duplicate field " + key);
                skipSpace(json, at);
                if (peek(json, at) == ',') {
                    at[0]++;
                } else {
                    expect(json, at, '}');
                    break;
                }
            }
        }
        skipSpace(json, at);
        if (at[0] != json.length()) throw new IllegalArgumentException("unexpected text after the object");
        return out;
    }

    /** Alternating keys and values. Numbers are written bare, everything else as an escaped string. */
    public static String write(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(kv[i].toString())).append("\":");
            Object v = kv[i + 1];
            if (v instanceof Number n) sb.append(n instanceof java.math.BigDecimal b ? b.toPlainString() : n.toString());
            else sb.append('"').append(escape(v.toString())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static char peek(String s, int[] at) {
        if (at[0] >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
        return s.charAt(at[0]);
    }

    private static void expect(String s, int[] at, char c) {
        if (peek(s, at) != c) throw new IllegalArgumentException("expected '" + c + "' at " + at[0]);
        at[0]++;
    }

    private static void skipSpace(String s, int[] at) {
        while (at[0] < s.length() && Character.isWhitespace(s.charAt(at[0]))) at[0]++;
    }

    private static String string(String s, int[] at) {
        expect(s, at, '"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = peek(s, at);
            at[0]++;
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = peek(s, at);
                at[0]++;
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        if (at[0] + 4 > s.length()) throw new IllegalArgumentException("bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(at[0], at[0] + 4), 16));
                        at[0] += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private static String number(String s, int[] at) {
        int start = at[0];
        while (at[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(at[0])) >= 0) at[0]++;
        String text = s.substring(start, at[0]);
        try {
            new java.math.BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a string or a number at " + start);
        }
        return text;
    }
}
