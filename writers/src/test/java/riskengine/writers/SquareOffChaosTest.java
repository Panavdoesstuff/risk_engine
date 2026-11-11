package riskengine.writers;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import riskengine.broker.MockBroker;
import riskengine.events.AccountControl;
import riskengine.events.Event;
import riskengine.events.EventLog;
import riskengine.events.LoggedEvent;
import riskengine.events.OrderRequest;
import riskengine.events.Tick;
import riskengine.host.RedpandaLogs;
import riskengine.host.ShardHost;
import riskengine.host.Topics;
import riskengine.margin.AccountBook;
import riskengine.margin.EngineOutput;
import riskengine.margin.EngineOutputCodec;
import riskengine.margin.EpisodeChange;
import riskengine.margin.OrderClosure;
import riskengine.margin.OrderDecision;
import riskengine.margin.PositionChange;
import riskengine.margin.ReferenceMarginEngine;
import riskengine.margin.SquareOffLeg;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentCsv;
import riskengine.shard.Fixtures;
import riskengine.shard.Partitioner;
import riskengine.shard.ShardConfig;
import riskengine.shard.ShardRunner;
import riskengine.shard.ShardState;

/**
 * The M5 gate: the whole square-off pipeline against a broker that misbehaves.
 *
 * <pre>
 *   ticks ─▶ Redpanda ─▶ shards ─▶ engine-out ─▶ writers ─▶ Postgres (orders + outbox, one transaction)
 *                ▲                                                  │
 *                └── fill ingester ◀── callbacks ── mock broker ◀── outbox relay
 * </pre>
 *
 * Everything is real except the broker: Redpanda and Postgres in containers, the real shards, writers,
 * relay and ingester. The broker runs at full chaos: 5% of requests time out (half lost on the way in,
 * half on the way out), 2% of orders are rejected, fills arrive late and in parts, 10% of callbacks are
 * delivered twice.
 *
 * <p>The book is a few hundred random accounts, many of them far over their limit, in a falling market,
 * so the engine squares off hundreds of them at once while users keep sending orders. When everything
 * has drained, the test checks the gate's three numbers, each from more than one side.
 */
@Testcontainers
class SquareOffChaosTest {
    @Container
    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.7");
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

    static final int N = 4;
    static final int ACCOUNTS = Integer.getInteger("chaos.accounts", 400);
    static final int TICKS = Integer.getInteger("chaos.ticks", 40);

    static long count(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Everything in one partition of a topic, decoded with {@code decode}. */
    static <T> List<T> readPartition(String bootstrap, String topic, int partition, java.util.function.Function<String, T> decode) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<T> out = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(p)) {
            TopicPartition tp = new TopicPartition(topic, partition);
            c.assign(List.of(tp));
            c.seekToBeginning(List.of(tp));
            long end = c.endOffsets(List.of(tp)).get(tp);
            while (c.position(tp) < end) {
                for (ConsumerRecord<byte[], byte[]> r : c.poll(Duration.ofMillis(500)).records(tp)) out.add(decode.apply(new String(r.value(), StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    static long totalEnd(String bootstrap, String topic) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(p)) {
            List<TopicPartition> tps = new ArrayList<>();
            for (int i = 0; i < N; i++) tps.add(new TopicPartition(topic, i));
            return c.endOffsets(tps).values().stream().mapToLong(Long::longValue).sum();
        }
    }

    @Test
    void underFullChaosNoLegIsClosedTwiceNoSurplusLegIsSentAndTheLedgerBalances(@TempDir Path dir) throws Exception {
        String bootstrap = REDPANDA.getBootstrapServers();
        String jdbc = POSTGRES.getJdbcUrl(), user = POSTGRES.getUsername(), password = POSTGRES.getPassword();
        Flyway.configure().dataSource(jdbc, user, password).locations("filesystem:" + System.getProperty("migrations.dir")).load().migrate();
        Topics.ensure(bootstrap, Topics.ENGINE_EVENTS, N);
        Topics.ensure(bootstrap, Topics.ENGINE_OUT, N);

        List<Instrument> instruments = Fixtures.instruments();
        // The fixture's random accounts, with a quarter of their cash: a good half of them cannot carry what they hold.
        List<AccountBook> books = Fixtures.books(ACCOUNTS, 55).stream()
                .map(b -> new AccountBook(b.id(), b.cash().divide(BigDecimal.valueOf(4), 2, RoundingMode.DOWN), b.collateral(), b.positions())).toList();
        InstrumentCsv.write(dir.resolve("instruments.csv"), instruments);
        StringBuilder accounts = new StringBuilder("account_id,archetype,thin_funded,cash_balance\n");
        StringBuilder positions = new StringBuilder("account_id,instrument_id,lots,qty,avg_price\n");
        for (AccountBook b : books) {
            accounts.append(b.id()).append(",TEST,false,").append(b.cash().toPlainString()).append('\n');
            b.positions().forEach((id, p) -> positions.append(b.id()).append(',').append(id).append(',').append(p.qty() / 25).append(',')
                    .append(p.qty()).append(',').append(p.avgPrice().toPlainString()).append('\n'));
        }
        Files.writeString(dir.resolve("accounts.csv"), accounts);
        Files.writeString(dir.resolve("positions.csv"), positions);
        BookLoader.load(jdbc, user, password, dir.resolve("instruments.csv"), dir, LocalDate.of(2024, 6, 3));

        MockBroker.Chaos chaos = new MockBroker.Chaos(0.05, 0.02, 0.10, 120, 450, 20240604L);
        long started = System.nanoTime();
        try (RedpandaLogs log = new RedpandaLogs(bootstrap, Topics.ENGINE_EVENTS, N, Duration.ofMillis(100));
             riskengine.host.ReplayClock clock = new riskengine.host.ReplayClock(bootstrap, Topics.ENGINE_EVENTS);
             FillIngester ingester = new FillIngester(0, jdbc, user, password, log, clock).start();
             MockBroker broker = new MockBroker(chaos, "http://localhost:" + ingester.port() + "/broker/callbacks", 0).start();
             OutboxRelay relay = new OutboxRelay(jdbc, user, password, "http://localhost:" + broker.port(), log, Duration.ofMillis(200), clock);
             EngineOutWriter writer = new EngineOutWriter(bootstrap, Topics.ENGINE_OUT, jdbc, user, password);
             ShardHost host = new ShardHost(bootstrap, N, Fixtures.CFG, ShardConfig.defaults(), instruments, books)) {
            writer.start();
            relay.start();
            host.start();

            // A falling, choppy market, one tick every 150 ms, with users sending orders all the while and
            // risk-ops squaring a few accounts off by hand.
            SplittableRandom r = new SplittableRandom(56);
            double spot = 23_300, vix = 21;
            int userOrders = 0;
            for (int t = 1; t <= TICKS; t++) {
                spot *= 1 + (r.nextDouble() - 0.6) * 0.01;
                vix = Math.max(11, Math.min(45, vix + (r.nextDouble() - 0.4) * 1.2));
                long time = Fixtures.T0 + t * 60_000L;
                log.appendToAll(new Tick(t, time, "NIFTY", BigDecimal.valueOf(Math.round(spot * 100), 2), BigDecimal.valueOf(Math.round(vix * 100), 2)));
                for (int i = 0; i < Math.max(4, ACCOUNTS / 40); i++) {
                    AccountBook b = books.get(r.nextInt(books.size()));
                    Instrument ins = instruments.get(r.nextInt(instruments.size()));
                    log.append(new OrderRequest("U-" + t + "-" + i, b.id(), ins.id(), r.nextBoolean() ? OrderRequest.Side.BUY : OrderRequest.Side.SELL,
                            25L * (1 + r.nextInt(3)), OrderRequest.Origin.USER, time));
                    userOrders++;
                }
                if (t % 10 == 5) {
                    log.append(new AccountControl(books.get(r.nextInt(books.size())).id(), AccountControl.Action.SQUARE_OFF, "risk-ops:chaos", "manual-" + t, time));
                }
                Thread.sleep(150);
            }

            // Drain: legs chain (a fill plans the next leg, which is stored, sent, filled, ...), so wait
            // until nothing has moved for a while on any side.
            long deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos();
            int quiet = 0;
            long lastEvents = -1;
            while (quiet < 4) {
                assertTrue(System.nanoTime() < deadline, "did not drain: unsent " + count("SELECT count(*) FROM outbox WHERE sent_at IS NULL")
                        + ", in flight " + count("SELECT count(*) FROM square_off_legs WHERE status IN ('PLANNED', 'SENT')") + ", broker " + broker.stats());
                assertNull(host.failure(), "a shard stopped");
                Thread.sleep(500);
                long events = totalEnd(bootstrap, Topics.ENGINE_EVENTS);
                boolean still = events == lastEvents && broker.idle() && host.lag().maxLag() == 0
                        && writer.records() == totalEnd(bootstrap, Topics.ENGINE_OUT)
                        && count("SELECT count(*) FROM outbox WHERE sent_at IS NULL") == 0;
                quiet = still ? quiet + 1 : 0;
                lastEvents = events;
            }
            double seconds = (System.nanoTime() - started) / 1e9;
            host.awaitCaughtUp(Duration.ofSeconds(30));
            host.stop();
            List<ShardState> shards = host.runners().stream().map(ShardRunner::state).toList();

            // ------------------------------------------------------------ what the engine said, in order
            Map<String, Long> legQty = new TreeMap<>(), legFilled = new TreeMap<>();
            long surplusLegs = 0, legsRejected = 0, episodesOpened = 0, resolved = 0, exhausted = 0, manual = 0, accepted = 0, ledgerExpected = 0, positionChanges = 0;
            for (int p = 0; p < N; p++) {
                Map<String, SquareOffLeg> inFlight = new TreeMap<>(); // account -> the leg that is out
                Map<String, Long> remaining = new TreeMap<>();
                for (EngineOutput o : readPartition(bootstrap, Topics.ENGINE_OUT, p, EngineOutputCodec::decode)) {
                    switch (o) {
                        case SquareOffLeg l -> {
                            // GATE 2: a new leg while the previous one is still in flight is a surplus leg.
                            if (inFlight.containsKey(l.accountId())) surplusLegs++;
                            inFlight.put(l.accountId(), l);
                            remaining.put(l.accountId(), l.qty());
                            assertNull(legQty.put(l.legKey(), l.qty()), "a leg key is used once: " + l.legKey());
                            String[] k = l.legKey().split(":");
                            assertEquals(List.of(l.accountId(), l.episodeId(), l.instrumentId(), String.valueOf(l.seq())), List.of(k), "accountId:episodeId:instrumentId:seq");
                        }
                        case PositionChange pc -> {
                            positionChanges++;
                            if (pc.cashDelta().setScale(2, RoundingMode.HALF_EVEN).signum() != 0) ledgerExpected++;
                            SquareOffLeg l = inFlight.get(pc.accountId());
                            if (legQty.containsKey(pc.orderId())) legFilled.merge(pc.orderId(), pc.fillQty(), Long::sum);
                            if (l != null && l.legKey().equals(pc.orderId())) {
                                long left = remaining.get(pc.accountId()) - pc.fillQty();
                                assertEquals(left, pc.orderRemaining(), "the engine's own count of what the leg has left");
                                if (left == 0) inFlight.remove(pc.accountId());
                                else remaining.put(pc.accountId(), left);
                            }
                        }
                        case OrderClosure c -> {
                            SquareOffLeg l = inFlight.get(c.accountId());
                            if (l != null && l.legKey().equals(c.orderId())) {
                                inFlight.remove(c.accountId());
                                legsRejected++;
                            }
                        }
                        case EpisodeChange e -> {
                            if (e.state() == EpisodeChange.State.OPENED) {
                                episodesOpened++;
                            } else {
                                assertFalse(inFlight.containsKey(e.accountId()), "an episode ended with a leg still out");
                                if (e.state() == EpisodeChange.State.RESOLVED) resolved++;
                                else exhausted++;
                                if (e.mode() == riskengine.margin.AccountRisk.Mode.MANUAL) manual++;
                            }
                        }
                        case OrderDecision d -> accepted += d.accepted() ? 1 : 0;
                        default -> { }
                    }
                }
                assertTrue(inFlight.isEmpty(), "after draining, no leg is still out: " + inFlight.keySet());
            }

            // ------------------------------------------------------------ GATE 1: no leg closed twice
            // (a) The broker executed each key once, though it was asked more often than that.
            Map<String, Long> b = broker.stats();
            long keys = count("SELECT count(*) FROM outbox");
            assertEquals(keys, broker.executed(), "orders executed by the broker = distinct keys sent");
            assertEquals(legQty.size() + accepted, keys, "one outbox row per leg and per accepted user order");
            assertTrue(b.get("placeRequests") > keys, "the relay had to send some keys more than once: " + b);
            // (b) What the engine applied for each leg is exactly the leg, or nothing if it was rejected.
            long legsClosedTwice = 0, legsFilled = 0;
            for (var e : legQty.entrySet()) {
                long filled = legFilled.getOrDefault(e.getKey(), 0L);
                if (filled != 0 && filled != e.getValue()) legsClosedTwice++;
                if (filled == e.getValue()) legsFilled++;
            }
            assertEquals(legQty.size(), legsFilled + legsRejected, "every leg was either filled in full or rejected");
            // (c) And the same from Postgres: fills per leg order sum to the leg, never more.
            long overFilledInPostgres = count("SELECT count(*) FROM square_off_legs l LEFT JOIN "
                    + "(SELECT order_id, sum(qty) q FROM fills GROUP BY order_id) f ON f.order_id = l.leg_key "
                    + "WHERE coalesce(f.q, 0) <> CASE l.status WHEN 'FILLED' THEN l.qty ELSE 0 END OR l.status NOT IN ('FILLED', 'REJECTED')");
            // (d) Duplicate callbacks reached the log and were dropped there.
            long duplicatesDropped = shards.stream().mapToLong(ShardState::duplicateFills).sum();
            assertEquals(b.get("duplicateCallbacks"), duplicatesDropped, "every duplicate callback was ignored by a shard");
            assertEquals(positionChanges, count("SELECT count(*) FROM fills"), "one fills row per fill the engine applied");
            // Fills carry replay time (the ticks' 2024 clock), not the day the test ran.
            long firstTick = Fixtures.T0 + 60_000L, lastTick = Fixtures.T0 + TICKS * 60_000L;
            assertEquals(0, count("SELECT count(*) FROM fills WHERE filled_at < to_timestamp(" + firstTick / 1000.0 + ") OR filled_at > to_timestamp(" + lastTick / 1000.0 + ")"),
                    "every fill is stamped with a tick's log time");
            assertEquals(0, count("SELECT count(*) FROM ledger_entries WHERE log_time > to_timestamp(" + lastTick / 1000.0 + ")"));

            // ------------------------------------------------------------ GATE 3: the ledger
            long unbalanced = count("SELECT count(*) FROM (SELECT txn_id FROM ledger_entries GROUP BY txn_id HAVING sum(amount) <> 0 OR count(*) <> 2) t");
            long transactions = count("SELECT count(DISTINCT txn_id) FROM ledger_entries");
            assertEquals(ledgerExpected, transactions, "one ledger transaction per fill that moved cash");

            // ------------------------------------------------------------ and the rest agrees with itself
            assertEquals(0, count("SELECT count(*) FROM square_off_episodes WHERE status = 'OPEN'"), "every episode ended");
            assertEquals(episodesOpened, count("SELECT count(*) FROM square_off_episodes"));
            // Postgres = the shards, account by account.
            Map<String, String> pg = new TreeMap<>(), engine = new TreeMap<>();
            try (Connection c = DriverManager.getConnection(jdbc, user, password); Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT id, status, cash_balance FROM accounts")) {
                    while (rs.next()) pg.put(rs.getString(1), rs.getString(2) + " cash " + rs.getBigDecimal(3));
                }
                try (ResultSet rs = st.executeQuery("SELECT account_id, instrument_id, net_qty FROM positions WHERE net_qty <> 0")) {
                    while (rs.next()) pg.put(rs.getString(1) + " " + rs.getString(2), String.valueOf(rs.getLong(3)));
                }
            }
            // The shards = the single-threaded reference engine fed the same partitions.
            ReferenceMarginEngine reference = referenceRun(log, instruments, books);
            long referenceMismatches = 0;
            for (AccountBook book : books) {
                ShardState s = shards.get(Partitioner.partition(book.id(), N));
                engine.put(book.id(), s.status(book.id()) + " cash " + s.cash(book.id()).setScale(2, RoundingMode.HALF_EVEN));
                s.positions(book.id()).forEach((id, pos) -> engine.put(book.id() + " " + id, String.valueOf(pos.qty())));
                if (!reference.positions(book.id()).equals(s.positions(book.id())) || reference.cash(book.id()).compareTo(s.cash(book.id())) != 0
                        || !reference.riskFingerprint(book.id()).equals(s.riskFingerprint(book.id()))) {
                    referenceMismatches++;
                    System.out.println("MISMATCH " + book.id() + "\n  shard     " + s.riskFingerprint(book.id()) + " cash " + s.cash(book.id()) + " " + s.positions(book.id())
                            + "\n  reference " + reference.riskFingerprint(book.id()) + " cash " + reference.cash(book.id()) + " " + reference.positions(book.id()));
                }
            }
            assertEquals(engine, pg, "accounts.status, cash and positions in Postgres equal the shards'");

            System.out.printf("""
                    M5 chaos gate: %d accounts, %d ticks, %d user orders, %.0f s
                      square-off      %d episodes (%d by hand): %d resolved, %d exhausted; %d legs planned, %d filled, %d rejected and re-planned
                      broker          %d distinct keys, executed %d; asked %d times (%d requests lost, %d answers lost, %d repeats of a known key)
                      relay           %d timeouts: %d confirmed by query (not re-sent), %d not at the broker (re-sent)
                      callbacks       %d fills in %d callbacks' worth of duplicates: %d duplicates, %d dropped by the shards; %d refused by the ingester
                      Postgres        %d fills, %d ledger transactions
                      GATE  legs closed twice %d (engine) / %d (Postgres)   surplus legs %d   unbalanced ledger transactions %d   shard vs reference mismatches %d
                    """,
                    ACCOUNTS, TICKS, userOrders, seconds, episodesOpened, manual, resolved, exhausted, legQty.size(), legsFilled, legsRejected,
                    keys, broker.executed(), b.get("placeRequests"), b.get("timeoutsRequestLost"), b.get("timeoutsResponseLost"), b.get("repeatsOfAKnownKey"),
                    relay.timeouts(), relay.confirmedByQuery(), relay.resentAfterTimeout(),
                    b.get("fills"), b.get("fills") + b.get("duplicateCallbacks"), b.get("duplicateCallbacks"), duplicatesDropped, ingester.refused(),
                    count("SELECT count(*) FROM fills"), transactions, legsClosedTwice, overFilledInPostgres, surplusLegs, unbalanced, referenceMismatches);

            // The gate.
            assertEquals(0, legsClosedTwice, "legs closed twice (engine)");
            assertEquals(0, overFilledInPostgres, "legs whose fills in Postgres do not add up to the leg");
            assertEquals(0, surplusLegs, "legs sent while another was in flight");
            assertEquals(0, unbalanced, "ledger transactions that do not sum to zero");
            assertEquals(0, referenceMismatches, "accounts where the shard and the reference engine disagree");
            // And the chaos really happened.
            assertTrue(episodesOpened >= ACCOUNTS / 8 && legQty.size() > episodesOpened / 2, "enough square-off to mean something");
            assertTrue(b.get("timeoutsRequestLost") > 0 && b.get("timeoutsResponseLost") > 0 && relay.confirmedByQuery() > 0 && relay.resentAfterTimeout() > 0, b.toString());
            assertTrue(legsRejected > 0 && duplicatesDropped > 0 && transactions > 0, "rejects " + legsRejected + ", duplicates " + duplicatesDropped);
            assertEquals(0, ingester.refused(), "the broker only called back about orders we placed");
            assertEquals(0, writer.failedBatches());

            // ------------------------------------------------------------ the ingester is a gate
            // Callbacks that do not match an order we placed are refused and never reach the log.
            long before = totalEnd(bootstrap, Topics.ENGINE_EVENTS);
            String someLeg = legQty.keySet().iterator().next();
            String[] k = someLeg.split(":");
            String url = "http://localhost:" + ingester.port() + "/broker/callbacks";
            assertEquals(422, post(url, "{\"type\":\"FILL\",\"key\":\"no-such-order\",\"fillId\":\"X1\",\"accountId\":\"" + k[0] + "\",\"instrumentId\":\"" + k[2] + "\",\"qty\":25,\"price\":10}"));
            assertEquals(422, post(url, "{\"type\":\"FILL\",\"key\":\"" + someLeg + "\",\"fillId\":\"X2\",\"accountId\":\"A-SOMEONE-ELSE\",\"instrumentId\":\"" + k[2] + "\",\"qty\":25,\"price\":10}"));
            assertEquals(422, post(url, "{\"type\":\"FILL\",\"key\":\"" + someLeg + "\",\"fillId\":\"X3\",\"accountId\":\"" + k[0] + "\",\"instrumentId\":\"NIFTY-20990101-FUT\",\"qty\":25,\"price\":10}"));
            assertEquals(422, post(url, "{\"type\":\"FILL\",\"key\":\"" + someLeg + "\",\"fillId\":\"X4\",\"accountId\":\"" + k[0] + "\",\"instrumentId\":\"" + k[2] + "\",\"qty\":0,\"price\":10}"));
            assertEquals(422, post(url, "{\"type\":\"FILL\",\"key\":\"" + someLeg + "\",\"fillId\":\"X5|TICK\",\"accountId\":\"" + k[0] + "\",\"instrumentId\":\"" + k[2] + "\",\"qty\":25,\"price\":10}"));
            assertEquals(422, post(url, "not json at all"));
            assertEquals(before, totalEnd(bootstrap, Topics.ENGINE_EVENTS), "nothing refused was written to the log");
            assertEquals(6, count("SELECT count(*) FROM audit_log WHERE actor = 'fill-ingester' AND action = 'CALLBACK_REFUSED'"), "each refusal is on record");
        }
    }

    static int post(String url, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Feeds the reference engine exactly what the partitions hold: a tick, then each partition's events up to the next tick. */
    static ReferenceMarginEngine referenceRun(RedpandaLogs log, List<Instrument> instruments, List<AccountBook> books) {
        List<List<Event>> recorded = new ArrayList<>();
        for (int p = 0; p < N; p++) {
            List<Event> events = new ArrayList<>();
            try (EventLog partition = log.partition(p)) {
                long end = partition.endOffset();
                while (events.size() < end) for (LoggedEvent le : partition.read(events.size(), 5_000)) events.add(le.event());
            }
            recorded.add(events);
        }
        ReferenceMarginEngine reference = new ReferenceMarginEngine(Fixtures.CFG, instruments, books);
        int[] at = new int[N];
        while (true) {
            Tick tick = null;
            for (int p = 0; p < N; p++) {
                List<Event> events = recorded.get(p);
                while (at[p] < events.size() && !(events.get(at[p]) instanceof Tick)) reference.apply(events.get(at[p]++));
                if (at[p] < events.size()) tick = (Tick) events.get(at[p]++);
            }
            if (tick == null) return reference;
            reference.apply(tick);
        }
    }
}
