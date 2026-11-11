package riskengine.host;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import riskengine.events.AccountEvent;
import riskengine.events.Event;
import riskengine.events.EventCodec;
import riskengine.events.EventLog;
import riskengine.events.Fill;
import riskengine.events.InMemoryEventLog;
import riskengine.events.LoggedEvent;
import riskengine.events.OrderRequest;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.margin.EngineOutput;
import riskengine.margin.EngineOutputCodec;
import riskengine.ref.Instrument;
import riskengine.shard.Fixtures;
import riskengine.shard.Partitioner;
import riskengine.shard.ShardConfig;
import riskengine.shard.ShardRunner;
import riskengine.shard.ShardState;

/** Runs against a real Redpanda broker in a container (the same image docker-compose uses). Needs Docker. */
@Testcontainers
class RedpandaShardsTest {
    @Container
    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.7");

    static final int N = 4;
    static String bootstrap;

    @BeforeAll
    static void broker() {
        bootstrap = REDPANDA.getBootstrapServers();
    }

    static Tick tick(long seq, String spot) {
        return new Tick(seq, Fixtures.T0 + seq * 60_000, "NIFTY", new BigDecimal(spot), new BigDecimal("20"));
    }

    // ---------------------------------------------------------------- the EventLog contract

    @Test
    void aPartitionBehavesLikeAnyOtherEventLog() {
        Topics.ensure(bootstrap, "contract-events", 2);
        try (RedpandaLogs logs = new RedpandaLogs(bootstrap, "contract-events", 2, Duration.ofMillis(300));
             EventLog p0 = logs.partition(0);
             EventLog p1 = logs.partition(1);
             InMemoryEventLog mem = new InMemoryEventLog()) {
            assertEquals(0, p0.endOffset(), "a new partition is an empty log");
            assertTrue(p0.read(0, 10).isEmpty(), "nothing yet");

            for (int i = 0; i < 5; i++) {
                assertEquals(i, p0.append(tick(i, "2300" + i + ".5")), "offsets start at 0 with no gaps");
                mem.append(tick(i, "2300" + i + ".5"));
            }
            Event fill = new Fill("F1", "O1", "A1", "NIFTY-20240627-FUT", 25, new BigDecimal("23300.05"), 7);
            assertEquals(0, p1.append(fill), "each partition has its own offsets");
            assertEquals(5, p0.endOffset());
            assertEquals(1, p1.endOffset());

            assertEquals(mem.read(0, 100), readAll(p0, 0, 5), "same events, same offsets, same order as the in-memory log");
            assertEquals(mem.read(1, 3), p0.read(1, 3), "reading from the middle, capped at max");
            assertEquals(mem.read(4, 1), p0.read(4, 1));
            assertEquals(List.of(new LoggedEvent(0, fill)), readAll(p1, 0, 1));
            assertTrue(p0.read(5, 10).isEmpty(), "caught up: empty, and the caller may ask again later");
            assertThrows(IllegalArgumentException.class, () -> p0.read(99, 1), "asking past the end is an error, not a silent jump");
            assertEquals(mem.read(0, 2), p0.read(0, 2), "and the log still works afterwards");

            // What is on the wire is exactly the EventCodec line a FileEventLog would hold.
            List<ConsumerRecord<byte[], byte[]>> raw = rawRecords("contract-events", 1, 1);
            assertEquals(EventCodec.encode(fill), new String(raw.get(0).value(), StandardCharsets.UTF_8));
            assertEquals("A1", new String(raw.get(0).key(), StandardCharsets.UTF_8));
        }
        // A second, independent reader (a restarted shard) sees the same log.
        try (RedpandaLogs logs = new RedpandaLogs(bootstrap, "contract-events", 2, Duration.ofMillis(300)); EventLog again = logs.partition(0)) {
            assertEquals(5, again.endOffset());
            assertEquals(5, readAll(again, 0, 5).size());
        }
    }

    @Test
    void aTopicWithTheWrongPartitionCountIsRefused() {
        Topics.ensure(bootstrap, "three-partitions", 3);
        var e = assertThrows(IllegalStateException.class, () -> new RedpandaLogs(bootstrap, "three-partitions", 4, Duration.ofMillis(100)));
        assertTrue(e.getMessage().contains("has 3 partitions but 4"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> Topics.ensure(bootstrap, "three-partitions", 4));
        assertThrows(IllegalStateException.class, () -> new RedpandaLogs(bootstrap, "no-such-topic", 4, Duration.ofMillis(100)));
    }

    @Test
    void accountEventsLandOnThePartitionThePartitionerPredictsAndTicksOnAll() {
        Topics.ensure(bootstrap, "routing-events", N);
        try (RedpandaLogs logs = new RedpandaLogs(bootstrap, "routing-events", N, Duration.ofMillis(300))) {
            logs.appendToAll(tick(1, "23300"));
            List<String> accounts = new ArrayList<>();
            for (int i = 0; i < 40; i++) accounts.add(String.format("A%07d", i));
            for (String a : accounts) {
                logs.append(new OrderRequest("O-" + a, a, "NIFTY-20240627-FUT", OrderRequest.Side.BUY, 25, OrderRequest.Origin.USER, 1));
            }
            int seen = 0;
            for (int p = 0; p < N; p++) {
                try (EventLog log = logs.partition(p)) {
                    List<LoggedEvent> events = readAll(log, 0, (int) log.endOffset());
                    assertEquals(tick(1, "23300"), events.get(0).event(), "every partition got the tick first");
                    for (LoggedEvent le : events.subList(1, events.size())) {
                        assertEquals(p, Partitioner.partition(((AccountEvent) le.event()).accountId(), N));
                        seen++;
                    }
                }
            }
            assertEquals(accounts.size(), seen);
        }
    }

    // ---------------------------------------------------------------- shards on Redpanda

    @Test
    void shardsOnRedpandaReachTheSameStateAndPublishTheSameOutputAsShardsOnInMemoryLogs() throws Exception {
        Topics.ensure(bootstrap, "parity-events", N);
        Topics.ensure(bootstrap, "parity-out", N);
        List<Instrument> instruments = Fixtures.instruments();
        List<AccountBook> books = Fixtures.books(2_000, 11);
        List<Event> events = Fixtures.events(books, 50, 20, 12);

        // Expected: the M3 way. In-memory logs, each shard replayed to the end on this thread.
        List<String> expectedHashes = new ArrayList<>();
        List<List<String>> expectedOut = new ArrayList<>();
        for (int p = 0; p < N; p++) {
            int shardId = p;
            List<String> lines = new ArrayList<>();
            try (InMemoryEventLog log = new InMemoryEventLog()) {
                for (Event e : events) {
                    if (!(e instanceof AccountEvent a) || Partitioner.partition(a.accountId(), N) == shardId) log.append(e);
                }
                List<AccountBook> mine = books.stream().filter(b -> Partitioner.partition(b.id(), N) == shardId).toList();
                ShardState state = new ShardState(p, Fixtures.CFG, ShardConfig.defaults(), instruments, mine);
                new ShardRunner(state, log, (EngineOutput o) -> lines.add(EngineOutputCodec.encode(o))).runToEnd();
                expectedHashes.add(state.stateHash());
            }
            expectedOut.add(lines);
        }
        assertTrue(expectedOut.stream().allMatch(l -> l.stream().anyMatch(s -> s.startsWith("DECISION|"))
                && l.stream().anyMatch(s -> s.startsWith("MARGIN|"))), "every shard has both kinds of output to compare");

        // Actual: the same events through Redpanda, shards running live while the events arrive.
        try (ShardHost host = new ShardHost(bootstrap, "parity-events", "parity-out", N, Fixtures.CFG, ShardConfig.defaults(), instruments, books);
             RedpandaLogs writer = new RedpandaLogs(bootstrap, "parity-events", N, Duration.ofMillis(100))) {
            host.start();
            for (Event e : events) {
                if (e instanceof AccountEvent a) writer.append(a);
                else writer.appendToAll((Tick) e);
            }
            host.awaitCaughtUp(Duration.ofSeconds(60));
            host.stop();
            assertNull(host.failure());
            assertEquals(expectedHashes, host.runners().stream().map(r -> r.state().stateHash()).toList(),
                    "per-shard state hashes: Redpanda vs in-memory");
        }
        for (int p = 0; p < N; p++) {
            assertEquals(expectedOut.get(p), lines(rawRecords("parity-out", p, expectedOut.get(p).size())),
                    "engine-out partition " + p + ": same lines in the same order");
        }

        // A restarted host has no snapshot (M6), so it replays from offset 0: identical state, and every
        // output published a second time, byte for byte. Consumers must treat engine-out as at-least-once.
        try (ShardHost restarted = new ShardHost(bootstrap, "parity-events", "parity-out", N, Fixtures.CFG, ShardConfig.defaults(), instruments, books)) {
            restarted.start();
            restarted.awaitCaughtUp(Duration.ofSeconds(60));
            restarted.stop();
            assertEquals(expectedHashes, restarted.runners().stream().map(r -> r.state().stateHash()).toList(), "replay after restart");
        }
        for (int p = 0; p < N; p++) {
            List<String> twice = new ArrayList<>(expectedOut.get(p));
            twice.addAll(expectedOut.get(p));
            assertEquals(twice, lines(rawRecords("parity-out", p, twice.size())), "engine-out partition " + p + " after the replay");
        }
        System.out.printf("Redpanda parity: %d events over %d partitions, %d output lines, hashes identical to in-memory run and after restart%n",
                events.size(), N, expectedOut.stream().mapToInt(List::size).sum());
    }

    @Test
    void aShardThatCannotApplyAnEventStopsAndSaysWhy() throws Exception {
        Topics.ensure(bootstrap, "poison-events", 1);
        Topics.ensure(bootstrap, "poison-out", 1);
        try (ShardHost host = new ShardHost(bootstrap, "poison-events", "poison-out", 1, Fixtures.CFG, ShardConfig.defaults(),
                Fixtures.instruments(), Fixtures.books(5, 1));
             RedpandaLogs writer = new RedpandaLogs(bootstrap, "poison-events", 1, Duration.ofMillis(100))) {
            host.start();
            writer.appendToAll(tick(5, "23300"));
            writer.appendToAll(tick(5, "23301")); // tick_seq must increase: the shard refuses to guess
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (host.failure() == null && System.nanoTime() < deadline) Thread.sleep(20);
            assertNotNull(host.failure(), "the shard stopped");
            assertTrue(host.failure().getMessage().contains("strictly increasing"), host.failure().getMessage());
            assertEquals(1, host.runners().get(0).appliedOffset(), "it applied the good event and nothing after it");
        }
    }

    @Test
    void lagIsTheNumberOfEventsAShardHasNotAppliedYet() throws Exception {
        Topics.ensure(bootstrap, "lag-events", 2);
        Topics.ensure(bootstrap, "lag-out", 2);
        try (RedpandaLogs writer = new RedpandaLogs(bootstrap, "lag-events", 2, Duration.ofMillis(100))) {
            // 40 events are waiting in partition 0 before any shard exists; partition 1 is empty.
            for (int i = 1; i <= 40; i++) writer.appendAsync(0, tick(i, "23300"));
            writer.flush();
            try (ShardHost host = new ShardHost(bootstrap, "lag-events", "lag-out", 2, Fixtures.CFG, ShardConfig.defaults(),
                    Fixtures.instruments(), Fixtures.books(20, 1))) {
                LagMonitor lag = host.lag();
                assertEquals(40, lag.lag(0), "not started yet: everything is lag");
                assertEquals(0, lag.lag(1));
                assertEquals(40, lag.maxLag());
                int port = lag.serve(0);
                assertTrue(http(port, "/metrics").body().contains("risk_shard_lag_events{shard=\"0\"} 40\n"), http(port, "/metrics").body());

                host.start();
                host.awaitCaughtUp(Duration.ofSeconds(30));
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (lag.maxLag() != 0 && System.nanoTime() < deadline) Thread.sleep(50);
                String metrics = http(port, "/metrics").body();
                for (String line : List.of("risk_shard_lag_events{shard=\"0\"} 0", "risk_shard_lag_events{shard=\"1\"} 0",
                        "risk_shard_applied_offset{shard=\"0\"} 40", "risk_shard_log_end_offset{shard=\"0\"} 40",
                        "risk_shard_up{shard=\"0\"} 1", "# TYPE risk_shard_lag_events gauge")) {
                    assertTrue(metrics.contains(line + "\n"), "missing: " + line + "\n" + metrics);
                }
                assertEquals(200, http(port, "/healthz").statusCode());

                // A shard that stops on an event it cannot apply shows up as down, and its lag stops falling.
                writer.appendToAll(tick(40, "23300")); // tick_seq does not increase
                writer.appendToAll(tick(41, "23300"));
                deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (http(port, "/healthz").statusCode() == 200 && System.nanoTime() < deadline) Thread.sleep(50);
                assertEquals(503, http(port, "/healthz").statusCode());
                deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (lag.lag(0) != 2 && System.nanoTime() < deadline) Thread.sleep(50);
                metrics = http(port, "/metrics").body();
                assertTrue(metrics.contains("risk_shard_up{shard=\"0\"} 0\n") && metrics.contains("risk_shard_lag_events{shard=\"0\"} 2\n"), metrics);
            }
        }
    }

    @Test
    void inAMinuteBarReplayTheTickMinimumKeepsSnapshotsFromHappeningOnEveryTick(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Topics.ensure(bootstrap, "snap-events", 1);
        Topics.ensure(bootstrap, "snap-out", 1);
        try (RedpandaLogs writer = new RedpandaLogs(bootstrap, "snap-events", 1, Duration.ofMillis(100))) {
            // 120 ticks one minute of log time apart, as in a replay of minute bars.
            for (int i = 1; i <= 120; i++) writer.appendAsync(0, new Tick(i, Fixtures.T0 + i * 60_000L, "NIFTY", new BigDecimal("23300"), new BigDecimal("20")));
            writer.flush();
            SnapshotStore store = new SnapshotStore(dir, 3);
            try (ShardHost host = new ShardHost(bootstrap, "snap-events", "snap-out", 1, Fixtures.CFG, ShardConfig.defaults(), Fixtures.instruments(),
                    Fixtures.books(20, 1), new ShardHost.Options(null, store, 60_000, 50))) {
                host.start();
                host.awaitCaughtUp(Duration.ofSeconds(30));
                host.stop();
                // M = 60 s alone would snapshot on 119 of the 120 ticks. With 50 ticks at least: after ticks 50 and 100.
                assertEquals(2, host.tickMetrics().snapshotsTaken(0));
            }
            // The newest is on disk (an older one still waiting is replaced by a newer one from the same shard).
            assertEquals(100L, new SnapshotStore(dir, 3).list(0).get(0).offset());
        }
    }

    static java.net.http.HttpResponse<String> http(int port, String path) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    // ---------------------------------------------------------------- helpers

    /** Reads until {@code count} events have arrived (a single read may return fewer than are available). */
    static List<LoggedEvent> readAll(EventLog log, long from, int count) {
        List<LoggedEvent> out = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (out.size() < count && System.nanoTime() < deadline) out.addAll(log.read(from + out.size(), count - out.size()));
        return out;
    }

    static List<String> lines(List<ConsumerRecord<byte[], byte[]>> records) {
        return records.stream().map(r -> new String(r.value(), StandardCharsets.UTF_8)).toList();
    }

    /** Everything in one partition, read with a plain Kafka consumer; waits until at least {@code atLeast} records are there. */
    static List<ConsumerRecord<byte[], byte[]>> rawRecords(String topic, int partition, int atLeast) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<byte[], byte[]>> out = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(p)) {
            TopicPartition tp = new TopicPartition(topic, partition);
            c.assign(List.of(tp));
            c.seekToBeginning(List.of(tp));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            int emptyPolls = 0;
            while (System.nanoTime() < deadline && (out.size() < atLeast || emptyPolls < 2)) {
                var batch = c.poll(Duration.ofMillis(300)).records(tp);
                if (batch.isEmpty()) emptyPolls++;
                else emptyPolls = 0;
                out.addAll(batch);
            }
        }
        return out;
    }
}
