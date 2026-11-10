package riskengine.replayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentCsv;
import riskengine.replayer.bhavcopy.InstrumentLoader;
import riskengine.replayer.bhavcopy.LotSizeTable;
import riskengine.replayer.marketdata.MarketDataLoader;
import riskengine.replayer.seed.Archetype;
import riskengine.replayer.seed.SeedConfig;
import riskengine.replayer.seed.SeedGenerator;
import riskengine.replayer.seed.SeedWriter;
import riskengine.replayer.seed.SeededBook;

/** ./gradlew :replayer:run --args="<command> --flag value ..." */
public final class Cli {
    private static final String USAGE = """
            usage: replayer <command> [--flag value ...]

              instruments  --bhavcopy FILE --date YYYY-MM-DD --out FILE
                           [--lots fo_mktlots.csv] [--symbols NIFTY]
                  Normalise an NSE F&O bhavcopy. Legacy-format bhavcopies need --lots.

              bars         --in FILE --out FILE [--from YYYY-MM-DD] [--to YYYY-MM-DD]
                  Normalise index minute bars or India VIX (minute or daily) to ts,open,high,low,close.

              seed         --instruments FILE --vix LEVEL --out DIR [--seed 42] [--accounts 1000]
                           [--as-of 2024-06-03] [--thin-bp 2500] [--spot LEVEL]
                  Generate the start-of-day book (accounts.csv, positions.csv, book.meta). Cash is sized
                  with the margin engine at the as-of close, so it needs that day's India VIX close.
                  --spot defaults to the level implied by the near-month futures settle.

              ticks        --bars FILE --vix FILE --out DIR [--partitions 4] [--underlying NIFTY]
                           [--from YYYY-MM-DD] [--to YYYY-MM-DD]
                  Turn normalised bars (output of `bars`) into Tick events and write every tick to
                  every partition log (partition-0.log ...). This is the only tick writer.
            """;

    private Cli() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.print(USAGE);
            return;
        }
        Map<String, String> f = flags(Arrays.copyOfRange(args, 1, args.length));
        try {
            switch (args[0]) {
                case "instruments" -> instruments(f);
                case "bars" -> bars(f);
                case "seed" -> seed(f);
                case "ticks" -> ticks(f);
                default -> throw new IllegalArgumentException("unknown command " + args[0]);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.print(USAGE);
            System.exit(2);
        }
    }

    private static void instruments(Map<String, String> f) {
        Set<String> symbols = new LinkedHashSet<>(List.of(f.getOrDefault("symbols", "NIFTY").split(",")));
        LotSizeTable lots = f.containsKey("lots") ? LotSizeTable.parseMktLots(Path.of(f.get("lots"))) : null;
        List<Instrument> out = InstrumentLoader.load(Path.of(req(f, "bhavcopy")), lots, symbols,
                LocalDate.parse(req(f, "date")));
        Path dest = Path.of(req(f, "out"));
        InstrumentCsv.write(dest, out);
        Map<String, Integer> counts = new HashMap<>();
        out.forEach(i -> counts.merge(i.underlying() + " " + i.type(), 1, Integer::sum));
        System.out.println("wrote " + out.size() + " instruments to " + dest + " " + new java.util.TreeMap<>(counts));
    }

    private static void bars(Map<String, String> f) {
        Path in = Path.of(req(f, "in"));
        var series = MarketDataLoader.load(in, date(f, "from"), date(f, "to"));
        System.out.print(MarketDataLoader.write(in, series, Path.of(req(f, "out"))));
    }

    private static void seed(Map<String, String> f) throws IOException {
        Path instrumentsFile = Path.of(req(f, "instruments"));
        List<Instrument> instruments = InstrumentCsv.read(instrumentsFile);
        SeedConfig cfg = SeedConfig.defaults(
                Long.parseLong(f.getOrDefault("seed", "42")),
                Integer.parseInt(f.getOrDefault("accounts", "1000")),
                LocalDate.parse(f.getOrDefault("as-of", "2024-06-03")),
                new java.math.BigDecimal(req(f, "vix")));
        if (f.containsKey("thin-bp")) cfg = cfg.withThinFundedBp(Integer.parseInt(f.get("thin-bp")));
        if (f.containsKey("spot")) cfg = cfg.withSpot("NIFTY", new java.math.BigDecimal(f.get("spot")));
        SeededBook book = SeedGenerator.generate(instruments, cfg);
        var written = SeedWriter.write(book, SeedWriter.sha256(Files.readAllBytes(instrumentsFile)), Path.of(req(f, "out")));

        Map<Archetype, Integer> mix = new EnumMap<>(Archetype.class);
        book.accounts().forEach(a -> mix.merge(a.archetype(), 1, Integer::sum));
        long thin = book.accounts().stream().filter(SeededBook.Account::thinFunded).count();
        System.out.println(book.accounts().size() + " accounts, " + book.positions().size() + " positions, "
                + thin + " thin-funded; mix " + mix + "; start spot " + book.startSpots() + ", VIX " + cfg.vix()
                + "; margin config " + cfg.margin().fingerprint().substring(0, 12));
        written.forEach((name, sha) -> System.out.println("  " + sha + "  " + name));
    }

    private static void ticks(Map<String, String> f) {
        var bars = riskengine.replayer.ticks.NormalisedBars.read(Path.of(req(f, "bars")));
        var vix = riskengine.replayer.ticks.NormalisedBars.read(Path.of(req(f, "vix")));
        var ticks = riskengine.replayer.ticks.TickBuilder.build(f.getOrDefault("underlying", "NIFTY"), bars, vix,
                date(f, "from"), date(f, "to"), 1);
        int n = Integer.parseInt(f.getOrDefault("partitions", "4"));
        Path dir = Path.of(req(f, "out"));
        List<Path> files = riskengine.replayer.ticks.TickPublisher.publishToFiles(ticks, dir, n);
        System.out.println("wrote " + ticks.size() + " ticks (tick_seq " + ticks.get(0).tickSeq() + ".." + ticks.get(ticks.size() - 1).tickSeq()
                + ", VIX " + vix.resolution() + ") to each of " + files.size() + " partition logs in " + dir);
    }

    private static Map<String, String> flags(String[] a) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < a.length; i++) {
            if (!a[i].startsWith("--") || i + 1 >= a.length) throw new IllegalArgumentException("bad flag near " + a[i]);
            m.put(a[i].substring(2), a[++i]);
        }
        return m;
    }

    private static String req(Map<String, String> f, String name) {
        String v = f.get(name);
        if (v == null) throw new IllegalArgumentException("missing --" + name);
        return v;
    }

    private static LocalDate date(Map<String, String> f, String name) {
        return f.containsKey(name) ? LocalDate.parse(f.get(name)) : null;
    }
}
