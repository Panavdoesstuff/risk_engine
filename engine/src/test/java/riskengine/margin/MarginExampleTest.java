package riskengine.margin;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentType;

/**
 * The M2 gate: the engine must reproduce docs/margin-example-short-straddle.md to the rupee.
 * The doc's numbers come from scripts/straddle_example.py, which shares no code with the engine.
 * Parameters are spelled out here (not read from margin.properties) so editing config can't silently
 * change what this test proves.
 */
class MarginExampleTest {
    static final double RUPEE = 0.5;

    static final UnderlyingParams PARAMS = new UnderlyingParams("NIFTY", 0.012, 0.01, 0.09, 0.04, 2, 0.35, 0.03, 0.02, 0.01);
    static final MarginConfig CONFIG = new MarginConfig(0.069, new TreeMap<>(java.util.Map.of("NIFTY", PARAMS)),
            new Thresholds(0.80, 1.00, 1.20, 3));
    static final long VALUATION = OffsetDateTime.parse("2024-06-06T15:30:00+05:30").toInstant().toEpochMilli();
    static final LocalDate EXPIRY = LocalDate.of(2024, 6, 13);

    static Instrument option(InstrumentType type) {
        return Instrument.of("NIFTY", type, EXPIRY, new BigDecimal("23000"), 25, BigDecimal.ONE, BigDecimal.ONE, 1);
    }

    @Test
    void shortStraddleMatchesTheHandWorkedExampleToTheRupee() {
        MarketState m = new MarketState("NIFTY", 23000, 20 * PARAMS.volScale(), VALUATION);
        ScenarioGrid grid = new ScenarioGrid(PARAMS);
        RiskArray call = RiskArray.compute(option(InstrumentType.CE), m, grid, CONFIG.riskFreeRate());
        RiskArray put = RiskArray.compute(option(InstrumentType.PE), m, grid, CONFIG.riskFreeRate());

        // Step 1: prices now (per unit)
        assertEquals(266.6923, call.price(), 0.0001);
        assertEquals(241.5694, put.price(), 0.0001);

        // Step 2: every scenario, account loss in rupees for short 50 + short 50
        AccountVector v = new AccountVector("NIFTY").add(-50, call).add(-50, put);
        double[] expected = {
                5_074.94, -5_073.49, 17_807.79, 12_300.61, 15_608.07, 9_985.88, 45_989.74, 44_886.68,
                43_297.78, 42_360.30, 79_426.75, 79_319.74, 76_861.54, 76_807.09, 63_978.40, 63_099.10};
        for (int s = 0; s < ScenarioGrid.SIZE; s++) {
            assertEquals(expected[s], v.scenarioLoss(s), 0.01, "scenario " + (s + 1));
        }

        // Step 3: margin
        MarginResult r = MarginCalculator.compute(v, CONFIG, 240_000, 0);
        assertEquals(79_426.75, r.worstScenarioLoss(), RUPEE);
        assertEquals(11, r.worstScenario());
        assertEquals(69_000.00, r.shortOptionMinimum(), RUPEE);
        assertEquals(79_426.75, r.spanRequirement(), RUPEE);
        assertEquals(-25_413.09, r.netOptionValue(), RUPEE);
        assertEquals(104_839.83, r.riskRequirement(), RUPEE);
        assertEquals(46_000.00, r.exposureMargin(), RUPEE);
        assertEquals(150_839.83, r.required(), RUPEE);
        assertEquals(240_000.00, r.available(), RUPEE);
        assertEquals(0.6285, r.utilisation(), 0.00005);

        // And to the paisa, which is what we actually get from two double-precision implementations.
        assertEquals(150_839.83, r.required(), 0.01);
    }
}
