package riskengine.api.orders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import riskengine.api.ApiProperties;
import riskengine.margin.EngineOutput;
import riskengine.margin.EngineOutputCodec;
import riskengine.margin.OrderDecision;

/**
 * Reads engine-out and records each order decision in the {@link OrderStore}.
 *
 * <p>It uses a consumer group, so with several API instances each decision is handled by one of them
 * (the store is shared). Offsets are committed only after a batch is stored. If storing fails, the batch
 * is read again; storing a decision twice is harmless, and so is seeing one twice after a shard replays
 * its log (engine-out is at-least-once).
 */
@Component
class DecisionListener implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DecisionListener.class);

    private final ApiProperties props;
    private final OrderStore store;
    private volatile KafkaConsumer<byte[], byte[]> consumer;
    private volatile Thread thread;
    private volatile boolean running;

    DecisionListener(ApiProperties props, OrderStore store) {
        this.props = props;
        this.store = store;
    }

    @Override
    public void start() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafkaBootstrap());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "order-api");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "100");
        consumer = new KafkaConsumer<>(p);
        consumer.subscribe(List.of(props.outTopic()));
        running = true;
        thread = new Thread(this::run, "engine-out-listener");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        KafkaConsumer<byte[], byte[]> c = consumer;
        try {
            while (running) {
                ConsumerRecords<byte[], byte[]> batch = c.poll(Duration.ofMillis(500));
                if (batch.isEmpty()) continue;
                try {
                    for (ConsumerRecord<byte[], byte[]> r : batch) handle(r);
                    c.commitSync();
                } catch (WakeupException e) {
                    throw e;
                } catch (RuntimeException e) {
                    // Most likely Redis is unreachable. Go back to the start of this batch and try again.
                    log.error("could not store decisions; will retry", e);
                    for (TopicPartition tp : batch.partitions()) c.seek(tp, batch.records(tp).get(0).offset());
                    sleepQuietly();
                }
            }
        } catch (WakeupException e) {
            // stop() was called
        } finally {
            c.close(Duration.ofSeconds(5));
        }
    }

    private void handle(ConsumerRecord<byte[], byte[]> r) {
        EngineOutput out;
        try {
            out = EngineOutputCodec.decode(new String(r.value(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            // A line this version cannot read must not block every decision behind it.
            log.warn("skipping unreadable engine-out record at {}-{} offset {}", r.topic(), r.partition(), r.offset());
            return;
        }
        if (out instanceof OrderDecision d) store.decided(d);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public void stop() {
        running = false;
        KafkaConsumer<byte[], byte[]> c = consumer;
        if (c != null) c.wakeup();
        Thread t = thread;
        if (t != null) {
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
