package riskengine.broker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import riskengine.host.FlatJson;

/**
 * A mock broker that misbehaves on purpose.
 *
 * <pre>
 *   POST /orders        {"key","accountId","instrumentId","qty" (signed),"refPrice"}  ->  200 {"status":"ACK"}
 *   GET  /orders/{key}                                                                 ->  200 {"status":...} or 404
 *   callbacks to the configured URL:
 *        {"type":"FILL","key","fillId","accountId","instrumentId","qty","price"}
 *        {"type":"REJECT","key","accountId","instrumentId"}
 * </pre>
 *
 * <b>Idempotent on the key.</b> The first POST for a key creates the order and executes it. Every later
 * POST for the same key is answered as if it were the first and executes nothing. That is the property
 * the square-off pipeline leans on: it may send a leg as often as it needs to.
 *
 * <p><b>The chaos</b> (each rate configurable):
 * <ul>
 *   <li><b>Timeouts, 5%.</b> Half of them lose the request (the order is never created), half lose only
 *       the answer (the order exists and will execute). The caller cannot tell which: it has to ask
 *       with GET before sending again.</li>
 *   <li><b>Rejects, 2%.</b> Acknowledged, then refused a moment later by callback.</li>
 *   <li><b>Delayed, split fills.</b> An order fills in one to three parts, each after a random delay.</li>
 *   <li><b>Duplicate callbacks, 10%.</b> The same fill, same fill id, delivered twice.</li>
 * </ul>
 * What happens to an order (reject, how it splits, prices) is drawn from the key, so the same key always
 * gets the same fate; whether a particular request times out is drawn per request.
 *
 * <pre>
 *   broker-mock --port 9300 --callback-url http://localhost:9200/broker/callbacks [--chaos full|none]
 * </pre>
 */
public final class MockBroker implements AutoCloseable {

    /**
     * @param timeoutHangMillis how long a timed-out request hangs before the broker gives up on it;
     *                          must be longer than the caller's own timeout
     */
    public record Chaos(double timeoutRate, double rejectRate, double duplicateRate, long maxFillDelayMillis, long timeoutHangMillis, long seed) {
        public static Chaos full() {
            return new Chaos(0.05, 0.02, 0.10, 400, 1_500, 20240604L);
        }

        public static Chaos none() {
            return new Chaos(0, 0, 0, 0, 0, 1);
        }
    }

    private record Order(String key, String accountId, String instrumentId, long qty, BigDecimal refPrice) {}

    private final Chaos chaos;
    private final String callbackUrl;
    private final HttpServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "mock-broker");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, String> status = new ConcurrentHashMap<>(); // key -> ACKED / REJECTED / FILLED
    private final SplittableRandom requestRandom;
    private final AtomicLong placeRequests = new AtomicLong(), executed = new AtomicLong(), repeats = new AtomicLong(),
            lostRequests = new AtomicLong(), lostResponses = new AtomicLong(), rejects = new AtomicLong(), fills = new AtomicLong(),
            duplicateCallbacks = new AtomicLong(), queries = new AtomicLong(), callbacksRefused = new AtomicLong(), callbacksPending = new AtomicLong();

    public MockBroker(Chaos chaos, String callbackUrl, int port) throws IOException {
        this.chaos = chaos;
        this.callbackUrl = callbackUrl;
        this.requestRandom = new SplittableRandom(chaos.seed());
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mock-broker-http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/orders", this::handle);
        server.createContext("/stats", ex -> respond(ex, 200, statsJson()));
    }

    public MockBroker start() {
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (ex.getRequestMethod().equals("GET") && path.startsWith("/orders/")) {
                queries.incrementAndGet();
                String s = status.get(path.substring("/orders/".length()));
                respond(ex, s == null ? 404 : 200, FlatJson.write("status", s == null ? "UNKNOWN" : s));
            } else if (ex.getRequestMethod().equals("POST") && path.equals("/orders")) {
                place(ex);
            } else {
                respond(ex, 404, FlatJson.write("error", "not found"));
            }
        } catch (IllegalArgumentException e) {
            respond(ex, 400, FlatJson.write("error", "bad request"));
        }
    }

    private void place(HttpExchange ex) throws IOException {
        Map<String, String> f = FlatJson.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Order order = new Order(req(f, "key"), req(f, "accountId"), req(f, "instrumentId"), Long.parseLong(req(f, "qty")), new BigDecimal(req(f, "refPrice")));
        if (order.qty() == 0) throw new IllegalArgumentException("qty");
        placeRequests.incrementAndGet();
        double roll;
        synchronized (requestRandom) {
            roll = requestRandom.nextDouble();
        }
        if (roll < chaos.timeoutRate() / 2) {
            // The request is lost on the way in: nothing is created.
            lostRequests.incrementAndGet();
            hang(ex);
            return;
        }
        if (status.putIfAbsent(order.key(), "ACKED") == null) {
            executed.incrementAndGet();
            execute(order);
        } else {
            repeats.incrementAndGet(); // seen this key before: answer, execute nothing
        }
        if (roll < chaos.timeoutRate()) {
            // The order exists, but the answer is lost on the way out.
            lostResponses.incrementAndGet();
            hang(ex);
            return;
        }
        respond(ex, 200, FlatJson.write("status", "ACK"));
    }

    private void hang(HttpExchange ex) throws IOException {
        try {
            Thread.sleep(chaos.timeoutHangMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        respond(ex, 504, FlatJson.write("error", "timeout"));
    }

    /** Decides the order's fate from its key and schedules the callbacks. */
    private void execute(Order o) {
        SplittableRandom r = new SplittableRandom(chaos.seed() * 31 + o.key().hashCode());
        long delay = chaos.maxFillDelayMillis() == 0 ? 0 : r.nextLong(chaos.maxFillDelayMillis() + 1);
        if (r.nextDouble() < chaos.rejectRate()) {
            rejects.incrementAndGet();
            schedule(delay, o.key(), "REJECTED", FlatJson.write("type", "REJECT", "key", o.key(), "accountId", o.accountId(), "instrumentId", o.instrumentId()), false);
            return;
        }
        // One to three parts, in whole lots of 25 where the quantity allows it.
        long units = Math.abs(o.qty()), sign = Long.signum(o.qty());
        int parts = units % 25 == 0 ? (int) Math.min(units / 25, 1 + r.nextInt(3)) : 1;
        long left = units;
        for (int n = 1; n <= parts; n++) {
            long qty = n == parts ? left : 25 * Math.max(1, (left / 25) / (parts - n + 1));
            left -= qty;
            // Within 0.1% of the reference price, on a 5-paisa tick, never negative.
            BigDecimal slip = o.refPrice().multiply(BigDecimal.valueOf((r.nextDouble() - 0.5) * 0.002));
            BigDecimal price = o.refPrice().add(slip).multiply(BigDecimal.valueOf(20)).setScale(0, RoundingMode.HALF_EVEN)
                    .divide(BigDecimal.valueOf(20), 2, RoundingMode.UNNECESSARY).max(BigDecimal.ZERO);
            String body = FlatJson.write("type", "FILL", "key", o.key(), "fillId", "BF:" + o.key() + ":" + n, "accountId", o.accountId(),
                    "instrumentId", o.instrumentId(), "qty", sign * qty, "price", price);
            fills.incrementAndGet();
            boolean duplicate = r.nextDouble() < chaos.duplicateRate();
            schedule(delay, o.key(), n == parts ? "FILLED" : null, body, duplicate);
            if (chaos.maxFillDelayMillis() > 0) delay += 1 + r.nextLong(chaos.maxFillDelayMillis() + 1);
        }
    }

    private void schedule(long delayMillis, String key, String statusAfter, String body, boolean duplicate) {
        callbacksPending.addAndGet(duplicate ? 2 : 1);
        scheduler.schedule(() -> {
            deliver(body);
            if (statusAfter != null) status.put(key, statusAfter);
        }, delayMillis, TimeUnit.MILLISECONDS);
        if (duplicate) {
            duplicateCallbacks.incrementAndGet();
            scheduler.schedule(() -> deliver(body), delayMillis + 20, TimeUnit.MILLISECONDS);
        }
    }

    /** Delivers one callback, trying again for a while if the receiver is not answering. */
    private void deliver(String body) {
        try {
            for (int attempt = 1; attempt <= 30; attempt++) {
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(callbackUrl)).timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() / 100 == 2) return;
                    if (r.statusCode() / 100 == 4) {
                        callbacksRefused.incrementAndGet(); // the receiver says the callback is wrong: sending it again will not help
                        return;
                    }
                } catch (IOException e) {
                    // receiver down or slow: try again
                }
                Thread.sleep(200L * attempt);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            callbacksPending.decrementAndGet();
        }
    }

    private static String req(Map<String, String> f, String name) {
        String v = f.get(name);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException(name);
        return v;
    }

    private static void respond(HttpExchange ex, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------------------------------------------------------------- what happened

    /** Orders actually executed: one per distinct key, however many requests arrived for it. */
    public long executed() {
        return executed.get();
    }

    /** Counters by name: requests, executed, repeats, lost requests and responses, rejects, fills, duplicates, queries. */
    public Map<String, Long> stats() {
        Map<String, Long> m = new TreeMap<>();
        m.put("placeRequests", placeRequests.get());
        m.put("executed", executed.get());
        m.put("repeatsOfAKnownKey", repeats.get());
        m.put("timeoutsRequestLost", lostRequests.get());
        m.put("timeoutsResponseLost", lostResponses.get());
        m.put("rejects", rejects.get());
        m.put("fills", fills.get());
        m.put("duplicateCallbacks", duplicateCallbacks.get());
        m.put("statusQueries", queries.get());
        m.put("callbacksRefused", callbacksRefused.get());
        m.put("callbacksPending", callbacksPending.get());
        return m;
    }

    /** True when every scheduled callback has been delivered (or given up on). */
    public boolean idle() {
        return callbacksPending.get() == 0;
    }

    private String statsJson() {
        Object[] kv = new Object[stats().size() * 2];
        int i = 0;
        for (var e : stats().entrySet()) {
            kv[i++] = e.getKey();
            kv[i++] = e.getValue();
        }
        return FlatJson.write(kv);
    }

    @Override
    public void close() {
        server.stop(0);
        scheduler.shutdownNow();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<String, String> f = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) f.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        if (!f.containsKey("callback-url")) {
            System.err.println("usage: broker-mock --callback-url URL [--port 9300] [--chaos full|none]");
            System.exit(2);
        }
        Chaos chaos = f.getOrDefault("chaos", "full").equals("none") ? Chaos.none() : Chaos.full();
        MockBroker broker = new MockBroker(chaos, f.get("callback-url"), Integer.parseInt(f.getOrDefault("port", "9300"))).start();
        System.out.println("mock broker on :" + broker.port() + ", callbacks to " + f.get("callback-url") + ", " + chaos);
        Thread.currentThread().join();
    }
}
