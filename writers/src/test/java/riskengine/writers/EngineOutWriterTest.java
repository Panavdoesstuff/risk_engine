package riskengine.writers;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import riskengine.events.AccountEvent;
import riskengine.events.Event;
import riskengine.events.OrderClosed;
import riskengine.events.OrderRequest;
import riskengine.events.Tick;
import riskengine.host.RedpandaLogs;
import riskengine.host.ShardHost;
import riskengine.host.Topics;
import riskengine.margin.AccountBook;
import riskengine.margin.AccountStatusChange;
import riskengine.margin.EpisodeChange;
import riskengine.margin.SquareOffLeg;
import riskengine.margin.EngineOutput;
import riskengine.margin.MarginEvent;
import riskengine.margin.OrderClosure;
import riskengine.margin.OrderDecision;
import riskengine.margin.Position;
import riskengine.margin.PositionChange;
import riskengine.margin.ReferenceMarginEngine;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentCsv;
import riskengine.shard.Fixtures;
import riskengine.shard.ShardConfig;

/** Real Postgres (with the real migrations) and real Redpanda, in containers. Needs Docker. */
@Testcontainers
class EngineOutWriterTest {
    @Container
    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.7");
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

    static final int N = 4;

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

    /** Every projected row except wall-clock columns, as text, in a fixed order. */
    static List<String> snapshot() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = db(); Statement st = c.createStatement()) {
            for (String sql : List.of(
                    "SELECT 'position', account_id, instrument_id, net_qty, avg_price, last_event_seq FROM positions ORDER BY 2, 3",
                    "SELECT 'account', id, cash_balance, last_event_seq, status, status_event_seq FROM accounts ORDER BY 2",
                    "SELECT 'order', id, account_id, instrument_id, side, qty, origin, status, reject_reason, required_after, available_after, last_event_seq FROM orders ORDER BY 2",
                    "SELECT 'margin', account_id, source_offset, tick_seq, required, available, utilisation, expected_utilisation, status_from, status_to, action FROM margin_events ORDER BY 2, 3",
                    "SELECT 'episode', episode_id, account_id, status, mode, actor, legs, started_tick_seq, ended_tick_seq FROM square_off_episodes ORDER BY 2",
                    "SELECT 'leg', leg_key, episode_id, seq, qty, expected_release, status, mark, last_event_seq FROM square_off_legs ORDER BY 2",
                    "SELECT 'outbox', idempotency_key, order_id, event_type, payload::text, sent_at FROM outbox ORDER BY 2",
                    "SELECT 'fill', broker_fill_id, order_id, qty, price FROM fills ORDER BY 2",
                    "SELECT 'ledger', txn_id, account, account_id, amount, reason, ref_id FROM ledger_entries ORDER BY 2, 3",
                    "SELECT 'audit', event_key, actor, action, account_id, tick_seq, reason, request_id, details::text FROM audit_log ORDER BY 2")) {
                try (ResultSet rs = st.executeQuery(sql)) {
                    int cols = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 1; i <= cols; i++) sb.append(rs.getString(i)).append('|');
                        out.add(sb.toString());
                    }
                }
            }
        }
        return out;
    }

    static void await(String what, java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!done.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
            Thread.sleep(50);
        }
    }

    @Test
    void theProjectionMatchesTheEngineAndReplayingEverythingChangesNothing(@TempDir Path dir) throws Exception {
        String bootstrap = REDPANDA.getBootstrapServers();
        Topics.ensure(bootstrap, Topics.ENGINE_EVENTS, N);
        Topics.ensure(bootstrap, Topics.ENGINE_OUT, N);
        List<Instrument> instruments = Fixtures.instruments();
        List<AccountBook> books = Fixtures.books(400, 41);
        List<Event> events = new ArrayList<>(Fixtures.events(books, 40, 25, 42));
        // One order for an account Postgres has never heard of: the shard rejects it, and its orders row
        // cannot be written (foreign key). It must be skipped without stopping the stream.
        events.add(new OrderRequest("O-ghost", "A-GHOST", instruments.get(0).id(), OrderRequest.Side.BUY, 25, OrderRequest.Origin.USER, Fixtures.T0));

        // The same files the shard host reads go into Postgres.
        InstrumentCsv.write(dir.resolve("instruments.csv"), instruments);
        StringBuilder accounts = new StringBuilder("account_id,archetype,thin_funded,cash_balance\n");
        StringBuilder positions = new StringBuilder("account_id,instrument_id,lots,qty,avg_price\n");
        for (AccountBook b : books) {
            accounts.append(b.id()).append(",TEST,false,").append(b.cash().toPlainString()).append('\n');
            b.positions().forEach((id, p) -> positions.append(b.id()).append(',').append(id).append(',').append(p.qty() / 25).append(',')
                    .append(p.qty()).append(',').append(p.avgPrice().toPlainString()).append('\n'));
        }
        Files.writeString(dir.resolve("accounts.csv"), accounts, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("positions.csv"), positions, StandardCharsets.UTF_8);
        BookLoader.Loaded loaded = BookLoader.load(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                dir.resolve("instruments.csv"), dir, LocalDate.of(2024, 6, 3));
        assertEquals(400, loaded.accounts());
        assertEquals(loaded, BookLoader.load(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                dir.resolve("instruments.csv"), dir, LocalDate.of(2024, 6, 3)), "loading twice is harmless");
        assertEquals(400, count("SELECT count(*) FROM accounts"));

        // What should end up in Postgres, worked out by the single-threaded reference engine.
        ReferenceMarginEngine reference = new ReferenceMarginEngine(Fixtures.CFG, instruments, books);
        List<EngineOutput> expected = new ArrayList<>();
        for (Event e : events) expected.addAll(reference.apply(e));
        long margins = expected.stream().filter(o -> o instanceof MarginEvent).count();
        long decisions = expected.stream().filter(o -> o instanceof OrderDecision).count();
        long closures = expected.stream().filter(o -> o instanceof OrderClosure).count();
        long legs = expected.stream().filter(o -> o instanceof SquareOffLeg).count();
        long episodes = expected.stream().filter(o -> o instanceof EpisodeChange).count();
        long statuses = expected.stream().filter(o -> o instanceof AccountStatusChange).count();
        Map<String, String> expectedStatus = new TreeMap<>();
        for (EngineOutput o : expected) {
            if (o instanceof OrderDecision d && !d.accountId().equals("A-GHOST")) expectedStatus.put(d.orderId(), d.accepted() ? "ACCEPTED" : "REJECTED");
            if (o instanceof SquareOffLeg l) expectedStatus.put(l.legKey(), "ACCEPTED"); // a leg is an order too, waiting to be sent
            if (o instanceof OrderClosure c && c.released() != 0) expectedStatus.put(c.orderId(), c.reason() == OrderClosed.Reason.REJECTED ? "BROKER_REJECTED" : "CANCELLED");
        }
        assertTrue(margins > 50 && decisions > 20 && expectedStatus.containsValue("CANCELLED") && expectedStatus.containsValue("ACCEPTED")
                && expectedStatus.containsValue("REJECTED"), "the stream exercises every kind of row: " + margins + " " + decisions + " " + expectedStatus.values());

        try (EngineOutWriter writer = new EngineOutWriter(bootstrap, Topics.ENGINE_OUT, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            writer.start();
            try (ShardHost host = new ShardHost(bootstrap, N, Fixtures.CFG, ShardConfig.defaults(), instruments, books);
                 RedpandaLogs log = new RedpandaLogs(bootstrap, Topics.ENGINE_EVENTS, N, Duration.ofMillis(100))) {
                host.start();
                for (Event e : events) {
                    if (e instanceof AccountEvent a) log.appendAsync(a);
                    else for (int p = 0; p < N; p++) log.appendAsync(p, (Tick) e);
                }
                log.flush();
                host.awaitCaughtUp(Duration.ofSeconds(60));
            }
            await("the writer to store every output", () -> writer.records() == expected.size());

            // Rows that are inserted once.
            assertEquals(margins, count("SELECT count(*) FROM margin_events"));
            assertEquals(margins + decisions + closures + legs + episodes + statuses, count("SELECT count(*) FROM audit_log"), "one audit row per engine decision");
            // Square-off: every leg has its order row and its outbox row (written in the same transaction), every
            // accepted user order has an outbox row too, and episodes and account statuses match the engine.
            assertTrue(legs > 5 && episodes > 5, "the stream squares accounts off: " + legs + " legs, " + episodes + " episode changes");
            assertEquals(legs, count("SELECT count(*) FROM square_off_legs l JOIN orders o ON o.id = l.leg_key AND o.origin = 'SQUARE_OFF' AND o.idempotency_key = l.leg_key "
                    + "JOIN outbox x ON x.idempotency_key = l.leg_key AND x.order_id = o.id AND x.payload->>'key' = l.leg_key"));
            long acceptedOrders = expected.stream().filter(o -> o instanceof OrderDecision d && d.accepted()).count();
            assertEquals(legs + acceptedOrders, count("SELECT count(*) FROM outbox WHERE sent_at IS NULL"));
            assertEquals(expected.stream().filter(o -> o instanceof EpisodeChange e && e.state() == EpisodeChange.State.OPENED).count(), count("SELECT count(*) FROM square_off_episodes"));
            Map<String, String> engineStatus = new TreeMap<>(), pgStatus = new TreeMap<>();
            for (AccountBook b : books) engineStatus.put(b.id(), reference.status(b.id()).name());
            try (Connection c = db(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, status FROM accounts")) {
                while (rs.next()) pgStatus.put(rs.getString(1), rs.getString(2));
            }
            assertEquals(engineStatus, pgStatus, "accounts.status");
            assertTrue(pgStatus.containsValue("SQUARING_OFF") && pgStatus.containsValue("MARGIN_CALL"), pgStatus.values().toString());
            // Fills and the ledger. Every transaction sums to zero (the schema would refuse it otherwise).
            long fillsForKnownOrders = count("SELECT count(*) FROM fills");
            assertEquals(0, fillsForKnownOrders, "no fill in this stream belongs to an order Postgres has, so none is stored");
            assertTrue(count("SELECT count(DISTINCT txn_id) FROM ledger_entries") > 500);
            assertEquals(0, count("SELECT count(*) FROM (SELECT txn_id FROM ledger_entries GROUP BY txn_id HAVING sum(amount) <> 0 OR count(*) <> 2) t"));
            assertEquals(1, count("SELECT count(*) FROM audit_log WHERE request_id = 'O-ghost' AND account_id IS NULL AND action = 'ORDER_REJECTED' AND reason = 'UNKNOWN_ACCOUNT'"));
            assertEquals(1, writer.skipped(), "only the ghost account's order row");
            // Order status.
            Map<String, String> status = new TreeMap<>();
            try (Connection c = db(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, status FROM orders")) {
                while (rs.next()) status.put(rs.getString(1), rs.getString(2));
            }
            assertEquals(expectedStatus, status);
            // Positions and cash: Postgres equals the engine, account by account.
            Map<String, String> pgPositions = new TreeMap<>();
            try (Connection c = db(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT account_id, instrument_id, net_qty, avg_price FROM positions WHERE net_qty <> 0")) {
                while (rs.next()) pgPositions.put(rs.getString(1) + " " + rs.getString(2), rs.getLong(3) + " @ " + rs.getBigDecimal(4));
            }
            Map<String, String> enginePositions = new TreeMap<>();
            Map<String, BigDecimal> engineCash = new TreeMap<>();
            for (AccountBook b : books) {
                for (Map.Entry<String, Position> e : reference.positions(b.id()).entrySet()) {
                    enginePositions.put(b.id() + " " + e.getKey(), e.getValue().qty() + " @ " + e.getValue().avgPrice().setScale(4, RoundingMode.HALF_EVEN));
                }
                engineCash.put(b.id(), reference.cash(b.id()).setScale(2, RoundingMode.HALF_EVEN));
            }
            assertEquals(enginePositions, pgPositions);
            Map<String, BigDecimal> pgCash = new TreeMap<>();
            try (Connection c = db(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, cash_balance FROM accounts")) {
                while (rs.next()) pgCash.put(rs.getString(1), rs.getBigDecimal(2));
            }
            assertEquals(engineCash, pgCash);

            // Now the shard host restarts. With no snapshot it replays its whole log and publishes every
            // output again. The writer reads all of it, and not one projected value changes.
            List<String> before = snapshot();
            try (ShardHost restarted = new ShardHost(bootstrap, N, Fixtures.CFG, ShardConfig.defaults(), instruments, books)) {
                restarted.start();
                restarted.awaitCaughtUp(Duration.ofSeconds(60));
            }
            await("the writer to read the replay", () -> writer.records() == 2L * expected.size());
            assertEquals(before, snapshot(), "replaying every output is a no-op");
            assertEquals(0, writer.failedBatches());
            System.out.printf("writers: %d outputs projected (%d level changes, %d decisions, %d closures, %d position changes, %d legs, %d episode changes, %d status changes) in %d batches; replay of all %d changed nothing%n",
                    expected.size(), margins, decisions, closures, expected.stream().filter(o -> o instanceof PositionChange).count(), legs, episodes, statuses, writer.batches(), expected.size());
        }
    }

    @Test
    void anOlderOutputNeverOverwritesANewerOne() throws Exception {
        try (Connection c = db()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO instruments (id, underlying, type, expiry, strike, lot_size, trade_date) VALUES ('NIFTY-20240627-FUT', 'NIFTY', 'FUT', '2024-06-27', NULL, 25, '2024-06-03') ON CONFLICT DO NOTHING");
                st.execute("INSERT INTO accounts (id, cash_balance) VALUES ('GUARD', 1000) ON CONFLICT DO NOTHING");
            }
            Projection projection = new Projection();
            PositionChange older = new PositionChange("GUARD", "NIFTY-20240627-FUT", 25, new BigDecimal("23000"), new BigDecimal("1000"), "GF-1", "O-guard", 25,
                    new BigDecimal("23000"), BigDecimal.ZERO, 0, 1);
            PositionChange newer = new PositionChange("GUARD", "NIFTY-20240627-FUT", 75, new BigDecimal("23100"), new BigDecimal("900"), "GF-2", "O-other", 50,
                    new BigDecimal("23150"), new BigDecimal("-100"), PositionChange.NOT_TRACKED, 2);
            OrderDecision accepted = new OrderDecision("O-guard", "GUARD", "NIFTY-20240627-FUT", OrderRequest.Side.BUY, 25, true, OrderDecision.Reason.OK, 1, 1, 10, 100, 0.1,
                    new BigDecimal("23000"));
            OrderClosure closed = new OrderClosure("O-guard", "GUARD", OrderClosed.Reason.REJECTED, 25, 2);

            // Newer first (offsets 7 and 8), then the older ones (offsets 3 and 4) arrive late, as in a replay.
            projection.write(c, List.of(new Projection.Item(0, 3, accepted), new Projection.Item(0, 7, newer), new Projection.Item(0, 8, closed)));
            projection.write(c, List.of(new Projection.Item(0, 3, accepted), new Projection.Item(0, 4, older)));
            c.commit();
        }
        assertEquals(1, count("SELECT count(*) FROM positions WHERE account_id = 'GUARD' AND net_qty = 75 AND last_event_seq = 7"));
        assertEquals(1, count("SELECT count(*) FROM accounts WHERE id = 'GUARD' AND cash_balance = 900 AND last_event_seq = 7"));
        assertEquals(1, count("SELECT count(*) FROM orders WHERE id = 'O-guard' AND status = 'BROKER_REJECTED' AND reject_reason = 'REJECTED' AND last_event_seq = 8"));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE event_key = 'DECISION:O-guard'"), "the decision is audited once, however often it is published");
        assertEquals(1, count("SELECT count(*) FROM outbox WHERE idempotency_key = 'O-guard'"), "and sent to the broker once: one outbox row");
        assertEquals(1, count("SELECT count(*) FROM fills WHERE broker_fill_id = 'GF-1' AND order_id = 'O-guard'"), "a fill for a known order is stored once");
        assertEquals(0, count("SELECT count(*) FROM fills WHERE broker_fill_id = 'GF-2'"), "a fill for an order Postgres does not have is not");
        assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE txn_id = 'FILL:GF-2'"), "the realised loss is booked once, as two rows");
        assertEquals(0, count("SELECT coalesce(sum(amount), 0) FROM ledger_entries WHERE txn_id = 'FILL:GF-2'"));
        assertEquals(0, count("SELECT count(*) FROM ledger_entries WHERE txn_id = 'FILL:GF-1'"), "a fill that moved no cash books nothing");
    }

    @Test
    void valuesAreFittedToTheirColumnsAndJsonIsEscaped() {
        assertEquals(new BigDecimal("999999.9999"), Projection.utilisation(Double.POSITIVE_INFINITY), "infinite utilisation is capped at what NUMERIC(10,4) can hold");
        assertEquals(new BigDecimal("1.2346"), Projection.utilisation(1.23456));
        assertEquals(new BigDecimal("12.35"), Projection.rupees(12.345678));
        assertEquals("{\"a\":\"x\\\"y\\\\z\",\"n\":5,\"d\":1.50}", Projection.json("a", "x\"y\\z", "n", 5L, "d", new BigDecimal("1.50")));
    }
}
