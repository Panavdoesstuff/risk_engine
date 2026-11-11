package riskengine.report;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.ref.Instrument;
import riskengine.shard.Fixtures;
import riskengine.shard.ShardConfig;

class CrashDayTest {

    /** A 9% fall over an hour with VIX from 20 to 32, then a partial recovery: written to every partition. */
    static List<List<Tick>> crash(int partitions) {
        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            double drop = i < 60 ? 0.09 * i / 60 : 0.09 - 0.03 * (i - 60) / 30;
            BigDecimal spot = BigDecimal.valueOf(Math.round(23_300 * (1 - drop) * 100), 2);
            BigDecimal vix = BigDecimal.valueOf(Math.round((20 + 12 * Math.min(1, i / 60.0)) * 100), 2);
            ticks.add(new Tick(i + 1, Fixtures.T0 + i * 60_000L, "NIFTY", spot, vix));
        }
        List<List<Tick>> out = new ArrayList<>();
        for (int p = 0; p < partitions; p++) out.add(ticks);
        return out;
    }

    @Test
    void theSameDayGivesTheSameAnswerAndEveryCountAddsUp(@TempDir Path dir) throws Exception {
        List<Instrument> instruments = Fixtures.instruments();
        List<AccountBook> books = Fixtures.books(2_000, 7);
        CrashDay.Result a = CrashDay.run(Fixtures.CFG, ShardConfig.defaults(), instruments, books, crash(4));
        CrashDay.Result b = CrashDay.run(Fixtures.CFG, ShardConfig.defaults(), instruments, books, crash(4));

        assertEquals(a.stateHashes(), b.stateHashes(), "same input, same state on every shard");
        assertEquals(CrashDayReport.counts(a), CrashDayReport.counts(b), "same counts, minute by minute");
        assertEquals(2_000, a.accounts().size());
        assertEquals(90, a.minutes().size());

        long squared = a.accounts().values().stream().filter(x -> x.squareOffStarted >= 0).count();
        long called = a.accounts().values().stream().filter(x -> x.firstMarginCall >= 0).count();
        long warned = a.accounts().values().stream().filter(x -> x.firstWarning >= 0).count();
        assertTrue(squared > 0, "a 9% fall squares someone off");
        assertTrue(warned >= called && called >= squared, "warned ⊇ margin-called ⊇ squared off: " + warned + " " + called + " " + squared);
        assertEquals(squared, a.minutes().stream().mapToLong(m -> m.squaredOff).sum());
        assertEquals(called, a.minutes().stream().mapToLong(m -> m.marginCalled).sum());
        assertEquals(warned, a.minutes().stream().mapToLong(m -> m.warned).sum());

        // The broker fills every leg before the next tick, so every episode has ended by then.
        long opened = a.minutes().stream().mapToLong(m -> m.episodesOpened).sum();
        long ended = a.minutes().stream().mapToLong(m -> m.episodesResolved + m.episodesExhausted).sum();
        assertEquals(opened, ended, "no episode left open");
        assertEquals(a.accounts().values().stream().mapToLong(x -> x.legs).sum(), a.minutes().stream().mapToLong(m -> m.legs).sum());
        for (CrashDay.Minute m : a.minutes()) {
            assertEquals(2_000, java.util.Arrays.stream(m.levels).sum(), "every account has one level each minute");
            assertEquals(0, m.statuses[riskengine.margin.AccountRisk.Status.SQUARING_OFF.ordinal()], "nobody is left squaring off at a tick");
        }

        // And the write-up renders from it.
        Map<String, String[]> kinds = new TreeMap<>();
        books.forEach(x -> kinds.put(x.id(), new String[] {"FUTURES_ONLY", "false", x.cash().toPlainString()}));
        CrashDayReport report = new CrashDayReport(a, kinds, instruments, "accounts=2000\nseed=7\nstart_spot=23300\nstart_vix=20\nmargin_config_sha256="
                + Fixtures.CFG.fingerprint() + "\n", "resolution=MINUTE\nrows=90\nmissing_session_minutes=0\n", 2, dir.resolve("crash.md"), dir.resolve("crash"));
        report.write();
        String md = java.nio.file.Files.readString(dir.resolve("crash.md"));
        assertTrue(md.contains(String.format(java.util.Locale.ROOT, "| Squared off (120%%, three ticks running) | %,d", squared)), md.substring(0, 1500));
        assertTrue(java.nio.file.Files.readString(dir.resolve("crash/chart.svg")).startsWith("<svg"));
        assertEquals(91, java.nio.file.Files.readAllLines(dir.resolve("crash/curve.csv")).size(), "a header and one row a minute");
    }
}
