package riskengine.margin;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.From;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentType;

class MarginPropertiesTest {
    static final MarginConfig CFG = MarginExampleTest.CONFIG;
    static final ScenarioGrid GRID = new ScenarioGrid(MarginExampleTest.PARAMS);
    static final long NOW = MarginExampleTest.VALUATION;
    static final List<LocalDate> EXPIRIES = List.of(LocalDate.of(2024, 6, 13), LocalDate.of(2024, 6, 20), LocalDate.of(2024, 6, 27));

    record Leg(Instrument instrument, long qty) {}

    @Provide
    Arbitrary<Leg> legs() {
        Arbitrary<InstrumentType> type = Arbitraries.of(InstrumentType.values());
        Arbitrary<Integer> strikeStep = Arbitraries.integers().between(-20, 20);
        Arbitrary<LocalDate> expiry = Arbitraries.of(EXPIRIES);
        Arbitrary<Integer> lots = Arbitraries.integers().between(-20, 20).filter(l -> l != 0);
        return Combinators.combine(type, strikeStep, expiry, lots).as((t, k, e, l) -> new Leg(
                Instrument.of("NIFTY", t, e, t == InstrumentType.FUT ? null : BigDecimal.valueOf(23000 + 100L * k),
                        25, BigDecimal.ONE, BigDecimal.ONE, 1),
                25L * l));
    }

    static MarketState market(double spot, double vix) {
        return new MarketState("NIFTY", spot, vix / 100, NOW);
    }

    static AccountVector vector(List<Leg> legs, MarketState m) {
        AccountVector v = new AccountVector("NIFTY");
        for (Leg l : legs) v.add(l.qty(), RiskArray.compute(l.instrument(), m, GRID, CFG.riskFreeRate()));
        return v;
    }

    static double required(List<Leg> legs, MarketState m) {
        return MarginCalculator.compute(vector(legs, m), CFG, 1_000_000, 0).required();
    }

    @Property(tries = 500)
    void marginIsNeverNegative(@ForAll @Size(min = 1, max = 8) List<@From("legs") Leg> legs,
                               @ForAll @DoubleRange(min = 18_000, max = 28_000) double spot,
                               @ForAll @DoubleRange(min = 10, max = 40) double vix) {
        MarginResult r = MarginCalculator.compute(vector(legs, market(spot, vix)), CFG, 100_000, 0);
        assertTrue(r.required() >= 0 && r.riskRequirement() >= 0 && r.exposureMargin() >= 0, r.toString());
        assertTrue(r.utilisation() >= 0);
    }

    @Property(tries = 500)
    void addingALongOptionHedgeNeverRaisesMargin(@ForAll @Size(min = 1, max = 8) List<@From("legs") Leg> legs,
                                                 @ForAll("legs") Leg hedge,
                                                 @ForAll @DoubleRange(min = 18_000, max = 28_000) double spot,
                                                 @ForAll @DoubleRange(min = 10, max = 40) double vix) {
        if (hedge.instrument().type() == InstrumentType.FUT) return; // futures add exposure margin; not a hedge here
        Leg longHedge = new Leg(hedge.instrument(), Math.abs(hedge.qty()));
        MarketState m = market(spot, vix);
        List<Leg> hedged = new ArrayList<>(legs);
        hedged.add(longHedge);
        // Tolerance: floating-point noise on six-figure sums, far below a paisa.
        assertTrue(required(hedged, m) <= required(legs, m) + 1e-6,
                () -> "hedge " + longHedge + " raised margin " + required(legs, m) + " -> " + required(hedged, m));
    }

    @Property(tries = 300)
    void marginOfCombinedBookNeverExceedsSumOfParts(@ForAll @Size(min = 1, max = 5) List<@From("legs") Leg> a,
                                                    @ForAll @Size(min = 1, max = 5) List<@From("legs") Leg> b,
                                                    @ForAll @DoubleRange(min = 18_000, max = 28_000) double spot) {
        MarketState m = market(spot, 20);
        List<Leg> both = new ArrayList<>(a);
        both.addAll(b);
        assertTrue(required(both, m) <= required(a, m) + required(b, m) + 1e-6);
    }

    @Property(tries = 300)
    void longOptionLossIsCappedAtPremium(@ForAll @Size(min = 1, max = 8) List<@From("legs") Leg> legs,
                                         @ForAll @DoubleRange(min = 18_000, max = 28_000) double spot,
                                         @ForAll @DoubleRange(min = 10, max = 40) double vix) {
        List<Leg> longOnly = legs.stream().filter(l -> l.instrument().type().isOption())
                .map(l -> new Leg(l.instrument(), Math.abs(l.qty()))).toList();
        if (longOnly.isEmpty()) return;
        MarketState m = market(spot, vix);
        AccountVector v = vector(longOnly, m);
        for (int s = 0; s < ScenarioGrid.SIZE; s++) {
            assertTrue(v.scenarioLoss(s) <= v.netOptionValue() + 1e-6, "scenario " + (s + 1) + " loses more than premium");
        }
        MarginResult r = MarginCalculator.compute(v, CFG, 0, 0);
        assertEquals(0, r.required(), 1e-6, "a buyer who already paid owes nothing more");
        assertEquals(0, r.utilisation(), 0);
    }

    @Property(tries = 200)
    void removeUndoesAdd(@ForAll @Size(min = 1, max = 6) List<@From("legs") Leg> legs) {
        MarketState m = market(23_000, 20);
        AccountVector v = vector(legs, m);
        for (Leg l : legs) v.remove(l.qty(), RiskArray.compute(l.instrument(), m, GRID, CFG.riskFreeRate()));
        for (int s = 0; s < ScenarioGrid.SIZE; s++) assertEquals(0, v.scenarioLoss(s), 1e-6);
        assertEquals(0, v.netOptionValue(), 1e-6);
        assertEquals(0, v.shortOptionNotional(), 1e-6);
        assertEquals(0, v.exposureNotional(), 1e-6);
    }

    // ---------------------------------------------------------------- component checks

    @Test
    void futuresMarginIsTheFullScanRangeMovePlusExposure() {
        MarketState m = market(23_000, 20);
        Instrument fut = Instrument.of("NIFTY", InstrumentType.FUT, LocalDate.of(2024, 6, 27), null, 25, BigDecimal.ONE, BigDecimal.ONE, 1);
        RiskArray a = RiskArray.compute(fut, m, GRID, CFG.riskFreeRate());
        MarginResult r = MarginCalculator.compute(new AccountVector("NIFTY").add(25, a), CFG, 100_000, 0);
        // long future loses most in scenario 13/14 (-1 x scan range); extreme 16 is 2 x 9% x 35% = 6.3% < 9%
        assertEquals(13, r.worstScenario());
        assertEquals(0.09 * a.price() * 25, r.worstScenarioLoss(), 1e-6);
        assertEquals(0, r.shortOptionMinimum());
        assertEquals(0.02 * a.price() * 25, r.exposureMargin(), 1e-6);
    }

    @Test
    void deepOtmShortOptionIsCaughtByShortOptionMinimumOrExtremeScenario() {
        MarketState m = market(23_000, 15);
        Instrument farPut = Instrument.of("NIFTY", InstrumentType.PE, LocalDate.of(2024, 6, 13), new BigDecimal("19000"), 25,
                BigDecimal.ONE, BigDecimal.ONE, 1);
        RiskArray a = RiskArray.compute(farPut, m, GRID, CFG.riskFreeRate());
        MarginResult r = MarginCalculator.compute(new AccountVector("NIFTY").add(-25, a), CFG, 100_000, 0);
        assertTrue(r.spanRequirement() >= 0.03 * 23_000 * 25 - 1e-6, "never below the short option minimum");
        assertTrue(r.required() > 0);
    }

    @Test
    void utilisationEdgeCases() {
        assertEquals(0, MarginCalculator.utilisation(0, 0));
        assertEquals(0, MarginCalculator.utilisation(0, 100));
        assertEquals(Double.POSITIVE_INFINITY, MarginCalculator.utilisation(10, 0));
        assertEquals(Double.POSITIVE_INFINITY, MarginCalculator.utilisation(10, -5));
        assertEquals(0.5, MarginCalculator.utilisation(50, 100));
    }

    @Test
    void scenarioGridLayout() {
        assertEquals(23_000 * 1.03, GRID.spot(2, 23_000), 1e-9, "#3 = +1/3 of 9%");
        assertEquals(23_000 * 0.91, GRID.spot(12, 23_000), 1e-9, "#13 = -9%");
        assertEquals(23_000 * 1.18, GRID.spot(14, 23_000), 1e-9, "#15 = +2 x 9%");
        assertEquals(0.24, GRID.vol(0, 0.20), 1e-12);
        assertEquals(0.16, GRID.vol(1, 0.20), 1e-12);
        assertEquals(0.20, GRID.vol(15, 0.20), 1e-12, "extreme scenarios keep vol unchanged");
        assertEquals(0.01, GRID.vol(1, 0.03), 1e-12, "vol floor");
        assertEquals(0.35, GRID.weight(14));
        assertEquals(1.0, GRID.weight(13));
    }
}
