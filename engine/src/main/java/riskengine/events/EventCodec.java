package riskengine.events;

import java.math.BigDecimal;

/**
 * Deterministic text encoding, one event per line: fixed field order, no maps, decimals as plain strings.
 * The same event always encodes to the same bytes, so a log file can be hashed and compared.
 *
 * <pre>TICK|v1|tickSeq|logTimeMillis|underlying|spot|vix</pre>
 */
public final class EventCodec {
    private EventCodec() {}

    public static String encode(Event e) {
        return switch (e) {
            case Tick t -> "TICK|v1|" + t.tickSeq() + "|" + t.logTimeMillis() + "|" + t.underlying() + "|"
                    + t.spot().toPlainString() + "|" + t.vix().toPlainString();
        };
    }

    public static Event decode(String line) {
        String[] f = line.split("\\|", -1);
        try {
            if (f.length == 7 && f[0].equals("TICK") && f[1].equals("v1")) {
                return new Tick(Long.parseLong(f[2]), Long.parseLong(f[3]), f[4], new BigDecimal(f[5]), new BigDecimal(f[6]));
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("bad event line: " + line, ex);
        }
        throw new IllegalArgumentException("unknown event line: " + line);
    }
}
