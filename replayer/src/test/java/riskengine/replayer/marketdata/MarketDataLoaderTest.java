package riskengine.replayer.marketdata;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import riskengine.replayer.marketdata.MarketDataLoader.Resolution;

class MarketDataLoaderTest {

    @Test
    void minuteBarsAreSortedNormalisedAndGapsCounted(@TempDir Path dir) throws IOException {
        Path in = Files.writeString(dir.resolve("nifty.csv"), """
                date,time,open,high,low,close
                2024-06-04,09:17,22900,22950,22880,22940
                2024-06-04,09:15,23100,23110,22950,22960
                2024-06-04,09:16,22960,22970,22890,22900
                2024-06-03,15:29,23260,23270,23255,23263.9
                """);
        var s = MarketDataLoader.load(in, LocalDate.of(2024, 6, 4), LocalDate.of(2024, 6, 4));
        assertEquals(Resolution.MINUTE, s.resolution());
        assertEquals(3, s.bars().size(), "the 3 June bar is filtered out by --from");
        assertEquals(MarketDataLoader.SESSION_MINUTES - 3, s.missingMinutes());
        assertTrue(MarketDataLoader.format(s.bars()).startsWith(
                "ts,open,high,low,close\n2024-06-04T09:15:00+05:30,23100.00,23110.00,22950.00,22960.00\n"));
    }

    @Test
    void nseStyleDailyVixWithQuotedNumbersIsDetectedAsDaily(@TempDir Path dir) throws IOException {
        Path in = Files.writeString(dir.resolve("vix.csv"), """
                Date ,Open ,High ,Low ,Close ,Prev. Close ,Change ,% Change\s
                04-JUN-2024,"21.50","31.71","19.10","26.68","21.73","4.95","22.78"
                03-JUN-2024,"24.60","24.60","19.36","21.73","24.60","-2.87","-11.67"
                """);
        var s = MarketDataLoader.load(in, null, null);
        assertEquals(Resolution.DAILY, s.resolution());
        assertEquals(2, s.bars().size());
        // Daily values are stamped at session close so a replayer cannot use them before they existed.
        assertEquals("2024-06-03T15:30+05:30", s.bars().get(0).ts().toString());
    }

    @Test
    void upstoxCandleJsonIsReadNewestFirstAndWrittenOldestFirst(@TempDir Path dir) throws IOException {
        Path in = Files.writeString(dir.resolve("nifty_1min.json"), """
                {"status":"success","data":{"candles":[["2024-06-04T09:16:00+05:30",23050.1,23060,22990.5,23000,0,0],\
                ["2024-06-04T09:15:00+05:30",23179.5,23179.5,23010,23050.1,0,0],\
                ["2024-06-03T15:29:00+05:30",23300,23310.2,23290,23305.95,0,0]]}}
                """);
        var s = MarketDataLoader.load(in, LocalDate.of(2024, 6, 4), null);
        assertEquals(Resolution.MINUTE, s.resolution());
        assertEquals(2, s.bars().size());
        assertEquals("""
                ts,open,high,low,close
                2024-06-04T09:15:00+05:30,23179.50,23179.50,23010.00,23050.10
                2024-06-04T09:16:00+05:30,23050.10,23060.00,22990.50,23000.00
                """, MarketDataLoader.format(s.bars()));
        // Anything that is not exactly the candle shape is refused, not skipped.
        Path odd = Files.writeString(dir.resolve("odd.json"),
                "{\"status\":\"success\",\"data\":{\"candles\":[[\"2024-06-04T09:15:00+05:30\",1,2,1,2,0,0],[\"oops\"]]}}");
        assertThrows(IllegalArgumentException.class, () -> MarketDataLoader.load(odd, null, null));
        Path error = Files.writeString(dir.resolve("error.json"), "{\"status\":\"error\",\"errors\":[]}");
        assertThrows(IllegalArgumentException.class, () -> MarketDataLoader.load(error, null, null));
    }

    @Test
    void duplicateTimestampsAndBadOhlcAreRejected(@TempDir Path dir) throws IOException {
        Path dup = Files.writeString(dir.resolve("dup.csv"), """
                datetime,open,high,low,close
                2024-06-04 09:15:00,1,2,1,2
                2024-06-04 09:15:00,1,2,1,2
                """);
        assertThrows(IllegalArgumentException.class, () -> MarketDataLoader.load(dup, null, null));
        Path bad = Files.writeString(dir.resolve("bad.csv"), """
                datetime,open,high,low,close
                2024-06-04 09:15:00,5,2,1,2
                """);
        assertThrows(IllegalArgumentException.class, () -> MarketDataLoader.load(bad, null, null));
    }
}
