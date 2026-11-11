package riskengine.shard;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import riskengine.events.Event;
import riskengine.events.EventLog;
import riskengine.events.AccountEvent;
import riskengine.events.FileEventLog;
import riskengine.events.Fill;
import riskengine.events.InMemoryEventLog;
import riskengine.events.LoggedEvent;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.margin.EngineOutput;
import riskengine.margin.OrderClosure;
import riskengine.margin.OrderDecision;
import riskengine.margin.PositionChange;
import riskengine.margin.AccountStatusChange;
import riskengine.margin.EpisodeChange;
import riskengine.margin.SquareOffLeg;
import riskengine.margin.MarginEvent;
import riskengine.margin.MarginResult;
import riskengine.margin.Position;
import riskengine.margin.ReferenceMarginEngine;
import riskengine.ref.Instrument;

class ShardStateTest {
    static final List<Instrument> INS = Fixtures.instruments();

    static ShardState shard(int id, List<AccountBook> books, ShardConfig cfg) {
        return new ShardState(id, Fixtures.CFG, cfg, INS, books);
    }

    static void assertClose(double expected, double actual, String what) {
        if (Double.isInfinite(expected) || Double.isInfinite(actual)) {
            assertEquals(expected, actual, what);
            return;
        }
        assertEquals(expected, actual, 1e-6 + 1e-9 * Math.abs(expected), what);
    }

    static void assertSameResult(MarginResult full, MarginResult incremental, String who) {
        if (full == null || incremental == null) {
            assertEquals(full, incremental, who);
            return;
        }
        assertClose(full.worstScenarioLoss(), incremental.worstScenarioLoss(), who + " worst loss");
        // The worst scenario NUMBER is not compared: scenarios that differ only in volatility lose exactly
        // the same on a futures-only book, so which one is "worst" is decided by rounding dust. The loss is compared.
        assertClose(full.shortOptionMinimum(), incremental.shortOptionMinimum(), who + " somc");
        assertClose(full.netOptionValue(), incremental.netOptionValue(), who + " nov");
        assertClose(full.exposureMargin(), incremental.exposureMargin(), who + " exposure");
        assertClose(full.required(), incremental.required(), who + " required");
        assertClose(full.futuresMtm(), incremental.futuresMtm(), who + " futures mtm");
        assertClose(full.available(), incremental.available(), who + " available");
        assertClose(full.utilisation(), incremental.utilisation(), who + " utilisation");
    }

    /** Level changes must match exactly; order decisions must agree on accept/reject and reason. */
    static void assertSameOutputs(List<EngineOutput> full, List<EngineOutput> incremental) {
        assertEquals(full.size(), incremental.size(), "number of outputs");
        for (int i = 0; i < full.size(); i++) {
            if (full.get(i) instanceof OrderDecision f) {
                OrderDecision s = (OrderDecision) incremental.get(i);
                String who = "order " + f.orderId();
                assertEquals(f.orderId(), s.orderId());
                assertEquals(f.accountId(), s.accountId(), who);
                assertEquals(f.reason(), s.reason(), who);
                assertEquals(f.accepted(), s.accepted(), who);
                assertEquals(f.lastTickSeq(), s.lastTickSeq(), who);
                assertClose(f.requiredAfter(), s.requiredAfter(), who + " required after");
                assertClose(f.availableAfter(), s.availableAfter(), who + " available after");
                assertClose(f.utilisationAfter(), s.utilisationAfter(), who + " utilisation after");
                continue;
            }
            if (full.get(i) instanceof PositionChange f) {
                PositionChange s = (PositionChange) incremental.get(i);
                String who = "fill " + f.fillId();
                assertEquals(f.fillId(), s.fillId());
                assertEquals(f.accountId(), s.accountId(), who);
                assertEquals(f.instrumentId(), s.instrumentId(), who);
                assertEquals(f.qty(), s.qty(), who);
                assertEquals(0, f.avgPrice().compareTo(s.avgPrice()), who + " average price");
                assertEquals(0, f.cash().compareTo(s.cash()), who + " cash");
                assertEquals(0, f.cashDelta().compareTo(s.cashDelta()), who + " cash delta");
                assertEquals(f.orderRemaining(), s.orderRemaining(), who + " order remaining");
                continue;
            }
            if (full.get(i) instanceof SquareOffLeg f) {
                SquareOffLeg s = (SquareOffLeg) incremental.get(i);
                assertEquals(f.legKey(), s.legKey());
                assertEquals(f.qty(), s.qty(), f.legKey());
                assertEquals(0, f.mark().compareTo(s.mark()), f.legKey() + " mark");
                assertClose(f.expectedRelease(), s.expectedRelease(), f.legKey() + " expected release");
                continue;
            }
            if (!(full.get(i) instanceof MarginEvent)) {
                // Closures, episode changes and status changes carry no computed money: they must be equal.
                assertEquals(full.get(i), incremental.get(i));
                continue;
            }
            MarginEvent f = (MarginEvent) full.get(i), s = (MarginEvent) incremental.get(i);
            assertEquals(f.accountId(), s.accountId());
            assertEquals(f.tickSeq(), s.tickSeq());
            assertEquals(f.from(), s.from(), f.accountId() + " tick " + f.tickSeq());
            assertEquals(f.to(), s.to(), f.accountId() + " tick " + f.tickSeq());
            assertSameResult(f.result(), s.result(), f.accountId() + " tick " + f.tickSeq());
        }
    }

    // ---------------------------------------------------------------- M3 gate, part 1

    @Test
    void incrementalEqualsFullOn10kRandomPortfolios() {
        List<AccountBook> books = Fixtures.books(10_000, 2024_06_04L);
        List<Event> events = Fixtures.events(books, 60, 50, 99);
        ShardState shard = shard(0, books, new ShardConfig(7, 0.01)); // every account is rebuilt and checked once per 7 ticks
        ReferenceMarginEngine reference = new ReferenceMarginEngine(Fixtures.CFG, INS, books);

        int transitions = 0, fills = 0, accepted = 0, rejected = 0, released = 0;
        long offset = 0;
        for (Event e : events) {
            List<EngineOutput> full = reference.apply(e);
            List<EngineOutput> incremental = shard.apply(new LoggedEvent(offset++, e));
            assertSameOutputs(full, incremental);
            for (EngineOutput o : full) {
                if (o instanceof OrderDecision d) {
                    if (d.accepted()) accepted++;
                    else rejected++;
                } else if (o instanceof MarginEvent) {
                    transitions++;
                } else if (o instanceof OrderClosure c && c.released() != 0) {
                    released++;
                }
            }
            if (e instanceof Fill) fills++;
            if (e instanceof Tick t && t.tickSeq() % 10 == 0) compareEveryAccount(books, reference, shard);
        }
        compareEveryAccount(books, reference, shard);

        assertTrue(transitions > 1_000, "the path must actually move accounts between levels, got " + transitions);
        assertTrue(fills > 2_000 && shard.duplicateFills() > 50, "fills " + fills + ", duplicates " + shard.duplicateFills());
        // 60 ticks with a 7-tick cycle: 8 whole cycles rebuild every account 8 times; the 4 leftover ticks
        // (tick_seq 57..60, remainders 1..4) rebuild indexes 1, 2, 3, 4 mod 7.
        long leftover = java.util.stream.IntStream.range(0, 10_000).filter(i -> i % 7 >= 1 && i % 7 <= 4).count();
        assertEquals(8L * 10_000 + leftover, shard.accountRebuilds());
        assertEquals((10_000 + 6) / 7, shard.maxRebuildsInOneTick(), "no spike: never more than ceil(n / K) accounts on one tick");
        assertTrue(shard.maxDriftSeen() < 1e-4, "drift " + shard.maxDriftSeen());
        assertTrue(accepted > 10 && rejected > 10, "pre-trade checks must go both ways: " + accepted + " accepted, " + rejected + " rejected");
        assertTrue(released > 5, "some accepted orders are closed and release their reservation: " + released);
        System.out.printf("M3 gate: 10,000 portfolios, %d events (%d fills), %d level changes, %d orders accepted, %d rejected, max drift %.3e rupees%n",
                events.size(), fills, transitions, accepted, rejected, shard.maxDriftSeen());
    }

    static void compareEveryAccount(List<AccountBook> books, ReferenceMarginEngine reference, ShardState shard) {
        for (AccountBook b : books) {
            String id = b.id();
            assertEquals(reference.positions(id), shard.positions(id), id + " positions");
            assertEquals(0, reference.cash(id).compareTo(shard.cash(id)), id + " cash");
            assertEquals(reference.riskFingerprint(id), shard.riskFingerprint(id), id + " level, status, episode, leg in flight");
            assertEquals(reference.pendingOrders(id), shard.pendingOrders(id), id + " reserved orders");
            assertSameResult(reference.latest(id), shard.latest(id), id);
        }
    }

    // ---------------------------------------------------------------- M3 gate, part 2

    /**
     * Shards run in parallel, so their outputs have no order between them. Sort for comparison: level
     * changes by (tick, account), then decisions by order id, position changes by fill id, closures by order id.
     */
    static final Comparator<EngineOutput> CANONICAL = Comparator
            .comparing((EngineOutput o) -> switch (o) {
                case MarginEvent m -> 0;
                case OrderDecision d -> 1;
                case PositionChange p -> 2;
                case OrderClosure c -> 3;
                case SquareOffLeg l -> 4;
                case EpisodeChange e -> 5;
                case AccountStatusChange s -> 6;
            })
            .thenComparing(o -> o instanceof MarginEvent m ? m.tickSeq() : 0L)
            .thenComparing(EngineOutput::accountId)
            .thenComparing(o -> switch (o) {
                case MarginEvent m -> "";
                case OrderDecision d -> d.orderId();
                case PositionChange p -> p.fillId();
                case OrderClosure c -> c.orderId();
                case SquareOffLeg l -> l.legKey();
                case EpisodeChange e -> e.episodeId() + e.state();
                case AccountStatusChange s -> s.tickSeq() + s.cause() + s.to();
            });

    static String replay(List<AccountBook> books, List<Event> events, ShardConfig cfg) {
        ShardState s = shard(3, books, cfg);
        try (EventLog log = new InMemoryEventLog()) {
            events.forEach(log::append);
            new ShardRunner(s, log, e -> {}).runToEnd();
        }
        return s.stateHash();
    }

    @Test
    void sameLogTwiceGivesTheSameHashAndAnyDifferenceChangesIt() {
        List<AccountBook> books = Fixtures.books(500, 7);
        List<Event> events = Fixtures.events(books, 40, 10, 8);
        String h1 = replay(books, events, ShardConfig.defaults());
        String h2 = replay(books, events, ShardConfig.defaults());
        assertEquals(h1, h2);
        assertEquals(64, h1.length());

        // One tick differs by one paisa: different hash.
        List<Event> nudged = new ArrayList<>(events);
        for (int i = nudged.size() - 1; i >= 0; i--) {
            if (nudged.get(i) instanceof Tick t) {
                nudged.set(i, new Tick(t.tickSeq(), t.logTimeMillis(), t.underlying(), t.spot().add(new BigDecimal("0.01")), t.vix()));
                break;
            }
        }
        assertNotEquals(h1, replay(books, nudged, ShardConfig.defaults()));
        // One event fewer: different hash.
        assertNotEquals(h1, replay(books, events.subList(0, events.size() - 1), ShardConfig.defaults()));
    }

    @Test
    void fourShardsOnFourThreadsAreRepeatableAndMatchTheSingleThreadedReference(@TempDir Path dir) throws Exception {
        int n = 4;
        List<AccountBook> books = Fixtures.books(2_000, 11);
        List<Event> events = Fixtures.events(books, 50, 20, 12);

        List<String> firstHashes = null;
        List<EngineOutput> firstEvents = null;
        for (int run = 0; run < 2; run++) {
            // The "replayer": every tick to every partition, account events to the owner's partition.
            List<FileEventLog> logs = new ArrayList<>();
            for (int p = 0; p < n; p++) logs.add(new FileEventLog(dir.resolve("run" + run + "-partition-" + p + ".log")));
            for (Event e : events) {
                if (e instanceof AccountEvent a) logs.get(Partitioner.partition(a.accountId(), n)).append(e);
                else logs.forEach(l -> l.append(e));
            }
            List<ShardRunner> runners = new ArrayList<>();
            List<List<EngineOutput>> sinks = new ArrayList<>();
            for (int p = 0; p < n; p++) {
                int shardId = p;
                List<AccountBook> mine = books.stream().filter(b -> Partitioner.partition(b.id(), n) == shardId).toList();
                assertFalse(mine.isEmpty());
                List<EngineOutput> sink = new ArrayList<>(); // written only by that shard's thread
                sinks.add(sink);
                runners.add(new ShardRunner(shard(p, mine, ShardConfig.defaults()), logs.get(p), sink::add));
            }
            List<Thread> threads = runners.stream().map(ShardRunner::start).toList();
            for (Thread t : threads) t.join();
            logs.forEach(FileEventLog::close);

            List<String> hashes = runners.stream().map(r -> r.state().stateHash()).toList();
            List<EngineOutput> merged = new ArrayList<>();
            sinks.forEach(merged::addAll);
            merged.sort(CANONICAL);
            if (run == 0) {
                firstHashes = hashes;
                firstEvents = merged;
            } else {
                assertEquals(firstHashes, hashes, "per-shard state hashes must be identical across runs");
                assertEquals(firstEvents, merged, "and so must every emitted event, bit for bit");
            }
        }
        assertEquals(n, firstHashes.stream().distinct().count(), "different shards hold different accounts");

        ReferenceMarginEngine reference = new ReferenceMarginEngine(Fixtures.CFG, INS, books);
        List<EngineOutput> expected = new ArrayList<>();
        for (Event e : events) expected.addAll(reference.apply(e));
        expected.sort(CANONICAL);
        assertSameOutputs(expected, firstEvents);
        assertTrue(expected.stream().anyMatch(o -> o instanceof OrderDecision), "the stream includes orders");
    }

    @Test
    void rollingRebuildVisitsEveryAccountOncePerCycleAndNeverSpikes() {
        List<AccountBook> books = Fixtures.books(1_000, 21);
        List<Event> events = Fixtures.events(books, 250, 0, 22);
        ShardState s = shard(0, books, ShardConfig.defaults());
        long o = 0, before = 0;
        for (Event e : events) {
            s.apply(new LoggedEvent(o++, e));
            long now = s.accountRebuilds();
            // index % 100 == tick_seq % 100 picks exactly 10 of 1,000 accounts on every tick
            assertEquals(10, now - before, "tick " + ((Tick) e).tickSeq());
            before = now;
        }
        assertEquals(10, s.maxRebuildsInOneTick());
        assertEquals(250 * 10, s.accountRebuilds());
    }

    @Test
    void theRebuildScheduleIsPartOfTheBehaviourSoItChangesTheHash() {
        List<AccountBook> books = Fixtures.books(500, 7);
        List<Event> events = Fixtures.events(books, 40, 10, 8);
        // Same log, different cycle length: accounts re-base on different ticks, so some vectors differ in
        // their last bits and the hash (which is bit-exact) differs. Same cycle: same hash.
        assertEquals(replay(books, events, new ShardConfig(7, 0.01)), replay(books, events, new ShardConfig(7, 0.01)));
        assertNotEquals(replay(books, events, new ShardConfig(7, 0.01)), replay(books, events, new ShardConfig(13, 0.01)));
    }

    @Test
    void replayFromTheStartMatchesAShardThatNeverStopped() {
        // The schedule depends only on tick_seq and the account list, not on a counter of ticks seen. So a
        // shard fed the log in two sittings is bit-identical to one fed in a single run.
        List<AccountBook> books = Fixtures.books(300, 31);
        List<Event> events = Fixtures.events(books, 230, 5, 32);
        ShardState oneGo = shard(3, books, ShardConfig.defaults());
        ShardState twoSittings = shard(3, books, ShardConfig.defaults());
        try (EventLog log = new InMemoryEventLog()) {
            events.forEach(log::append);
            new ShardRunner(oneGo, log, e -> {}).runToEnd();
        }
        try (EventLog log = new InMemoryEventLog()) {
            ShardRunner r = new ShardRunner(twoSittings, log, e -> {});
            events.subList(0, 777).forEach(log::append);
            r.runToEnd();
            events.subList(777, events.size()).forEach(log::append);
            r.runToEnd();
        }
        assertEquals(oneGo.stateHash(), twoSittings.stateHash());
    }

    // ---------------------------------------------------------------- indexes, drift check, guards

    static Fill fill(String id, String account, Instrument i, long qty, String price) {
        return new Fill(id, "O-" + id, account, i.id(), qty, new BigDecimal(price), Fixtures.T0);
    }

    static Tick tick(long seq, String spot) {
        return new Tick(seq, Fixtures.T0 + seq * 60_000, "NIFTY", new BigDecimal(spot), new BigDecimal("20"));
    }

    @Test
    void holdersAndByUnderlyingFollowFills() {
        Instrument fut = INS.get(0), call = INS.get(10);
        AccountBook a = new AccountBook("A", new BigDecimal("1000000"), BigDecimal.ZERO, new TreeMap<>(Map.of(fut.id(), Position.of(25, "23300"))));
        AccountBook b = new AccountBook("B", new BigDecimal("1000000"), BigDecimal.ZERO, new TreeMap<>());
        ShardState s = shard(0, List.of(a, b), ShardConfig.defaults());
        long o = 0;
        assertEquals(List.of("A"), List.copyOf(s.holders(fut.id())));
        assertEquals(List.of(fut.id()), List.copyOf(s.heldInstruments("NIFTY")));

        s.apply(new LoggedEvent(o++, tick(1, "23300")));
        double mtmAtTick1 = s.latest("A").futuresMtm();
        assertNull(s.latest("B"), "B holds nothing, so a tick does not touch it");
        s.apply(new LoggedEvent(o++, fill("F1", "B", call, -50, "120")));
        assertEquals(List.of("B"), List.copyOf(s.holders(call.id())));
        assertEquals(2, s.heldInstruments("NIFTY").size());
        assertTrue(s.latest("B").required() > 0, "margined at once from the last tick's market, without waiting for the next tick");
        assertEquals(0, new BigDecimal("1006000").compareTo(s.cash("B")), "premium received");

        s.apply(new LoggedEvent(o++, fill("F1", "B", call, -50, "120"))); // duplicate callback
        assertEquals(-50, s.positions("B").get(call.id()).qty());
        assertEquals(1, s.duplicateFills());

        s.apply(new LoggedEvent(o++, fill("F2", "B", call, 50, "100")));
        assertTrue(s.holders(call.id()).isEmpty());
        assertEquals(List.of(fut.id()), List.copyOf(s.heldInstruments("NIFTY")), "nobody holds the call any more, so it is no longer priced");
        assertEquals(0, s.latest("B").required(), 0.0);
        assertEquals(0, new BigDecimal("1001000").compareTo(s.cash("B")));

        // A is long 25 futures; spot falls 300 points. The future falls 300 x e^((r-q)T), a shade more than
        // spot, because it is priced by cost of carry.
        s.apply(new LoggedEvent(o++, tick(2, "23000")));
        assertEquals(-25 * 300, s.latest("A").futuresMtm() - mtmAtTick1, 25 * 300 * 0.01, "about Rs 7,500 lost between the two ticks");
    }

    @Test
    void driftCheckIsLive() {
        List<AccountBook> books = Fixtures.books(200, 3);
        List<Event> events = Fixtures.events(books, 5, 0, 4);
        // Demand bit-exact equality between incremental and rebuilt vectors on every tick: floating point
        // can't deliver that, so the check must fire. Proves the comparison really runs.
        ShardState strict = shard(0, books, new ShardConfig(1, Double.MIN_VALUE));
        var e = assertThrows(IllegalStateException.class, () -> {
            long o = 0;
            for (Event ev : events) strict.apply(new LoggedEvent(o++, ev));
        });
        assertTrue(e.getMessage().contains("drifted"), e.getMessage());
    }

    @Test
    void outOfOrderInputIsRejected() {
        List<AccountBook> books = Fixtures.books(10, 5);
        ShardState s = shard(0, books, ShardConfig.defaults());
        s.apply(new LoggedEvent(0, tick(5, "23300")));
        assertThrows(IllegalStateException.class, () -> s.apply(new LoggedEvent(2, tick(6, "23300"))), "offset gap");
        assertThrows(IllegalStateException.class, () -> s.apply(new LoggedEvent(1, tick(5, "23300"))), "tick_seq must increase");
        ShardState t = shard(0, books, ShardConfig.defaults());
        assertThrows(IllegalStateException.class, () -> t.apply(new LoggedEvent(0, fill("F", "NOT-MINE", INS.get(0), 25, "23300"))));
    }
}
