package riskengine.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import riskengine.api.security.Caller.Role;
import riskengine.api.security.JwtTokens;
import riskengine.events.EventLog;
import riskengine.events.LoggedEvent;
import riskengine.events.OrderClosed;
import riskengine.events.OrderRequest;
import riskengine.events.Tick;
import riskengine.host.RedpandaLogs;
import riskengine.host.ShardHost;
import riskengine.host.Topics;
import riskengine.margin.AccountBook;
import riskengine.ref.InstrumentCsv;
import riskengine.writers.BookLoader;
import riskengine.writers.EngineOutWriter;
import riskengine.shard.Fixtures;
import riskengine.shard.ShardConfig;

/**
 * The whole path, with nothing mocked: the real Spring Security filter chain, a real Redis (rate limit,
 * replay guard), a real Redpanda broker, real shards doing the pre-trade check, and the real writers
 * putting the result into a real Postgres with the real migrations.
 * Needs Docker.
 *
 * <p>Every test uses its own user ids, so one test's rate-limit bucket and request ids never affect another.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTest {
    static final String SECRET = "test-only-secret-0123456789-abcdefghij";
    static final String ISSUER = "risk-engine";
    static final int PARTITIONS = 4;
    static final int BURST = 10;
    static final String CALL = "NIFTY-20240613-23300-CE";

    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.7");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");
    static final ShardHost SHARDS;
    static final RedpandaLogs LOGS;
    static final EngineOutWriter WRITER;

    static {
        REDPANDA.start();
        REDIS.start();
        POSTGRES.start();
        String bootstrap = REDPANDA.getBootstrapServers();
        Topics.ensure(bootstrap, Topics.ENGINE_EVENTS, PARTITIONS);
        Topics.ensure(bootstrap, Topics.ENGINE_OUT, PARTITIONS);
        List<AccountBook> books = List.of(
                new AccountBook("A-RICH", new BigDecimal("5000000"), BigDecimal.ZERO, new TreeMap<>()),
                new AccountBook("A-POOR", new BigDecimal("20000"), BigDecimal.ZERO, new TreeMap<>()),
                new AccountBook("A-VICTIM", new BigDecimal("5000000"), BigDecimal.ZERO, new TreeMap<>()));
        // Postgres: the real migrations, then the same book the shards start from.
        try {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("filesystem:" + System.getProperty("migrations.dir")).load().migrate();
            Path dir = Files.createTempDirectory("api-test-book");
            InstrumentCsv.write(dir.resolve("instruments.csv"), Fixtures.instruments());
            StringBuilder accounts = new StringBuilder("account_id,archetype,thin_funded,cash_balance\n");
            for (AccountBook b : books) accounts.append(b.id()).append(",TEST,false,").append(b.cash().toPlainString()).append('\n');
            Files.writeString(dir.resolve("accounts.csv"), accounts);
            Files.writeString(dir.resolve("positions.csv"), "account_id,instrument_id,lots,qty,avg_price\n");
            BookLoader.load(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), dir.resolve("instruments.csv"), dir, LocalDate.of(2024, 6, 3));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
        WRITER = new EngineOutWriter(bootstrap, Topics.ENGINE_OUT, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        WRITER.start();
        SHARDS = new ShardHost(bootstrap, PARTITIONS, Fixtures.CFG, ShardConfig.defaults(), Fixtures.instruments(), books);
        SHARDS.start();
        LOGS = new RedpandaLogs(bootstrap, Topics.ENGINE_EVENTS, PARTITIONS, Duration.ofMillis(300));
        LOGS.appendToAll(new Tick(1, Fixtures.T0, "NIFTY", new BigDecimal("23300"), new BigDecimal("20")));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("api.jwt-secret", () -> SECRET);
        r.add("api.jwt-issuer", () -> ISSUER);
        r.add("api.kafka-bootstrap", REDPANDA::getBootstrapServers);
        r.add("api.partitions", () -> PARTITIONS);
        r.add("api.rate-limit.capacity", () -> BURST);
        r.add("api.rate-limit.refill-per-second", () -> 5);
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stop() {
        assertNull(SHARDS.failure(), "no request, however malformed, may stop a shard");
        assertEquals(0, WRITER.failedBatches(), "the writers never had to retry a batch");
        LOGS.close();
        SHARDS.close();
        WRITER.close();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    static final AtomicInteger SEQ = new AtomicInteger();

    static String newUser() {
        return "user-" + SEQ.incrementAndGet();
    }

    static String newRequestId() {
        return "req-" + SEQ.incrementAndGet() + "-0000";
    }

    static String trader(String user, String account) {
        return JwtTokens.mint(SECRET, ISSUER, user, Role.TRADER, account, Instant.now(), Duration.ofMinutes(5));
    }

    static String riskOps(String user) {
        return JwtTokens.mint(SECRET, ISSUER, user, Role.RISK_OPS, null, Instant.now(), Duration.ofMinutes(5));
    }

    static String order(String account, String instrument, String side, Object qty) {
        return "{\"accountId\":\"" + account + "\",\"instrumentId\":\"" + instrument + "\",\"side\":\"" + side + "\",\"qty\":" + qty + "}";
    }

    static String sellOneLot(String account) {
        return order(account, CALL, "SELL", 25);
    }

    MockHttpServletResponse send(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn().getResponse();
    }

    MockHttpServletResponse submit(String token, String requestId, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/orders").contentType(MediaType.APPLICATION_JSON).content(body);
        if (requestId != null) request.header("X-Request-Id", requestId);
        return send(request, token);
    }

    JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    void assertRefused(MockHttpServletResponse response, int status, String error) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        assertEquals(error, body(response).path("error").asText(), response.getContentAsString());
    }

    /** Polls GET /orders/{id} as a fresh risk-ops user (slower than its rate limit) until the shard has decided. */
    JsonNode awaitDecision(String orderId) throws Exception {
        String viewer = riskOps(newUser());
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            MockHttpServletResponse r = send(get("/orders/" + orderId), viewer);
            assertEquals(200, r.getStatus(), r.getContentAsString());
            JsonNode view = body(r);
            if (!view.path("status").asText().equals("PENDING")) return view;
            assertTrue(System.nanoTime() < deadline, "still PENDING after 30 s: " + view);
            Thread.sleep(250);
        }
    }

    /** Every OrderRequest for the account that is actually in the log (read straight from its partition). */
    static List<OrderRequest> ordersInLog(String account) {
        List<OrderRequest> out = new ArrayList<>();
        try (EventLog log = LOGS.partition(LOGS.partitionOf(account))) {
            long end = log.endOffset(), next = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (next < end && System.nanoTime() < deadline) {
                for (LoggedEvent e : log.read(next, 1_000)) {
                    next = e.offset() + 1;
                    if (e.event() instanceof OrderRequest o && o.accountId().equals(account)) out.add(o);
                }
            }
            assertEquals(end, next, "read the whole partition");
        }
        return out;
    }

    // ---------------------------------------------------------------- 401: who are you?

    @Test
    void noTokenIs401() throws Exception {
        MockHttpServletResponse r = submit(null, newRequestId(), sellOneLot("A-RICH"));
        assertRefused(r, 401, "unauthenticated");
        assertEquals("Bearer", r.getHeader("WWW-Authenticate"));
        assertRefused(send(get("/orders/O-00000000000000000000000000000000"), null), 401, "unauthenticated");
        assertRefused(send(get("/anything-else"), null), 401, "unauthenticated");
    }

    @Test
    void aTokenThatIsNotOursIs401() throws Exception {
        String user = newUser();
        Instant now = Instant.now();
        String good = trader(user, "A-RICH");
        String[] parts = good.split("\\.");

        List<String> bad = new ArrayList<>();
        bad.add("not-a-jwt");
        // Signed with a different key.
        bad.add(JwtTokens.mint("another-secret-0123456789-abcdefghijkl", ISSUER, user, Role.TRADER, "A-RICH", now, Duration.ofMinutes(5)));
        // Expired two minutes ago (beyond the 60 s clock-skew allowance).
        bad.add(JwtTokens.mint(SECRET, ISSUER, user, Role.TRADER, "A-RICH", now.minusSeconds(600), Duration.ofSeconds(480)));
        // Right key, wrong issuer.
        bad.add(JwtTokens.mint(SECRET, "someone-else", user, Role.TRADER, "A-RICH", now, Duration.ofMinutes(5)));
        // "alg":"none": the classic forgery. Header says unsigned, claims say whatever the attacker likes.
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        bad.add(b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "." + parts[1] + ".");
        // Real header and signature, but the claims were edited afterwards (account swapped).
        String edited = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("A-RICH", "A-VICTIM");
        bad.add(parts[0] + "." + b64.encodeToString(edited.getBytes(StandardCharsets.UTF_8)) + "." + parts[2]);
        // Correctly signed, but missing things every token must have.
        bad.add(sign(new JWTClaimsSet.Builder().issuer(ISSUER).subject(user).claim("account", "A-RICH")
                .expirationTime(Date.from(now.plusSeconds(300)))));                                   // no role
        bad.add(sign(new JWTClaimsSet.Builder().issuer(ISSUER).subject(user).claim("role", "admin").claim("account", "A-RICH")
                .expirationTime(Date.from(now.plusSeconds(300)))));                                   // unknown role
        bad.add(sign(new JWTClaimsSet.Builder().issuer(ISSUER).subject(user).claim("role", "trader")
                .expirationTime(Date.from(now.plusSeconds(300)))));                                   // trader without an account
        bad.add(sign(new JWTClaimsSet.Builder().issuer(ISSUER).subject(user).claim("role", "trader").claim("account", "A-RICH"))); // never expires
        bad.add(sign(new JWTClaimsSet.Builder().issuer(ISSUER).claim("role", "risk-ops")
                .expirationTime(Date.from(now.plusSeconds(300)))));                                   // no subject

        for (String token : bad) {
            assertRefused(submit(token, newRequestId(), sellOneLot("A-RICH")), 401, "unauthenticated");
            assertRefused(send(get("/orders/O-00000000000000000000000000000000"), token), 401, "unauthenticated");
        }
        assertEquals(202, submit(good, newRequestId(), sellOneLot("A-RICH")).getStatus(), "the untampered token works");
    }

    static String sign(JWTClaimsSet.Builder claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(JwtTokens.key(SECRET)));
        return jwt.serialize();
    }

    // ---------------------------------------------------------------- 403: you may not do that

    @Test
    void aTraderCannotPlaceOrdersOnSomeoneElsesAccount() throws Exception {
        String mallory = trader(newUser(), "A-RICH");
        assertRefused(submit(mallory, newRequestId(), sellOneLot("A-VICTIM")), 403, "forbidden");
        assertEquals(202, submit(mallory, newRequestId(), sellOneLot("A-RICH")).getStatus(), "their own account is fine");
        assertTrue(ordersInLog("A-VICTIM").isEmpty(), "the refused order never reached the log");
    }

    @Test
    void riskOpsCannotPlaceUserOrdersAndNobodyCanReachUnlistedPaths() throws Exception {
        String ops = riskOps(newUser());
        assertRefused(submit(ops, newRequestId(), sellOneLot("A-RICH")), 403, "forbidden");
        assertRefused(send(get("/orders"), ops), 403, "forbidden");
        assertRefused(send(get("/actuator/env"), trader(newUser(), "A-RICH")), 403, "forbidden");
    }

    @Test
    void aTraderCannotSeeAnotherAccountsOrderButRiskOpsCan() throws Exception {
        String owner = trader(newUser(), "A-RICH");
        String orderId = body(submit(owner, newRequestId(), sellOneLot("A-RICH"))).path("orderId").asText();

        assertEquals(200, send(get("/orders/" + orderId), owner).getStatus());
        assertEquals(200, send(get("/orders/" + orderId), riskOps(newUser())).getStatus());
        MockHttpServletResponse other = send(get("/orders/" + orderId), trader(newUser(), "A-POOR"));
        MockHttpServletResponse missing = send(get("/orders/O-ffffffffffffffffffffffffffffffff"), trader(newUser(), "A-POOR"));
        assertRefused(other, 404, "not_found");
        assertEquals(missing.getContentAsString(), other.getContentAsString(), "someone else's order is indistinguishable from no order");
    }

    // ---------------------------------------------------------------- 429: slow down

    @Test
    void aBurstBeyondTheBucketIs429AndOtherUsersAreUnaffected() throws Exception {
        String user = newUser();
        String greedy = trader(user, "A-RICH");
        List<Integer> statuses = new ArrayList<>();
        MockHttpServletResponse last = null;
        for (int i = 0; i < BURST + 5; i++) {
            last = submit(greedy, newRequestId(), sellOneLot("A-RICH"));
            statuses.add(last.getStatus());
        }
        long accepted = statuses.stream().filter(s -> s == 202).count();
        long limited = statuses.stream().filter(s -> s == 429).count();
        assertEquals(BURST + 5, accepted + limited, statuses.toString());
        // The bucket holds BURST tokens and refills at 5 a second while the loop runs, so a few more than
        // BURST can get through, but not all 15, and once it runs dry everything is refused.
        assertTrue(accepted >= BURST && limited >= 1, statuses.toString());
        assertEquals(List.copyOf(statuses.subList(0, BURST)), java.util.Collections.nCopies(BURST, 202), "the first " + BURST + " are the burst");
        assertRefused(last, 429, "rate_limited");
        assertTrue(Integer.parseInt(last.getHeader("Retry-After")) >= 1, "says when to come back");

        assertEquals(202, submit(trader(newUser(), "A-RICH"), newRequestId(), sellOneLot("A-RICH")).getStatus(), "limits are per user");
        Thread.sleep(1_200); // 5 tokens a second: after 1.2 s there are tokens again
        assertEquals(202, submit(greedy, newRequestId(), sellOneLot("A-RICH")).getStatus(), "the bucket refills");
    }

    @Test
    void requestsWithoutAValidTokenDoNotUseUpAnyonesBucket() throws Exception {
        String user = newUser();
        String expired = JwtTokens.mint(SECRET, ISSUER, user, Role.TRADER, "A-RICH", Instant.now().minusSeconds(600), Duration.ofSeconds(480));
        for (int i = 0; i < BURST * 3; i++) assertEquals(401, submit(expired, newRequestId(), sellOneLot("A-RICH")).getStatus());
        // The same user's real token still has its whole burst.
        for (int i = 0; i < BURST; i++) assertEquals(202, submit(trader(user, "A-RICH"), newRequestId(), sellOneLot("A-RICH")).getStatus());
    }

    // ---------------------------------------------------------------- replay

    @Test
    void aRepeatedRequestIdIsRejectedAndTheOrderIsWrittenOnce() throws Exception {
        String user = newUser();
        String token = trader(user, "A-POOR");
        String requestId = newRequestId();

        MockHttpServletResponse first = submit(token, requestId, order("A-POOR", CALL, "BUY", 25));
        assertEquals(202, first.getStatus(), first.getContentAsString());
        String orderId = body(first).path("orderId").asText();
        assertEquals("/orders/" + orderId, first.getHeader("Location"));

        MockHttpServletResponse replay = submit(token, requestId, order("A-POOR", CALL, "BUY", 25));
        assertRefused(replay, 409, "replayed_request");
        assertEquals(orderId, body(replay).path("orderId").asText(), "it points at the order the id was first used for");

        // Same id with a different body is still a replay: the id is what is protected, not the content.
        assertRefused(submit(token, requestId, order("A-POOR", CALL, "BUY", 5000)), 409, "replayed_request");

        awaitDecision(orderId);
        List<OrderRequest> written = ordersInLog("A-POOR").stream().filter(o -> o.orderId().equals(orderId)).toList();
        assertEquals(1, written.size(), "three requests, one order in the log");
        assertEquals(25, written.get(0).qty(), "and it is the first one");
    }

    @Test
    void requestIdsBelongToTheUserWhoSentThem() throws Exception {
        String requestId = newRequestId();
        MockHttpServletResponse a = submit(trader(newUser(), "A-RICH"), requestId, sellOneLot("A-RICH"));
        MockHttpServletResponse b = submit(trader(newUser(), "A-RICH"), requestId, sellOneLot("A-RICH"));
        assertEquals(202, a.getStatus());
        assertEquals(202, b.getStatus(), "another user's use of the same id is not a replay, so ids cannot be burned for someone else");
        assertNotEquals(body(a).path("orderId").asText(), body(b).path("orderId").asText());
    }

    @Test
    void aMissingOrMalformedRequestIdIs400() throws Exception {
        String token = trader(newUser(), "A-RICH");
        assertRefused(submit(token, null, sellOneLot("A-RICH")), 400, "invalid_request");
        assertRefused(submit(token, "short", sellOneLot("A-RICH")), 400, "invalid_request");
        assertRefused(submit(token, "has spaces and | pipes", sellOneLot("A-RICH")), 400, "invalid_request");
        assertRefused(submit(token, "x".repeat(65), sellOneLot("A-RICH")), 400, "invalid_request");
    }

    // ---------------------------------------------------------------- input validation

    @Test
    void malformedOrdersAre400AndNeverReachTheLog() throws Exception {
        String token = trader(newUser(), "A-RICH");
        String riskOpsReader = riskOps(newUser());
        int before = ordersInLog("A-RICH").size();
        List<String> bad = List.of(
                "",
                "{not json",
                "[]",
                order("A-RICH", CALL, "SELL", 0),
                order("A-RICH", CALL, "SELL", -25),
                order("A-RICH", CALL, "SELL", 25.5),
                order("A-RICH", CALL, "SELL", "\"25\""),
                order("A-RICH", CALL, "SELL", 100_001),
                order("A-RICH", CALL, "SELL", "99999999999999999999"),
                order("A-RICH", CALL, "HOLD", 25),
                order("A-RICH", CALL, "sell", 25),
                order("A-RICH", "NIFTY", "SELL", 25),
                order("A-RICH", "NIFTY-20240613-23300-CE|FILL|v1|F1|O1|A-VICTIM|X|1|1|1", "SELL", 25),
                order("A-RICH", "'; DROP TABLE orders; --", "SELL", 25),
                order("A-RICH", "NIFTY-20240613-23300-CE\\nTICK", "SELL", 25),
                "{\"accountId\":\"A-RICH\",\"instrumentId\":\"" + CALL + "\",\"side\":\"SELL\"}",
                "{\"accountId\":\"A-RICH\",\"instrumentId\":\"" + CALL + "\",\"side\":\"SELL\",\"qty\":null}",
                "{\"accountId\":null,\"instrumentId\":\"" + CALL + "\",\"side\":\"SELL\",\"qty\":25}",
                "{\"accountId\":\"A-RICH\",\"instrumentId\":\"" + CALL + "\",\"side\":\"SELL\",\"qty\":25,\"origin\":\"SQUARE_OFF\"}",
                "{\"accountId\":\"A-RICH\",\"instrumentId\":\"" + CALL + "\",\"side\":\"SELL\",\"qty\":25,\"orderId\":\"O-mine\"}");
        int sent = 0;
        for (String body : bad) {
            // Stay inside this user's rate limit so every refusal here is about the body.
            if (++sent % (BURST - 2) == 0) Thread.sleep(2_000);
            MockHttpServletResponse r = submit(token, newRequestId(), body);
            assertRefused(r, 400, "invalid_request");
            assertFalse(r.getContentAsString().contains("riskengine") || r.getContentAsString().contains("jackson"),
                    "no class names or parser internals in the answer: " + r.getContentAsString());
        }
        // '|' separates fields in the log. It cannot get in through the body (400) ...
        assertRefused(submit(token, newRequestId(), order("A-RICH|X", CALL, "SELL", 25)), 400, "invalid_request");
        // ... nor through a token: one whose account claim contains it is not a valid login at all.
        assertRefused(submit(trader(newUser(), "A|B"), newRequestId(), order("A|B", CALL, "SELL", 25)), 401, "unauthenticated");

        MockHttpServletResponse wrongType = send(post("/orders").header("X-Request-Id", newRequestId()).contentType(MediaType.TEXT_PLAIN).content(sellOneLot("A-RICH")), token);
        assertEquals(415, wrongType.getStatus());
        assertEquals(404, send(get("/orders/not-an-order-id"), riskOpsReader).getStatus());
        assertEquals(before, ordersInLog("A-RICH").size(), "nothing malformed was written");
    }

    // ---------------------------------------------------------------- the asynchronous path, end to end

    @Test
    void anOrderIsAnswered202AtOnceAndDecidedByTheShardThatOwnsTheAccount() throws Exception {
        String token = trader(newUser(), "A-RICH");
        MockHttpServletResponse r = submit(token, newRequestId(), sellOneLot("A-RICH"));
        assertEquals(202, r.getStatus(), r.getContentAsString());
        assertEquals("PENDING", body(r).path("status").asText(), "202 means written to the log, not accepted by risk");
        String orderId = body(r).path("orderId").asText();
        assertTrue(orderId.matches("O-[0-9a-f]{32}"), orderId);

        JsonNode view = awaitDecision(orderId);
        assertEquals("ACCEPTED", view.path("status").asText(), view.toString());
        assertEquals("A-RICH", view.path("accountId").asText());
        assertEquals(CALL, view.path("instrumentId").asText());
        assertEquals("SELL", view.path("side").asText());
        assertEquals(25, view.path("qty").asLong());
        assertTrue(view.path("reason").isMissingNode() || view.path("reason").isNull(), "no reason on an accepted order: " + view);

        // The same decision, straight from Postgres: the order row and one audit row.
        assertEquals("ACCEPTED", jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE request_id = ? AND action = 'ORDER_ACCEPTED' "
                + "AND account_id = 'A-RICH' AND (details->>'requiredAfter')::numeric > 0", Integer.class, orderId));

        OrderRequest inLog = ordersInLog("A-RICH").stream().filter(o -> o.orderId().equals(orderId)).findFirst().orElseThrow();
        assertEquals(OrderRequest.Origin.USER, inLog.origin(), "the API can only ever write USER orders");
        assertEquals(200, send(get("/orders/" + orderId), token).getStatus(), "the owner can read it too");
    }

    @Test
    void theShardRejectsWhatTheApiCannotJudge() throws Exception {
        // Not enough margin: 20,000 in cash cannot carry a short at-the-money call.
        String poor = trader(newUser(), "A-POOR");
        JsonNode noMargin = awaitDecision(body(submit(poor, newRequestId(), sellOneLot("A-POOR"))).path("orderId").asText());
        assertEquals("REJECTED", noMargin.path("status").asText(), noMargin.toString());
        assertEquals("INSUFFICIENT_MARGIN", noMargin.path("reason").asText());

        // Well-formed but not a whole number of lots: the API answers 202, the shard says no.
        String rich = trader(newUser(), "A-RICH");
        JsonNode lots = awaitDecision(body(submit(rich, newRequestId(), order("A-RICH", CALL, "BUY", 30))).path("orderId").asText());
        assertEquals("REJECTED", lots.path("status").asText(), lots.toString());
        assertEquals("BAD_LOT_SIZE", lots.path("reason").asText(), lots.toString());
    }

    @Test
    void anOrderForAnAccountOrInstrumentThatDoesNotExistIs422AndNeverReachesTheLog() throws Exception {
        String rich = trader(newUser(), "A-RICH");
        String requestId = newRequestId();
        assertRefused(submit(rich, requestId, order("A-RICH", "NIFTY-20240613-99900-CE", "BUY", 25)), 422, "unknown_account_or_instrument");
        // Nothing was created, so the same request id is still free for the corrected order.
        assertEquals(202, submit(rich, requestId, order("A-RICH", CALL, "BUY", 25)).getStatus());

        // A validly signed token for an account that does not exist.
        assertRefused(submit(trader(newUser(), "A-GHOST"), newRequestId(), sellOneLot("A-GHOST")), 422, "unknown_account_or_instrument");
        assertTrue(ordersInLog("A-GHOST").isEmpty());
        assertTrue(ordersInLog("A-RICH").stream().noneMatch(o -> o.instrumentId().contains("99900")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM orders WHERE instrument_id LIKE '%99900%' OR account_id = 'A-GHOST'", Integer.class));
    }

    @Test
    void closingAnAcceptedOrderShowsUpAsCancelled() throws Exception {
        String token = trader(newUser(), "A-RICH");
        String orderId = body(submit(token, newRequestId(), sellOneLot("A-RICH"))).path("orderId").asText();
        assertEquals("ACCEPTED", awaitDecision(orderId).path("status").asText());

        // What M5's broker side will write when an accepted order ends without filling.
        LOGS.append(new OrderClosed(orderId, "A-RICH", OrderClosed.Reason.CANCELLED, System.currentTimeMillis()));
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode view;
        do {
            Thread.sleep(250);
            view = body(send(get("/orders/" + orderId), token));
        } while (!view.path("status").asText().equals("CANCELLED") && System.nanoTime() < deadline);
        assertEquals("CANCELLED", view.path("status").asText(), view.toString());
        assertEquals("CANCELLED", view.path("reason").asText());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE request_id = ? AND action = 'ORDER_CLOSED' "
                + "AND (details->>'released')::int = -25", Integer.class, orderId), "the release of its 25 reserved units is audited");
    }
}
