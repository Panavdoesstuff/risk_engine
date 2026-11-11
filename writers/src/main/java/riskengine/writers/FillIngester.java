package riskengine.writers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import riskengine.events.Event;
import riskengine.events.Fill;
import riskengine.events.OrderClosed;
import riskengine.host.FlatJson;
import riskengine.host.RedpandaLogs;

/**
 * Where the broker's callbacks arrive. It is the only thing that writes fills to the log, and it is the
 * gate in front of the shards: a shard stops for good on a fill it cannot apply (decision 41), so nothing
 * is written here unless it is certain to apply.
 *
 * <pre>
 *   POST /broker/callbacks   {"type":"FILL","key","fillId","accountId","instrumentId","qty","price"}
 *                            {"type":"REJECT","key","accountId","instrumentId"}
 * </pre>
 *
 * A callback is refused with 422 (and recorded in the audit log) unless
 * <ul>
 *   <li>it is exactly that shape, with well-formed ids, a non-zero quantity and a non-negative price;</li>
 *   <li>its key is an order we know, <b>for that account and that instrument</b>. The order row has
 *       foreign keys to both, so this also proves the account and the instrument exist, and it stops a
 *       fill being booked to the wrong account.</li>
 * </ul>
 * A fill is also refused (422, audited, reason {@value #ALREADY_BOOKED}) if its broker fill id is already in
 * the {@code fills} table. Such a fill has been applied by its shard and booked by the writers, so writing it
 * again can only do harm: the shard forgets fill ids after two days of log time (decision 111) and would
 * apply a very late repeat that Postgres then refuses, leaving the two disagreeing.
 * Otherwise it is written to the account's partition as a Fill (order id = the key) or an
 * OrderClosed(REJECTED), and answered 200 once the broker has acknowledged the write. If the log or the
 * database is unreachable the answer is 503 and the broker tries again.
 *
 * <p>A duplicate that arrives before the first copy is booked in Postgres is still let through: the shard
 * ignores a fill id it has seen, and that is the one place where "seen" is decided in log order.
 */
public final class FillIngester implements AutoCloseable {
    /** The reason recorded when a fill id is already in the fills table. */
    public static final String ALREADY_BOOKED = "fill already booked";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,120}");

    private final String jdbcUrl, dbUser, dbPassword;
    private final RedpandaLogs log;
    private final java.util.function.LongSupplier clock;
    private final HttpServer server;
    private final AtomicLong fills = new AtomicLong(), rejects = new AtomicLong(), refused = new AtomicLong(), alreadyBooked = new AtomicLong();
    private final ThreadLocal<Connection> connections = new ThreadLocal<>();

    /** @param clock log time to stamp fills with: the replay's time (see ReplayClock), never the wall clock in a replay */
    public FillIngester(int port, String jdbcUrl, String dbUser, String dbPassword, RedpandaLogs log, java.util.function.LongSupplier clock) throws IOException {
        this.clock = clock;
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.log = log;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "fill-ingester");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/broker/callbacks", this::handle);
    }

    public FillIngester start() {
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            respond(ex, 405, "POST only");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            String problem = ingest(body);
            if (problem == null) {
                respond(ex, 200, "ok");
            } else {
                (problem.equals(ALREADY_BOOKED) ? alreadyBooked : refused).incrementAndGet();
                audit(problem, body);
                respond(ex, 422, problem);
            }
        } catch (SQLException | RuntimeException e) {
            // Database or log unreachable: nothing was decided. The broker will call again.
            dropConnection();
            respond(ex, 503, "try again");
        }
    }

    /** @return null if the callback was written to the log; otherwise why it was refused */
    private String ingest(String body) throws SQLException {
        Map<String, String> f;
        try {
            f = FlatJson.parse(body);
        } catch (IllegalArgumentException e) {
            return "not a valid callback";
        }
        String type = f.get("type"), key = f.get("key"), account = f.get("accountId"), instrument = f.get("instrumentId");
        if (type == null || key == null || account == null || instrument == null) return "missing field";
        if (!ID.matcher(key).matches() || !ID.matcher(account).matches() || !ID.matcher(instrument).matches()) return "malformed id";
        Event event;
        long now = clock.getAsLong(); // replay time: the latest tick's log time
        if (type.equals("FILL")) {
            String fillId = f.get("fillId");
            if (fillId == null || !ID.matcher(fillId).matches()) return "malformed fill id";
            long qty;
            BigDecimal price;
            try {
                qty = Long.parseLong(String.valueOf(f.get("qty")));
                price = new BigDecimal(String.valueOf(f.get("price")));
            } catch (NumberFormatException e) {
                return "qty or price is not a number";
            }
            if (qty == 0 || price.signum() < 0 || price.scale() > 4) return "bad qty or price";
            event = new Fill(fillId, key, account, instrument, qty, price, now);
        } else if (type.equals("REJECT")) {
            event = new OrderClosed(key, account, OrderClosed.Reason.REJECTED, now);
        } else {
            return "unknown type";
        }
        // The order must be one we placed, for this account and this instrument.
        try (PreparedStatement ps = connection().prepareStatement("SELECT account_id, instrument_id FROM orders WHERE id = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "unknown order";
                if (!rs.getString(1).equals(account)) return "order belongs to another account";
                if (!rs.getString(2).equals(instrument)) return "order is for another instrument";
            }
        }
        // A fill already booked has been applied; never write it to the log again.
        if (event instanceof Fill fill) {
            try (PreparedStatement ps = connection().prepareStatement("SELECT 1 FROM fills WHERE broker_fill_id = ?")) {
                ps.setString(1, fill.fillId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return ALREADY_BOOKED;
                }
            }
        }
        log.append((riskengine.events.AccountEvent) event);
        (event instanceof Fill ? fills : rejects).incrementAndGet();
        return null;
    }

    private void audit(String problem, String body) {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT INTO audit_log (actor, action, log_time, details, reason) VALUES ('fill-ingester', 'CALLBACK_REFUSED', now(), ?::jsonb, ?)")) {
            ps.setString(1, FlatJson.write("callback", body.length() > 2_000 ? body.substring(0, 2_000) : body));
            ps.setString(2, problem);
            ps.executeUpdate();
        } catch (SQLException e) {
            dropConnection();
            System.err.println("fill ingester: refused a callback (" + problem + ") but could not record it: " + e.getMessage());
        }
    }

    private Connection connection() throws SQLException {
        Connection c = connections.get();
        if (c == null || c.isClosed()) {
            c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
            connections.set(c);
        }
        return c;
    }

    private void dropConnection() {
        Connection c = connections.get();
        connections.remove();
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // already broken
            }
        }
    }

    private static void respond(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Fills written to the log (duplicates included). */
    public long fills() {
        return fills.get();
    }

    public long rejects() {
        return rejects.get();
    }

    /** Fill callbacks refused because the fill id was already in the fills table. */
    public long alreadyBooked() {
        return alreadyBooked.get();
    }

    /** Callbacks refused because they did not match an order we placed (already-booked fills are counted apart). */
    public long refused() {
        return refused.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
