package riskengine.margin;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import riskengine.events.EventLog;
import riskengine.events.FileEventLog;
import riskengine.events.InMemoryEventLog;
import riskengine.events.Tick;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentType;

class ReferenceMarginEngineTest {
    static final Instrument CE = MarginExampleTest.option(InstrumentType.CE);
    static final Instrument PE = MarginExampleTest.option(InstrumentType.PE);
    static final Instrument FAR_PUT = Instrument.of("NIFTY", InstrumentType.PE, LocalDate.of(2024, 6, 13), new BigDecimal("21000"),
            25, BigDecimal.ONE, BigDecimal.ONE, 1);
    static final List<Instrument> INSTRUMENTS = List.of(CE, PE, FAR_PUT);

    /** Positions opened at a price of 1: fine for options (premium is already in cash), not used for futures here. */
    static AccountBook book(String id, String cash, Map<String, Long> pos) {
        TreeMap<String, Position> positions = new TreeMap<>();
        pos.forEach((k, v) -> positions.put(k, new Position(v, BigDecimal.ONE)));
        return new AccountBook(id, new BigDecimal(cash), BigDecimal.ZERO, positions);
    }

    static final List<AccountBook> BOOKS = List.of(
            book("STRADDLE", "240000", Map.of(CE.id(), -50L, PE.id(), -50L)),   // the hand-worked example
            book("THIN", "160000", Map.of(CE.id(), -50L, PE.id(), -50L)),       // same position, thinly funded
            book("BUYER", "0", Map.of(FAR_PUT.id(), 250L)));                     // spent everything on puts

    /** A gap down: 23000 -> 21850 (-5%) with VIX 20 -> 30, one tick a minute. */
    static List<Tick> crash() {
        String[][] path = {{"23000", "20"}, {"22700", "22"}, {"22400", "25"}, {"22100", "27"}, {"21950", "28"},
                {"21900", "29"}, {"21850", "30"}, {"21850", "30"}, {"21850", "30"}, {"21850", "30"}};
        List<Tick> out = new ArrayList<>();
        for (int i = 0; i < path.length; i++) {
            out.add(new Tick(i + 1, MarginExampleTest.VALUATION + i * 60_000L, "NIFTY", new BigDecimal(path[i][0]), new BigDecimal(path[i][1])));
        }
        return out;
    }

    static List<MarginEvent> run(EventLog log) {
        crash().forEach(log::append);
        var engine = new ReferenceMarginEngine(MarginExampleTest.CONFIG, INSTRUMENTS, BOOKS);
        List<MarginEvent> events = new ArrayList<>();
        assertEquals(log.endOffset(), engine.run(log, 0, o -> {
            if (o instanceof MarginEvent m) events.add(m); // level changes only; square-off has its own tests
        }));
        return events;
    }

    @Test
    void firstTickReproducesTheHandWorkedExample() {
        var engine = new ReferenceMarginEngine(MarginExampleTest.CONFIG, INSTRUMENTS, BOOKS);
        engine.onTick(crash().get(0));
        assertEquals(150_839.83, engine.latest("STRADDLE").required(), 0.5);
        assertEquals(MarginLevel.OK, engine.level("STRADDLE"));
    }

    static List<MarginEvent> eventsFor(List<MarginEvent> all, String account) {
        return all.stream().filter(e -> e.accountId().equals(account)).toList();
    }

    @Test
    void crashEscalatesTheThinStraddleOnlyWarnsTheFundedOneAndLeavesTheBuyerAlone() {
        List<MarginEvent> events = run(new InMemoryEventLog());

        // Per-tick utilisation from the independent Python model (scripts/straddle_example.py maths):
        //   tick:    1      2       3       4       5       6       7..10
        //   THIN:  94.3%  100.8%  109.0%  117.1%  121.2%  122.6%  123.9%
        //   FUNDED:62.8%   67.2%   72.6%   78.1%   80.8%   81.7%   82.6%
        // THIN warns at once (tick 1); >=100% from tick 2, so the 3rd consecutive tick (4) is the call;
        // >=120% from tick 5, so square-off on tick 7. FUNDED crosses 80% at tick 5.
        List<MarginEvent> thin = eventsFor(events, "THIN");
        assertEquals(List.of(MarginLevel.WARNING, MarginLevel.MARGIN_CALL, MarginLevel.SQUARE_OFF),
                thin.stream().map(MarginEvent::to).toList(), events.toString());
        assertEquals(List.of(1L, 4L, 7L), thin.stream().map(MarginEvent::tickSeq).toList());

        // Funded: a 5% fall with VIX 30 takes it to about 83%: a warning, nothing more.
        List<MarginEvent> funded = eventsFor(events, "STRADDLE");
        assertEquals(List.of(MarginLevel.WARNING), funded.stream().map(MarginEvent::to).toList());
        assertEquals(5L, funded.get(0).tickSeq());
        assertEquals(0.808, funded.get(0).result().utilisation(), 0.0005);

        assertTrue(eventsFor(events, "BUYER").isEmpty(), "long puts gain in a crash; buyer never flagged");
        for (MarginEvent e : events) {
            assertTrue(e.to().compareTo(e.from()) > 0);
            if (e.to() == MarginLevel.SQUARE_OFF) assertTrue(e.result().utilisation() >= 1.20);
        }
    }

    @Test
    void fileLogAndInMemoryLogGiveIdenticalResults(@TempDir Path dir) {
        List<MarginEvent> mem = run(new InMemoryEventLog());
        List<MarginEvent> file;
        try (FileEventLog log = new FileEventLog(dir.resolve("p0.log"))) {
            file = run(log);
        }
        assertEquals(mem, file, "same log, same results, bit for bit (records compare doubles exactly)");
    }

    @Test
    void outOfOrderTicksAndUnconfiguredUnderlyingsAreRejected() {
        var engine = new ReferenceMarginEngine(MarginExampleTest.CONFIG, INSTRUMENTS, BOOKS);
        engine.onTick(crash().get(1));
        assertThrows(IllegalStateException.class, () -> engine.onTick(crash().get(0)));

        Instrument bank = Instrument.of("BANKNIFTY", InstrumentType.FUT, LocalDate.of(2024, 6, 26), null, 15, BigDecimal.ONE, BigDecimal.ONE, 1);
        var e = assertThrows(IllegalArgumentException.class, () -> new ReferenceMarginEngine(MarginExampleTest.CONFIG,
                List.of(bank), List.of(book("B", "1", Map.of(bank.id(), 15L)))));
        assertTrue(e.getMessage().contains("not configured"));
    }
}
