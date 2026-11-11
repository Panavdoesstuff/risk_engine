package riskengine.writers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import riskengine.host.EngineOutPublisher;
import riskengine.margin.EngineOutputCodec;

/**
 * Reads engine-out and writes it to Postgres in batches.
 *
 * <pre>
 *   poll (up to 2,000 records)  ->  one database transaction  ->  commit it  ->  commit the Kafka offsets
 * </pre>
 *
 * The database commit comes first. If the process dies between the two commits, the same records are
 * read again after restart and written again, and every statement in {@link Projection} is safe to run
 * twice. So nothing is lost and nothing is applied twice, without a transaction spanning Kafka and
 * Postgres.
 *
 * <p>If the database is unreachable the batch is rolled back, the consumer goes back to the start of the
 * batch, and the writer retries every second. It never skips ahead.
 */
public final class EngineOutWriter implements Runnable, AutoCloseable {
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final KafkaConsumer<byte[], byte[]> consumer;
    private final Projection projection = new Projection();
    private final AtomicLong records = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile boolean running = true;
    private volatile Thread thread;
    private Connection connection;

    public EngineOutWriter(String bootstrap, String outTopic, String jdbcUrl, String dbUser, String dbPassword) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "postgres-writers");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "2000");
        p.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "100");
        this.consumer = new KafkaConsumer<>(p);
        consumer.subscribe(List.of(outTopic));
    }

    @Override
    public void run() {
        try {
            while (running) {
                ConsumerRecords<byte[], byte[]> polled = consumer.poll(Duration.ofMillis(200));
                if (polled.isEmpty()) continue;
                List<Projection.Item> batch = new ArrayList<>(polled.count());
                for (ConsumerRecord<byte[], byte[]> r : polled) {
                    Projection.Item item = decode(r);
                    if (item != null) batch.add(item);
                }
                try {
                    Connection c = connection();
                    Projection.Counts counts = projection.write(c, batch);
                    c.commit();
                    consumer.commitSync();
                    records.addAndGet(polled.count());
                    batches.incrementAndGet();
                    skipped.addAndGet(counts.skipped());
                } catch (SQLException e) {
                    failures.incrementAndGet();
                    System.err.println("writers: batch failed, will retry: " + e.getMessage());
                    dropConnection();
                    for (TopicPartition tp : polled.partitions()) consumer.seek(tp, polled.records(tp).get(0).offset());
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } catch (WakeupException e) {
            // close() was called
        } finally {
            dropConnection();
            consumer.close(Duration.ofSeconds(5));
        }
    }

    /** Null for a record this version cannot read: it is skipped so it cannot block everything behind it. */
    private Projection.Item decode(ConsumerRecord<byte[], byte[]> r) {
        try {
            Header h = r.headers().lastHeader(EngineOutPublisher.SOURCE_OFFSET_HEADER);
            if (h == null) throw new IllegalArgumentException("no " + EngineOutPublisher.SOURCE_OFFSET_HEADER + " header");
            long sourceOffset = Long.parseLong(new String(h.value(), StandardCharsets.UTF_8));
            return new Projection.Item(r.partition(), sourceOffset, EngineOutputCodec.decode(new String(r.value(), StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            skipped.incrementAndGet();
            System.err.println("writers: skipping unreadable record at " + r.topic() + "-" + r.partition() + " offset " + r.offset() + ": " + e.getMessage());
            return null;
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null) {
            connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
            connection.setAutoCommit(false);
        }
        return connection;
    }

    private void dropConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // already broken
            }
            connection = null;
        }
    }

    public Thread start() {
        Thread t = new Thread(this, "engine-out-writer");
        thread = t;
        t.start();
        return t;
    }

    /** Records read from engine-out and committed to Postgres so far. */
    public long records() {
        return records.get();
    }

    public long batches() {
        return batches.get();
    }

    /** Rows not written because they referred to an account or instrument Postgres does not have, plus unreadable records. */
    public long skipped() {
        return skipped.get();
    }

    public long failedBatches() {
        return failures.get();
    }

    @Override
    public void close() {
        running = false;
        consumer.wakeup();
        Thread t = thread;
        if (t != null) {
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
