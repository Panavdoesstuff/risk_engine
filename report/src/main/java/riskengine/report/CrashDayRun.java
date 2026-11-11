package riskengine.report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import riskengine.events.Event;
import riskengine.events.EventCodec;
import riskengine.events.Tick;
import riskengine.host.BookCsv;
import riskengine.margin.AccountBook;
import riskengine.margin.MarginConfig;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentCsv;
import riskengine.shard.ShardConfig;

/**
 * The crash-day replay: 4 June 2024 against the seeded book, then the write-up.
 *
 * <pre>
 *   ./gradlew :report:crashDay
 *   options: --instruments data/processed/instruments-2024-06-03.csv --book data/seed
 *            --ticks data/processed/logs --vix-meta data/processed/INDIAVIX-1m.csv.meta
 *            --out docs/crash-day-2024-06-04.md --assets docs/crash-day --runs 2
 * </pre>
 *
 * The inputs are the files the README's data steps produce. It runs the replay {@code runs} times and
 * refuses to write anything unless every run ends with the same state hashes and the same counts.
 */
public final class CrashDayRun {
    private CrashDayRun() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> f = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) f.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        Path instrumentsFile = Path.of(f.getOrDefault("instruments", "data/processed/instruments-2024-06-03.csv"));
        Path bookDir = Path.of(f.getOrDefault("book", "data/seed"));
        Path tickDir = Path.of(f.getOrDefault("ticks", "data/processed/logs"));
        Path vixMeta = Path.of(f.getOrDefault("vix-meta", "data/processed/INDIAVIX-1m.csv.meta"));
        Path out = Path.of(f.getOrDefault("out", "docs/crash-day-2024-06-04.md"));
        Path assets = Path.of(f.getOrDefault("assets", "docs/crash-day"));
        int runs = Integer.parseInt(f.getOrDefault("runs", "2"));

        List<Instrument> instruments = InstrumentCsv.read(instrumentsFile);
        List<AccountBook> books = BookCsv.read(bookDir);
        Map<String, String[]> kinds = archetypes(bookDir.resolve("accounts.csv"));
        List<List<Tick>> ticks = new ArrayList<>();
        for (int p = 0; ; p++) {
            Path file = tickDir.resolve("partition-" + p + ".log");
            if (!Files.exists(file)) break;
            ticks.add(readTicks(file));
        }
        if (ticks.isEmpty()) throw new IllegalArgumentException("no partition-N.log files in " + tickDir);
        String bookMeta = Files.readString(bookDir.resolve("book.meta"), StandardCharsets.UTF_8);
        String vixInfo = Files.exists(vixMeta) ? Files.readString(vixMeta, StandardCharsets.UTF_8) : "";

        MarginConfig cfg = MarginConfig.defaults();
        if (!bookMeta.contains("margin_config_sha256=" + cfg.fingerprint())) {
            throw new IllegalArgumentException("the book was seeded with a different margin config; run `replayer seed` again");
        }
        System.out.printf("crash day: %d accounts, %d instruments, %d ticks x %d partitions, margin config %s%n",
                books.size(), instruments.size(), ticks.get(0).size(), ticks.size(), cfg.fingerprint().substring(0, 12));

        CrashDay.Result first = null;
        List<long[]> timings = new ArrayList<>();
        for (int run = 1; run <= runs; run++) {
            long t0 = System.nanoTime();
            CrashDay.Result r = CrashDay.run(cfg, ShardConfig.defaults(), instruments, books, ticks);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("run %d: %,d ms, state hashes %s%n", run, ms, r.stateHashes().stream().map(h -> h.substring(0, 12)).toList());
            timings.add(new long[] {ms});
            if (first == null) {
                first = r;
            } else if (!first.stateHashes().equals(r.stateHashes()) || !CrashDayReport.counts(first).equals(CrashDayReport.counts(r))) {
                throw new IllegalStateException("two runs of the same replay disagree: " + first.stateHashes() + " vs " + r.stateHashes());
            }
        }
        CrashDayReport report = new CrashDayReport(first, kinds, instruments, bookMeta, vixInfo, runs, out, assets);
        System.out.print(report.summary());
        report.write();
        System.out.println("wrote " + out + " and " + assets + "/");
    }

    static List<Tick> readTicks(Path file) throws java.io.IOException {
        List<Tick> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            Event e = EventCodec.decode(line);
            if (!(e instanceof Tick t)) throw new IllegalArgumentException(file + ": expected only ticks, found " + line);
            out.add(t);
        }
        return out;
    }

    /** account id -> {archetype, thin_funded, cash} from accounts.csv. */
    static Map<String, String[]> archetypes(Path accountsCsv) throws java.io.IOException {
        Map<String, String[]> m = new TreeMap<>();
        List<String> lines = Files.readAllLines(accountsCsv, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] c = line.split(",");
            m.put(c[0], new String[] {c[1], c[2], c[3]});
        }
        return m;
    }
}
