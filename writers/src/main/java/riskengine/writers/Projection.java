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
import java.util.List;
import java.util.TreeSet;
import riskengine.events.OrderClosed;
import riskengine.margin.AccountStatusChange;
import riskengine.margin.EngineOutput;
import riskengine.margin.EpisodeChange;
import riskengine.margin.MarginEvent;
import riskengine.margin.MarginResult;
import riskengine.margin.OrderClosure;
import riskengine.margin.OrderDecision;
import riskengine.margin.PositionChange;
import riskengine.margin.SquareOffLeg;

/**
 * Turns one batch of engine outputs into SQL, inside the caller's transaction.
 *
 * <p>Every statement is parameterised (no value is ever spliced into SQL text) and safe to run twice:
 *
 * <table>
 *   <caption>What each output writes</caption>
 *   <tr><td>MarginEvent</td><td>margin_events, audit_log</td></tr>
 *   <tr><td>OrderDecision</td><td>orders (ACCEPTED / REJECTED, with the margin figures); if accepted, an outbox row for the broker; audit_log</td></tr>
 *   <tr><td>SquareOffLeg</td><td>orders (origin SQUARE_OFF, id = the leg key), outbox row, square_off_legs, audit_log</td></tr>
 *   <tr><td>EpisodeChange</td><td>square_off_episodes (opened, then RESOLVED / EXHAUSTED), audit_log</td></tr>
 *   <tr><td>AccountStatusChange</td><td>accounts.status, audit_log</td></tr>
 *   <tr><td>PositionChange</td><td>positions, accounts.cash_balance, fills, ledger_entries (two rows that sum to zero),
 *       orders and square_off_legs status (PARTIALLY_FILLED / FILLED)</td></tr>
 *   <tr><td>OrderClosure</td><td>orders and square_off_legs status when something was released; audit_log</td></tr>
 * </table>
 *
 * <b>Order and outbox in one transaction.</b> The order row and the outbox row that tells the relay to
 * send it are written in the same database transaction (the batch's). Either both exist or neither does,
 * so there is never an order nobody will send, nor a send for an order that was not stored.
 *
 * <p><b>Replays.</b> Rows inserted once have a unique key and ON CONFLICT DO NOTHING. Rows that are
 * updated carry the source offset that last wrote them and only accept a newer one. So when a restarted
 * shard publishes its whole history again, nothing changes; in particular no outbox row is created
 * twice, so nothing is sent to the broker twice.
 *
 * <p>Rows that would break a foreign key (an account or instrument Postgres has never heard of) are
 * skipped and counted; the audit row is still written, without the account link.
 */
final class Projection {
    /** One engine output plus where it came from. */
    record Item(int shard, long sourceOffset, EngineOutput out) {}

    record Counts(int rows, int skipped) {}

    private static final String MARGIN_SQL = """
            INSERT INTO margin_events (account_id, tick_seq, log_time, required, available, utilisation,
                                       expected_utilisation, status_from, status_to, action, source_offset)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_id, source_offset) DO NOTHING""";
    private static final String DECISION_SQL = """
            INSERT INTO orders (id, account_id, instrument_id, side, qty, order_type, origin, status, reject_reason,
                                required_after, available_after, created_by, last_event_seq)
            VALUES (?, ?, ?, ?, ?, 'MARKET', 'USER', ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE
               SET status = EXCLUDED.status, reject_reason = EXCLUDED.reject_reason,
                   required_after = EXCLUDED.required_after, available_after = EXCLUDED.available_after,
                   last_event_seq = EXCLUDED.last_event_seq, updated_at = now()
             WHERE orders.last_event_seq < EXCLUDED.last_event_seq""";
    private static final String LEG_ORDER_SQL = """
            INSERT INTO orders (id, account_id, instrument_id, side, qty, order_type, origin, status, idempotency_key,
                                created_by, last_event_seq)
            VALUES (?, ?, ?, ?, ?, 'MARKET', 'SQUARE_OFF', 'ACCEPTED', ?, ?, ?)
            ON CONFLICT (id) DO NOTHING""";
    private static final String OUTBOX_SQL = """
            INSERT INTO outbox (order_id, event_type, idempotency_key, payload)
            VALUES (?, 'BROKER_PLACE_ORDER', ?, ?::jsonb)
            ON CONFLICT (idempotency_key) DO NOTHING""";
    private static final String EPISODE_OPEN_SQL = """
            INSERT INTO square_off_episodes (episode_id, account_id, started_tick_seq, status, started_at, mode, actor)
            VALUES (?, ?, ?, 'OPEN', ?, ?, ?)
            ON CONFLICT (episode_id) DO NOTHING""";
    private static final String EPISODE_END_SQL = """
            UPDATE square_off_episodes SET status = ?, ended_tick_seq = ?, ended_at = ?, legs = ?, mode = ?, actor = ?
             WHERE episode_id = ? AND status = 'OPEN'""";
    private static final String LEG_SQL = """
            INSERT INTO square_off_legs (leg_key, episode_id, account_id, instrument_id, seq, qty, expected_release, order_id,
                                         status, planned_tick_seq, mark, last_event_seq)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PLANNED', ?, ?, ?)
            ON CONFLICT (leg_key) DO NOTHING""";
    private static final String LEG_STATUS_SQL = """
            UPDATE square_off_legs SET status = ?, last_event_seq = ?, updated_at = now()
             WHERE leg_key = ? AND last_event_seq < ?""";
    private static final String ORDER_STATUS_SQL = """
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
    private static final String ACCOUNT_STATUS_SQL = """
            UPDATE accounts SET status = ?, status_event_seq = ?, updated_at = now()
             WHERE id = ? AND status_event_seq < ?""";
    // Only for an order Postgres knows (fills.order_id is a foreign key).
    private static final String FILL_SQL = """
            INSERT INTO fills (broker_fill_id, order_id, account_id, instrument_id, qty, price, filled_at)
            SELECT ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM orders WHERE id = ?)
            ON CONFLICT (broker_fill_id) DO NOTHING""";
    // Both sides of the entry in one statement, and only if the transaction is not booked yet: the two
    // rows are stored together or not at all, so a transaction can never be half there.
    private static final String LEDGER_SQL = """
            INSERT INTO ledger_entries (txn_id, account, account_id, amount, reason, ref_id, log_time)
            SELECT v.* FROM (VALUES (?, 'CASH', ?, ?::numeric, ?, ?, ?::timestamptz),
                                    (?, ?, NULL, ?::numeric, ?, ?, ?::timestamptz)) AS v
             WHERE NOT EXISTS (SELECT 1 FROM ledger_entries WHERE txn_id = ?)
            ON CONFLICT (txn_id, account) DO NOTHING""";
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
        int skipped = 0, rows = 0;
        // Statements run grouped by kind, parents before children (orders before outbox, fills and legs).
        // Within a kind the batch keeps log order; across kinds the order does not matter, because every
        // update is guarded by its source offset: the newest one wins whichever runs first.
        try (PreparedStatement margin = c.prepareStatement(MARGIN_SQL);
             PreparedStatement decision = c.prepareStatement(DECISION_SQL);
             PreparedStatement legOrder = c.prepareStatement(LEG_ORDER_SQL);
             PreparedStatement outbox = c.prepareStatement(OUTBOX_SQL);
             PreparedStatement episodeOpen = c.prepareStatement(EPISODE_OPEN_SQL);
             PreparedStatement episodeEnd = c.prepareStatement(EPISODE_END_SQL);
             PreparedStatement leg = c.prepareStatement(LEG_SQL);
             PreparedStatement legStatus = c.prepareStatement(LEG_STATUS_SQL);
             PreparedStatement orderStatus = c.prepareStatement(ORDER_STATUS_SQL);
             PreparedStatement position = c.prepareStatement(POSITION_SQL);
             PreparedStatement cash = c.prepareStatement(CASH_SQL);
             PreparedStatement accountStatus = c.prepareStatement(ACCOUNT_STATUS_SQL);
             PreparedStatement fill = c.prepareStatement(FILL_SQL);
             PreparedStatement ledger = c.prepareStatement(LEDGER_SQL);
             PreparedStatement audit = c.prepareStatement(AUDIT_SQL)) {
            for (Item item : batch) {
                long offset = item.sourceOffset();
                boolean knownAccount = accounts.contains(item.out().accountId());
                switch (item.out()) {
                    case MarginEvent m -> {
                        if (knownAccount) {
                            MarginResult r = m.result();
                            set(margin, m.accountId(), m.tickSeq(), time(m.logTimeMillis()), rupees(r.required()), rupees(r.available()),
                                    utilisation(r.utilisation()), utilisation(m.expectedUtilisation()), m.from().name(), m.to().name(), m.action(), offset);
                        } else {
                            skipped++;
                        }
                    }
                    case OrderDecision d -> {
                        if (knownAccount && instruments.contains(d.instrumentId())) {
                            set(decision, d.orderId(), d.accountId(), d.instrumentId(), d.side().name(), d.qty(), d.accepted() ? "ACCEPTED" : "REJECTED",
                                    d.accepted() ? null : d.reason().name(), rupees(d.requiredAfter()), rupees(d.availableAfter()), actor(item), offset);
                            if (d.accepted()) {
                                long signed = d.side() == riskengine.events.OrderRequest.Side.BUY ? d.qty() : -d.qty();
                                set(outbox, d.orderId(), d.orderId(), brokerOrder(d.orderId(), d.accountId(), d.instrumentId(), signed, d.mark()));
                            }
                        } else {
                            skipped++;
                        }
                    }
                    case SquareOffLeg l -> {
                        if (knownAccount && instruments.contains(l.instrumentId())) {
                            set(legOrder, l.legKey(), l.accountId(), l.instrumentId(), l.qty() > 0 ? "BUY" : "SELL", Math.abs(l.qty()), l.legKey(), actor(item), offset);
                            set(outbox, l.legKey(), l.legKey(), brokerOrder(l.legKey(), l.accountId(), l.instrumentId(), l.qty(), l.mark()));
                            set(leg, l.legKey(), episodeKey(l.accountId(), l.episodeId()), l.accountId(), l.instrumentId(), l.seq(), l.qty(),
                                    rupees(l.expectedRelease()), l.legKey(), l.tickSeq(), l.mark(), offset);
                        } else {
                            skipped++;
                        }
                    }
                    case EpisodeChange e -> {
                        if (!knownAccount) {
                            skipped++;
                        } else if (e.state() == EpisodeChange.State.OPENED) {
                            set(episodeOpen, episodeKey(e.accountId(), e.episodeId()), e.accountId(), e.tickSeq(), time(e.logTimeMillis()), e.mode().name(), e.actor());
                        } else {
                            set(episodeEnd, e.state().name(), e.tickSeq(), time(e.logTimeMillis()), e.legs(), e.mode().name(), e.actor(),
                                    episodeKey(e.accountId(), e.episodeId()));
                        }
                    }
                    case AccountStatusChange s -> {
                        if (knownAccount) set(accountStatus, s.to().name(), offset, s.accountId(), offset);
                        else skipped++;
                    }
                    case OrderClosure cl -> {
                        if (cl.released() != 0) {
                            boolean rejected = cl.reason() == OrderClosed.Reason.REJECTED;
                            set(orderStatus, rejected ? "BROKER_REJECTED" : "CANCELLED", cl.reason().name(), offset, cl.orderId(), offset);
                            set(legStatus, rejected ? "REJECTED" : "TIMED_OUT", offset, cl.orderId(), offset); // no row unless it is a leg
                        }
                    }
                    case PositionChange p -> {
                        if (knownAccount && instruments.contains(p.instrumentId())) {
                            set(position, p.accountId(), p.instrumentId(), p.qty(), p.avgPrice().setScale(4, RoundingMode.HALF_EVEN), offset);
                            set(cash, p.cash().setScale(2, RoundingMode.HALF_EVEN), offset, p.accountId(), offset);
                            set(fill, p.fillId(), p.orderId(), p.accountId(), p.instrumentId(), p.fillQty(), p.fillPrice().setScale(4, RoundingMode.HALF_EVEN),
                                    time(p.logTimeMillis()), p.orderId());
                            BigDecimal amount = p.cashDelta().setScale(2, RoundingMode.HALF_EVEN);
                            if (amount.signum() != 0) {
                                // Options: the premium changes hands with the clearing side. Futures: the
                                // profit or loss the fill realised. The two rows always sum to zero.
                                boolean future = p.instrumentId().endsWith("-FUT");
                                String txn = "FILL:" + p.fillId(), reason = future ? "REALISED_PNL" : "PREMIUM";
                                OffsetDateTime at = time(p.logTimeMillis());
                                set(ledger, txn, p.accountId(), amount, reason, p.fillId(), at,
                                        txn, future ? "REALISED_PNL" : "BROKER_CLEARING", amount.negate(), reason, p.fillId(), at, txn);
                            }
                            if (p.orderRemaining() != PositionChange.NOT_TRACKED) {
                                boolean done = p.orderRemaining() == 0;
                                set(orderStatus, done ? "FILLED" : "PARTIALLY_FILLED", null, offset, p.orderId(), offset);
                                if (done) set(legStatus, "FILLED", offset, p.orderId(), offset);
                            }
                        } else {
                            skipped++;
                        }
                    }
                }
                Audit a = Audit.of(item);
                if (a != null) {
                    set(audit, a.actor(), a.action(), knownAccount ? item.out().accountId() : null, a.tickSeq() == Long.MIN_VALUE ? null : a.tickSeq(),
                            time(a.logTimeMillis()), a.detailsJson(), a.reason(), a.requestId(), a.eventKey());
                }
            }
            for (PreparedStatement ps : List.of(margin, decision, legOrder, outbox, episodeOpen, episodeEnd, leg, position, cash, accountStatus,
                    fill, ledger, orderStatus, legStatus, audit)) {
                rows += ps.executeBatch().length;
            }
        }
        return new Counts(rows, skipped);
    }

    /** Binds the parameters in order and adds the statement to its batch. A null is bound as an untyped NULL. */
    private static void set(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p == null) ps.setNull(i + 1, Types.NULL);
            else if (p instanceof String s) ps.setString(i + 1, s);
            else if (p instanceof Long l) ps.setLong(i + 1, l);
            else if (p instanceof Integer n) ps.setInt(i + 1, n);
            else if (p instanceof BigDecimal b) ps.setBigDecimal(i + 1, b);
            else ps.setObject(i + 1, p);
        }
        ps.addBatch();
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

    /** square_off_episodes.episode_id: episode ids (E1, E2, ...) are per account. */
    static String episodeKey(String accountId, String episodeId) {
        return accountId + ":" + episodeId;
    }

    /** The outbox payload: everything the broker needs, and the key it must dedupe on. */
    static String brokerOrder(String key, String accountId, String instrumentId, long signedQty, BigDecimal refPrice) {
        return json("key", key, "accountId", accountId, "instrumentId", instrumentId, "qty", signedQty, "refPrice", refPrice);
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

    /** The audit_log row for one output; null for outputs that are not decisions (position changes). */
    record Audit(String actor, String action, long tickSeq, long logTimeMillis, String reason, String requestId, String eventKey, String detailsJson) {
        static Audit of(Item item) {
            String engine = Projection.actor(item);
            long o = item.sourceOffset();
            return switch (item.out()) {
                case MarginEvent m -> new Audit(engine, "LEVEL_CHANGE", m.tickSeq(), m.logTimeMillis(), m.action(), null,
                        "MARGIN:" + m.accountId() + ":" + o,
                        json("accountId", m.accountId(), "from", m.from().name(), "to", m.to().name(),
                                "required", rupees(m.result().required()), "available", rupees(m.result().available()),
                                "utilisation", utilisation(m.result().utilisation()), "expectedUtilisation", utilisation(m.expectedUtilisation()), "sourceOffset", o));
                case OrderDecision d -> new Audit(engine, d.accepted() ? "ORDER_ACCEPTED" : "ORDER_REJECTED",
                        d.lastTickSeq(), d.logTimeMillis(), d.reason().name(), d.orderId(), "DECISION:" + d.orderId(),
                        json("accountId", d.accountId(), "instrumentId", d.instrumentId(), "side", d.side().name(), "qty", d.qty(),
                                "requiredAfter", rupees(d.requiredAfter()), "availableAfter", rupees(d.availableAfter()),
                                "utilisationAfter", utilisation(d.utilisationAfter()), "sourceOffset", o));
                case OrderClosure cl -> new Audit(engine, "ORDER_CLOSED", Long.MIN_VALUE, cl.logTimeMillis(), cl.reason().name(), cl.orderId(),
                        // An order can be closed more than once in the log; each close is its own audit row.
                        "CLOSED:" + cl.orderId() + ":" + o,
                        json("accountId", cl.accountId(), "released", cl.released(), "sourceOffset", o));
                case SquareOffLeg l -> new Audit(engine, "SQUARE_OFF_LEG", l.tickSeq(), l.logTimeMillis(), "PLANNED", l.legKey(), "LEG:" + l.legKey(),
                        json("accountId", l.accountId(), "episodeId", l.episodeId(), "instrumentId", l.instrumentId(), "seq", l.seq(), "qty", l.qty(),
                                "mark", l.mark(), "expectedRelease", rupees(l.expectedRelease()), "sourceOffset", o));
                case EpisodeChange e -> new Audit(e.actor().equals("engine") ? engine : e.actor(), "SQUARE_OFF_" + e.state().name(), e.tickSeq(), e.logTimeMillis(),
                        e.mode().name(), null, "EPISODE:" + e.accountId() + ":" + e.episodeId() + ":" + e.state(),
                        json("accountId", e.accountId(), "episodeId", e.episodeId(), "mode", e.mode().name(), "legs", e.legs(), "sourceOffset", o));
                case AccountStatusChange s -> new Audit(s.actor().equals("engine") ? engine : s.actor(), "ACCOUNT_STATUS", s.tickSeq(), s.logTimeMillis(),
                        s.cause(), null, "STATUS:" + s.accountId() + ":" + o + ":" + s.to(),
                        json("accountId", s.accountId(), "from", s.from().name(), "to", s.to().name(), "sourceOffset", o));
                case PositionChange p -> null;
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
