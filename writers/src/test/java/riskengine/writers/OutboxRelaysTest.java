package riskengine.writers;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import riskengine.broker.MockBroker;
import riskengine.host.RedpandaLogs;
import riskengine.host.Topics;

/** Three outbox relays on one outbox: every row is sent by exactly one of them, once. Real Postgres. */
@Testcontainers
class OutboxRelaysTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");
    @Container
    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.7");

    static final int ROWS = 3_000;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + System.getProperty("migrations.dir")).load().migrate();
    }

    static Connection db() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    static long count(String sql) throws SQLException {
        try (Connection c = db(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** {@code n} accepted orders, each with its outbox row, under keys {@code prefix-0 ..}. */
    static void orders(String prefix, int n) throws SQLException {
        try (Connection c = db()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO instruments (id, underlying, type, expiry, strike, lot_size, trade_date) VALUES ('NIFTY-20240627-FUT', 'NIFTY', 'FUT', '2024-06-27', NULL, 25, '2024-06-03') ON CONFLICT DO NOTHING");
                st.execute("INSERT INTO accounts (id, cash_balance) VALUES ('R1', 1000000) ON CONFLICT DO NOTHING");
            }
            try (PreparedStatement o = c.prepareStatement("INSERT INTO orders (id, account_id, instrument_id, side, qty, order_type, origin, status, created_by) "
                    + "VALUES (?, 'R1', 'NIFTY-20240627-FUT', 'BUY', 25, 'MARKET', 'USER', 'ACCEPTED', 'test')");
                 PreparedStatement x = c.prepareStatement("INSERT INTO outbox (order_id, event_type, idempotency_key, payload) VALUES (?, 'BROKER_PLACE_ORDER', ?, ?::jsonb)")) {
                for (int i = 0; i < n; i++) {
                    String key = prefix + "-" + i;
                    o.setString(1, key);
                    o.addBatch();
                    x.setString(1, key);
                    x.setString(2, key);
                    x.setString(3, Projection.brokerOrder(key, "R1", "NIFTY-20240627-FUT", 25, new BigDecimal("23300")));
                    x.addBatch();
                }
                o.executeBatch();
                x.executeBatch();
            }
            c.commit();
        }
    }

    static void drain(List<OutboxRelay> relays, String prefix) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (count("SELECT count(*) FROM outbox WHERE sent_at IS NULL AND idempotency_key LIKE '" + prefix + "-%'") > 0) {
            assertTrue(System.nanoTime() < deadline, "outbox did not drain");
            Thread.sleep(50);
        }
    }

    @Test
    void threeRelaysSendEveryRowExactlyOnce() throws Exception {
        orders("CALM", ROWS);
        List<OutboxRelay> relays = new ArrayList<>();
        String bootstrap = REDPANDA.getBootstrapServers();
        Topics.ensure(bootstrap, Topics.ENGINE_EVENTS, 4);
        // A well-behaved broker: no timeouts, so every POST is a send decision, and any second POST of a key is a duplicate send.
        try (RedpandaLogs log = new RedpandaLogs(bootstrap, Topics.ENGINE_EVENTS, 4, Duration.ofMillis(100));
             MockBroker broker = new MockBroker(MockBroker.Chaos.none(), "http://localhost:1/unused", 0).start()) {
            for (int r = 1; r <= 3; r++) {
                relays.add(new OutboxRelay(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), "http://localhost:" + broker.port(), log,
                        Duration.ofSeconds(2), () -> 0L).named("relay-" + r));
            }
            relays.forEach(OutboxRelay::start);
            drain(relays, "CALM");
            relays.forEach(OutboxRelay::close);

            Map<String, Long> stats = broker.stats();
            assertEquals(ROWS, stats.get("placeRequests"), "one request per row, in total across the three relays");
            assertEquals(0L, stats.get("repeatsOfAKnownKey"), "duplicate sends");
            assertEquals(ROWS, broker.executed());
            assertEquals(ROWS, relays.stream().mapToLong(OutboxRelay::requests).sum());
            for (OutboxRelay r : relays) assertTrue(r.sent() > ROWS / 10, "all three relays did a share of the work: " + relays.stream().map(OutboxRelay::sent).toList());
            assertEquals(3, count("SELECT count(DISTINCT sent_by) FROM outbox WHERE idempotency_key LIKE 'CALM-%'"));
            assertEquals(ROWS, count("SELECT count(*) FROM outbox WHERE idempotency_key LIKE 'CALM-%' AND attempts = 1"), "no row was attempted twice");
            assertEquals(ROWS, count("SELECT count(*) FROM orders WHERE id LIKE 'CALM-%' AND status = 'SENT'"));
            System.out.printf("3 relays, %d rows: %d requests, 0 duplicates; shares %s%n", ROWS, stats.get("placeRequests"),
                    relays.stream().map(OutboxRelay::sent).toList());
        }
    }

    @Test
    void underChaosThreeRelaysStillNeverSendARowTheBrokerAlreadyHas() throws Exception {
        orders("CHAOS", 1_000);
        String bootstrap = REDPANDA.getBootstrapServers();
        Topics.ensure(bootstrap, Topics.ENGINE_EVENTS, 4);
        List<OutboxRelay> relays = new ArrayList<>();
        try (RedpandaLogs log = new RedpandaLogs(bootstrap, Topics.ENGINE_EVENTS, 4, Duration.ofMillis(100));
             MockBroker broker = new MockBroker(new MockBroker.Chaos(0.05, 0, 0, 0, 450, 7L), "http://localhost:1/unused", 0).start()) {
            for (int r = 1; r <= 3; r++) {
                relays.add(new OutboxRelay(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), "http://localhost:" + broker.port(), log,
                        Duration.ofMillis(200), () -> 0L).named("relay-" + r));
            }
            relays.forEach(OutboxRelay::start);
            drain(relays, "CHAOS");
            relays.forEach(OutboxRelay::close);
            Map<String, Long> stats = broker.stats();
            assertEquals(1_000, broker.executed());
            // A resend happens only after the broker said it did not have the order, so it is never a repeat of a key it has.
            assertEquals(0L, stats.get("repeatsOfAKnownKey"), stats.toString());
            assertTrue(stats.get("timeoutsRequestLost") > 0 && stats.get("timeoutsResponseLost") > 0, stats.toString());
            assertEquals(1_000 + stats.get("timeoutsRequestLost"), stats.get("placeRequests"), "every extra request is a resend of a request the broker never got");
        }
    }
}
