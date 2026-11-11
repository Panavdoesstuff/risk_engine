package riskengine.host;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import riskengine.events.AccountEvent;
import riskengine.events.Event;
import riskengine.events.EventCodec;
import riskengine.events.EventLog;
import riskengine.events.Tick;
import riskengine.shard.Partitioner;

/**
 * The {@code engine-events} topic: N partitions, each one an {@link EventLog} for one shard.
 *
 * <p>All partitions share one producer (producers are thread-safe and expensive); each partition's log has
 * its own consumer. Writers that only append (the order API, the replayer) use {@link #append(AccountEvent)}
 * and {@link #appendToAll(Tick)} and never open a consumer.
 *
 * <p>Every record is one {@link EventCodec} line, so a partition holds byte-for-byte what a
 * {@code FileEventLog} would. The partition is always set explicitly from {@link Partitioner}; the record
 * key (the account id) is there for people reading the topic, not for routing.
 */
public final class RedpandaLogs implements AutoCloseable {
    private static final long SEND_TIMEOUT_MS = 10_000;

    private final String bootstrap;
    private final String topic;
    private final int partitions;
    private final Duration pollWait;
    private final KafkaProducer<byte[], byte[]> producer;
    private final java.util.concurrent.atomic.AtomicReference<Exception> asyncFailure = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * @param pollWait how long an empty {@code read} waits for new events before returning nothing
     * @throws IllegalStateException if the topic is missing or its partition count is not {@code partitions}
     */
    public RedpandaLogs(String bootstrap, String topic, int partitions, Duration pollWait) {
        Topics.requirePartitions(bootstrap, topic, partitions);
        this.bootstrap = bootstrap;
        this.topic = topic;
        this.partitions = partitions;
        this.pollWait = pollWait;
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        // Acknowledged by the broker before append() returns, and retries can neither duplicate nor
        // reorder: with idempotence on, the broker drops a batch it has already written.
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        this.producer = new KafkaProducer<>(p);
    }

    public int partitions() {
        return partitions;
    }

    public int partitionOf(String accountId) {
        return Partitioner.partition(accountId, partitions);
    }

    /** The log of one partition. The caller closes it; closing it does not close the shared producer. */
    public EventLog partition(int partition) {
        if (partition < 0 || partition >= partitions) throw new IllegalArgumentException("no partition " + partition);
        return new RedpandaEventLog(this, bootstrap, topic, partition, pollWait);
    }

    /** Appends to the partition that owns the event's account; returns the offset. Thread-safe. */
    public long append(AccountEvent e) {
        return send(partitionOf(e.accountId()), (Event) e);
    }

    /** Writes one tick to every partition, as the single tick writer must. Returns nothing: N offsets. */
    public void appendToAll(Tick t) {
        for (int p = 0; p < partitions; p++) send(p, t);
    }

    /**
     * Bulk writing (the replayer, the stress harness): hands the event to the producer and returns without
     * waiting for the broker. Events sent to one partition from one thread keep their order. Call
     * {@link #flush()} at the end; it throws if any of them was not written.
     */
    public void appendAsync(int partition, Event e) {
        producer.send(record(partition, e), (meta, ex) -> {
            if (ex != null) asyncFailure.compareAndSet(null, ex);
        });
    }

    /** Async version of {@link #append(AccountEvent)}. */
    public void appendAsync(AccountEvent e) {
        appendAsync(partitionOf(e.accountId()), (Event) e);
    }

    /** Waits for every {@link #appendAsync} so far to be acknowledged. */
    public void flush() {
        producer.flush();
        Exception ex = asyncFailure.get();
        if (ex != null) throw new EventLogUnavailableException("an asynchronous write to " + topic + " failed", ex);
    }

    private ProducerRecord<byte[], byte[]> record(int partition, Event e) {
        byte[] key = (e instanceof AccountEvent a ? a.accountId() : ((Tick) e).underlying()).getBytes(StandardCharsets.UTF_8);
        return new ProducerRecord<>(topic, partition, key, EventCodec.encode(e).getBytes(StandardCharsets.UTF_8));
    }

    /** Blocks until the broker has acknowledged the record. */
    long send(int partition, Event e) {
        try {
            RecordMetadata m = producer.send(record(partition, e)).get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return m.offset();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EventLogUnavailableException("interrupted writing to " + topic + "-" + partition, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new EventLogUnavailableException("could not write to " + topic + "-" + partition, ex);
        }
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }

    /** The write was not acknowledged. It may or may not have reached the log; retrying must be safe. */
    public static final class EventLogUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public EventLogUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
