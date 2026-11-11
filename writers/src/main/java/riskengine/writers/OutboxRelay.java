package riskengine.writers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import riskengine.events.OrderClosed;
import riskengine.host.FlatJson;
import riskengine.host.RedpandaLogs;

/**
 * Sends orders to the broker: every outbox row that has not been sent yet, oldest first.
 *
 * <p>The writers put an order and its outbox row into Postgres in one transaction. This relay is the
 * other half of the outbox pattern: it turns "a row says this must be sent" into "it was sent", and it
 * may crash anywhere in between, because sending is safe to repeat. The broker dedupes on the
 * idempotency key (for a square-off leg, {@code accountId:episodeId:instrumentId:seq}).
 *
 * <p>For each unsent row:
 * <ul>
 *   <li><b>Acknowledged</b>: mark it sent; the order becomes SENT.</li>
 *   <li><b>Timeout or connection error</b>: the order may or may not exist at the broker. Ask
 *       ({@code GET /orders/{key}}) before doing anything else. If the broker has it, mark it sent. If
 *       it does not, leave the row unsent; the next round sends it again with the same key. If even the
 *       question fails, leave it and try later.</li>
 *   <li><b>Refused as malformed (4xx)</b>: sending again cannot help. Write an OrderClosed(REJECTED) to
 *       the log so the engine releases the order (or re-plans the leg), and mark the row sent.</li>
 * </ul>
 *
 * One relay at a time: rows are sent in id order by a single thread, so two legs of one account can
 * never overtake each other. (Running two relays would need row locking; nothing here does that.)
 */
public final class OutboxRelay implements Runnable, AutoCloseable {
    private final String jdbcUrl, dbUser, dbPassword, brokerUrl;
    private final RedpandaLogs log;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicLong sent = new AtomicLong(), requests = new AtomicLong(), timeouts = new AtomicLong(), confirmedByQuery = new AtomicLong(),
            notFoundAfterTimeout = new AtomicLong(), refused = new AtomicLong();
    private volatile boolean running = true;
    private volatile Thread thread;

    /** @param timeout how long to wait for the broker's answer before treating the request as lost */
    public OutboxRelay(String jdbcUrl, String dbUser, String dbPassword, String brokerUrl, RedpandaLogs log, Duration timeout) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.brokerUrl = brokerUrl.endsWith("/") ? brokerUrl.substring(0, brokerUrl.length() - 1) : brokerUrl;
        this.log = log;
        this.timeout = timeout;
    }

    private record Row(long id, String orderId, String key, String payload) {}

    @Override
    public void run() {
        while (running) {
            try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
                while (running) {
                    List<Row> rows = new ArrayList<>();
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT id, order_id, idempotency_key, payload::text FROM outbox WHERE sent_at IS NULL ORDER BY id LIMIT 200");
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                    }
                    for (Row row : rows) {
                        if (!running) break;
                        send(c, row);
                    }
                    if (rows.isEmpty()) Thread.sleep(50);
                }
            } catch (SQLException e) {
                System.err.println("relay: database problem, will retry: " + e.getMessage());
                pause(1_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void send(Connection c, Row row) throws SQLException, InterruptedException {
        requests.incrementAndGet();
        String problem;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(brokerUrl + "/orders")).timeout(timeout)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(row.payload())).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 == 2) {
                markSent(c, row, null);
                return;
            }
            if (r.statusCode() / 100 == 4) {
                // The broker will never take this order. Tell the engine, so the margin it reserved (or the
                // leg it has in flight) is released.
                refused.incrementAndGet();
                Map<String, String> f = FlatJson.parse(row.payload());
                log.append(new OrderClosed(row.key(), f.get("accountId"), OrderClosed.Reason.REJECTED, System.currentTimeMillis()));
                markSent(c, row, "refused by broker: HTTP " + r.statusCode());
                return;
            }
            problem = "HTTP " + r.statusCode();
        } catch (IOException e) {
            problem = e.getClass().getSimpleName(); // includes HttpTimeoutException
        }
        // No usable answer. Did the order get there? Ask before sending it again.
        timeouts.incrementAndGet();
        try {
            HttpResponse<String> q = http.send(HttpRequest.newBuilder(URI.create(brokerUrl + "/orders/" + row.key())).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (q.statusCode() == 200) {
                confirmedByQuery.incrementAndGet();
                markSent(c, row, "sent; answer lost (" + problem + "), confirmed by query");
                return;
            }
            if (q.statusCode() == 404) notFoundAfterTimeout.incrementAndGet();
            problem += q.statusCode() == 404 ? "; broker does not have it" : "; query answered HTTP " + q.statusCode();
        } catch (IOException e) {
            problem += "; query failed too";
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?")) {
            ps.setString(1, problem);
            ps.setLong(2, row.id());
            ps.executeUpdate();
        }
    }

    private void markSent(Connection c, Row row, String note) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE outbox SET sent_at = now(), attempts = attempts + 1, last_error = ? WHERE id = ? AND sent_at IS NULL")) {
            ps.setString(1, note);
            ps.setLong(2, row.id());
            ps.executeUpdate();
        }
        // ACCEPTED -> SENT only: if fills have already been projected the order is further along than that.
        try (PreparedStatement ps = c.prepareStatement("UPDATE orders SET status = 'SENT', updated_at = now() WHERE id = ? AND status = 'ACCEPTED'")) {
            ps.setString(1, row.orderId());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE square_off_legs SET status = 'SENT', updated_at = now() WHERE leg_key = ? AND status = 'PLANNED'")) {
            ps.setString(1, row.key());
            ps.executeUpdate();
        }
        sent.incrementAndGet();
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    public Thread start() {
        Thread t = new Thread(this, "outbox-relay");
        thread = t;
        t.start();
        return t;
    }

    /** Outbox rows marked sent. */
    public long sent() {
        return sent.get();
    }

    /** POSTs made to the broker, including repeats of the same key. */
    public long requests() {
        return requests.get();
    }

    /** Requests that got no usable answer. */
    public long timeouts() {
        return timeouts.get();
    }

    /** Timeouts where asking the broker showed the order had arrived, so it was not sent again. */
    public long confirmedByQuery() {
        return confirmedByQuery.get();
    }

    /** Timeouts where the broker did not have the order, so it was sent again. */
    public long resentAfterTimeout() {
        return notFoundAfterTimeout.get();
    }

    @Override
    public void close() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
