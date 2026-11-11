package riskengine.host;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLongArray;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import riskengine.shard.ShardRunner;

/**
 * Per-shard lag: how many events are in a shard's partition that it has not applied yet
 * (log end offset minus the shard's applied offset). The number that says whether a shard is keeping up.
 *
 * <p>A background thread asks the broker for every partition's end offset twice a second, with its own
 * consumer, so measuring never gets in a shard's way. The latest numbers are served as plain text in the
 * Prometheus format at {@code /metrics} (M6 points Prometheus at it), and {@code /healthz} answers 200
 * while every shard is running and 503 once one has stopped.
 *
 * <p>End offset and applied offset are read a moment apart, so a busy shard can show a lag that is a few
 * events off. It is a gauge for dashboards and alerts, not an exact count.
 */
public final class LagMonitor implements AutoCloseable {
    private final List<ShardRunner> runners;
    private final List<TopicPartition> partitions = new ArrayList<>();
    private final KafkaConsumer<byte[], byte[]> consumer;
    private final AtomicLongArray endOffsets;
    private final Thread sampler;
    private volatile boolean running = true;
    private HttpServer server;
    private volatile TickMetrics tickMetrics;

    /** Also serve these at /metrics. */
    void include(TickMetrics m) {
        this.tickMetrics = m;
    }

    public LagMonitor(String bootstrap, String eventsTopic, List<ShardRunner> runners) {
        this.runners = List.copyOf(runners);
        // Runner i reads partition runners.get(i).state().shardId(): a host may run only some shards.
        for (ShardRunner r : runners) partitions.add(new TopicPartition(eventsTopic, r.state().shardId()));
        this.endOffsets = new AtomicLongArray(runners.size());
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        this.consumer = new KafkaConsumer<>(props);
        sample();
        this.sampler = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(500);
                    sample();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    // The broker is unreachable for a moment: keep the last numbers and try again.
                }
            }
        }, "lag-monitor");
        sampler.setDaemon(true);
        sampler.start();
    }

    private void sample() {
        Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, Duration.ofSeconds(5));
        for (int p = 0; p < partitions.size(); p++) endOffsets.set(p, ends.get(partitions.get(p)));
    }

    /** Events shard {@code p} had still to apply at the last sample. Never negative. */
    public long lag(int p) {
        return Math.max(0, endOffsets.get(p) - runners.get(p).appliedOffset());
    }

    public long maxLag() {
        long max = 0;
        for (int p = 0; p < runners.size(); p++) max = Math.max(max, lag(p));
        return max;
    }

    /** The current numbers in the Prometheus text format. */
    public String metricsText() {
        StringBuilder sb = new StringBuilder();
        gauge(sb, "risk_shard_lag_events", "Events in the shard's partition that it has not applied yet.", this::lag);
        gauge(sb, "risk_shard_applied_offset", "Offset of the next event the shard will apply.", p -> runners.get(p).appliedOffset());
        gauge(sb, "risk_shard_log_end_offset", "End offset of the shard's partition at the last sample.", endOffsets::get);
        gauge(sb, "risk_shard_up", "1 while the shard is running, 0 once it has stopped on an event it could not apply.",
                p -> runners.get(p).failure() == null ? 1 : 0);
        TickMetrics m = tickMetrics;
        if (m != null) m.write(sb);
        return sb.toString();
    }

    private void gauge(StringBuilder sb, String name, String help, java.util.function.IntToLongFunction value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        for (int p = 0; p < runners.size(); p++) sb.append(name).append("{shard=\"").append(runners.get(p).state().shardId()).append("\"} ").append(value.applyAsLong(p)).append('\n');
    }

    /** Serves /metrics and /healthz. Port 0 picks a free port; the port in use is returned. */
    public int serve(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", ex -> respond(ex, 200, metricsText()));
        server.createContext("/healthz", ex -> {
            boolean up = runners.stream().allMatch(r -> r.failure() == null);
            respond(ex, up ? 200 : 503, up ? "ok\n" : "a shard has stopped\n");
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        running = false;
        sampler.interrupt();
        try {
            sampler.join(6_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (server != null) server.stop(0);
        consumer.close(Duration.ofSeconds(2));
    }
}
