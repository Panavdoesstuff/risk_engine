package riskengine.broker;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import riskengine.host.FlatJson;

class MockBrokerTest {
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    HttpResponse<String> post(int port, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders")).timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theSameKeyPostedManyTimesExecutesOnce() throws Exception {
        List<String> callbacks = new CopyOnWriteArrayList<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress(0), 0);
        receiver.createContext("/cb", ex -> {
            callbacks.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        receiver.start();
        try (MockBroker broker = new MockBroker(MockBroker.Chaos.none(), "http://localhost:" + receiver.getAddress().getPort() + "/cb", 0).start()) {
            String order = FlatJson.write("key", "A1:E1:NIFTY-20240627-FUT:1", "accountId", "A1", "instrumentId", "NIFTY-20240627-FUT", "qty", 75L, "refPrice", "23300.00");
            for (int i = 0; i < 5; i++) assertEquals(200, post(broker.port(), order).statusCode(), "every repeat is answered as if it were the first");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!broker.idle() && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(1, broker.executed());
            assertEquals(4L, broker.stats().get("repeatsOfAKnownKey"));
            long filled = callbacks.stream().map(FlatJson::parse).mapToLong(f -> Long.parseLong(f.get("qty"))).sum();
            assertEquals(75, filled, "filled once, in whatever parts: " + callbacks);
            HttpResponse<String> status = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + broker.port() + "/orders/A1:E1:NIFTY-20240627-FUT:1")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("FILLED", FlatJson.parse(status.body()).get("status"));
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + broker.port() + "/orders/never-sent")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400, post(broker.port(), "{\"key\":\"x\"}").statusCode(), "a malformed order is refused");
        } finally {
            receiver.stop(0);
        }
    }
}
