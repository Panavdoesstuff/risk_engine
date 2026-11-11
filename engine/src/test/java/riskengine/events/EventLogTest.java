package riskengine.events;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventLogTest {
    static Tick tick(long seq, String spot) {
        return new Tick(seq, 1_717_652_700_000L + seq * 60_000, "NIFTY", new BigDecimal(spot), new BigDecimal("24.6"));
    }

    @Test
    void codecIsDeterministicAndRoundTrips() {
        Tick t = tick(7, "22530.70");
        String line = EventCodec.encode(t);
        assertEquals("TICK|v1|7|1717653120000|NIFTY|22530.70|24.6", line);
        assertEquals(t, EventCodec.decode(line));
        assertThrows(IllegalArgumentException.class, () -> EventCodec.decode("TICK|v2|1|2|NIFTY|1|1"));
        assertThrows(IllegalArgumentException.class, () -> EventCodec.decode("TICK|v1|x|2|NIFTY|1|1"));
    }

    @Test
    void everyEventTypeRoundTripsThroughOneFixedLine() {
        Event order = new OrderRequest("O-1", "A0000001", "NIFTY-20240613-23000-CE", OrderRequest.Side.SELL, 50, OrderRequest.Origin.USER, 111);
        Event fill = new Fill("BF-9", "O-1", "A0000001", "NIFTY-20240613-23000-CE", -50, new BigDecimal("266.70"), 222);
        Event sq = new SquareOffResult("A0000001:E1:NIFTY-20240613-23000-CE:0", "A0000001", "NIFTY-20240613-23000-CE", SquareOffResult.Status.REJECTED, 333);
        assertEquals("ORDER|v1|O-1|A0000001|NIFTY-20240613-23000-CE|SELL|50|USER|111", EventCodec.encode(order));
        assertEquals("FILL|v1|BF-9|O-1|A0000001|NIFTY-20240613-23000-CE|-50|266.70|222", EventCodec.encode(fill));
        assertEquals("SQOFF|v1|A0000001:E1:NIFTY-20240613-23000-CE:0|A0000001|NIFTY-20240613-23000-CE|REJECTED|333", EventCodec.encode(sq));
        Event closed = new OrderClosed("O-1", "A0000001", OrderClosed.Reason.CANCELLED, 444);
        assertEquals("CLOSED|v1|O-1|A0000001|CANCELLED|444", EventCodec.encode(closed));
        for (Event e : List.of(order, fill, closed, sq, tick(1, "23000"))) assertEquals(e, EventCodec.decode(EventCodec.encode(e)));
    }

    @Test
    void identifiersThatWouldBreakTheLineFormatAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Fill("a|b", "O", "A", "I", 1, BigDecimal.ONE, 0));
        assertThrows(IllegalArgumentException.class, () -> new Fill("F", "O", "A\nX", "I", 1, BigDecimal.ONE, 0));
        assertThrows(IllegalArgumentException.class, () -> new Fill("F", "O", "A", "I", 0, BigDecimal.ONE, 0));
        assertThrows(IllegalArgumentException.class, () -> new OrderRequest("O", "A", "I", OrderRequest.Side.BUY, -5, OrderRequest.Origin.USER, 0));
        assertThrows(IllegalArgumentException.class, () -> EventCodec.decode("FILL|v1|F|O|A|I|1|1"));
    }

    @Test
    void inMemoryLogReadsInOrderWithOffsets() {
        try (EventLog log = new InMemoryEventLog()) {
            for (int i = 0; i < 5; i++) assertEquals(i, log.append(tick(i, "23000")));
            List<LoggedEvent> batch = log.read(1, 3);
            assertEquals(List.of(1L, 2L, 3L), batch.stream().map(LoggedEvent::offset).toList());
            assertTrue(log.read(5, 10).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> log.read(6, 1));
        }
    }

    @Test
    void fileLogSurvivesReopenAndMatchesInMemory(@TempDir Path dir) {
        Path f = dir.resolve("p0.log");
        InMemoryEventLog mem = new InMemoryEventLog();
        try (FileEventLog log = new FileEventLog(f)) {
            for (int i = 0; i < 3; i++) {
                log.append(tick(i, "2300" + i + ".5"));
                mem.append(tick(i, "2300" + i + ".5"));
            }
        }
        try (FileEventLog log = new FileEventLog(f)) {
            assertEquals(3, log.endOffset(), "reopen continues after the last event");
            assertEquals(3, log.append(tick(3, "23003.5")));
            mem.append(tick(3, "23003.5"));
            assertEquals(mem.read(0, 100), log.read(0, 100));
            assertEquals(mem.read(2, 1), log.read(2, 1));
        }
    }

    @Test
    void fileLogRefusesTornOrCorruptFiles(@TempDir Path dir) throws IOException {
        Path torn = Files.writeString(dir.resolve("torn.log"), "TICK|v1|0|1|NIFTY|23000|20\nTICK|v1|1|2|NIF");
        assertThrows(IllegalStateException.class, () -> new FileEventLog(torn));
        Path bad = Files.writeString(dir.resolve("bad.log"), "TICK|v1|0|1|NIFTY|23000|20\nGARBAGE\n");
        assertThrows(IllegalArgumentException.class, () -> new FileEventLog(bad));
    }
}
