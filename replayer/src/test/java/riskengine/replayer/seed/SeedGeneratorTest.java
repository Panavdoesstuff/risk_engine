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

    private static SeededBook book(long seed, int n) {
        return SeedGenerator.generate(universe(), SeedConfig.defaults(seed, n, AS_OF));
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
        var cfg = SeedConfig.defaults(7, 500, AS_OF);
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
        var d = SeedConfig.defaults(42, 500, AS_OF);
        var both = new java.util.TreeMap<>(Map.of("NIFTY", 70, "BANKNIFTY", 30));
        var cfg = new SeedConfig(d.seed(), d.accounts(), d.asOf(), d.proxyMarginRate(), d.thinFundedBp(), d.archetypeWeights(), both);
        assertEquals(List.of("BANKNIFTY", "NIFTY"), SeedGenerator.generate(universe(), cfg).underlyings());
    }

    @Test
    void failsClearlyWhenNoUsableChain() {
        List<Instrument> futuresOnly = universe().stream().filter(i -> i.type() == InstrumentType.FUT).toList();
        var e = assertThrows(IllegalArgumentException.class,
                () -> SeedGenerator.generate(futuresOnly, SeedConfig.defaults(1, 10, AS_OF)));
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
