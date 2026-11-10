package riskengine.replayer.seed;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.TreeMap;
import riskengine.events.Tick;
import riskengine.margin.AccountBook;
import riskengine.margin.MarginConfig;
import riskengine.margin.ReferenceMarginEngine;
import riskengine.margin.UnderlyingParams;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentType;

class SeedGeneratorTest {
    static final LocalDate AS_OF = LocalDate.of(2024, 6, 3);

    /** Synthetic but shaped like a real chain: two weeklies + two monthly futures per underlying. */
    static List<Instrument> universe() {
        List<Instrument> out = new ArrayList<>();
        addUnderlying(out, "NIFTY", 25, 23300, 50, LocalDate.of(2024, 6, 6), LocalDate.of(2024, 6, 13), LocalDate.of(2024, 6, 27));
        addUnderlying(out, "BANKNIFTY", 15, 49600, 100, LocalDate.of(2024, 6, 5), LocalDate.of(2024, 6, 12), LocalDate.of(2024, 6, 26));
        // Expired / zero-OI noise the generator must ignore.
        out.add(Instrument.of("NIFTY", InstrumentType.CE, AS_OF, new BigDecimal("23300"), 25, BigDecimal.ONE, BigDecimal.ONE, 999));
        out.add(Instrument.of("NIFTY", InstrumentType.CE, LocalDate.of(2024, 6, 6), new BigDecimal("30000"), 25, BigDecimal.ONE, new BigDecimal("0.05"), 0));
        return out;
    }

    private static void addUnderlying(List<Instrument> out, String u, int lot, int spot, int step, LocalDate w1, LocalDate w2, LocalDate monthly) {
        out.add(Instrument.of(u, InstrumentType.FUT, monthly, null, lot, bd(spot + 70), bd(spot + 70), 100_000));
        out.add(Instrument.of(u, InstrumentType.FUT, monthly.plusDays(28), null, lot, bd(spot + 180), bd(spot + 180), 10_000));
        for (LocalDate exp : List.of(w1, w2)) {
            for (int k = -30; k <= 30; k++) {
                int strike = spot + k * step;
                double intrinsicC = Math.max(0, spot - strike), intrinsicP = Math.max(0, strike - spot);
                double tv = Math.max(2, 120 - Math.abs(k) * 4);
                out.add(Instrument.of(u, InstrumentType.CE, exp, bd(strike), lot, bd(intrinsicC + tv), bd(intrinsicC + tv), 1000 + k * k));
                out.add(Instrument.of(u, InstrumentType.PE, exp, bd(strike), lot, bd(intrinsicP + tv), bd(intrinsicP + tv), 1000 + k * k));
            }
        }
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(Math.round(v * 100), 2);
    }

    static final BigDecimal VIX = new BigDecimal("21.50");

    private static SeedConfig config(long seed, int n) {
        return SeedConfig.defaults(seed, n, AS_OF, VIX);
    }

    private static SeededBook book(long seed, int n) {
        return SeedGenerator.generate(universe(), config(seed, n));
    }

    // ---------------------------------------------------------------- M1 gate

    @Test
    void sameSeedProducesByteIdenticalFiles(@TempDir Path dir) throws IOException {
        var a = SeedWriter.write(book(42, 2_000), "x", dir.resolve("a"));
        var b = SeedWriter.write(book(42, 2_000), "x", dir.resolve("b"));
        assertEquals(a, b);
        for (String f : List.of("accounts.csv", "positions.csv", "book.meta")) {
            assertArrayEquals(Files.readAllBytes(dir.resolve("a").resolve(f)), Files.readAllBytes(dir.resolve("b").resolve(f)), f);
        }
    }

    @Test
    void inputOrderDoesNotMatter() {
        List<Instrument> shuffled = new ArrayList<>(universe());
        java.util.Collections.reverse(shuffled);
        var cfg = config(7, 500);
        assertEquals(SeedWriter.positionsCsv(SeedGenerator.generate(universe(), cfg)),
                SeedWriter.positionsCsv(SeedGenerator.generate(shuffled, cfg)));
    }

    @Test
    void differentSeedProducesDifferentBook() {
        assertNotEquals(SeedWriter.positionsCsv(book(1, 500)), SeedWriter.positionsCsv(book(2, 500)));
    }

    @Test
    void addingAccountsDoesNotChangeExistingOnes() {
        String small = SeedWriter.accountsCsv(book(42, 100));
        String big = SeedWriter.accountsCsv(book(42, 300));
        assertTrue(big.startsWith(small));
    }

    // ---------------------------------------------------------------- book shape

    @Test
    void booksAreBelievable() {
        SeededBook b = book(42, 5_000);
        Map<String, Instrument> byId = universe().stream().collect(Collectors.toMap(Instrument::id, Function.identity(), (x, y) -> x));
        Map<String, List<SeededBook.Position>> legs = b.positions().stream().collect(Collectors.groupingBy(SeededBook.Position::accountId));

        for (var acct : b.accounts()) {
            List<SeededBook.Position> ps = legs.getOrDefault(acct.accountId(), List.of());
            assertFalse(ps.isEmpty(), acct + " has no positions");
            assertTrue(acct.cashBalance().signum() >= 0, acct.toString());
            for (var p : ps) {
                Instrument i = byId.get(p.instrumentId());
                assertNotNull(i, p.instrumentId());
                assertTrue(i.expiry().isAfter(AS_OF) && i.openInterest() > 0, "stale or illiquid " + i.id());
                assertEquals((long) p.lots() * i.lotSize(), p.qty());
                assertEquals(0, p.avgPrice().compareTo(i.settle()), "opened at previous settle");
            }
            switch (acct.archetype()) {
                case FUTURES_ONLY -> assertTrue(ps.stream().allMatch(p -> byId.get(p.instrumentId()).type() == InstrumentType.FUT));
                case OPTION_BUYER -> assertTrue(ps.stream().allMatch(p -> p.lots() > 0 && byId.get(p.instrumentId()).type().isOption()));
                case SHORT_STRADDLE -> {
                    assertEquals(2, ps.size());
                    assertTrue(ps.stream().allMatch(p -> p.lots() < 0));
                    assertEquals(0, byId.get(ps.get(0).instrumentId()).strike().compareTo(byId.get(ps.get(1).instrumentId()).strike()));
                }
                case SHORT_STRANGLE -> {
                    assertEquals(2, ps.size());
                    assertTrue(ps.stream().allMatch(p -> p.lots() < 0));
                }
                case HEDGED_SPREAD -> {
                    long shortLots = ps.stream().filter(p -> p.lots() < 0).mapToLong(p -> -p.lots()).sum();
                    long longLots = ps.stream().filter(p -> p.lots() > 0).mapToLong(SeededBook.Position::lots).sum();
                    assertEquals(shortLots, longLots, "every short leg is covered by a long wing");
                }
            }
            if (acct.thinFunded()) {
                assertTrue(acct.archetype() == Archetype.SHORT_STRADDLE || acct.archetype() == Archetype.SHORT_STRANGLE
                        || acct.archetype() == Archetype.FUTURES_ONLY, acct.toString());
            }
        }

        Map<Archetype, Long> mix = b.accounts().stream().collect(Collectors.groupingBy(SeededBook.Account::archetype, Collectors.counting()));
        assertEquals(Archetype.values().length, mix.size(), "every archetype appears: " + mix);
        long naked = b.accounts().stream().filter(a -> a.archetype().sellsNakedRisk()).count();
        long thinNaked = b.accounts().stream().filter(a -> a.archetype().sellsNakedRisk() && a.thinFunded()).count();
        double share = (double) thinNaked / naked;
        assertTrue(share > 0.20 && share < 0.30, "about 25% of naked sellers are thin: " + share);
        assertEquals(List.of("NIFTY"), b.underlyings(), "Nifty only by default until M5");
    }

    @Test
    void bankNiftyStillWorksWhenAskedFor() {
        // Needs a margin config that knows Bank Nifty; the checked-in one is Nifty only.
        var nifty = MarginConfig.defaults().params("NIFTY");
        var bank = new UnderlyingParams("BANKNIFTY", nifty.dividendYield(), nifty.volScale(), nifty.priceScanRange(), nifty.volScanRange(),
                nifty.extremeMultiple(), nifty.extremeCoverage(), nifty.shortOptionMinimum(), nifty.exposureMargin(), nifty.minVol());
        var margin = new MarginConfig(MarginConfig.defaults().riskFreeRate(), new TreeMap<>(Map.of("NIFTY", nifty, "BANKNIFTY", bank)),
                MarginConfig.defaults().thresholds());
        var cfg = config(42, 500).withUnderlyingWeights(new TreeMap<>(Map.of("NIFTY", 70, "BANKNIFTY", 30)), margin);
        assertEquals(List.of("BANKNIFTY", "NIFTY"), SeedGenerator.generate(universe(), cfg).underlyings());
    }

    // ---------------------------------------------------------------- funding from the real margin engine

    /** Recomputes every account's starting utilisation with the reference engine, independently of the generator. */
    private static Map<String, Double> startingUtilisation(SeededBook b) {
        Map<String, TreeMap<String, riskengine.margin.Position>> positions = new TreeMap<>();
        for (var p : b.positions()) {
            positions.computeIfAbsent(p.accountId(), k -> new TreeMap<>()).put(p.instrumentId(), new riskengine.margin.Position(p.qty(), p.avgPrice()));
        }
        List<AccountBook> books = b.accounts().stream()
                .map(a -> new AccountBook(a.accountId(), a.cashBalance(), BigDecimal.ZERO, positions.get(a.accountId()))).toList();
        var engine = new ReferenceMarginEngine(b.config().margin(), universe(), books);
        long valuation = java.time.OffsetDateTime.parse(AS_OF + "T15:30:00+05:30").toInstant().toEpochMilli();
        engine.onTick(new Tick(1, valuation, "NIFTY", b.startSpots().get("NIFTY"), b.config().vix()));
        Map<String, Double> out = new TreeMap<>();
        for (var a : b.accounts()) out.put(a.accountId(), engine.latest(a.accountId()).utilisation());
        return out;
    }

    @Test
    void everyAccountStartsInsideItsTargetUtilisationBand() {
        SeededBook b = book(42, 5_000);
        Map<String, Double> util = startingUtilisation(b);
        int thin = 0;
        for (var a : b.accounts()) {
            double u = util.get(a.accountId());
            String who = a + " at " + u;
            // Cash is rounded down to the paisa, so utilisation can sit a hair above the drawn target.
            double slack = 1e-6;
            if (a.archetype() == Archetype.OPTION_BUYER) {
                assertEquals(0.0, u, 0.0, who + ": a buyer who has paid needs no margin");
            } else if (a.thinFunded()) {
                thin++;
                assertTrue(u >= 0.80 && u < 0.99, who + ": thin shorts start at 80-99%");
            } else if (a.archetype() == Archetype.HEDGED_SPREAD) {
                assertTrue(u >= 0.20 && u < 0.55 + slack, who + ": spreads start comfortably under 60%");
            } else {
                assertTrue(u >= 0.30 && u < 0.70 + slack, who + ": funded shorts and futures start at 30-70%");
            }
        }
        assertTrue(thin > 300, "enough thin accounts to matter: " + thin);
    }

    @Test
    void bookMetaRecordsTheMarginConfigItWasSizedWith(@TempDir Path dir) throws IOException {
        SeededBook b = book(42, 200);
        SeedWriter.write(b, "x", dir);
        String meta = Files.readString(dir.resolve("book.meta"));
        assertTrue(meta.contains("generator_version=2\n"));
        assertTrue(meta.contains("margin_config_sha256=" + MarginConfig.defaults().fingerprint() + "\n"), meta);
        assertTrue(meta.contains("start_vix=21.50\n") && meta.contains("start_spot={NIFTY="), meta);

        // A different margin config gives a different fingerprint AND different cash balances.
        var n = MarginConfig.defaults().params("NIFTY");
        var wider = new UnderlyingParams("NIFTY", n.dividendYield(), n.volScale(), 0.12, n.volScanRange(), n.extremeMultiple(),
                n.extremeCoverage(), n.shortOptionMinimum(), n.exposureMargin(), n.minVol());
        var other = new MarginConfig(MarginConfig.defaults().riskFreeRate(), new TreeMap<>(Map.of("NIFTY", wider)), MarginConfig.defaults().thresholds());
        assertNotEquals(MarginConfig.defaults().fingerprint(), other.fingerprint());
        SeededBook b2 = SeedGenerator.generate(universe(), config(42, 200).withUnderlyingWeights(new TreeMap<>(Map.of("NIFTY", 100)), other));
        assertEquals(SeedWriter.positionsCsv(b), SeedWriter.positionsCsv(b2), "same positions");
        assertNotEquals(SeedWriter.accountsCsv(b), SeedWriter.accountsCsv(b2), "different cash");
    }

    @Test
    void impliedSpotPutsTheNearFutureAtItsSettlePrice() {
        SeededBook b = book(1, 10);
        BigDecimal spot = b.startSpots().get("NIFTY");
        // universe(): near future settles at 23,370 on 27 June; carry over 24 days at (6.9% - 1.2%) is about 87 points
        assertTrue(spot.compareTo(new BigDecimal("23270")) > 0 && spot.compareTo(new BigDecimal("23295")) < 0, spot.toString());
        assertEquals(2, spot.scale());
    }

    @Test
    void failsClearlyWhenNoUsableChain() {
        List<Instrument> futuresOnly = universe().stream().filter(i -> i.type() == InstrumentType.FUT).toList();
        var e = assertThrows(IllegalArgumentException.class,
                () -> SeedGenerator.generate(futuresOnly, config(1, 10)));
        assertTrue(e.getMessage().contains("option chain"));
    }

    @Test
    void rngIsUniformEnoughAndInRange() {
        SplitMix64 r = new SplitMix64(123);
        int[] counts = new int[10];
        for (int i = 0; i < 100_000; i++) counts[r.nextInt(0, 9)]++;
        for (int c : counts) assertTrue(c > 9_500 && c < 10_500, java.util.Arrays.toString(counts));
        // Reference vector for SplitMix64, seed 1234567. If this changes, every seeded book changes.
        SplitMix64 ref = new SplitMix64(1234567);
        assertEquals(List.of("6457827717110365317", "3203168211198807973", "9817491932198370423"),
                List.of(Long.toUnsignedString(ref.nextLong()), Long.toUnsignedString(ref.nextLong()),
                        Long.toUnsignedString(ref.nextLong())));
    }
}
