package riskengine.writers;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
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
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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
import riskengine.events.EventLog;
import riskengine.events.LoggedEvent;
import riskengine.events.OrderRequest;
import riskengine.events.Tick;
import riskengine.host.RedpandaLogs;
import riskengine.host.ReplayClock;
import riskengine.host.Topics;
import riskengine.margin.AccountBook;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentCsv;
import riskengine.shard.Fixtures;
import riskengine.shard.Partitioner;
import riskengine.shard.ShardConfig;
import riskengine.shard.ShardState;

/**
 * M6 recovery gate: kill -9 shards in the middle of a square-off under full broker chaos.
 *
 * <p>Each shard runs as its own process (the real {@code ShardHost} main, {@code --only p}), snapshotting
 * every 5 minutes of log time and 5 ticks (the production minimum is 50; lowered so a short run has many). While ticks, user orders and the chaos broker's fills flow,
 * a killer sends SIGKILL to a random shard process at random moments, at least five times, and starts it
 * again; each restart loads its newest valid snapshot and replays from there. Everything else (writers,
 * outbox relay, fill ingester, mock broker) keeps running, as it would in production.
 *
 * <p>Afterwards each shard process is stopped cleanly and prints its final offset and state hash. The
 * run "with no kills" it must equal is a fresh, single-threaded replay of exactly what that partition
 * holds, from offset 0. (The log itself depends on timing, so a second live run would get different
 * input; the same input replayed without interruption is the meaningful comparison.)
 */
@Testcontainers
class ShardKillTest {
    @Container
    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.7");
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

    static final int N = 4, ACCOUNTS = 400, TICKS = 80, KILLS = 6;
    static final Pattern STATE = Pattern.compile("STATE shard=(\\d+) offset=(\\d+) hash=([0-9a-f]{64})");

    static long count(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
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

    /** One shard's process, restartable. */
    static final class ShardProcess {
        final int shard;
        final List<String> command;
        final Path logDir;
        Process process;
        int incarnation;

        ShardProcess(int shard, List<String> command, Path logDir) {
            this.shard = shard;
            this.command = command;
            this.logDir = logDir;
        }

        Path log(int k) {
            return logDir.resolve("shard-" + shard + "-run-" + k + ".log");
        }

        void start() throws Exception {
            incarnation++;
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log(incarnation).toFile());
            process = pb.start();
        }

        void killNine() throws Exception {
            process.destroyForcibly(); // SIGKILL: no shutdown hook, no final snapshot, no goodbye
            process.waitFor();
        }

        /** SIGTERM and wait; returns the STATE line's offset and hash. */
        String[] stopCleanly() throws Exception {
            process.destroy();
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "shard " + shard + " did not stop");
            String out = Files.readString(log(incarnation), StandardCharsets.UTF_8);
            Matcher m = STATE.matcher(out);
            assertTrue(m.find(), "shard " + shard + " printed no STATE line:\n" + out);
            return new String[] {m.group(2), m.group(3)};
        }
    }

    @Test
    void killedShardsRecoverToExactlyTheStateOfARunWithNoKills(@TempDir Path dir) throws Exception {
        String bootstrap = REDPANDA.getBootstrapServers();
        String jdbc = POSTGRES.getJdbcUrl(), user = POSTGRES.getUsername(), password = POSTGRES.getPassword();
        Flyway.configure().dataSource(jdbc, user, password).locations("filesystem:" + System.getProperty("migrations.dir")).load().migrate();
        Topics.ensure(bootstrap, Topics.ENGINE_EVENTS, N);
        Topics.ensure(bootstrap, Topics.ENGINE_OUT, N);

        List<Instrument> instruments = Fixtures.instruments();
        List<AccountBook> books = Fixtures.books(ACCOUNTS, 55).stream()
                .map(b -> new AccountBook(b.id(), b.cash().divide(BigDecimal.valueOf(4), 2, RoundingMode.DOWN), b.collateral(), b.positions())).toList();
        Path bookDir = Files.createDirectories(dir.resolve("book"));
        InstrumentCsv.write(bookDir.resolve("instruments.csv"), instruments);
        StringBuilder accounts = new StringBuilder("account_id,archetype,thin_funded,cash_balance\n");
        StringBuilder positions = new StringBuilder("account_id,instrument_id,lots,qty,avg_price\n");
        for (AccountBook b : books) {
            accounts.append(b.id()).append(",TEST,false,").append(b.cash().toPlainString()).append('\n');
            b.positions().forEach((id, p) -> positions.append(b.id()).append(',').append(id).append(',').append(p.qty() / 25).append(',')
                    .append(p.qty()).append(',').append(p.avgPrice().toPlainString()).append('\n'));
        }
        Files.writeString(bookDir.resolve("accounts.csv"), accounts);
        Files.writeString(bookDir.resolve("positions.csv"), positions);
        BookLoader.load(jdbc, user, password, bookDir.resolve("instruments.csv"), bookDir, LocalDate.of(2024, 6, 3));

        String java = ProcessHandle.current().info().command().orElse("java");
        Path snapshots = Files.createDirectories(dir.resolve("snapshots")), logs = Files.createDirectories(dir.resolve("logs"));
        List<ShardProcess> shards = new ArrayList<>();
        for (int p = 0; p < N; p++) {
            shards.add(new ShardProcess(p, List.of(java, "-Xmx512m", "-cp", System.getProperty("java.class.path"), "riskengine.host.ShardHost",
                    "--instruments", bookDir.resolve("instruments.csv").toString(), "--book", bookDir.toString(), "--bootstrap", bootstrap,
                    "--partitions", String.valueOf(N), "--metrics-port", "0", "--snapshot-dir", snapshots.toString(),
                    "--snapshot-every-seconds", "300", "--snapshot-min-ticks", "5", "--only", String.valueOf(p)), logs));
        }

        MockBroker.Chaos chaos = new MockBroker.Chaos(0.05, 0.02, 0.10, 120, 450, 20240604L);
        int kills = 0;
        long started = System.nanoTime();
        try (RedpandaLogs log = new RedpandaLogs(bootstrap, Topics.ENGINE_EVENTS, N, Duration.ofMillis(100));
             ReplayClock clock = new ReplayClock(bootstrap, Topics.ENGINE_EVENTS);
             FillIngester ingester = new FillIngester(0, jdbc, user, password, log, clock).start();
             MockBroker broker = new MockBroker(chaos, "http://localhost:" + ingester.port() + "/broker/callbacks", 0).start();
             OutboxRelay relay = new OutboxRelay(jdbc, user, password, "http://localhost:" + broker.port(), log, Duration.ofMillis(200), clock);
             EngineOutWriter writer = new EngineOutWriter(bootstrap, Topics.ENGINE_OUT, jdbc, user, password)) {
            writer.start();
            relay.start();
            for (ShardProcess s : shards) s.start();

            // The killer: at random moments, SIGKILL a random shard and start it again.
            SplittableRandom chance = new SplittableRandom(99);
            List<String> killLog = new ArrayList<>();
            Thread killer = new Thread(() -> {
                try {
                    for (int k = 0; k < KILLS; k++) {
                        Thread.sleep(1_500 + chance.nextInt(2_000));
                        ShardProcess victim = shards.get(chance.nextInt(N));
                        victim.killNine();
                        killLog.add(String.format("%.1f s: kill -9 shard %d (run %d)", (System.nanoTime() - started) / 1e9, victim.shard, victim.incarnation));
                        Thread.sleep(chance.nextInt(400));
                        victim.start();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, "killer");
            killer.start();

            SplittableRandom r = new SplittableRandom(56);
            double spot = 23_300, vix = 21;
            for (int t = 1; t <= TICKS; t++) {
                spot *= 1 + (r.nextDouble() - 0.6) * 0.01;
                vix = Math.max(11, Math.min(45, vix + (r.nextDouble() - 0.4) * 1.2));
                long time = Fixtures.T0 + t * 60_000L;
                log.appendToAll(new Tick(t, time, "NIFTY", BigDecimal.valueOf(Math.round(spot * 100), 2), BigDecimal.valueOf(Math.round(vix * 100), 2)));
                for (int i = 0; i < ACCOUNTS / 40; i++) {
                    AccountBook b = books.get(r.nextInt(books.size()));
                    Instrument ins = instruments.get(r.nextInt(instruments.size()));
                    log.append(new OrderRequest("U-" + t + "-" + i, b.id(), ins.id(), r.nextBoolean() ? OrderRequest.Side.BUY : OrderRequest.Side.SELL,
                            25L * (1 + r.nextInt(3)), OrderRequest.Origin.USER, time, System.currentTimeMillis()));
                }
                if (t % 20 == 10) log.append(new AccountControl(books.get(r.nextInt(books.size())).id(), AccountControl.Action.SQUARE_OFF, "risk-ops:kill", "m-" + t, time));
                Thread.sleep(200);
            }
            killer.join();
            kills = killLog.size();

            // Drain: nothing moving anywhere for a while.
            long deadline = System.nanoTime() + Duration.ofMinutes(6).toNanos();
            int quiet = 0;
            long lastIn = -1, lastOut = -1;
            while (quiet < 6) {
                assertTrue(System.nanoTime() < deadline, "did not drain");
                Thread.sleep(500);
                long in = totalEnd(bootstrap, Topics.ENGINE_EVENTS), outEnd = totalEnd(bootstrap, Topics.ENGINE_OUT);
                boolean still = in == lastIn && outEnd == lastOut && broker.idle() && writer.records() == outEnd
                        && count("SELECT count(*) FROM outbox WHERE sent_at IS NULL") == 0;
                quiet = still ? quiet + 1 : 0;
                lastIn = in;
                lastOut = outEnd;
            }
            double seconds = (System.nanoTime() - started) / 1e9;

            // Stop each shard cleanly and compare with an uninterrupted replay of the same partition.
            Map<Integer, String[]> finals = new TreeMap<>();
            for (ShardProcess s : shards) finals.put(s.shard, s.stopCleanly());
            List<String> mismatches = new ArrayList<>();
            long referenceOutputs = 0;
            for (int p = 0; p < N; p++) {
                int shard = p;
                List<AccountBook> mine = books.stream().filter(b -> Partitioner.partition(b.id(), N) == shard).toList();
                ShardState fresh = new ShardState(p, Fixtures.CFG, ShardConfig.defaults(), instruments, mine);
                long end = Long.parseLong(finals.get(p)[0]);
                try (EventLog partition = log.partition(p)) {
                    assertEquals(partition.endOffset(), end, "shard " + p + " stopped caught up");
                    while (fresh.nextOffset() < end) {
                        for (LoggedEvent le : partition.read(fresh.nextOffset(), 5_000)) referenceOutputs += fresh.apply(le).size();
                    }
                }
                if (!fresh.stateHash().equals(finals.get(p)[1])) mismatches.add("shard " + p + ": recovered " + finals.get(p)[1] + ", uninterrupted " + fresh.stateHash());
            }

            // How the restarts went, from the processes' own logs.
            int fromSnapshot = 0, fromZero = 0;
            List<String> restores = new ArrayList<>();
            for (ShardProcess s : shards) {
                for (int k = 2; k <= s.incarnation; k++) {
                    String out = Files.readString(s.log(k), StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("shard \\d+: restored from \\S+ \\(offset (\\d+), ([\\d,]+) bytes\\) in (\\d+) ms").matcher(out);
                    if (m.find()) {
                        fromSnapshot++;
                        restores.add("shard " + s.shard + " run " + k + ": snapshot at offset " + m.group(1) + ", " + m.group(2) + " bytes, loaded in " + m.group(3) + " ms");
                    } else if (out.contains("replaying from offset 0")) {
                        fromZero++;
                        restores.add("shard " + s.shard + " run " + k + ": no snapshot yet, replayed from 0");
                    }
                }
            }

            Map<String, Long> b = broker.stats();
            long keys = count("SELECT count(*) FROM outbox");
            long overFilled = count("SELECT count(*) FROM square_off_legs l LEFT JOIN (SELECT order_id, sum(qty) q FROM fills GROUP BY order_id) f ON f.order_id = l.leg_key "
                    + "WHERE coalesce(f.q, 0) <> CASE l.status WHEN 'FILLED' THEN l.qty ELSE 0 END OR l.status NOT IN ('FILLED', 'REJECTED')");
            long unbalanced = count("SELECT count(*) FROM (SELECT txn_id FROM ledger_entries GROUP BY txn_id HAVING sum(amount) <> 0 OR count(*) <> 2) t");
            long engineOut = totalEnd(bootstrap, Topics.ENGINE_OUT);
            System.out.printf("""
                    M6 kill gate: %d accounts, %d ticks, %.0f s; %d kills -9, %d restarts from a snapshot, %d from offset 0
                      %s
                      %s
                      engine-out: %,d records for %,d outputs (%,d published again after restarts, all absorbed by the writers)
                      broker: %d distinct keys, executed %d, repeats of a known key %d; %d legs, %d episodes
                      GATE  state hash vs uninterrupted replay: %d of %d shards differ   duplicate broker orders %d   legs closed twice %d   unbalanced ledger txns %d
                    """, ACCOUNTS, TICKS, seconds, kills, fromSnapshot, fromZero, String.join("\n  ", killLog), String.join("\n  ", restores),
                    engineOut, referenceOutputs, engineOut - referenceOutputs, keys, broker.executed(), b.get("repeatsOfAKnownKey"),
                    count("SELECT count(*) FROM square_off_legs"), count("SELECT count(*) FROM square_off_episodes"),
                    mismatches.size(), N, broker.executed() - keys + b.get("repeatsOfAKnownKey"), overFilled, unbalanced);

            assertTrue(kills >= 5, "kills: " + kills);
            assertTrue(fromSnapshot >= 3, "most restarts must load a snapshot, not replay from 0: " + restores);
            assertEquals(List.of(), mismatches, "recovered state equals an uninterrupted run, bit for bit");
            assertEquals(keys, broker.executed(), "each key executed once: no duplicate broker orders");
            assertEquals(0L, b.get("repeatsOfAKnownKey"), "and no key sent again after the broker had it");
            assertEquals(0, overFilled, "legs closed twice");
            assertEquals(0, unbalanced, "ledger transactions that do not sum to zero");
            assertEquals(0, count("SELECT count(*) FROM square_off_episodes WHERE status = 'OPEN'"));
            assertTrue(count("SELECT count(*) FROM square_off_legs") > 20, "the run squared accounts off");
            assertTrue(engineOut > referenceOutputs, "restarts re-published outputs, and the projection still came out right");
            assertEquals(0, writer.failedBatches());
            assertEquals(0, ingester.refused());
            assertTrue(new File(snapshots.toString()).list().length > 0);
        } finally {
            for (ShardProcess s : shards) if (s.process != null && s.process.isAlive()) s.process.destroyForcibly();
        }
    }
}
