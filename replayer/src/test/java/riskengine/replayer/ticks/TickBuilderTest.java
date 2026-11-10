package riskengine.replayer.ticks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import riskengine.events.FileEventLog;
import riskengine.events.InMemoryEventLog;
import riskengine.events.LoggedEvent;
import riskengine.events.Tick;
import riskengine.replayer.marketdata.MarketDataLoader;

class TickBuilderTest {
    static final LocalDate JUN4 = LocalDate.of(2024, 6, 4);

    static NormalisedBars.Series normalise(Path dir, String name, String csv) throws IOException {
        Path raw = Files.writeString(dir.resolve(name + ".raw.csv"), csv);
        Path out = dir.resolve(name + ".csv");
        MarketDataLoader.write(raw, MarketDataLoader.load(raw, null, null), out);
        return NormalisedBars.read(out); // through the real file format, including the .meta sidecar
    }

    static final String INDEX = """
            datetime,open,high,low,close
            2024-06-03 15:29:00,23250,23270,23240,23263.90
            2024-06-04 09:15:00,23180,23190,22900,22950.50
            2024-06-04 09:16:00,22950,22960,22800,22810.25
            2024-06-04 15:29:00,21900,21910,21880,21884.50
            """;

    static long millis(String iso) {
        return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
    }

    @Test
    void dailyVixIsNeverReadBeforeItExisted(@TempDir Path dir) throws IOException {
        var index = normalise(dir, "nifty", INDEX);
        var vix = normalise(dir, "vix", """
                date,open,high,low,close
                2024-06-03,24.6,24.6,19.4,20.94
                2024-06-04,21.5,31.7,19.1,26.75
                """);
        List<Tick> ticks = TickBuilder.build("NIFTY", index, vix, JUN4, JUN4, 1);
        assertEquals(3, ticks.size(), "3 June bar filtered out");
        Tick first = ticks.get(0);
        assertEquals(1, first.tickSeq());
        assertEquals(millis("2024-06-04T09:16:00+05:30"), first.logTimeMillis(), "the 09:15 bar's close is known at 09:16");
        assertEquals(0, new BigDecimal("22950.50").compareTo(first.spot()));
        assertEquals(0, new BigDecimal("20.94").compareTo(first.vix()), "during 4 June only 3 June's close is known");
        assertEquals(0, new BigDecimal("20.94").compareTo(ticks.get(1).vix()));
        Tick close = ticks.get(2);
        assertEquals(millis("2024-06-04T15:30:00+05:30"), close.logTimeMillis());
        assertEquals(0, new BigDecimal("26.75").compareTo(close.vix()), "at 15:30 the day's close exists");
    }

    @Test
    void minuteVixIsJoinedAsOfTheTick(@TempDir Path dir) throws IOException {
        var index = normalise(dir, "nifty", INDEX);
        var vix = normalise(dir, "vix", """
                datetime,open,high,low,close
                2024-06-04 09:15:00,21,23,21,22.10
                2024-06-04 09:16:00,22,25,22,24.80
                2024-06-04 09:17:00,25,27,25,26.50
                """);
        List<Tick> ticks = TickBuilder.build("NIFTY", index, vix, JUN4, JUN4, 100);
        assertEquals(List.of(100L, 101L, 102L), ticks.stream().map(Tick::tickSeq).toList());
        assertEquals(0, new BigDecimal("22.10").compareTo(ticks.get(0).vix()));
        assertEquals(0, new BigDecimal("24.80").compareTo(ticks.get(1).vix()));
        assertEquals(0, new BigDecimal("26.50").compareTo(ticks.get(2).vix()), "last known value carries forward");
    }

    @Test
    void failsWhenNoVixIsKnownYet(@TempDir Path dir) throws IOException {
        var index = normalise(dir, "nifty", INDEX);
        var vix = normalise(dir, "vix", "date,open,high,low,close\n2024-06-04,21.5,31.7,19.1,26.75\n");
        var e = assertThrows(IllegalArgumentException.class, () -> TickBuilder.build("NIFTY", index, vix, JUN4, JUN4, 1));
        assertTrue(e.getMessage().contains("previous trading day"));
    }

    @Test
    void everyPartitionGetsEveryTickInTheSameOrder(@TempDir Path dir) throws IOException {
        var index = normalise(dir, "nifty", INDEX);
        var vix = normalise(dir, "vix", "date,open,high,low,close\n2024-06-03,24.6,24.6,19.4,20.94\n");
        List<Tick> ticks = TickBuilder.build("NIFTY", index, vix, JUN4, JUN4, 1);

        List<Path> files = TickPublisher.publishToFiles(ticks, dir.resolve("logs"), 4);
        assertEquals(4, files.size());
        byte[] first = Files.readAllBytes(files.get(0));
        for (Path f : files) assertArrayEquals(first, Files.readAllBytes(f), "partition logs are byte-identical before any account events");
        try (FileEventLog log = new FileEventLog(files.get(3))) {
            assertEquals(ticks, log.read(0, 100).stream().map(LoggedEvent::event).toList());
        }
        // One writer, written once.
        assertThrows(IllegalArgumentException.class, () -> TickPublisher.publishToFiles(ticks, dir.resolve("logs"), 4));
        // Out-of-order sequence numbers are refused.
        var mem = List.of(new InMemoryEventLog());
        assertThrows(IllegalArgumentException.class, () -> TickPublisher.publish(List.of(ticks.get(1), ticks.get(0)), mem));
    }
}
