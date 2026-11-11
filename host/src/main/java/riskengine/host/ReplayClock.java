package riskengine.host;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import riskengine.events.Event;
import riskengine.events.EventCodec;
import riskengine.events.Tick;

/**
 * "Now", as the replay sees it: the log time of the latest tick written to {@code engine-events}.
 *
 * <p>Processes outside the engine that write events (the fill ingester, the outbox relay) stamp them with
 * this instead of the wall clock. In a replay of 4 June 2024 a fill then says 4 June 2024, like the ticks
 * around it, not the day the replay was run. In a live run, ticks carry the live time, so it is the same thing.
 *
 * <p>Every tick goes to every partition, so following partition 0 is enough. A background consumer starts
 * a little before the end of that partition (it only needs the latest tick, not the history) and keeps
 * reading. Granularity is one tick: a fill between two ticks gets the earlier tick's time.
 *
 * <p>Until a tick has been seen, {@link #now()} falls back to the wall clock, and {@link #known()} is false.
 * That only happens before the market has opened, when no order can have been accepted (no tick, no
 * pre-trade check), so no fill can be waiting to be stamped.
 */
public final class ReplayClock implements LongSupplier, AutoCloseable {
    /** How far back from the end of partition 0 to look for the latest tick on start-up. */
    private static final long LOOK_BACK = 20_000;

    private final AtomicLong latest = new AtomicLong(Long.MIN_VALUE);
    private final KafkaConsumer<byte[], byte[]> consumer;
    private final Thread thread;
    private volatile boolean running = true;

    public ReplayClock(String bootstrap, String eventsTopic) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "100");
        consumer = new KafkaConsumer<>(p);
        TopicPartition tp = new TopicPartition(eventsTopic, 0);
        consumer.assign(List.of(tp));
        long end = consumer.endOffsets(List.of(tp)).get(tp);
        consumer.seek(tp, Math.max(0, end - LOOK_BACK));
        thread = new Thread(() -> {
            try {
                while (running) {
                    for (ConsumerRecord<byte[], byte[]> r : consumer.poll(Duration.ofMillis(200))) {
                        String line = new String(r.value(), StandardCharsets.UTF_8);
                        if (!line.startsWith("TICK|")) continue;
                        Event e = EventCodec.decode(line);
                        latest.accumulateAndGet(((Tick) e).logTimeMillis(), Math::max);
                    }
                }
            } catch (org.apache.kafka.common.errors.WakeupException e) {
                // closing
            } finally {
                consumer.close(Duration.ofSeconds(2));
            }
        }, "replay-clock");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The latest tick in partition 0 of {@code eventsTopic} right now (null if there is none in the last
     * stretch of the partition): for a tick writer that has to carry on where the last one stopped.
     */
    public static Tick latestTick(String bootstrap, String eventsTopic) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(p)) {
            TopicPartition tp = new TopicPartition(eventsTopic, 0);
            c.assign(List.of(tp));
            long end = c.endOffsets(List.of(tp)).get(tp);
            c.seek(tp, Math.max(0, end - LOOK_BACK));
            Tick latest = null;
            while (c.position(tp) < end) {
                for (ConsumerRecord<byte[], byte[]> r : c.poll(Duration.ofMillis(500)).records(tp)) {
                    String line = new String(r.value(), StandardCharsets.UTF_8);
                    if (line.startsWith("TICK|")) latest = (Tick) EventCodec.decode(line);
                }
            }
            return latest;
        }
    }

    /** Log time of the latest tick seen; the wall clock if none has been seen yet. */
    public long now() {
        long t = latest.get();
        return t == Long.MIN_VALUE ? System.currentTimeMillis() : t;
    }

    @Override
    public long getAsLong() {
        return now();
    }

    /** Whether a tick has been seen, so that {@link #now()} is replay time. */
    public boolean known() {
        return latest.get() != Long.MIN_VALUE;
    }

    @Override
    public void close() {
        running = false;
        consumer.wakeup();
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
