package riskengine.events;

import java.math.BigDecimal;

/**
 * Deterministic text encoding, one event per line: fixed field order, no maps, decimals as plain strings.
 * The same event always encodes to the same bytes, so a log file can be hashed and compared.
 *
 * <pre>
 *   TICK|v1|tickSeq|logTimeMillis|underlying|spot|vix
 *   ORDER|v1|orderId|accountId|instrumentId|side|qty|origin|logTimeMillis
 *   FILL|v1|fillId|orderId|accountId|instrumentId|qty|price|logTimeMillis
 *   CLOSED|v1|orderId|accountId|reason|logTimeMillis
 *   SQOFF|v1|legKey|accountId|instrumentId|status|logTimeMillis
 * </pre>
 */
public final class EventCodec {
    private EventCodec() {}

    public static String encode(Event e) {
        return switch (e) {
            case Tick t -> "TICK|v1|" + t.tickSeq() + "|" + t.logTimeMillis() + "|" + t.underlying() + "|"
                    + t.spot().toPlainString() + "|" + t.vix().toPlainString();
            case OrderRequest o -> "ORDER|v1|" + o.orderId() + "|" + o.accountId() + "|" + o.instrumentId() + "|"
                    + o.side() + "|" + o.qty() + "|" + o.origin() + "|" + o.logTimeMillis();
            case Fill f -> "FILL|v1|" + f.fillId() + "|" + f.orderId() + "|" + f.accountId() + "|" + f.instrumentId() + "|"
                    + f.qty() + "|" + f.price().toPlainString() + "|" + f.logTimeMillis();
            case OrderClosed c -> "CLOSED|v1|" + c.orderId() + "|" + c.accountId() + "|" + c.reason() + "|" + c.logTimeMillis();
            case SquareOffResult s -> "SQOFF|v1|" + s.legKey() + "|" + s.accountId() + "|" + s.instrumentId() + "|"
                    + s.status() + "|" + s.logTimeMillis();
        };
    }

    public static Event decode(String line) {
        String[] f = line.split("\\|", -1);
        try {
            if (f.length >= 2 && f[1].equals("v1")) {
                switch (f[0]) {
                    case "TICK":
                        if (f.length == 7) return new Tick(Long.parseLong(f[2]), Long.parseLong(f[3]), f[4], new BigDecimal(f[5]), new BigDecimal(f[6]));
                        break;
                    case "ORDER":
                        if (f.length == 9) return new OrderRequest(f[2], f[3], f[4], OrderRequest.Side.valueOf(f[5]), Long.parseLong(f[6]),
                                OrderRequest.Origin.valueOf(f[7]), Long.parseLong(f[8]));
                        break;
                    case "FILL":
                        if (f.length == 9) return new Fill(f[2], f[3], f[4], f[5], Long.parseLong(f[6]), new BigDecimal(f[7]), Long.parseLong(f[8]));
                        break;
                    case "CLOSED":
                        if (f.length == 6) return new OrderClosed(f[2], f[3], OrderClosed.Reason.valueOf(f[4]), Long.parseLong(f[5]));
                        break;
                    case "SQOFF":
                        if (f.length == 7) return new SquareOffResult(f[2], f[3], f[4], SquareOffResult.Status.valueOf(f[5]), Long.parseLong(f[6]));
                        break;
                    default:
                        break;
                }
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("bad event line: " + line, ex);
        }
        throw new IllegalArgumentException("unknown event line: " + line);
    }
}
