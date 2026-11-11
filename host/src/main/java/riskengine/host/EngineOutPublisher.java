package riskengine.host;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import riskengine.margin.EngineOutput;
import riskengine.margin.EngineOutputCodec;

/**
 * Publishes what shards say (level changes, order decisions) to {@code engine-out}. Shard p writes to
 * partition p, so one account's outputs stay in the order its shard produced them.
 *
 * <p>Sends are not awaited one by one: the shard must not wait on the network for every output. They are
 * idempotent and ordered per partition, and {@link #flush()} waits for everything sent so far. If a send
 * fails for good, {@link #failure()} reports it and the host stops; on restart the shard replays its log
 * and publishes the same lines again (consumers dedupe on order id, or on account + tick_seq).
 */
public final class EngineOutPublisher implements AutoCloseable {
    private final String topic;
    private final KafkaProducer<byte[], byte[]> producer;
    private final AtomicReference<Exception> failure = new AtomicReference<>();

    public EngineOutPublisher(String bootstrap, String topic, int partitions) {
        Topics.requirePartitions(bootstrap, topic, partitions);
        this.topic = topic;
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.LINGER_MS_CONFIG, "2");
        this.producer = new KafkaProducer<>(p);
    }

    /** Record header carrying the offset (in engine-events, same partition) of the event that caused the output. */
    public static final String SOURCE_OFFSET_HEADER = "source-offset";

    /**
     * Called on a shard's thread.
     *
     * @param sourceOffset offset of the causing event in the shard's engine-events partition. Unlike the
     *                     record's own offset in engine-out, it is the same every time the log is
     *                     replayed, so a consumer can refuse to let an old output overwrite a newer one.
     */
    public void publish(int shardId, long sourceOffset, EngineOutput out) {
        byte[] key = out.accountId().getBytes(StandardCharsets.UTF_8);
        byte[] value = EngineOutputCodec.encode(out).getBytes(StandardCharsets.UTF_8);
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, shardId, key, value);
        record.headers().add(SOURCE_OFFSET_HEADER, Long.toString(sourceOffset).getBytes(StandardCharsets.UTF_8));
        producer.send(record, (meta, ex) -> {
            if (ex != null) failure.compareAndSet(null, ex);
        });
    }

    public void flush() {
        producer.flush();
    }

    /** The first send that failed for good, or null. */
    public Exception failure() {
        return failure.get();
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(10));
    }
}
