package riskengine.margin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import riskengine.events.OrderClosed;
import riskengine.events.OrderRequest;

/**
 * Deterministic text encoding of what shards publish to {@code engine-out}, one output per line, in the
 * same style as the input log ({@link riskengine.events.EventCodec}).
 *
 * <pre>
 *   MARGIN|v1|accountId|tickSeq|logTimeMillis|from|to|worstScenarioLoss|worstScenario|shortOptionMinimum|
 *            spanRequirement|netOptionValue|riskRequirement|exposureMargin|required|futuresMtm|available|utilisation|expectedUtilisation
 *   DECISION|v1|orderId|accountId|instrumentId|side|qty|ACCEPTED or REJECTED|reason|lastTickSeq|logTimeMillis|
 *            requiredAfter|availableAfter|utilisationAfter|mark
 *   POSITION|v1|accountId|instrumentId|qty|avgPrice|cash|fillId|orderId|fillQty|fillPrice|cashDelta|orderRemaining|logTimeMillis
 *   RELEASED|v1|orderId|accountId|reason|released|logTimeMillis
 *   LEG|v1|legKey|accountId|episodeId|instrumentId|seq|qty|mark|expectedRelease|tickSeq|logTimeMillis
 *   EPISODE|v1|accountId|episodeId|mode|state|actor|legs|tickSeq|logTimeMillis
 *   STATUS|v1|accountId|from|to|cause|actor|tickSeq|logTimeMillis
 * </pre>
 *
 * Position and cash values are exact decimals and are written in full. Rupee amounts are written to 4 decimals and utilisation to 6. Each is converted exactly from the
 * double's bits and then rounded, never through {@code Double.toString} (whose output changed between
 * JDK versions), so the same state gives the same bytes on every machine. Decoding gives back the rounded
 * values: this is a report for downstream consumers, not a way to restore engine state.
 */
public final class EngineOutputCodec {
    private EngineOutputCodec() {}

    public static String encode(EngineOutput out) {
        return switch (out) {
            case MarginEvent e -> {
                MarginResult r = e.result();
                yield "MARGIN|v1|" + e.accountId() + "|" + e.tickSeq() + "|" + e.logTimeMillis() + "|" + e.from() + "|" + e.to() + "|"
                        + rupees(r.worstScenarioLoss()) + "|" + r.worstScenario() + "|" + rupees(r.shortOptionMinimum()) + "|"
                        + rupees(r.spanRequirement()) + "|" + rupees(r.netOptionValue()) + "|" + rupees(r.riskRequirement()) + "|"
                        + rupees(r.exposureMargin()) + "|" + rupees(r.required()) + "|" + rupees(r.futuresMtm()) + "|"
                        + rupees(r.available()) + "|" + ratio(r.utilisation()) + "|" + ratio(e.expectedUtilisation());
            }
            case OrderDecision d -> "DECISION|v1|" + d.orderId() + "|" + d.accountId() + "|" + d.instrumentId() + "|" + d.side() + "|"
                    + d.qty() + "|" + (d.accepted() ? "ACCEPTED" : "REJECTED") + "|" + d.reason() + "|" + d.lastTickSeq() + "|"
                    + d.logTimeMillis() + "|" + rupees(d.requiredAfter()) + "|" + rupees(d.availableAfter()) + "|" + ratio(d.utilisationAfter()) + "|" + d.mark().toPlainString();
            case PositionChange p -> "POSITION|v1|" + p.accountId() + "|" + p.instrumentId() + "|" + p.qty() + "|"
                    + p.avgPrice().toPlainString() + "|" + p.cash().toPlainString() + "|" + p.fillId() + "|" + p.orderId() + "|" + p.fillQty() + "|"
                    + p.fillPrice().toPlainString() + "|" + p.cashDelta().toPlainString() + "|"
                    + (p.orderRemaining() == PositionChange.NOT_TRACKED ? "-" : Long.toString(p.orderRemaining())) + "|" + p.logTimeMillis();
            case OrderClosure c -> "RELEASED|v1|" + c.orderId() + "|" + c.accountId() + "|" + c.reason() + "|" + c.released() + "|" + c.logTimeMillis();
            case SquareOffLeg l -> "LEG|v1|" + l.legKey() + "|" + l.accountId() + "|" + l.episodeId() + "|" + l.instrumentId() + "|" + l.seq() + "|"
                    + l.qty() + "|" + l.mark().toPlainString() + "|" + rupees(l.expectedRelease()) + "|" + l.tickSeq() + "|" + l.logTimeMillis();
            case EpisodeChange e -> "EPISODE|v1|" + e.accountId() + "|" + e.episodeId() + "|" + e.mode() + "|" + e.state() + "|" + e.actor() + "|"
                    + e.legs() + "|" + e.tickSeq() + "|" + e.logTimeMillis();
            case AccountStatusChange s -> "STATUS|v1|" + s.accountId() + "|" + s.from() + "|" + s.to() + "|" + s.cause() + "|" + s.actor() + "|"
                    + s.tickSeq() + "|" + s.logTimeMillis();
        };
    }

    public static EngineOutput decode(String line) {
        String[] f = line.split("\\|", -1);
        try {
            if (f.length == 19 && f[0].equals("MARGIN") && f[1].equals("v1")) {
                MarginResult r = new MarginResult(num(f[7]), Integer.parseInt(f[8]), num(f[9]), num(f[10]), num(f[11]), num(f[12]),
                        num(f[13]), num(f[14]), num(f[15]), num(f[16]), num(f[17]));
                return new MarginEvent(f[2], Long.parseLong(f[3]), Long.parseLong(f[4]), r, MarginLevel.valueOf(f[5]), MarginLevel.valueOf(f[6]), num(f[18]));
            }
            if (f.length == 15 && f[0].equals("DECISION") && f[1].equals("v1")) {
                boolean accepted = switch (f[7]) {
                    case "ACCEPTED" -> true;
                    case "REJECTED" -> false;
                    default -> throw new IllegalArgumentException("bad outcome " + f[7]);
                };
                return new OrderDecision(f[2], f[3], f[4], OrderRequest.Side.valueOf(f[5]), Long.parseLong(f[6]), accepted,
                        OrderDecision.Reason.valueOf(f[8]), Long.parseLong(f[9]), Long.parseLong(f[10]), num(f[11]), num(f[12]), num(f[13]), new BigDecimal(f[14]));
            }
            if (f.length == 14 && f[0].equals("POSITION") && f[1].equals("v1")) {
                return new PositionChange(f[2], f[3], Long.parseLong(f[4]), new BigDecimal(f[5]), new BigDecimal(f[6]), f[7], f[8], Long.parseLong(f[9]),
                        new BigDecimal(f[10]), new BigDecimal(f[11]), f[12].equals("-") ? PositionChange.NOT_TRACKED : Long.parseLong(f[12]), Long.parseLong(f[13]));
            }
            if (f.length == 12 && f[0].equals("LEG") && f[1].equals("v1")) {
                return new SquareOffLeg(f[2], f[3], f[4], f[5], Integer.parseInt(f[6]), Long.parseLong(f[7]), new BigDecimal(f[8]), num(f[9]),
                        Long.parseLong(f[10]), Long.parseLong(f[11]));
            }
            if (f.length == 10 && f[0].equals("EPISODE") && f[1].equals("v1")) {
                return new EpisodeChange(f[2], f[3], AccountRisk.Mode.valueOf(f[4]), EpisodeChange.State.valueOf(f[5]), f[6], Integer.parseInt(f[7]),
                        Long.parseLong(f[8]), Long.parseLong(f[9]));
            }
            if (f.length == 9 && f[0].equals("STATUS") && f[1].equals("v1")) {
                return new AccountStatusChange(f[2], AccountRisk.Status.valueOf(f[3]), AccountRisk.Status.valueOf(f[4]), f[5], f[6],
                        Long.parseLong(f[7]), Long.parseLong(f[8]));
            }
            if (f.length == 7 && f[0].equals("RELEASED") && f[1].equals("v1")) {
                return new OrderClosure(f[2], f[3], OrderClosed.Reason.valueOf(f[4]), Long.parseLong(f[5]), Long.parseLong(f[6]));
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("bad engine-out line: " + line, ex);
        }
        throw new IllegalArgumentException("unknown engine-out line: " + line);
    }

    private static String rupees(double v) {
        return fixed(v, 4);
    }

    private static String ratio(double v) {
        return fixed(v, 6);
    }

    private static String fixed(double v, int scale) {
        if (Double.isNaN(v)) throw new IllegalArgumentException("NaN in engine output");
        if (Double.isInfinite(v)) return v > 0 ? "INF" : "-INF";
        return new BigDecimal(v).setScale(scale, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static double num(String s) {
        return switch (s) {
            case "INF" -> Double.POSITIVE_INFINITY;
            case "-INF" -> Double.NEGATIVE_INFINITY;
            default -> new BigDecimal(s).doubleValue();
        };
    }
}
