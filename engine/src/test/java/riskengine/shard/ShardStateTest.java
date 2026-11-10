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

    static void assertSameTransitions(List<MarginEvent> full, List<MarginEvent> incremental) {
        assertEquals(full.size(), incremental.size(), "number of level changes");
        for (int i = 0; i < full.size(); i++) {
            MarginEvent f = full.get(i), s = incremental.get(i);
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
        ShardState shard = shard(0, books, new ShardConfig(7, 0.01)); // drift-check and re-base every 7 ticks
        ReferenceMarginEngine reference = new ReferenceMarginEngine(Fixtures.CFG, INS, books);

        int transitions = 0, fills = 0;
        long offset = 0;
        for (Event e : events) {
            List<MarginEvent> full = reference.apply(e);
            List<MarginEvent> incremental = shard.apply(new LoggedEvent(offset++, e));
            assertSameTransitions(full, incremental);
            transitions += full.size();
            if (e instanceof Fill) fills++;
            if (e instanceof Tick t && t.tickSeq() % 10 == 0) compareEveryAccount(books, reference, shard);
        }
        compareEveryAccount(books, reference, shard);

        assertTrue(transitions > 1_000, "the path must actually move accounts between levels, got " + transitions);
        assertTrue(fills > 2_000 && shard.duplicateFills() > 50, "fills " + fills + ", duplicates " + shard.duplicateFills());
        assertEquals(60 / 7, shard.fullRecomputes());
        assertTrue(shard.maxDriftSeen() < 1e-4, "drift " + shard.maxDriftSeen());
        System.out.printf("M3 gate: 10,000 portfolios, %d events (%d fills), %d level changes, max drift %.3e rupees%n",
                events.size(), fills, transitions, shard.maxDriftSeen());
    }

    static void compareEveryAccount(List<AccountBook> books, ReferenceMarginEngine reference, ShardState shard) {
        for (AccountBook b : books) {
            String id = b.id();
            assertEquals(reference.positions(id), shard.positions(id), id + " positions");
            assertEquals(0, reference.cash(id).compareTo(shard.cash(id)), id + " cash");
            assertEquals(reference.level(id), shard.level(id), id + " level");
            assertSameResult(reference.latest(id), shard.latest(id), id);
        }
    }

    // ---------------------------------------------------------------- M3 gate, part 2

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
        List<MarginEvent> firstEvents = null;
        for (int run = 0; run < 2; run++) {
            // The "replayer": every tick to every partition, account events to the owner's partition.
            List<FileEventLog> logs = new ArrayList<>();
            for (int p = 0; p < n; p++) logs.add(new FileEventLog(dir.resolve("run" + run + "-partition-" + p + ".log")));
            for (Event e : events) {
                if (e instanceof AccountEvent a) logs.get(Partitioner.partition(a.accountId(), n)).append(e);
                else logs.forEach(l -> l.append(e));
            }
            List<ShardRunner> runners = new ArrayList<>();
            List<List<MarginEvent>> sinks = new ArrayList<>();
            for (int p = 0; p < n; p++) {
                int shardId = p;
                List<AccountBook> mine = books.stream().filter(b -> Partitioner.partition(b.id(), n) == shardId).toList();
                assertFalse(mine.isEmpty());
                List<MarginEvent> sink = new ArrayList<>(); // written only by that shard's thread
                sinks.add(sink);
                runners.add(new ShardRunner(shard(p, mine, ShardConfig.defaults()), logs.get(p), sink::add));
            }
            List<Thread> threads = runners.stream().map(ShardRunner::start).toList();
            for (Thread t : threads) t.join();
            logs.forEach(FileEventLog::close);

            List<String> hashes = runners.stream().map(r -> r.state().stateHash()).toList();
            List<MarginEvent> merged = new ArrayList<>();
            sinks.forEach(merged::addAll);
            merged.sort(Comparator.comparingLong(MarginEvent::tickSeq).thenComparing(MarginEvent::accountId));
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
        List<MarginEvent> expected = new ArrayList<>();
        for (Event e : events) expected.addAll(reference.apply(e));
        expected.sort(Comparator.comparingLong(MarginEvent::tickSeq).thenComparing(MarginEvent::accountId));
        assertSameTransitions(expected, firstEvents);
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
