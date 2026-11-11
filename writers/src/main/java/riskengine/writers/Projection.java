package riskengine.writers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import riskengine.events.OrderClosed;
import riskengine.margin.EngineOutput;
import riskengine.margin.MarginEvent;
import riskengine.margin.MarginResult;
import riskengine.margin.OrderClosure;
import riskengine.margin.OrderDecision;
import riskengine.margin.PositionChange;

/**
 * Turns one batch of engine outputs into SQL, inside the caller's transaction.
 *
 * <p>Every statement is parameterised (no value is ever spliced into SQL text) and safe to run twice:
 *
 * <table>
 *   <caption>What each output writes</caption>
 *   <tr><td>MarginEvent</td><td>margin_events (unique on account + tick_seq), audit_log</td></tr>
 *   <tr><td>OrderDecision</td><td>orders: status ACCEPTED / REJECTED (insert, or update the API's PENDING row), audit_log</td></tr>
 *   <tr><td>OrderClosure</td><td>orders: status CANCELLED / BROKER_REJECTED when something was released, audit_log</td></tr>
 *   <tr><td>PositionChange</td><td>positions (upsert), accounts.cash_balance</td></tr>
 * </table>
 *
 * Updated rows are guarded by {@code last_event_seq}: a row only accepts an output whose source offset is
 * newer than the one that last wrote it. So when a restarted shard publishes its whole history again, the
 * old outputs change nothing.
 *
 * <p>Rows that would break a foreign key (an order for an account or instrument Postgres has never heard
 * of) are skipped and counted; the audit row is still written, without the account link. One bad row
 * must not block the stream behind it.
 */
final class Projection {
    /** One engine output plus where it came from. */
    record Item(int shard, long sourceOffset, EngineOutput out) {}

    record Counts(int marginEvents, int orders, int positions, int audit, int skipped) {
        Counts plus(Counts o) {
            return new Counts(marginEvents + o.marginEvents, orders + o.orders, positions + o.positions, audit + o.audit, skipped + o.skipped);
        }
        static final Counts ZERO = new Counts(0, 0, 0, 0, 0);
    }

    private static final String MARGIN_SQL = """
            INSERT INTO margin_events (account_id, tick_seq, log_time, required, available, utilisation,
                                       expected_utilisation, status_from, status_to, action)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_id, tick_seq) DO NOTHING""";
    private static final String DECISION_SQL = """
            INSERT INTO orders (id, account_id, instrument_id, side, qty, order_type, origin, status, reject_reason,
                                created_by, last_event_seq)
            VALUES (?, ?, ?, ?, ?, 'MARKET', 'USER', ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE
               SET status = EXCLUDED.status, reject_reason = EXCLUDED.reject_reason,
                   last_event_seq = EXCLUDED.last_event_seq, updated_at = now()
             WHERE orders.last_event_seq < EXCLUDED.last_event_seq""";
    private static final String CLOSURE_SQL = """
            UPDATE orders SET status = ?, reject_reason = ?, last_event_seq = ?, updated_at = now()
             WHERE id = ? AND last_event_seq < ?""";
    private static final String POSITION_SQL = """
            INSERT INTO positions (account_id, instrument_id, net_qty, avg_price, last_event_seq)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (account_id, instrument_id) DO UPDATE
               SET net_qty = EXCLUDED.net_qty, avg_price = EXCLUDED.avg_price,
                   last_event_seq = EXCLUDED.last_event_seq, updated_at = now()
             WHERE positions.last_event_seq < EXCLUDED.last_event_seq""";
    private static final String CASH_SQL = """
            UPDATE accounts SET cash_balance = ?, last_event_seq = ?, updated_at = now()
             WHERE id = ? AND last_event_seq < ?""";
    private static final String AUDIT_SQL = """
            INSERT INTO audit_log (actor, action, account_id, tick_seq, log_time, details, reason, request_id, event_key)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (event_key) WHERE event_key IS NOT NULL DO NOTHING""";

    /** Largest value NUMERIC(10,4) holds. Utilisation is infinite when nothing is available. */
    private static final BigDecimal MAX_UTILISATION = new BigDecimal("999999.9999");

    private final TreeSet<String> accounts = new TreeSet<>();
    private final TreeSet<String> instruments = new TreeSet<>();

    /** Applies the batch. The caller commits. */
    Counts write(Connection c, List<Item> batch) throws SQLException {
        refreshReferenceDataIfNeeded(c, batch);
        int skipped = 0;
        // Updated rows: only the newest output per key in this batch matters, and in one partition the
        // newest is the last. (Writing each key once also keeps the batch small.)
        Map<String, Item> decisionWrites = new LinkedHashMap<>();
        Map<String, Item> closureWrites = new LinkedHashMap<>();
        Map<String, Item> positionWrites = new LinkedHashMap<>();
        Map<String, Item> cashWrites = new LinkedHashMap<>();
        List<Item> marginWrites = new ArrayList<>();
        List<Item> auditWrites = new ArrayList<>();
        for (Item item : batch) {
            switch (item.out()) {
                case MarginEvent m -> {
                    if (accounts.contains(m.accountId())) marginWrites.add(item);
                    else skipped++;
                    auditWrites.add(item);
                }
                case OrderDecision d -> {
                    if (accounts.contains(d.accountId()) && instruments.contains(d.instrumentId())) putLast(decisionWrites, d.orderId(), item);
                    else skipped++;
                    auditWrites.add(item);
                }
                case OrderClosure cl -> {
                    if (cl.released() != 0) putLast(closureWrites, cl.orderId(), item);
                    auditWrites.add(item);
                }
                case PositionChange p -> {
                    if (accounts.contains(p.accountId()) && instruments.contains(p.instrumentId())) {
                        putLast(positionWrites, p.accountId() + "|" + p.instrumentId(), item);
                        putLast(cashWrites, p.accountId(), item);
                    } else {
                        skipped++;
                    }
                }
            }
        }

        try (PreparedStatement ps = c.prepareStatement(MARGIN_SQL)) {
            for (Item item : marginWrites) {
                MarginEvent m = (MarginEvent) item.out();
                MarginResult r = m.result();
                ps.setString(1, m.accountId());
                ps.setLong(2, m.tickSeq());
                ps.setObject(3, time(m.logTimeMillis()));
                ps.setBigDecimal(4, rupees(r.required()));
                ps.setBigDecimal(5, rupees(r.available()));
                ps.setBigDecimal(6, utilisation(r.utilisation()));
                ps.setBigDecimal(7, utilisation(r.utilisation())); // no in-flight legs until M5: expected = actual
                ps.setString(8, m.from().name());
                ps.setString(9, m.to().name());
                ps.setString(10, m.action());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement decision = c.prepareStatement(DECISION_SQL); PreparedStatement closure = c.prepareStatement(CLOSURE_SQL)) {
            for (Item item : decisionWrites.values()) {
                OrderDecision d = (OrderDecision) item.out();
                decision.setString(1, d.orderId());
                decision.setString(2, d.accountId());
                decision.setString(3, d.instrumentId());
                decision.setString(4, d.side().name());
                decision.setLong(5, d.qty());
                decision.setString(6, d.accepted() ? "ACCEPTED" : "REJECTED");
                decision.setString(7, d.accepted() ? null : d.reason().name());
                decision.setString(8, actor(item));
                decision.setLong(9, item.sourceOffset());
                decision.addBatch();
            }
            for (Item item : closureWrites.values()) {
                OrderClosure cl = (OrderClosure) item.out();
                closure.setString(1, cl.reason() == OrderClosed.Reason.REJECTED ? "BROKER_REJECTED" : "CANCELLED");
                closure.setString(2, cl.reason().name());
                closure.setLong(3, item.sourceOffset());
                closure.setString(4, cl.orderId());
                closure.setLong(5, item.sourceOffset());
                closure.addBatch();
            }
            // Decisions first: a closure is an UPDATE, and its order's row may be created by a decision
            // in this same batch. The last_event_seq guard makes the pair come out right in any case.
            decision.executeBatch();
            closure.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement(POSITION_SQL)) {
            for (Item item : positionWrites.values()) {
                PositionChange p = (PositionChange) item.out();
                ps.setString(1, p.accountId());
                ps.setString(2, p.instrumentId());
                ps.setLong(3, p.qty());
                ps.setBigDecimal(4, p.avgPrice().setScale(4, RoundingMode.HALF_EVEN));
                ps.setLong(5, item.sourceOffset());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement(CASH_SQL)) {
            for (Item item : cashWrites.values()) {
                PositionChange p = (PositionChange) item.out();
                ps.setBigDecimal(1, p.cash().setScale(2, RoundingMode.HALF_EVEN));
                ps.setLong(2, item.sourceOffset());
                ps.setString(3, p.accountId());
                ps.setLong(4, item.sourceOffset());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement(AUDIT_SQL)) {
            for (Item item : auditWrites) {
                Audit a = Audit.of(item);
                ps.setString(1, actor(item));
                ps.setString(2, a.action());
                if (accounts.contains(item.out().accountId())) ps.setString(3, item.out().accountId());
                else ps.setNull(3, Types.VARCHAR);
                if (a.tickSeq() == Long.MIN_VALUE) ps.setNull(4, Types.BIGINT);
                else ps.setLong(4, a.tickSeq());
                ps.setObject(5, time(a.logTimeMillis()));
                ps.setString(6, a.detailsJson());
                ps.setString(7, a.reason());
                ps.setString(8, a.orderId());
                ps.setString(9, a.eventKey());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return new Counts(marginWrites.size(), decisionWrites.size() + closureWrites.size(), positionWrites.size(), auditWrites.size(), skipped);
    }

    private static void putLast(Map<String, Item> map, String key, Item item) {
        map.remove(key); // re-insert so iteration order is the order of the newest write per key
        map.put(key, item);
    }

    /** Loads the ids Postgres knows, the first time and again whenever a batch names one we have not seen. */
    private void refreshReferenceDataIfNeeded(Connection c, List<Item> batch) throws SQLException {
        boolean unknown = accounts.isEmpty();
        for (Item item : batch) {
            if (unknown) break;
            unknown = !accounts.contains(item.out().accountId())
                    || (item.out() instanceof OrderDecision d && !instruments.contains(d.instrumentId()))
                    || (item.out() instanceof PositionChange p && !instruments.contains(p.instrumentId()));
        }
        if (!unknown) return;
        try (Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id FROM accounts")) {
                while (rs.next()) accounts.add(rs.getString(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT id FROM instruments")) {
                while (rs.next()) instruments.add(rs.getString(1));
            }
        }
    }

    private static String actor(Item item) {
        return "engine:shard-" + item.shard();
    }

    private static OffsetDateTime time(long millis) {
        return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC);
    }

    static BigDecimal rupees(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN);
    }

    static BigDecimal utilisation(double v) {
        if (Double.isInfinite(v) || v > MAX_UTILISATION.doubleValue()) return MAX_UTILISATION;
        return new BigDecimal(v).setScale(4, RoundingMode.HALF_EVEN);
    }

    /** The audit_log row for one output. */
    record Audit(String action, long tickSeq, long logTimeMillis, String reason, String orderId, String eventKey, String detailsJson) {
        static Audit of(Item item) {
            return switch (item.out()) {
                case MarginEvent m -> new Audit("STATUS_CHANGE", m.tickSeq(), m.logTimeMillis(), m.action(), null,
                        "MARGIN:" + m.accountId() + ":" + m.tickSeq(),
                        json("accountId", m.accountId(), "from", m.from().name(), "to", m.to().name(),
                                "required", rupees(m.result().required()), "available", rupees(m.result().available()),
                                "utilisation", utilisation(m.result().utilisation()), "sourceOffset", item.sourceOffset()));
                case OrderDecision d -> new Audit(d.accepted() ? "ORDER_ACCEPTED" : "ORDER_REJECTED",
                        d.lastTickSeq(), d.logTimeMillis(), d.reason().name(), d.orderId(), "DECISION:" + d.orderId(),
                        json("accountId", d.accountId(), "instrumentId", d.instrumentId(), "side", d.side().name(), "qty", d.qty(),
                                "requiredAfter", rupees(d.requiredAfter()), "availableAfter", rupees(d.availableAfter()),
                                "utilisationAfter", utilisation(d.utilisationAfter()), "sourceOffset", item.sourceOffset()));
                case OrderClosure cl -> new Audit("ORDER_CLOSED", Long.MIN_VALUE, cl.logTimeMillis(), cl.reason().name(), cl.orderId(),
                        // An order can be closed more than once in the log; each close is its own audit row.
                        "CLOSED:" + cl.orderId() + ":" + item.sourceOffset(),
                        json("accountId", cl.accountId(), "released", cl.released(), "sourceOffset", item.sourceOffset()));
                case PositionChange p -> throw new IllegalArgumentException("position changes are not audited here");
            };
        }
    }

    /** A flat JSON object from alternating keys and values. Strings are escaped; numbers are written bare. */
    static String json(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(kv[i].toString())).append("\":");
            Object v = kv[i + 1];
            if (v instanceof Number n) sb.append(n instanceof BigDecimal b ? b.toPlainString() : n.toString());
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
}
